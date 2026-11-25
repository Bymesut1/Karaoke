package com.karaoke.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ModelStore {

    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems/resolve/main/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun dir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }

    /** Model dosyalarini (yoksa) indirir. Doner: (vokal modeli, muzik modeli) */
    fun ensure(ctx: Context, onProgress: (String, Int) -> Unit): Pair<File, File> {
        val names = listOf("vocals.onnx", "accompaniment.onnx")
        for ((i, name) in names.withIndex()) {
            val f = File(dir(ctx), name)
            if (f.exists() && f.length() > 5_000_000) continue
            var last = -1
            download(BASE + name, f) { pct ->
                if (pct != last) {
                    last = pct
                    onProgress("Model indiriliyor (${i + 1}/2)", pct)
                }
            }
        }
        return Pair(File(dir(ctx), names[0]), File(dir(ctx), names[1]))
    }

    private fun download(url: String, out: File, cb: (Int) -> Unit) {
        val tmp = File(out.path + ".part")
        if (tmp.exists()) tmp.delete()
        val req = Request.Builder().url(url).header("User-Agent", "KaraokeApp/6.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Model indirilemedi: HTTP ${r.code} ($url)")
            val body = r.body!!
            val len = body.contentLength()
            tmp.outputStream().use { os ->
                val buf = ByteArray(65536)
                var read = 0L
                body.byteStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (len > 0) cb((read * 100 / len).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        if (tmp.length() < 5_000_000) {
            val sz = tmp.length()
            tmp.delete()
            throw Exception("Model dosyasi cok kucuk ($sz bayt): $url")
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw Exception("Model dosyasi yeniden adlandirilamadi")
    }
}
