package com.example.clipper

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class MainActivity : Activity() {

    private fun parseTime(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            total = total * 60 + n
        }
        return total
    }

    private fun fmt(t: Int) = "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)

    private fun saveToGallery(file: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clipper")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 120, 48, 48)

        val title = TextView(this)
        title.text = "Clipper"
        title.textSize = 28f

        val link = EditText(this)
        link.hint = "YouTube ya Twitch link paste karo"

        val moment = EditText(this)
        moment.hint = "Moment, jaise 1:47:42"

        val lenLabel = TextView(this)
        lenLabel.text = "Clip length"

        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL
        for (s in listOf(15, 30, 60)) {
            val rb = RadioButton(this)
            rb.text = "$s sec"
            rb.id = 1000 + s
            group.addView(rb)
        }
        group.check(1015)

        val btn = Button(this)
        btn.text = "Make clips"

        val result = TextView(this)
        result.textSize = 15f

        btn.setOnClickListener {
            val url = link.text.toString().trim()
            val start = parseTime(moment.text.toString())
            val sec = group.checkedRadioButtonId - 1000
            if (url.isEmpty() || start == null) {
                result.text = "Link aur moment sahi daalo (jaise 1:47:42)"
                return@setOnClickListener
            }
            btn.isEnabled = false
            result.text = "Kaam shuru... 1 se 2 minute ruko"

            Thread {
                try {
                    val dir = File(getExternalFilesDir(null), "clips")
                    dir.mkdirs()
                    val name = "clip_${System.currentTimeMillis()}"
                    val req = YoutubeDLRequest(url)
                    req.addOption("--download-sections", "*${fmt(start)}-${fmt(start + sec)}")
                    req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                    req.addOption("--merge-output-format", "mp4")
                    req.addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
                    YoutubeDL.getInstance().execute(req) { progress, _, line ->
                        runOnUiThread { result.text = "Progress: $progress%\n$line" }
                    }
                    val out = dir.listFiles()?.firstOrNull {
                        it.name.startsWith(name) && it.extension == "mp4"
                    }
                    val saved = out != null && saveToGallery(out)
                    runOnUiThread {
                        result.text = if (saved) "Ho gaya! Gallery mein dekho: Movies/Clipper"
                        else "Download hua, par gallery mein save nahi hua"
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        result.text = "Error: ${e.message}"
                        btn.isEnabled = true
                    }
                }
            }.start()
        }

        root.addView(title)
        root.addView(link)
        root.addView(moment)
        root.addView(lenLabel)
        root.addView(group)
        root.addView(btn)
        root.addView(result)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
