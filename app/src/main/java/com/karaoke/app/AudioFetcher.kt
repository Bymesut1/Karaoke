package com.karaoke.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object AudioFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun req(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", DownloaderImpl.USER_AGENT)

    private fun findLength(url: String): Long {
        val m = Regex("[?&]clen=(\\d+)").find(url)
        if (m != null) return m.groupValues[1].toLong()
        return try {
            client.newCall(req(url).header("Range", "bytes=0-0").build()).execute().use { r ->
                val cr = r.header("Content-Range") ?: ""
                val total = cr.substringAfter("/", "")
                total.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /** Dosyayi indirir. Donus: log notu. Basarisizsa exception firlatir. */
    fun download(url: String, out: File, onProgress: (Int) -> Unit): String {
        val total = findLength(url)
        if (out.exists()) out.delete()
        var chunkedErr = ""
        if (total > 0 && !url.contains("range=")) {
            try {
                chunked(url, out, total, onProgress)
                if (out.length() == total) return "parcali indirme: $total bayt"
                chunkedErr = "boyut uyusmadi (${out.length()}/$total)"
            } catch (e: Exception) {
                chunkedErr = e.message ?: e.javaClass.simpleName
            }
            if (out.exists()) out.delete()
        }
        plain(url, out, total, onProgress)
        return "duz indirme: ${out.length()} bayt (beklenen $total)" +
            if (chunkedErr.isNotEmpty()) " [parcali basarisiz: $chunkedErr]" else ""
    }

    private fun chunked(url: String, out: File, total: Long, onProgress: (Int) -> Unit) {
        val chunk = 1_048_576L
        val count = ((total + chunk - 1) / chunk).toInt()
        val raf = RandomAccessFile(out, "rw")
        raf.setLength(total)
        val next = AtomicInteger(0)
        val done = AtomicLong(0)
        val error = arrayOfNulls<Throwable>(1)
        val sep = if (url.contains("?")) "&" else "?"

        val threads = (0 until 4).map {
            Thread {
                try {
                    while (error[0] == null) {
                        val i = next.getAndIncrement()
                        if (i >= count) break
                        val s = i * chunk
                        val e = minOf(total - 1, s + chunk - 1)
                        val r = req("$url${sep}range=$s-$e").build()
                        client.newCall(r).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            val bytes = resp.body!!.bytes()
                            if (bytes.size.toLong() != e - s + 1) {
                                throw Exception("kisa parca ${bytes.size}")
                            }
                            synchronized(raf) {
                                raf.seek(s)
                                raf.write(bytes)
                            }
                            val d = done.addAndGet(bytes.size.toLong())
                            onProgress((d * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                } catch (t: Throwable) {
                    error[0] = t
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        raf.close()
        error[0]?.let { throw Exception(it.message ?: it.javaClass.simpleName) }
    }

    private fun plain(url: String, out: File, total: Long, onProgress: (Int) -> Unit) {
        client.newCall(req(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body!!
            val len = if (total > 0) total else body.contentLength()
            out.outputStream().use { os ->
                val buf = ByteArray(65536)
                var read = 0L
                body.byteStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (len > 0) onProgress((read * 100 / len).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
    }
}
