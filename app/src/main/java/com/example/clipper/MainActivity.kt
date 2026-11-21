package com.example.clipper

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
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

    // Most replayed graph se sabse zyada dekhe gaye hisse dhoondo
    private fun findPeaks(url: String, count: Int, sec: Int): List<Int> {
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--skip-download")
        req.addOption("--no-playlist")
        val json = YoutubeDL.getInstance().execute(req).out
        val obj = org.json.JSONObject(json)
        val arr = obj.optJSONArray("heatmap") ?: return emptyList()
        val items = ArrayList<Pair<Double, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mid = (o.getDouble("start_time") + o.getDouble("end_time")) / 2
            items.add(Pair(mid, o.getDouble("value")))
        }
        val sorted = items.sortedByDescending { item -> item.second }
        val picks = ArrayList<Int>()
        for (item in sorted) {
            val s = maxOf(0, (item.first - sec / 2.0).toInt())
            if (picks.none { p -> Math.abs(p - s) < 60 }) picks.add(s)
            if (picks.size >= count) break
        }
        return picks.sorted()
    }

    private fun makeClip(
        url: String, start: Int, sec: Int, makeVertical: Boolean,
        label: String, onLine: (String) -> Unit
    ): Boolean {
        val dir = File(getExternalFilesDir(null), "clips")
        dir.mkdirs()
        val name = "clip_${System.currentTimeMillis()}"
        val req = YoutubeDLRequest(url)
        req.addOption("--download-sections", "*${fmt(start)}-${fmt(start + sec)}")
        req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
        if (makeVertical) {
            val filter = "split=2[a][b];" +
                "[a]crop=w=ih*1080/1170:h=ih,scale=1080:1170[t];" +
                "[b]crop=w=iw/4:h=iw/4/1.44:x=iw*3/4:y=ih-iw/4/1.44,scale=1080:750[c];" +
                "[t][c]vstack,setsar=1"
            req.addOption("--merge-output-format", "mkv")
            req.addOption("--recode-video", "mp4")
            req.addOption(
                "--postprocessor-args",
                "VideoConvertor:-vf $filter -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p"
            )
        } else {
            req.addOption("--merge-output-format", "mp4")
        }
        req.addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req) { progress, _, line ->
            onLine("$label Progress: $progress%\n$line")
        }
        val out = dir.listFiles()?.firstOrNull {
            it.name.startsWith(name) && it.extension == "mp4"
        }
        return out != null && saveToGallery(out)
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
        moment.hint = "Moment(s): 1:47:42 ya 3:00, 10:00"

        val auto = CheckBox(this)
        auto.text = "Auto: viral moments dhoondo (Most replayed, top 3)"

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

        val vertical = CheckBox(this)
        vertical.text = "Vertical 9:16 (game upar, facecam neeche)"
        vertical.isChecked = true

        val btn = Button(this)
        btn.text = "Make clips"
        btn.isEnabled = false

        val result = TextView(this)
        result.textSize = 15f
        result.text = "Engine tayyar ho raha hai..."

        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                runOnUiThread {
                    result.text = "yt-dlp update ho raha hai... 1 minute ruko"
                }
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        applicationContext,
                        YoutubeDL.UpdateChannel._NIGHTLY
                    )
                } catch (t: Throwable) {
                    // update fail ho to purane engine se hi chalao
                }
                val ver = try {
                    YoutubeDL.getInstance().versionName(applicationContext)
                } catch (t: Throwable) {
                    "?"
                }
                runOnUiThread {
                    result.text = "Engine tayyar (yt-dlp $ver). Link daalo."
                    btn.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    result.text = "Engine error: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }.start()

        btn.setOnClickListener {
            val url = link.text.toString().trim()
            val sec = group.checkedRadioButtonId - 1000
            val makeVertical = vertical.isChecked
            val isAuto = auto.isChecked
            val momentText = moment.text.toString()

            if (url.isEmpty()) {
                result.text = "Pehle link paste karo"
                return@setOnClickListener
            }
            var manual: List<Int> = emptyList()
            if (!isAuto) {
                val parsed = momentText.split(",").map { parseTime(it) }
                if (parsed.isEmpty() || parsed.any { it == null }) {
                    result.text = "Moment sahi daalo (jaise 1:47:42 ya 3:00, 10:00), ya Auto tick karo"
                    return@setOnClickListener
                }
                manual = parsed.filterNotNull()
            }

            btn.isEnabled = false
            result.text = "Kaam shuru... app khula rakho"

            Thread {
                try {
                    val starts: List<Int>
                    if (isAuto) {
                        runOnUiThread { result.text = "Viral moments dhoondh raha hoon..." }
                        starts = findPeaks(url, 3, sec)
                        if (starts.isEmpty()) {
                            runOnUiThread {
                                result.text = "Is video mein Most replayed graph nahi mila " +
                                    "(live stream ya kam views). Moment khud daalo."
                                btn.isEnabled = true
                            }
                            return@Thread
                        }
                        runOnUiThread {
                            result.text = "Mile: " + starts.joinToString { fmt(it) }
                        }
                    } else {
                        starts = manual
                    }

                    var saved = 0
                    for ((i, st) in starts.withIndex()) {
                        val label = "Clip ${i + 1} of ${starts.size} (${fmt(st)})"
                        val ok = makeClip(url, st, sec, makeVertical, label) { text ->
                            runOnUiThread { result.text = text }
                        }
                        if (ok) saved++
                    }
                    runOnUiThread {
                        result.text = "Ho gaya! $saved clips Gallery mein: Movies/Clipper"
                        btn.isEnabled = true
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        result.text = "Error: ${t.message}"
                        btn.isEnabled = true
                    }
                }
            }.start()
        }

        root.addView(title)
        root.addView(link)
        root.addView(moment)
        root.addView(auto)
        root.addView(lenLabel)
        root.addView(group)
        root.addView(vertical)
        root.addView(btn)
        root.addView(result)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
