package com.karaoke.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** Kaydedilen sese efekt uygular ve vokalsiz muzikle karistirir. */
object VoiceFx {

    private const val SR = 44100
    private const val CH = 8192

    val MODES = listOf("Orijinal", "Stüdyo", "Salon", "Katedral", "Echo", "Uzun echo", "Koro", "Radyo", "Robot")
    val KEYS = listOf("orijinal", "studyo", "salon", "katedral", "echo", "uzun_echo", "koro", "radyo", "robot")

    // ---------------- Efekt parcalari ----------------
    private class Comb(size: Int, private val fb: Float, private val damp: Float) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        fun tick(x: Float): Float {
            val o = buf[idx]
            store = o * (1f - damp) + store * damp
            buf[idx] = x + store * fb
            if (++idx >= buf.size) idx = 0
            return o
        }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        fun tick(x: Float): Float {
            val b = buf[idx]
            val o = -x + b
            buf[idx] = x + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return o
        }
    }

    private class Reverb(room: Float, damp: Float, private val wet: Float) {
        private val ct = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val at = intArrayOf(556, 441, 341, 225)
        private val fb = room * 0.28f + 0.7f
        private val d = damp * 0.4f
        private val cl = Array(8) { Comb(ct[it], fb, d) }
        private val cr = Array(8) { Comb(ct[it] + 23, fb, d) }
        private val al = Array(4) { Allpass(at[it]) }
        private val ar = Array(4) { Allpass(at[it] + 23) }
        var wl = 0f
        var wr = 0f
        fun tick(x: Float) {
            val inp = x * 0.015f
            var l = 0f
            var r = 0f
            for (c in cl) l += c.tick(inp)
            for (c in cr) r += c.tick(inp)
            for (a in al) l = a.tick(l)
            for (a in ar) r = a.tick(r)
            wl = l * wet * 3f
            wr = r * wet * 3f
        }
    }

    private class Echo(ms: Int, private val fb: Float, private val wet: Float) {
        private val dl = (ms * SR / 1000)
        private val dr = (dl * 1.31).toInt()
        private val bl = FloatArray(dl)
        private val br = FloatArray(dr)
        private var il = 0
        private var ir = 0
        var wl = 0f
        var wr = 0f
        fun tick(x: Float) {
            val a = bl[il]
            val b = br[ir]
            bl[il] = x + a * fb
            br[ir] = x + b * fb
            if (++il >= dl) il = 0
            if (++ir >= dr) ir = 0
            wl = a * wet
            wr = b * wet
        }
    }

    private class Chorus {
        private val size = 4096
        private val buf = FloatArray(size)
        private var w = 0
        private var phase = 0.0
        var wl = 0f
        var wr = 0f

        private fun read(d: Float): Float {
            var p = w - d
            while (p < 0f) p += size
            val i0 = p.toInt()
            val fr = p - i0
            val a = buf[i0 % size]
            val b = buf[(i0 + 1) % size]
            return a * (1f - fr) + b * fr
        }

        fun tick(x: Float) {
            buf[w] = x
            phase += 2.0 * PI * 0.8 / SR
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            val d1 = 0.018f * SR + 0.004f * SR * sin(phase).toFloat()
            val d2 = 0.026f * SR + 0.005f * SR * sin(phase * 1.13 + 2.0).toFloat()
            wl = read(d1)
            wr = read(d2)
            if (++w >= size) w = 0
        }
    }

    private class Biquad(highpass: Boolean, f: Double, q: Double) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var z1 = 0f
        private var z2 = 0f

        init {
            val w0 = 2.0 * PI * f / SR
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            val nb0: Double
            val nb1: Double
            val nb2: Double
            if (highpass) {
                nb0 = (1.0 + cw) / 2.0
                nb1 = -(1.0 + cw)
                nb2 = (1.0 + cw) / 2.0
            } else {
                nb0 = (1.0 - cw) / 2.0
                nb1 = 1.0 - cw
                nb2 = (1.0 - cw) / 2.0
            }
            b0 = (nb0 / a0).toFloat()
            b1 = (nb1 / a0).toFloat()
            b2 = (nb2 / a0).toFloat()
            a1 = (-2.0 * cw / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun tick(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private class Chain(private val mode: Int) {
        private var reverb: Reverb? = null
        private var echo: Echo? = null
        private var chorus: Chorus? = null
        private var hp: Biquad? = null
        private var lp: Biquad? = null
        private var lpBright = 0f
        private var phase = 0.0
        private val rb = FloatArray(220)
        private var ri = 0

        init {
            when (mode) {
                1 -> reverb = Reverb(0.55f, 0.5f, 0.12f)
                2 -> reverb = Reverb(0.78f, 0.35f, 0.28f)
                3 -> reverb = Reverb(0.93f, 0.25f, 0.38f)
                4 -> echo = Echo(380, 0.32f, 0.5f)
                5 -> echo = Echo(560, 0.5f, 0.45f)
                6 -> chorus = Chorus()
                7 -> {
                    hp = Biquad(true, 380.0, 0.7)
                    lp = Biquad(false, 3200.0, 0.7)
                }
            }
        }

        fun process(x: FloatArray, n: Int, oL: FloatArray, oR: FloatArray) {
            for (i in 0 until n) {
                val v = x[i]
                var l = v
                var r = v
                when (mode) {
                    1 -> {
                        lpBright += 0.3f * (v - lpBright)
                        val y = v + 0.35f * (v - lpBright)
                        val rv = reverb!!
                        rv.tick(y)
                        l = y + rv.wl
                        r = y + rv.wr
                    }
                    2, 3 -> {
                        val rv = reverb!!
                        rv.tick(v)
                        l = v + rv.wl
                        r = v + rv.wr
                    }
                    4, 5 -> {
                        val e = echo!!
                        e.tick(v)
                        l = v + e.wl
                        r = v + e.wr
                    }
                    6 -> {
                        val c = chorus!!
                        c.tick(v)
                        l = 0.65f * v + 0.55f * c.wl
                        r = 0.65f * v + 0.55f * c.wr
                    }
                    7 -> {
                        var y = lp!!.tick(hp!!.tick(v))
                        y = tanh(1.6f * y) * 1.1f
                        l = y
                        r = y
                    }
                    8 -> {
                        val m = 0.3f + 0.7f * sin(phase).toFloat()
                        phase += 2.0 * PI * 55.0 / SR
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        val y = v * m + 0.5f * rb[ri]
                        rb[ri] = y
                        if (++ri >= rb.size) ri = 0
                        l = y * 1.2f
                        r = l
                    }
                }
                oL[i] = l
                oR[i] = r
            }
        }
    }

    // ---------------- Kayit cozme (mono) ----------------
    private class Mono {
        var a = ShortArray(1 shl 20)
        var n = 0
        var sr = SR
        fun add(s: Short) {
            if (n == a.size) a = a.copyOf(a.size + a.size / 2 + 4096)
            a[n++] = s
        }
    }

    private fun decodeMono(f: File): Mono {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val tf = ex.getTrackFormat(i)
                if ((tf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i; fmt = tf; break
                }
            }
            if (track < 0 || fmt == null) throw Exception("kayitta ses izi yok")
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val mono = Mono()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chn = max(ch, 1)
                        val frames = sb.remaining() / chn
                        for (i in 0 until frames) {
                            var sum = 0
                            for (k in 0 until chn) sum += sb.get().toInt()
                            mono.add((sum / chn).toShort())
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            mono.sr = sr
            if (sr == SR) return mono

            // dogrusal ornek donusumu
            val n2 = (mono.n.toLong() * SR / sr).toInt()
            val o = Mono()
            o.a = ShortArray(n2 + 16)
            for (i in 0 until n2) {
                val pos = i.toDouble() * sr / SR
                val i0 = pos.toInt()
                val fr = (pos - i0).toFloat()
                val i1 = min(i0 + 1, mono.n - 1)
                o.a[i] = (mono.a[i0] + (mono.a[i1] - mono.a[i0]) * fr).toInt().toShort()
            }
            o.n = n2
            return o
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0f) -y else y
    }

    /**
     * Kaydi (rec) efektle isler, vokalsiz muzikle (instr, 44.1 kHz stereo WAV) karistirip out'a yazar.
     * shiftMs: eksi = ses one alinir (kulaklik gecikmesini telafi). Doner: kisa not.
     */
    fun render(
        rec: File,
        instr: File?,
        mode: Int,
        voiceLevel: Float,
        musicVol: Float,
        shiftMs: Int,
        out: File,
        onProgress: (Int) -> Unit
    ): String {
        val mono = decodeMono(rec)
        val vn = mono.n
        if (vn < SR / 2) throw Exception("kayit cok kisa")

        // otomatik seviye dengeleme (en yuksek %0.1 tepe noktasina gore)
        val hist = IntArray(1025)
        for (i in 0 until vn) {
            val a = abs(mono.a[i].toInt())
            hist[min(a / 32, 1024)]++
        }
        var acc = 0
        var bin = 1024
        val limit = (vn * 0.001).toInt()
        while (bin > 0 && acc + hist[bin] < limit) {
            acc += hist[bin]
            bin--
        }
        val peak = (bin + 1) * 32 / 32768f
        val norm = if (peak < 0.01f) 1f else (0.6f / peak).coerceIn(0.5f, 8f)
        val scale = norm * voiceLevel / 32768f

        var raf: RandomAccessFile? = null
        var musicFrames = 0L
        if (instr != null && instr.exists() && instr.length() > 44) {
            raf = RandomAccessFile(instr, "r")
            musicFrames = (instr.length() - 44) / 4
        }

        val shiftS = shiftMs.toLong() * SR / 1000L
        val base = if (raf != null) musicFrames else (vn + shiftS + SR * 2L)
        val outLen = max(base, SR.toLong()) + SR

        val chain = Chain(mode)
        val x = FloatArray(CH)
        val fl = FloatArray(CH)
        val fr = FloatArray(CH)
        val mb = ByteArray(CH * 4)
        val ob = ByteArray(CH * 4)

        if (out.exists()) out.delete()
        val o = RandomAccessFile(out, "rw")
        try {
            o.write(ByteArray(44))
            var pos = 0L
            var written = 0L
            var lastPct = -1
            while (pos < outLen) {
                val n = min(CH.toLong(), outLen - pos).toInt()
                for (i in 0 until n) {
                    val vi = (pos + i - shiftS).toInt()
                    x[i] = if (vi >= 0 && vi < vn) mono.a[vi] * scale else 0f
                }
                chain.process(x, n, fl, fr)

                java.util.Arrays.fill(mb, 0)
                if (raf != null && pos < musicFrames) {
                    raf.seek(44L + pos * 4)
                    raf.read(mb, 0, n * 4)
                }
                var p = 0
                for (i in 0 until n) {
                    val ml = ((mb[i * 4].toInt() and 0xFF) or (mb[i * 4 + 1].toInt() shl 8)).toShort() / 32768f * musicVol
                    val mr = ((mb[i * 4 + 2].toInt() and 0xFF) or (mb[i * 4 + 3].toInt() shl 8)).toShort() / 32768f * musicVol
                    val l = soft(ml + fl[i])
                    val r = soft(mr + fr[i])
                    val sl = (l * 32767f).toInt()
                    val sr = (r * 32767f).toInt()
                    ob[p++] = (sl and 0xFF).toByte()
                    ob[p++] = ((sl shr 8) and 0xFF).toByte()
                    ob[p++] = (sr and 0xFF).toByte()
                    ob[p++] = ((sr shr 8) and 0xFF).toByte()
                }
                o.write(ob, 0, n * 4)
                written += n
                pos += n
                val pct = (pos * 100 / outLen).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }

            val dataBytes = written * 4
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray())
            h.putInt(16)
            h.putShort(1)
            h.putShort(2)
            h.putInt(SR)
            h.putInt(SR * 4)
            h.putShort(4)
            h.putShort(16)
            h.put("data".toByteArray())
            h.putInt(dataBytes.toInt())
            o.seek(0)
            o.write(h.array())
        } finally {
            try { o.close() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
        }
        return "ses=${vn / SR} sn, muzik=${musicFrames / SR} sn (${if (raf != null) "var" else "yok"}), " +
            "seviye carpani=${"%.2f".format(norm)}, kaydirma=$shiftMs ms"
    }
}
