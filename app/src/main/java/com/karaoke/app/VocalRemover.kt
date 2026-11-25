package com.karaoke.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

object VocalRemover {

    class Result(
        val ok: Boolean,        // stereo idi ve vokalsiz dosya uretildi
        val onsetMs: Long,      // ses girisi (-1: aranmadi / bulunamadi)
        val confDb: Float,      // algilama guveni (dB)
        val reliable: Boolean,  // guvenilir mi
        val durationMs: Long,   // cozulen ses suresi
        val note: String
    )

    /**
     * src veya uri'den okur. detectHiMs <= 0 ise ses girisi aranmaz (sadece vokal silinir).
     * Aranirsa [detectLoMs, detectHiMs] araliginda aranir.
     */
    fun process(
        ctx: Context,
        src: File?,
        uri: Uri?,
        out: File,
        detectLoMs: Long,
        detectHiMs: Long,
        cancel: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var raf: RandomAccessFile? = null
        try {
            if (src != null) {
                ex.setDataSource(src.absolutePath)
            } else {
                ex.setDataSource(ctx, uri!!, null)
            }

            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) return Result(false, -1, 0f, false, 0, "ses izi yok")

            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels != 2) return Result(false, -1, 0f, false, 0, "stereo degil ($channels kanal)")
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs =
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            if (out.exists()) out.delete()
            val file = RandomAccessFile(out, "rw")
            raf = file
            file.write(ByteArray(44)) // baslik sonra yazilacak

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var dataBytes = 0L
            var lp = 0f
            var alpha = (1.0 - exp(-2.0 * Math.PI * 150.0 / sampleRate)).toFloat()
            var lastPct = -1

            // --- ses girisi analizi (orta kanal / yan kanal orani, 300-3400 Hz) ---
            var aA = (1.0 - exp(-2.0 * Math.PI * 300.0 / sampleRate)).toFloat()
            var aB = (1.0 - exp(-2.0 * Math.PI * 3400.0 / sampleRate)).toFloat()
            var mA = 0f; var mB = 0f; var sA = 0f; var sB = 0f
            var eM = 0.0; var eS = 0.0
            var frames = 0L
            var binFrames = (sampleRate / 10).toLong()
            val ratio = ArrayList<Float>()
            val loud = ArrayList<Float>()

            while (!outputDone) {
                if (cancel()) return Result(false, -1, 0f, false, 0, "iptal")

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
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
                        val n = sb.remaining() / 2 * 2
                        val bytes = ByteArray(n * 2)
                        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        var i = 0
                        while (i < n) {
                            val l = sb.get().toInt().toFloat()
                            val r = sb.get().toInt().toFloat()
                            val mid = (l + r) / 2f
                            val side = (l - r) / 2f

                            // vokalsiz cikis
                            lp += alpha * (mid - lp)
                            var o = side + lp
                            if (o > 32767f) o = 32767f
                            if (o < -32768f) o = -32768f
                            val s = o.toInt().toShort()
                            bb.putShort(s)
                            bb.putShort(s)

                            // analiz
                            mA += aA * (mid - mA); mB += aB * (mid - mB)
                            sA += aA * (side - sA); sB += aB * (side - sB)
                            val bm = mB - mA
                            val bs = sB - sA
                            eM += (bm * bm).toDouble()
                            eS += (bs * bs).toDouble()
                            frames++
                            if (frames % binFrames == 0L) {
                                ratio.add((10.0 * log10((eM + 1.0) / (eS + 1.0))).toFloat())
                                loud.add((10.0 * log10((eM + eS) / binFrames + 1.0)).toFloat())
                                eM = 0.0; eS = 0.0
                            }
                            i += 2
                        }
                        file.write(bytes, 0, n * 2)
                        dataBytes += n * 2

                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        alpha = (1.0 - exp(-2.0 * Math.PI * 150.0 / sampleRate)).toFloat()
                        aA = (1.0 - exp(-2.0 * Math.PI * 300.0 / sampleRate)).toFloat()
                        aB = (1.0 - exp(-2.0 * Math.PI * 3400.0 / sampleRate)).toFloat()
                        binFrames = (sampleRate / 10).toLong()
                    }
                }
            }

            // WAV basligi (her zaman 2 kanal, 16 bit)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray())
            h.putInt(16)
            h.putShort(1)
            h.putShort(2)
            h.putInt(sampleRate)
            h.putInt(sampleRate * 4)
            h.putShort(4)
            h.putShort(16)
            h.put("data".toByteArray())
            h.putInt(dataBytes.toInt())
            file.seek(0)
            file.write(h.array())

            val durMs = if (sampleRate > 0) frames * 1000L / sampleRate else 0L

            if (detectHiMs <= 0) return Result(true, -1, 0f, false, durMs, "tespit yapilmadi")

            // ---------- ses girisini bul ----------
            val n = ratio.size
            if (n < 60) return Result(true, -1, 0f, false, durMs, "sarki cok kisa")

            val sm = FloatArray(n) { idx ->
                var a = 0f
                var cnt = 0
                for (k in -2..2) {
                    val j = idx + k
                    if (j in 0 until n) { a += ratio[j]; cnt++ }
                }
                a / cnt
            }

            val lo = max(3, (detectLoMs / 100).toInt())
            val hi = min(n - 10, (detectHiMs / 100).toInt())
            if (hi <= lo) return Result(true, -1, 0f, false, durMs, "arama araligi gecersiz")

            var bestT = -1
            var best = -1e9f
            for (t in lo until hi) {
                var b = 0f; var bc = 0
                for (k in max(0, t - 20) until t) { b += sm[k]; bc++ }
                var a = 0f; var ac = 0
                var l = 0f
                for (k in t until min(n, t + 15)) { a += sm[k]; l += loud[k]; ac++ }
                if (bc == 0 || ac == 0) continue
                if (l / ac < 20f) continue // sessizlik
                val score = a / ac - b / bc
                if (score > best) { best = score; bestT = t }
            }

            if (bestT < 0) return Result(true, -1, 0f, false, durMs, "ses girisi bulunamadi")
            val reliable = best >= 5f
            val note = if (reliable) "ses girisi bulundu" else "guven dusuk"
            return Result(true, bestT * 100L, best, reliable, durMs, note)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
        }
    }
}
