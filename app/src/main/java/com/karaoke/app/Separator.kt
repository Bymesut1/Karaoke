package com.karaoke.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spleeter (2 stems) ile vokal / muzik ayirma. Cikis: vokalsiz stereo 44.1 kHz WAV. */
object Separator {

    private const val SR = 44100
    private const val NFFT = 4096
    private const val HOP = 1024
    private const val BINS = 2049
    private const val T = 512
    private const val F = 1024

    // ---------------- FFT ----------------
    private class Fft {
        private val cosT = FloatArray(NFFT / 2)
        private val sinT = FloatArray(NFFT / 2)
        private val rev = IntArray(NFFT)

        init {
            for (i in 0 until NFFT / 2) {
                val a = -2.0 * PI * i / NFFT
                cosT[i] = cos(a).toFloat()
                sinT[i] = sin(a).toFloat()
            }
            for (i in 0 until NFFT) {
                var x = i
                var r = 0
                for (b in 0 until 12) {
                    r = (r shl 1) or (x and 1)
                    x = x shr 1
                }
                rev[i] = r
            }
        }

        fun forward(re: FloatArray, im: FloatArray) {
            for (i in 0 until NFFT) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= NFFT) {
                val half = len / 2
                val step = NFFT / len
                var i = 0
                while (i < NFFT) {
                    var k = 0
                    for (j in i until i + half) {
                        val wr = cosT[k]
                        val wi = sinT[k]
                        val xr = re[j + half]
                        val xi = im[j + half]
                        val tr = xr * wr - xi * wi
                        val ti = xr * wi + xi * wr
                        re[j + half] = re[j] - tr
                        im[j + half] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        k += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** Ters FFT (re/im yer degistirme hilesi). */
        fun inverse(re: FloatArray, im: FloatArray) {
            forward(im, re)
            val s = 1f / NFFT
            for (i in 0 until NFFT) {
                re[i] *= s
                im[i] *= s
            }
        }
    }

    // ---------------- Cozme ----------------
    private class Pcm(cap: Int) {
        var l = ShortArray(cap)
        var r = ShortArray(cap)
        var n = 0
        var sampleRate = SR

        fun add(a: Short, b: Short) {
            if (n == l.size) {
                val nc = l.size + l.size / 2 + 4096
                l = l.copyOf(nc)
                r = r.copyOf(nc)
            }
            l[n] = a
            r[n] = b
            n++
        }
    }

    private fun decode(
        ctx: Context, src: File?, uri: Uri?,
        cancel: () -> Boolean, onProgress: (String, Int) -> Unit
    ): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (src != null) ex.setDataSource(src.absolutePath) else ex.setDataSource(ctx, uri!!, null)

            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) throw Exception("ses izi yok")

            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs =
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val cap = if (durUs > 0) (durUs / 1_000_000.0 * 48000 * 1.05).toInt() + 4096 else 8_000_000
            val pcm = Pcm(cap)

            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var lastPct = -1

            while (!outDone) {
                if (cancel()) return null
                if (!inDone) {
                    val inIdx = c.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / max1(ch)
                        var i = 0
                        while (i < frames) {
                            val a = sb.get()
                            var b = a
                            if (ch >= 2) {
                                b = sb.get()
                                for (k in 2 until ch) sb.get()
                            }
                            pcm.add(a, b)
                            i++
                        }
                        if (durUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress("Şarkı çözülüyor", pct)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            pcm.sampleRate = sr
            return pcm
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun max1(x: Int) = if (x < 1) 1 else x

    private fun resampleTo44k(p: Pcm): Pcm {
        if (p.sampleRate == SR) return p
        val sr = p.sampleRate
        val n2 = (p.n.toLong() * SR / sr).toInt()
        val o = Pcm(n2 + 16)
        for (i in 0 until n2) {
            val pos = i.toDouble() * sr / SR
            val i0 = pos.toInt()
            val fr = (pos - i0).toFloat()
            val i1 = min(i0 + 1, p.n - 1)
            o.l[i] = (p.l[i0] + (p.l[i1] - p.l[i0]) * fr).toInt().toShort()
            o.r[i] = (p.r[i0] + (p.r[i1] - p.r[i0]) * fr).toInt().toShort()
        }
        o.n = n2
        return o
    }

    /**
     * Modelin istedigi giris sekline gore tensor sekli secer.
     * Veri dizilimi her iki durumda da ayni: [kanal][kare][bin].
     *  - (batch, kanal=2, 512, 1024) -> 1x2x512x1024
     *  - (kanal=2, parca, 512, 1024) -> 2x1x512x1024
     */
    private fun feedShape(sess: OrtSession, name: String): LongArray {
        val s = (sess.inputInfo[name]!!.info as TensorInfo).shape
        val str = s.joinToString("x")
        if (s.size != 4) throw Exception("Beklenmeyen model girisi: $str")
        val okTF = (s[2] == -1L || s[2] == T.toLong()) && (s[3] == -1L || s[3] == F.toLong())
        if (!okTF) throw Exception("Beklenmeyen model girisi: $str")
        if (s[1] == 2L) return longArrayOf(1, 2, T.toLong(), F.toLong())
        if (s[0] == 2L) return longArrayOf(2, 1, T.toLong(), F.toLong())
        throw Exception("Beklenmeyen model girisi: $str")
    }

    private fun runModel(
        env: OrtEnvironment, sess: OrtSession, name: String, shape: LongArray,
        input: FloatArray, out: FloatArray
    ) {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        try {
            sess.run(mapOf(name to tensor)).use { res ->
                val o = res.get(0) as OnnxTensor
                val fb = o.floatBuffer
                fb.rewind()
                if (fb.remaining() != out.size) {
                    throw Exception("model cikis boyutu ${fb.remaining()}, beklenen ${out.size}")
                }
                fb.get(out, 0, out.size)
            }
        } finally {
            tensor.close()
        }
    }

    /** Ses girisini (vokalin basladigi an) vokal enerjisinden bulur. Doner: (ms, oran) */
    private fun findOnset(e: FloatArray, n: Int): Pair<Long, Float> {
        if (n < 100) return Pair(-1L, 0f)
        val s = FloatArray(n) { sqrt(e[it]) }
        val sorted = s.copyOf()
        sorted.sort()
        val ref = sorted[(n * 0.9).toInt().coerceAtMost(n - 1)]
        if (ref <= 0f) return Pair(-1L, 0f)
        val thr = ref * 0.25f
        for (t in 0 until n - 45) {
            var a = 0f
            for (k in 0 until 8) a += s[t + k]
            a /= 8f
            var b = 0f
            for (k in 0 until 43) b += s[t + k]
            b /= 43f
            if (a > thr && b > thr * 0.7f) {
                return Pair(t * HOP * 1000L / SR + 46, a / ref)
            }
        }
        return Pair(-1L, 0f)
    }

    /**
     * strength: 1 = hafif, 2 = orta (varsayilan), 3 = guclu.
     * Yuksek deger = daha az vokal sizintisi, muzik biraz daha ince/boguk.
     */
    fun process(
        ctx: Context,
        src: File?,
        uri: Uri?,
        vocalsModel: File,
        accModel: File,
        out: File,
        strength: Int,
        cancel: () -> Boolean,
        onProgress: (String, Int) -> Unit
    ): VocalRemover.Result {
        val gain = when (strength) { 1 -> 1f; 3 -> 4f; else -> 2f }
        val power = when (strength) { 1 -> 1.5f; 3 -> 2.5f; else -> 2.0f }

        onProgress("Şarkı çözülüyor", 0)
        val pcm0 = decode(ctx, src, uri, cancel, onProgress)
            ?: return VocalRemover.Result(false, -1, 0f, false, 0, "iptal")
        val pcm = resampleTo44k(pcm0)
        val nSamples = pcm.n
        if (nSamples < NFFT * 2) throw Exception("sarki cok kisa")
        val durMs = nSamples * 1000L / SR

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        val sv = env.createSession(vocalsModel.absolutePath, opts)
        val sa = env.createSession(accModel.absolutePath, opts)
        var raf: RandomAccessFile? = null
        try {
            val nameV = sv.inputNames.iterator().next()
            val nameA = sa.inputNames.iterator().next()
            val rawShapeStr = (sv.inputInfo[nameV]!!.info as TensorInfo).shape.joinToString("x")
            val shapeV = feedShape(sv, nameV)
            val shapeA = feedShape(sa, nameA)
            val shapeStr = rawShapeStr + "->" + shapeV.joinToString("x")

            val frames = nSamples / HOP + 1
            val nSplits = (frames + T - 1) / T

            val win = FloatArray(NFFT) { (0.5 - 0.5 * cos(2.0 * PI * it / NFFT)).toFloat() }
            val fft = Fft()
            val re = FloatArray(NFFT)
            val im = FloatArray(NFFT)
            val lRe = FloatArray(T * BINS)
            val lIm = FloatArray(T * BINS)
            val rRe = FloatArray(T * BINS)
            val rIm = FloatArray(T * BINS)
            val inp = FloatArray(2 * T * F)
            val outV = FloatArray(2 * T * F)
            val outA = FloatArray(2 * T * F)
            val mk = FloatArray(F)
            val carry = NFFT - HOP
            val accL = FloatArray(T * HOP + carry)
            val accR = FloatArray(T * HOP + carry)
            val vEn = FloatArray(frames)
            val ioBuf = ByteArray(T * HOP * 4)

            if (out.exists()) out.delete()
            val file = RandomAccessFile(out, "rw")
            raf = file
            file.write(ByteArray(44))
            var written = 0L

            for (s in 0 until nSplits) {
                if (cancel()) return VocalRemover.Result(false, -1, 0f, false, durMs, "iptal")
                onProgress("Vokal ayrılıyor", s * 100 / nSplits)

                val f0 = s * T
                val fh = min(T, frames - f0)
                java.util.Arrays.fill(inp, 0f)

                // --- STFT ---
                for (t in 0 until fh) {
                    val base = (f0 + t) * HOP
                    for (n in 0 until NFFT) {
                        val idx = base + n
                        if (idx < nSamples) {
                            re[n] = pcm.l[idx] * win[n] / 32768f
                            im[n] = pcm.r[idx] * win[n] / 32768f
                        } else {
                            re[n] = 0f
                            im[n] = 0f
                        }
                    }
                    fft.forward(re, im)
                    val o = t * BINS
                    for (k in 0 until BINS) {
                        val m = (NFFT - k) and (NFFT - 1)
                        val zr = re[k]; val zi = im[k]
                        val wr = re[m]; val wi = im[m]
                        val a = (zr + wr) * 0.5f
                        val b = (zi - wi) * 0.5f
                        val c = (zi + wi) * 0.5f
                        val d = (wr - zr) * 0.5f
                        lRe[o + k] = a; lIm[o + k] = b
                        rRe[o + k] = c; rIm[o + k] = d
                        if (k < F) {
                            inp[t * F + k] = sqrt(a * a + b * b)
                            inp[(T + t) * F + k] = sqrt(c * c + d * d)
                        }
                    }
                }

                // --- Model ---
                runModel(env, sv, nameV, shapeV, inp, outV)
                runModel(env, sa, nameA, shapeA, inp, outA)

                // --- Maske + ters STFT ---
                for (t in 0 until fh) {
                    val o = t * BINS
                    for (c in 0..1) {
                        val base = (c * T + t) * F
                        var sumHi = 0f
                        var vE = 0f
                        for (k in 0 until F) {
                            val v = outV[base + k]
                            val a = outA[base + k]
                            val v2 = v * v
                            val a2 = a * a
                            val sum = v2 + a2 + 1e-10f
                            val vm = (v2 + 5e-11f) / sum
                            val ve = vm * inp[base + k]
                            vE += ve * ve
                            // vokal ihtimali olan yerleri 'gain' kadar agirlikla kes
                            var m = (a2 + 5e-11f) / (a2 + gain * v2 + 1e-10f)
                            m = m.pow(power)
                            mk[k] = m
                            if (k >= 896) sumHi += m
                        }
                        vEn[f0 + t] += vE
                        val hi = sumHi / 128f
                        for (k in 0 until BINS) {
                            val m = if (k < F) mk[k] else hi
                            if (c == 0) {
                                lRe[o + k] *= m; lIm[o + k] *= m
                            } else {
                                rRe[o + k] *= m; rIm[o + k] *= m
                            }
                        }
                    }
                    for (k in 0 until NFFT) {
                        if (k < BINS) {
                            re[k] = lRe[o + k] - rIm[o + k]
                            im[k] = lIm[o + k] + rRe[o + k]
                        } else {
                            val m = NFFT - k
                            re[k] = lRe[o + m] + rIm[o + m]
                            im[k] = -lIm[o + m] + rRe[o + m]
                        }
                    }
                    fft.inverse(re, im)
                    val ob = t * HOP
                    for (n in 0 until NFFT) {
                        accL[ob + n] += re[n] * win[n]
                        accR[ob + n] += im[n] * win[n]
                    }
                }

                // --- Yaz ---
                val toWrite = min((T * HOP).toLong(), nSamples - written).toInt()
                var p = 0
                for (i in 0 until toWrite) {
                    var a = accL[i] * (2f / 3f) * 32768f
                    var b = accR[i] * (2f / 3f) * 32768f
                    if (a > 32767f) a = 32767f
                    if (a < -32768f) a = -32768f
                    if (b > 32767f) b = 32767f
                    if (b < -32768f) b = -32768f
                    val sa16 = a.toInt()
                    val sb16 = b.toInt()
                    ioBuf[p++] = (sa16 and 0xFF).toByte()
                    ioBuf[p++] = ((sa16 shr 8) and 0xFF).toByte()
                    ioBuf[p++] = (sb16 and 0xFF).toByte()
                    ioBuf[p++] = ((sb16 shr 8) and 0xFF).toByte()
                }
                file.write(ioBuf, 0, toWrite * 4)
                written += toWrite

                System.arraycopy(accL, T * HOP, accL, 0, carry)
                System.arraycopy(accR, T * HOP, accR, 0, carry)
                java.util.Arrays.fill(accL, carry, accL.size, 0f)
                java.util.Arrays.fill(accR, carry, accR.size, 0f)
            }

            // WAV basligi
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
            file.seek(0)
            file.write(h.array())

            onProgress("Vokal ayrılıyor", 100)
            val (onsetMs, ratio) = findOnset(vEn, frames)
            return VocalRemover.Result(
                true, onsetMs, ratio, onsetMs >= 0, durMs,
                "spleeter onnx, girdi=$shapeStr, parca=$nSplits, guc=$strength (gain=$gain, power=$power), " +
                    "kaynak ${pcm0.sampleRate} Hz"
            )
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            try { sv.close() } catch (_: Exception) {}
            try { sa.close() } catch (_: Exception) {}
            try { opts.close() } catch (_: Exception) {}
        }
    }
}
