package com.karaoke.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.URL
import java.net.URLEncoder

data class KWord(val start: Long, val text: String)
data class KLine(val start: Long, val words: List<KWord>)

class MainActivity : Activity() {

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var reviewPlayer: MediaPlayer? = null
    private var audioUri: Uri? = null
    private var streamUrl: String? = null
    private var lines: List<KLine> = emptyList()
    private var recFile: File? = null
    private var running = false
    private var offsetMs = 0L
    private var vocalRemove = true
    private var sessionId = 0

    private lateinit var input: EditText
    private lateinit var lyricView: TextView
    private lateinit var nextView: TextView
    private lateinit var status: TextView
    private lateinit var vocalBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    private val BLUE = Color.parseColor("#2196F3")
    private val WHITE = Color.WHITE

    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                updateLyrics()
                handler.postDelayed(this, 40)
            }
        }
    }

    private fun updateVocalBtn() {
        vocalBtn.text = if (vocalRemove) "Vokal silme: AÇIK" else "Vokal silme: KAPALI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NewPipe.init(DownloaderImpl())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 64, 32, 32)
        }

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 14f
            text = "Şarkı adını yaz (sanatçı + şarkı) ve Ara'ya bas."
        }
        input = EditText(this).apply {
            hint = "örn: Tarkan Kuzu Kuzu"
            setTextColor(WHITE); setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        lyricView = TextView(this).apply {
            setTextColor(WHITE); textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 80, 0, 24)
        }
        nextView = TextView(this).apply {
            setTextColor(Color.GRAY); textSize = 20f
            gravity = Gravity.CENTER
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        vocalBtn = btn("") {
            vocalRemove = !vocalRemove
            updateVocalBtn()
        }
        updateVocalBtn()

        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Söz -0.5s") { offsetMs -= 500; status.text = "Kaydırma: ${offsetMs} ms" },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("Söz +0.5s") { offsetMs += 500; status.text = "Kaydırma: ${offsetMs} ms" },
                LinearLayout.LayoutParams(0, -2, 1f))
        }

        root.addView(status)
        root.addView(input)
        root.addView(btn("ARA (YouTube karaoke + söz)") {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) searchAndLoad(q)
        })
        root.addView(btn("veya: Müzik dosyası seç") { pickFile(1) })
        root.addView(btn("veya: LRC dosyası seç") { pickFile(2) })
        root.addView(vocalBtn)
        root.addView(btn("BAŞLA") { onStartClicked() })
        root.addView(btn("DURDUR") { stopSession() })
        root.addView(btn("Kaydı dinle") { playRecording() })
        root.addView(offsetRow)
        root.addView(lyricView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nextView)

        setContentView(root)
    }

    // ---------- YouTube (NewPipe) + LRCLIB ----------
    private fun searchAndLoad(q: String) {
        status.text = "Aranıyor..."
        streamUrl = null
        audioUri = null
        Thread {
            try {
                val service = ServiceList.YouTube
                val qh = service.searchQHFactory.fromQuery(
                    "$q karaoke instrumental",
                    listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                    ""
                )
                val ex = service.getSearchExtractor(qh)
                ex.fetchPage()
                val item = ex.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .firstOrNull() ?: throw Exception("Sonuç bulunamadı")

                val info = StreamInfo.getInfo(service, item.url)
                val audio = info.audioStreams
                    .filter { it.format == MediaFormat.M4A }
                    .maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams.firstOrNull()
                    ?: throw Exception("Ses akışı yok")

                val url = audio.content
                runOnUiThread { status.text = "Müzik bulundu: ${item.name}\nSözler aranıyor..." }

                val lrc = fetchLyrics(q)
                runOnUiThread {
                    streamUrl = url
                    vocalRemove = false // karaoke videosu zaten vokalsiz
                    updateVocalBtn()
                    if (lrc != null) {
                        lines = parseLrc(lrc)
                        status.text = "Hazır: ${item.name}\n${lines.size} satır söz. BAŞLA'ya bas."
                    } else {
                        lines = emptyList()
                        status.text = "Hazır: ${item.name}\nSöz bulunamadı (sadece müzik çalar)."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Hata: ${e.message}" }
            }
        }.start()
    }

    private fun fetchLyrics(q: String): String? {
        return try {
            val u = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
            val conn = URL(u).openConnection()
            conn.setRequestProperty("User-Agent", "KaraokeApp/3.0")
            val txt = conn.getInputStream().bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.isNull("syncedLyrics")) {
                    val s = o.getString("syncedLyrics")
                    if (s.isNotBlank()) return s
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Dosya seçme ----------
    private fun pickFile(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == 1) {
            audioUri = uri
            streamUrl = null
            vocalRemove = true
            updateVocalBtn()
            status.text = "Müzik dosyası seçildi. Vokal silme AÇIK."
        } else if (requestCode == 2) {
            try {
                val text = contentResolver.openInputStream(uri)!!
                    .bufferedReader().use { it.readText() }
                lines = parseLrc(text)
                status.text = "${lines.size} satır söz yüklendi."
            } catch (e: Exception) {
                status.text = "LRC okunamadı: ${e.message}"
            }
        }
    }

    // ---------- LRC ayrıştırma ----------
    private val lineTag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
    private val wordTag = Regex("""<(\d+):(\d+)(?:[.:](\d+))?>""")

    private fun toMs(m: String, s: String, f: String?): Long {
        var ms = 0L
        if (!f.isNullOrEmpty()) {
            ms = when (f.length) {
                1 -> f.toLong() * 100
                2 -> f.toLong() * 10
                else -> f.take(3).toLong()
            }
        }
        return m.toLong() * 60000 + s.toLong() * 1000 + ms
    }

    private fun parseLrc(text: String): List<KLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        for (ln in text.lines()) {
            var rest = ln.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val m = lineTag.find(rest)
                if (m != null && m.range.first == 0) {
                    times.add(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                    rest = rest.substring(m.range.last + 1)
                } else break
            }
            if (times.isEmpty() || rest.isBlank()) continue
            for (t in times) raw.add(t to rest.trim())
        }
        raw.sortBy { it.first }

        val result = mutableListOf<KLine>()
        for ((idx, pair) in raw.withIndex()) {
            val start = pair.first
            val content = pair.second
            val nextStart = if (idx + 1 < raw.size) raw[idx + 1].first else start + 6000
            val plain = content.replace(wordTag, "").trim()
            val dur = minOf(nextStart - start, maxOf(1500L, plain.length * 280L))
            val words = mutableListOf<KWord>()

            val tags = wordTag.findAll(content).toList()
            if (tags.isNotEmpty()) {
                for ((i, m) in tags.withIndex()) {
                    val end = if (i + 1 < tags.size) tags[i + 1].range.first else content.length
                    val w = content.substring(m.range.last + 1, end).trim()
                    if (w.isNotEmpty()) {
                        words.add(KWord(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]), w))
                    }
                }
            } else {
                val parts = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val total = parts.sumOf { it.length }.coerceAtLeast(1)
                var acc = 0
                for (p in parts) {
                    words.add(KWord(start + dur * acc / total, p))
                    acc += p.length
                }
            }
            if (words.isNotEmpty()) result.add(KLine(start, words))
        }
        return result
    }

    // ---------- Gösterim ----------
    private fun render(line: KLine, pos: Long): SpannableString {
        val sb = StringBuilder()
        val ranges = mutableListOf<Triple<Int, Int, Boolean>>()
        for ((i, w) in line.words.withIndex()) {
            if (i > 0) sb.append(' ')
            val s = sb.length
            sb.append(w.text)
            ranges.add(Triple(s, sb.length, pos >= w.start))
        }
        val sp = SpannableString(sb.toString())
        for ((s, e, sung) in ranges) {
            sp.setSpan(
                ForegroundColorSpan(if (sung) BLUE else WHITE),
                s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    private fun updateLyrics() {
        val p = player ?: return
        val pos = p.currentPosition.toLong() - offsetMs
        if (lines.isEmpty()) {
            lyricView.text = "♪"
            return
        }
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].start <= pos) idx = i else break
        }
        if (idx == -1) {
            lyricView.text = "♪ ♪ ♪"
            nextView.text = lines[0].words.joinToString(" ") { it.text }
            return
        }
        lyricView.text = render(lines[idx], pos)
        nextView.text = if (idx + 1 < lines.size)
            lines[idx + 1].words.joinToString(" ") { it.text } else ""
    }

    // ---------- Oynat + Kaydet ----------
    private fun onStartClicked() {
        if (streamUrl == null && audioUri == null) {
            status.text = "Önce şarkı ara veya müzik dosyası seç."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        startSession()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 10 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            status.text = "Mikrofon izni gerekli."
        }
    }

    private fun startSession() {
        stopSession()
        val myId = ++sessionId

        if (!vocalRemove) {
            beginPlayback(null)
            return
        }

        status.text = "Vokal ayrılıyor... %0"
        Thread {
            try {
                val outFile = File(cacheDir, "instrumental.wav")
                val ok = VocalRemover.process(this, streamUrl, audioUri, outFile) { pct ->
                    runOnUiThread {
                        if (myId == sessionId) status.text = "Vokal ayrılıyor... %$pct"
                    }
                }
                runOnUiThread {
                    if (myId != sessionId) return@runOnUiThread
                    if (ok) {
                        beginPlayback(outFile)
                    } else {
                        status.text = "Bu şarkı stereo değil, vokal silinemedi. Orijinal çalınıyor."
                        beginPlayback(null)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (myId == sessionId) status.text = "Vokal ayırma hatası: ${e.message}"
                }
            }
        }.start()
    }

    private fun beginPlayback(processed: File?) {
        try {
            val f = File(getExternalFilesDir(null), "kayit_${System.currentTimeMillis()}.m4a")
            recFile = f

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            recorder = r

            val p = MediaPlayer()
            val url = streamUrl
            if (processed != null) {
                p.setDataSource(processed.absolutePath)
            } else if (url != null) {
                p.setDataSource(this, Uri.parse(url), mapOf("User-Agent" to DownloaderImpl.USER_AGENT))
            } else {
                p.setDataSource(this, audioUri!!)
            }
            p.setOnPreparedListener {
                try {
                    recorder?.start()
                    it.start()
                    running = true
                    handler.post(ticker)
                    status.text = "Çalıyor ve kaydediyor... (kulaklık tak)"
                } catch (e: Exception) {
                    status.text = "Başlatılamadı: ${e.message}"
                    stopSession()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                status.text = "Oynatma hatası ($what/$extra)"
                stopSession()
                true
            }
            p.setOnCompletionListener { stopSession() }
            player = p
            status.text = "Müzik yükleniyor..."
            p.prepareAsync()
        } catch (e: Exception) {
            status.text = "Hata: ${e.message}"
            stopSession()
        }
    }

    private fun stopSession() {
        sessionId++
        running = false
        handler.removeCallbacks(ticker)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        recFile?.let {
            if (it.exists() && it.length() > 0) status.text = "Kayıt: ${it.absolutePath}"
        }
    }

    private fun playRecording() {
        val f = recFile
        if (f == null || !f.exists()) {
            status.text = "Henüz kayıt yok."
            return
        }
        try {
            reviewPlayer?.release()
            reviewPlayer = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            status.text = "Oynatılamadı: ${e.message}"
        }
    }

    override fun onDestroy() {
        stopSession()
        reviewPlayer?.release()
        super.onDestroy()
    }
}
