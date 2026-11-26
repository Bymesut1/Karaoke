package com.karaoke.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var sessionId = 0

    private var stage = 0            // 0 bosta, 1 hazirlik (indirme+ayirma), 2 calma+kayit
    private var analysis: VocalRemover.Result? = null
    private var instrFile: File? = null
    private var srcFile: File? = null
    private var plannedOffset = 0L
    private var syncNote = ""
    private var videoSec = -1L
    private var lrcSec = -1.0
    private var songKey = ""

    private var musicVol = 0.5f      // muzik sesi (0..1)
    private var strength = 2         // vokal bastirma gucu (1..3)

    private val dbg = StringBuilder()

    private lateinit var input: EditText
    private lateinit var lyricView: TextView
    private lateinit var nextView: TextView
    private lateinit var status: TextView
    private lateinit var volLabel: TextView
    private lateinit var strengthBtn: Button
    private lateinit var fx: FxPanel
    private val handler = Handler(Looper.getMainLooper())

    private val BLUE = Color.parseColor("#2196F3")
    private val WHITE = Color.WHITE

    // ---------- gunluk / debug ----------
    private fun dlog(msg: String) {
        synchronized(dbg) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            dbg.append("[").append(t).append("] ").append(msg).append("\n")
            if (dbg.length > 30000) dbg.delete(0, dbg.length - 30000)
        }
    }

    private fun logError(where: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        dlog("HATA @ $where: ${e.javaClass.name}: ${e.message}\n${sw.toString().take(2500)}")
    }

    private fun copyToDownloads(src: File, name: String, mime: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return false
                val os = contentResolver.openOutputStream(uri) ?: return false
                os.use { out -> src.inputStream().use { it.copyTo(out) } }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                src.copyTo(File(dir, name), overwrite = true)
            }
            true
        } catch (e: Exception) {
            logError("Indirilenlere kopyalama ($name)", e)
            false
        }
    }

    private fun saveDebug() {
        val sb = StringBuilder()
        val now = Date()
        sb.append("=== KARAOKE DEBUG ===\n")
        sb.append("Zaman: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)).append("\n")
        sb.append("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}\n")
        sb.append("Asama: $stage\n")
        sb.append("Kaynak: ${if (streamUrl != null) "YouTube" else if (audioUri != null) "dosya" else "yok"}\n")
        sb.append("Video suresi: $videoSec sn, soz suresi: $lrcSec sn\n")
        sb.append("Muzik sesi: ${(musicVol * 100).toInt()}%, vokal bastirma gucu: $strength\n")
        sb.append("Soz satiri: ${lines.size}, ilk satir: ${lines.firstOrNull()?.start} ms\n")
        val md = File(filesDir, "models")
        sb.append("Model dosyalari: ").append(
            md.listFiles()?.joinToString { "${it.name}=${it.length()}" } ?: "yok"
        ).append("\n")
        val a = analysis
        if (a != null) {
            sb.append("Ayirma: ok=${a.ok} ses girisi=${a.onsetMs} ms oran=${"%.2f".format(a.confDb)} " +
                "bulundu=${a.reliable} sure=${a.durationMs} ms not=${a.note}\n")
        } else {
            sb.append("Ayirma: yok\n")
        }
        sb.append("Planlanan kaydirma: $plannedOffset ms, su anki kaydirma (offset): $offsetMs ms\n")
        sb.append("\n--- Olay gunlugu ---\n")
        synchronized(dbg) { sb.append(dbg) }

        val text = sb.toString()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("karaoke debug", text))
        } catch (_: Exception) {}

        val name = "karaoke_debug_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".txt"
        status.text = "Debug kaydediliyor..."
        Thread {
            val ok = try {
                val f = File(cacheDir, "debug.txt")
                f.writeText(text)
                copyToDownloads(f, name, "text/plain")
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                status.text = if (ok) "Debug İndirilenler klasörüne kaydedildi:\n$name\n(Panoya da kopyalandı)"
                else "İndirilenlere yazılamadı ama panoya kopyalandı. Bana yapıştır."
            }
        }.start()
    }

    // ---------- UI ----------
    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                updateLyrics()
                handler.postDelayed(this, 40)
            }
        }
    }

    private fun updateStrengthBtn() {
        val name = when (strength) { 1 -> "hafif"; 3 -> "güçlü"; else -> "orta" }
        strengthBtn.text = "Vokal bastırma gücü: $strength ($name) – değiştirmek için dokun"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NewPipe.init(DownloaderImpl())

        val prefs = getSharedPreferences("karaoke", MODE_PRIVATE)
        musicVol = prefs.getFloat("vol", 0.5f)
        strength = prefs.getInt("strength", 2).coerceIn(1, 3)

        fx = FxPanel(
            this,
            { recFile },
            { instrFile },
            { musicVol },
            { t -> status.text = t },
            { m -> dlog(m) },
            { f, n, mime -> copyToDownloads(f, n, mime) }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 64, 32, 24)
        }

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
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
            setPadding(0, 24, 0, 16)
        }
        nextView = TextView(this).apply {
            setTextColor(Color.GRAY); textSize = 20f
            gravity = Gravity.CENTER
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        // --- muzik sesi ---
        volLabel = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
            text = "Müzik sesi: %${(musicVol * 100).toInt()}"
        }
        val volBar = SeekBar(this).apply {
            max = 100
            progress = (musicVol * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    musicVol = p / 100f
                    volLabel.text = "Müzik sesi: %$p"
                    try { player?.setVolume(musicVol, musicVol) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putFloat("vol", musicVol).apply()
                    dlog("Muzik sesi: ${(musicVol * 100).toInt()}%")
                }
            })
        }

        // --- vokal bastirma gucu ---
        strengthBtn = btn("") {
            strength = if (strength >= 3) 1 else strength + 1
            prefs.edit().putInt("strength", strength).apply()
            updateStrengthBtn()
            dlog("Vokal bastirma gucu: $strength")
            status.text = "Vokal bastırma gücü $strength. Sonraki BAŞLA 1'de uygulanır (şarkı yeniden ayrılır)."
        }
        updateStrengthBtn()

        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("-2s") { shift(-2000) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("-0.5s") { shift(-500) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("+0.5s") { shift(500) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("+2s") { shift(2000) }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(volLabel)
            addView(volBar)
            addView(strengthBtn)
            addView(btn("ARA (YouTube + söz)") {
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) searchAndLoad(q)
            })
            addView(btn("veya: Müzik dosyası seç") { pickFile(1) })
            addView(btn("veya: LRC dosyası seç") { pickFile(2) })
            addView(btn("BAŞLA 1 (indir + vokal ayır)") { onStartClicked() })
            addView(btn("BAŞLA 2 (vokalsiz müzik + kayıt)") { startStage2(false) })
            addView(btn("DURDUR") { stopSession(); status.text = "Durduruldu." })
            addView(offsetRow)
            addView(btn("Kaydırmayı sıfırla (0)") {
                offsetMs = 0
                dlog("Kaydirma sifirlandi")
                status.text = "Söz kaydırma: 0 sn"
            })
            addView(btn("SENKRON: sıradaki satır ŞİMDİ başlıyor") { syncToNow() })
            addView(btn("Kaydı dinle") { playRecording() })
            addView(fx.build())
            addView(btn("Yapay zekâ modelini önceden indir (bir kez)") { preloadModels() })
            addView(btn("Ayrılmış şarkı önbelleğini sil") { clearCache() })
            addView(btn("DEBUG KAYDET (İndirilenler)") { saveDebug() })
        }
        val scroll = ScrollView(this).apply { addView(controls) }

        root.addView(status)
        root.addView(input)
        root.addView(lyricView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nextView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.3f))

        setContentView(root)
    }

    private fun preloadModels() {
        status.text = "Model indiriliyor..."
        Thread {
            try {
                ModelStore.ensure(this) { label, pct ->
                    runOnUiThread { status.text = "$label %$pct" }
                }
                dlog("Model hazir")
                runOnUiThread { status.text = "Model hazır. Artık şarkı ayırabilirsin." }
            } catch (e: Exception) {
                logError("model indirme", e)
                runOnUiThread { status.text = "Model indirilemedi: ${e.message}\n('DEBUG KAYDET' butonuna bas)" }
            }
        }.start()
    }

    private fun clearCache() {
        var n = 0
        filesDir.listFiles()?.forEach {
            if (it.name.startsWith("inst_")) {
                if (it.delete()) n++
            }
        }
        dlog("Onbellek silindi: $n dosya")
        status.text = "Önbellek silindi ($n dosya)."
    }

    private fun pruneCache(keep: File) {
        val files = filesDir.listFiles { f -> f.name.startsWith("inst_") && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(3).forEach { if (it.absolutePath != keep.absolutePath) it.delete() }
    }

    // ---------- Senkron ----------
    private fun shift(ms: Long) {
        offsetMs += ms
        status.text = "Söz kaydırma: ${"%.1f".format(offsetMs / 1000.0)} sn"
        dlog("Elle kaydirma: offset=$offsetMs")
    }

    private fun currentLineIndex(pos: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].start <= pos) idx = i else break
        }
        return idx
    }

    private fun syncToNow() {
        val p = player
        if (p == null || !running || lines.isEmpty()) {
            status.text = "Önce BAŞLA 2 çalışırken, sıradaki satırın başlaması gereken anda bas."
            return
        }
        val rawPos = p.currentPosition.toLong()
        val idx = currentLineIndex(rawPos - offsetMs)
        val target = (idx + 1).coerceIn(0, lines.size - 1)
        offsetMs = rawPos - lines[target].start
        dlog("Elle senkron: offset=$offsetMs")
        status.text = "Senkron ayarlandı. Kaydırma: ${"%.1f".format(offsetMs / 1000.0)} sn"
    }

    // ---------- YouTube (NewPipe) + LRCLIB ----------
    private fun searchAndLoad(q: String) {
        status.text = "Aranıyor..."
        streamUrl = null
        audioUri = null
        srcFile = null
        analysis = null
        offsetMs = 0
        songKey = q.lowercase(Locale.ROOT)
        Thread {
            try {
                val service = ServiceList.YouTube
                val qh = service.searchQHFactory.fromQuery(
                    "$q official audio",
                    listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                    ""
                )
                val ex = service.getSearchExtractor(qh)
                ex.fetchPage()
                val items = ex.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .take(3)
                if (items.isEmpty()) throw Exception("Sonuç bulunamadı")

                var found: Triple<String, String, Long>? = null
                var lastEx: Throwable? = null
                for (item in items) {
                    try {
                        val info = StreamInfo.getInfo(service, item.url)
                        val audio = info.audioStreams
                            .filter { it.format == MediaFormat.M4A }
                            .maxByOrNull { it.averageBitrate }
                            ?: info.audioStreams.firstOrNull()
                            ?: throw Exception("Ses akışı yok")
                        found = Triple(item.name, audio.content, info.duration)
                        break
                    } catch (e: Exception) {
                        lastEx = e
                        logError("video denemesi: ${item.url}", e)
                    }
                }
                if (found == null) throw (lastEx ?: Exception("Ses akışı yok"))

                val title = found.first
                val url = found.second
                val vSec = found.third
                dlog("Video: $title ($vSec sn)")
                runOnUiThread { status.text = "Şarkı bulundu: $title\nSözler aranıyor..." }

                val lrc = fetchLyrics(q, vSec)
                runOnUiThread {
                    streamUrl = url
                    videoSec = vSec
                    lrcSec = lrc?.second ?: -1.0
                    if (lrc != null) {
                        lines = parseLrc(lrc.first)
                        dlog("Soz: ${lines.size} satir, sure=${lrc.second} sn")
                        status.text = "Hazır: $title\n${lines.size} satır söz. BAŞLA 1'e bas."
                    } else {
                        lines = emptyList()
                        status.text = "Hazır: $title\nSöz bulunamadı (sadece müzik çalar). BAŞLA 1'e bas."
                    }
                }
            } catch (e: Exception) {
                logError("arama: $q", e)
                runOnUiThread {
                    status.text = "Hata: ${e.javaClass.simpleName}: ${e.message}\n" +
                        "('DEBUG KAYDET' butonuna bas)"
                }
            }
        }.start()
    }

    /** Süresi videoya en yakın zaman damgalı sözü seçer. Döner: (LRC metni, süre sn) */
    private fun fetchLyrics(q: String, videoSec: Long): Pair<String, Double>? {
        return try {
            val u = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
            val conn = URL(u).openConnection()
            conn.setRequestProperty("User-Agent", "KaraokeApp/7.0")
            val txt = conn.getInputStream().bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            var best: Pair<String, Double>? = null
            var bestDiff = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.isNull("syncedLyrics")) continue
                val s = o.getString("syncedLyrics")
                if (s.isBlank()) continue
                val d = if (o.isNull("duration")) -1.0 else o.getDouble("duration")
                val diff = if (d > 0 && videoSec > 0) Math.abs(videoSec - d) else 1e9
                if (best == null || diff < bestDiff) {
                    best = Pair(s, d)
                    bestDiff = diff
                }
            }
            best
        } catch (e: Exception) {
            logError("söz arama", e)
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
            srcFile = null
            analysis = null
            offsetMs = 0
            videoSec = -1
            lrcSec = -1.0
            songKey = uri.toString()
            status.text = "Müzik dosyası seçildi. BAŞLA 1'e bas."
        } else if (requestCode == 2) {
            try {
                val text = contentResolver.openInputStream(uri)!!
                    .bufferedReader().use { it.readText() }
                lines = parseLrc(text)
                status.text = "${lines.size} satır söz yüklendi."
            } catch (e: Exception) {
                logError("LRC okuma", e)
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
        val idx = currentLineIndex(pos)
        if (idx == -1) {
            lyricView.text = "♪ ♪ ♪"
            nextView.text = lines[0].words.joinToString(" ") { it.text }
            return
        }
        lyricView.text = render(lines[idx], pos)
        nextView.text = if (idx + 1 < lines.size)
            lines[idx + 1].words.joinToString(" ") { it.text } else ""
    }

    // ---------- Başlat 1 ----------
    private fun onStartClicked() {
        if (streamUrl == null && audioUri == null) {
            status.text = "Önce şarkı ara veya müzik dosyası seç."
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 10)
            return
        }
        startStage1()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startStage1()
            } else {
                status.text = "Mikrofon izni gerekli."
            }
        }
    }

    private fun setSource(p: MediaPlayer, processed: File?) {
        val url = streamUrl
        val sf = srcFile
        if (processed != null) {
            p.setDataSource(processed.absolutePath)
        } else if (sf != null && sf.exists() && sf.length() > 0) {
            p.setDataSource(sf.absolutePath)
        } else if (url != null) {
            p.setDataSource(this, Uri.parse(url), mapOf("User-Agent" to DownloaderImpl.USER_AGENT))
        } else {
            p.setDataSource(this, audioUri!!)
        }
    }

    private fun startStage1() {
        stopSession()
        val myId = ++sessionId
        stage = 1
        analysis = null
        instrFile = null
        srcFile = null
        offsetMs = 0
        plannedOffset = 0
        lyricView.text = ""
        nextView.text = ""

        val first = lines.firstOrNull()?.start ?: -1L
        val url = streamUrl
        val uri = audioUri
        val vSec = videoSec
        val lSec = lrcSec
        val str = strength
        val keyHex = Integer.toHexString(songKey.hashCode())
        dlog("BASLA1: ilkSoz=$first ms, video=$vSec sn, soz suresi=$lSec sn, anahtar=$keyHex, guc=$str")
        status.text = "Başlat 1: hazırlanıyor..."

        fun ui(text: String) {
            runOnUiThread { if (myId == sessionId) status.text = text }
        }

        Thread {
            try {
                // 1) Sarkiyi indir (YouTube ise)
                var src: File? = null
                if (url != null) {
                    val f = File(cacheDir, "source_audio.m4a")
                    val t0 = System.currentTimeMillis()
                    var lastPct = -1
                    val note = AudioFetcher.download(url, f) { pct ->
                        if (pct != lastPct) {
                            lastPct = pct
                            ui("Başlat 1: şarkı indiriliyor %$pct")
                        }
                    }
                    dlog("Indirme: $note, ${System.currentTimeMillis() - t0} ms")
                    src = f
                }
                if (myId != sessionId) return@Thread

                // 2) Vokal ayirma (onbellek -> yapay zeka -> eski yontem)
                val cached = File(filesDir, "inst_${keyHex}_s$str.wav")
                var instr: File = cached
                var res: VocalRemover.Result
                var usedFallback = false
                val t1 = System.currentTimeMillis()

                if (cached.exists() && cached.length() > 1_000_000) {
                    val dMs = (cached.length() - 44) / 4 * 1000L / 44100
                    res = VocalRemover.Result(true, -1, 0f, false, dMs, "onbellekten")
                    dlog("Onbellekten yuklendi: ${cached.name}")
                } else {
                    try {
                        val models = ModelStore.ensure(this) { label, pct ->
                            ui("Başlat 1: $label %$pct")
                        }
                        if (myId != sessionId) return@Thread
                        val tmp = File(filesDir, "inst_${keyHex}_s$str.tmp")
                        var lastLabel = ""
                        var lastP = -1
                        res = Separator.process(
                            this, src, uri, models.first, models.second, tmp, str,
                            { myId != sessionId }
                        ) { label, pct ->
                            if (label != lastLabel || pct != lastP) {
                                lastLabel = label
                                lastP = pct
                                ui("Başlat 1: $label %$pct")
                            }
                        }
                        if (res.ok) {
                            if (cached.exists()) cached.delete()
                            tmp.renameTo(cached)
                            pruneCache(cached)
                        }
                    } catch (e: Throwable) {
                        logError("yapay zeka ayirma", e)
                        usedFallback = true
                        ui("Başlat 1: yapay zekâ çalışmadı, eski yöntem deneniyor...")
                        val old = File(cacheDir, "instrumental.wav")
                        res = VocalRemover.process(this, src, uri, old, 0L, 0L, { myId != sessionId }) { pct ->
                            ui("Başlat 1: eski yöntemle ayrılıyor %$pct")
                        }
                        instr = old
                    }
                }
                dlog("Ayirma ${System.currentTimeMillis() - t1} ms surdu")
                if (myId != sessionId) return@Thread

                // 3) Senkron plani
                val durMatch = lSec > 0 && vSec > 0 && Math.abs(vSec - lSec) <= 3.0
                val planned = when {
                    first < 0 -> 0L
                    durMatch -> 0L
                    res.reliable && res.onsetMs >= 0 && Math.abs(res.onsetMs - first) <= 30000L -> res.onsetMs - first
                    else -> 0L
                }

                val sn = StringBuilder()
                if (first >= 0) {
                    if (durMatch) {
                        sn.append("\nSüre eşleşti (video $vSec sn, söz ${lSec.toInt()} sn): söz zamanı doğrudan kullanılıyor.")
                    } else if (planned != 0L) {
                        sn.append("\nVokal girişi ${"%.1f".format(res.onsetMs / 1000.0)} sn bulundu, " +
                            "kaydırma ${"%.1f".format(planned / 1000.0)} sn.")
                    } else {
                        sn.append("\n⚠ Senkron emin değil, kaydırma 0. Kayıyorsa -/+ ile düzelt, sonra DEBUG KAYDET.")
                    }
                }
                if (usedFallback) {
                    sn.append("\n⚠ Yapay zekâ modeli çalışmadı, eski yöntem kullanıldı (vokal kalabilir). DEBUG KAYDET.")
                }
                if (vSec > 0 && res.ok && res.durationMs < vSec * 1000 - 4000) {
                    sn.append("\n⚠ Ses ${res.durationMs / 1000} sn, video $vSec sn: şarkı eksik inmiş olabilir.")
                }
                if (!res.ok) sn.append("\n(Vokal silinemedi: ${res.note})")

                runOnUiThread {
                    if (myId != sessionId) return@runOnUiThread
                    srcFile = src
                    analysis = res
                    instrFile = if (res.ok) instr else null
                    plannedOffset = planned
                    syncNote = sn.toString()
                    dlog("Ayirma bitti: ok=${res.ok} onset=${res.onsetMs} oran=${res.confDb} " +
                        "bulundu=${res.reliable} sure=${res.durationMs} ms not=${res.note} planlanan=$planned")
                    startStage2(true)
                }
            } catch (e: Throwable) {
                logError("asama1", e)
                runOnUiThread {
                    if (myId == sessionId) {
                        stage = 0
                        status.text = "Hata: ${e.javaClass.simpleName}: ${e.message}\n('DEBUG KAYDET' butonuna bas)"
                    }
                }
            }
        }.start()
    }

    // ---------- Başlat 2 ----------
    private fun startStage2(auto: Boolean) {
        val a = analysis
        if (a == null) {
            status.text = "Önce BAŞLA 1'e bas, hazırlık bitsin."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Mikrofon izni gerekli. BAŞLA 1'e bas."
            return
        }
        stopSession()
        stage = 2
        offsetMs = plannedOffset
        dlog("BASLA2 (${if (auto) "otomatik" else "elle"}): offset=$offsetMs vokalsiz=${instrFile != null} " +
            "muzik sesi=${(musicVol * 100).toInt()}%")
        beginPlayback(instrFile)
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
            setSource(p, processed)
            p.setVolume(musicVol, musicVol)
            p.setOnPreparedListener {
                try {
                    it.setVolume(musicVol, musicVol)
                    recorder?.start()
                    it.start()
                    running = true
                    handler.post(ticker)
                    status.text = "Çalıyor ve kaydediyor... (kulaklık tak)$syncNote"
                } catch (e: Exception) {
                    logError("asama2 baslatma", e)
                    status.text = "Başlatılamadı: ${e.message}"
                    stopSession()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                logError("MediaPlayer asama2", Exception("what=$what extra=$extra"))
                status.text = "Oynatma hatası ($what/$extra)\n('DEBUG KAYDET' butonuna bas)"
                stopSession()
                true
            }
            p.setOnCompletionListener { stopSession() }
            player = p
            status.text = "Başlat 2: müzik yükleniyor..."
            p.prepareAsync()
        } catch (e: Exception) {
            logError("beginPlayback", e)
            status.text = "Hata: ${e.message}"
            stopSession()
        }
    }

    private fun stopSession() {
        sessionId++
        running = false
        stage = 0
        handler.removeCallbacks(ticker)
        val hadRecorder = recorder != null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null

        if (hadRecorder) {
            val f = recFile
            if (f != null && f.exists() && f.length() > 0) {
                status.text = "Kayıt kaydediliyor..."
                Thread {
                    val ok = copyToDownloads(f, f.name, "audio/mp4")
                    runOnUiThread {
                        status.text = if (ok) "Kayıt İndirilenler klasörüne kaydedildi:\n${f.name}"
                        else "İndirilenlere yazılamadı. Kayıt burada: ${f.absolutePath}"
                    }
                }.start()
            }
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
            logError("kaydı dinle", e)
            status.text = "Oynatılamadı: ${e.message}"
        }
    }

    override fun onDestroy() {
        stopSession()
        reviewPlayer?.release()
        super.onDestroy()
    }
}
