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

object VocalRemover {

    /** true: işlendi (out dosyası hazır). false: işlenemedi (mono vs.), orijinal çalınmalı. */
    fun process(
        ctx: Context,
        url: String?,
        uri: Uri?,
        out: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var raf: RandomAccessFile? = null
        try {
            if (url != null) {
                ex.setDataSource(url, mapOf("User-Agent" to DownloaderImpl.USER_AGENT))
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
            if (track < 0 || fmt == null) return false

            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels != 2) return false
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
            file.write(ByteArray(44)) // başlık sonra yazılacak

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var dataBytes = 0L
            var lp = 0f
            var alpha = (1.0 - exp(-2.0 * Math.PI * 150.0 / sampleRate)).toFloat()
            var lastPct = -1

            while (!outputDone) {
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
                            val l = sb.get().toInt()
                            val r = sb.get().toInt()
                            val mid = (l + r) / 2f
                            lp += alpha * (mid - lp)
                            var o = (l - r) * 0.5f + lp
                            if (o > 32767f) o = 32767f
                            if (o < -32768f) o = -32768f
                            val s = o.toInt().toShort()
                            bb.putShort(s)
                            bb.putShort(s)
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
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            // WAV başlığı (her zaman 2 kanal, 16 bit)
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
            return true
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
        }
    }
}
