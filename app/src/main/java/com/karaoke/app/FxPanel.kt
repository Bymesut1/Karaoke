package com.karaoke.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ses efektleri bolumu: mod secimi, onizleme, kaydetme. */
class FxPanel(
    private val act: Activity,
    private val getRec: () -> File?,
    private val getInstr: () -> File?,
    private val getMusicVol: () -> Float,
    private val say: (String) -> Unit,
    private val log: (String) -> Unit,
    private val saveDl: (File, String, String) -> Boolean
) {
    private val prefs = act.getSharedPreferences("karaoke_fx", Context.MODE_PRIVATE)
    private var mode = prefs.getInt("mode", 0).coerceIn(0, VoiceFx.MODES.size - 1)
    private var voiceLevel = prefs.getInt("voice", 100).coerceIn(0, 200)
    private var shiftMs = prefs.getInt("shift", -120)
    private var preview: MediaPlayer? = null
    private val modeBtns = ArrayList<Button>()
    private lateinit var shiftLabel: TextView
    private lateinit var voiceLabel: TextView
    @Volatile private var busy = false

    private fun refreshModes() {
        for (i in modeBtns.indices) {
            modeBtns[i].text = (if (i == mode) "● " else "") + VoiceFx.MODES[i]
        }
    }

    private fun refreshShift() {
        shiftLabel.text = "Ses hizası: $shiftMs ms (eksi = sesin öne alınır)"
    }

    fun build(): LinearLayout {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 8)
        }
        root.addView(TextView(act).apply {
            text = "SES EFEKTLERİ (kayıttan sonra)"
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, 16, 0, 4)
        })
        root.addView(TextView(act).apply {
            text = "Kayıt yaptıktan sonra mod seç, ÖNİZLE ile müzikle birlikte dinle, beğenirsen KAYDET."
            setTextColor(Color.GRAY); textSize = 12f
        })

        for (row in VoiceFx.MODES.indices.chunked(3)) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in row) {
                val b = Button(act).apply {
                    textSize = 12f
                    setOnClickListener {
                        mode = i
                        prefs.edit().putInt("mode", mode).apply()
                        refreshModes()
                        say("Mod: ${VoiceFx.MODES[i]}. ÖNİZLE'ye bas.")
                    }
                }
                modeBtns.add(b)
                line.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(line)
        }
        refreshModes()

        voiceLabel = TextView(act).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
            text = "Benim sesim: %$voiceLevel"
        }
        root.addView(voiceLabel)
        root.addView(SeekBar(act).apply {
            max = 200
            progress = voiceLevel
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    voiceLevel = p
                    voiceLabel.text = "Benim sesim: %$p"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt("voice", voiceLevel).apply()
                }
            })
        })

        shiftLabel = TextView(act).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
        }
        root.addView(shiftLabel)
        refreshShift()
        val shiftRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        shiftRow.addView(Button(act).apply {
            text = "-50 ms"
            setOnClickListener {
                shiftMs -= 50
                prefs.edit().putInt("shift", shiftMs).apply()
                refreshShift()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        shiftRow.addView(Button(act).apply {
            text = "+50 ms"
            setOnClickListener {
                shiftMs += 50
                prefs.edit().putInt("shift", shiftMs).apply()
                refreshShift()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(shiftRow)

        root.addView(Button(act).apply {
            text = "ÖNİZLE (efektli karışımı dinle)"
            setOnClickListener { run(play = true, save = false) }
        })
        root.addView(Button(act).apply {
            text = "Önizlemeyi durdur"
            setOnClickListener { stopPreview() }
        })
        root.addView(Button(act).apply {
            text = "EFEKTLİ KAYDI KAYDET (İndirilenler)"
            setOnClickListener { run(play = false, save = true) }
        })
        return root
    }

    private fun stopPreview() {
        try { preview?.stop() } catch (_: Exception) {}
        try { preview?.release() } catch (_: Exception) {}
        preview = null
    }

    private fun startPreview(f: File) {
        try {
            stopPreview()
            preview = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            log("HATA onizleme: ${e.message}")
            say("Önizleme çalınamadı: ${e.message}")
        }
    }

    private fun latestRecording(): File? {
        return act.getExternalFilesDir(null)
            ?.listFiles { f -> f.name.startsWith("kayit_") && f.name.endsWith(".m4a") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun run(play: Boolean, save: Boolean) {
        val rec = getRec()?.takeIf { it.exists() && it.length() > 1000 } ?: latestRecording()
        if (rec == null) {
            say("Önce bir kayıt yap (BAŞLA 2 çalışırken söyle, sonra DURDUR).")
            return
        }
        if (busy) {
            say("İşleniyor, bekle...")
            return
        }
        busy = true
        stopPreview()
        val m = mode
        val vl = voiceLevel / 100f
        val sh = shiftMs
        val mv = getMusicVol()
        val instr = getInstr()
        say("Efekt uygulanıyor (${VoiceFx.MODES[m]})...")

        Thread {
            try {
                val out = File(act.cacheDir, "fx_mix.wav")
                val note = VoiceFx.render(rec, instr, m, vl, mv, sh, out) { pct ->
                    act.runOnUiThread { say("Efekt uygulanıyor (${VoiceFx.MODES[m]}) %$pct") }
                }
                log("Efekt: mod=${VoiceFx.MODES[m]} ses=%${(vl * 100).toInt()} muzik=%${(mv * 100).toInt()} $note")
                if (play) {
                    act.runOnUiThread {
                        say("Önizleme çalıyor: ${VoiceFx.MODES[m]}")
                        startPreview(out)
                    }
                }
                if (save) saveOut(out, m)
            } catch (e: Throwable) {
                log("HATA efekt: ${e.javaClass.name}: ${e.message}")
                act.runOnUiThread { say("Efekt hatası: ${e.message}\n('DEBUG KAYDET' butonuna bas)") }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun saveOut(wav: File, m: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "karaoke_${VoiceFx.KEYS[m]}_$stamp"
        act.runOnUiThread { say("M4A'ya çevriliyor...") }
        val m4a = File(act.cacheDir, "fx_mix.m4a")
        var ok = false
        var name = "$base.m4a"
        try {
            Aac.encode(wav, m4a)
            ok = saveDl(m4a, name, "audio/mp4")
        } catch (e: Throwable) {
            log("HATA aac: ${e.javaClass.name}: ${e.message}")
        }
        if (!ok) {
            name = "$base.wav"
            ok = saveDl(wav, name, "audio/wav")
        }
        act.runOnUiThread {
            say(if (ok) "Kaydedildi (İndirilenler):\n$name" else "Kaydedilemedi. DEBUG KAYDET'e bas.")
        }
    }
}
