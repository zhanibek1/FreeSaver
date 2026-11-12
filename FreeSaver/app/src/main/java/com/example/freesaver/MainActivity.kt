package com.example.freesaver

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var downloadBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val qualities = listOf(
        "Лучшее доступное" to "bv*+ba/b",
        "1080p" to "bv*[height<=1080]+ba/b[height<=1080]",
        "720p" to "bv*[height<=720]+ba/b[height<=720]",
        "480p" to "bv*[height<=480]+ba/b[height<=480]",
        "360p" to "bv*[height<=360]+ba/b[height<=360]",
        "Только аудио (MP3)" to "AUDIO"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "FreeSaver"
            textSize = 28f
        })

        urlInput = EditText(this).apply {
            hint = "Вставьте ссылку на видео"
            setSingleLine(true)
        }
        root.addView(urlInput)

        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                qualities.map { it.first }
            )
        }
        root.addView(qualitySpinner)

        downloadBtn = Button(this).apply {
            text = "Скачать"
            isEnabled = false
            setOnClickListener { startDownload() }
        }
        root.addView(downloadBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar)

        statusText = TextView(this).apply {
            text = "Запускаю движок…"
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusText)

        updateBtn = Button(this).apply {
            text = "Обновить движок (если не качает)"
            isEnabled = false
            setOnClickListener { updateEngine() }
        }
        root.addView(updateBtn)

        setContentView(root)
        handleShareIntent(intent)

        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                runOnUiThread {
                    downloadBtn.isEnabled = true
                    updateBtn.isEnabled = true
                }
                setStatus("Готово. Вставьте ссылку и нажмите «Скачать».")
            } catch (e: Exception) {
                setStatus("Ошибка запуска: ${e.message}")
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val link = Regex("https?://\\S+").find(text)?.value ?: text
            urlInput.setText(link)
        }
    }

    private fun setStatus(t: String) = runOnUiThread { statusText.text = t }

    private fun setBusy(busy: Boolean) = runOnUiThread {
        downloadBtn.isEnabled = !busy
        updateBtn.isEnabled = !busy
    }

    private fun updateEngine() {
        setBusy(true)
        Thread {
            try {
                setStatus("Обновляю движок…")
                YoutubeDL.getInstance().updateYoutubeDL(application)
                setStatus("Движок обновлён.")
            } catch (e: Exception) {
                setStatus("Ошибка обновления: ${e.message}")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    private fun startDownload() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Вставьте ссылку", Toast.LENGTH_SHORT).show()
            return
        }
        val format = qualities[qualitySpinner.selectedItemPosition].second
        setBusy(true)
        progressBar.progress = 0

        Thread {
            val tmp = File(cacheDir, "dl")
            try {
                tmp.deleteRecursively()
                tmp.mkdirs()

                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
                if (format == "AUDIO") {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                } else {
                    req.addOption("-f", format)
                    req.addOption("--merge-output-format", "mp4")
                }

                setStatus("Загрузка…")
                YoutubeDL.getInstance().execute(req, "freesaver") { progress, _, _ ->
                    runOnUiThread {
                        progressBar.progress = progress.toInt()
                        statusText.text = "Загрузка… ${progress.toInt()}%"
                    }
                }

                var saved = 0
                val allowed = setOf("mp4", "mp3", "webm", "mkv", "m4a")
                tmp.listFiles()
                    ?.filter { it.extension.lowercase() in allowed }
                    ?.forEach { saveToDownloads(it); saved++ }

                setStatus(
                    if (saved > 0) "Готово! Файл в папке Загрузки/FreeSaver"
                    else "Файл не найден. Попробуйте обновить движок."
                )
            } catch (e: Exception) {
                setStatus("Ошибка: ${e.message}")
            } finally {
                tmp.deleteRecursively()
                setBusy(false)
            }
        }.start()
    }

    private fun saveToDownloads(f: File) {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FreeSaver")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Не удалось создать файл в Загрузках")
        contentResolver.openOutputStream(uri)!!.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
    }
}
