package com.example.clipper

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var result: TextView
    private lateinit var btn: Button
    private lateinit var clipsList: LinearLayout
    private var customMain: RectF? = null
    private var customStreamer: RectF? = null

    private fun say(s: String) {
        runOnUiThread { result.text = s }
    }

    private fun setBusy(busy: Boolean) {
        runOnUiThread { btn.isEnabled = !busy }
    }

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

    private fun parseMoments(text: String): List<Moment>? {
        val out = ArrayList<Moment>()
        for (raw in text.split(",")) {
            val t = raw.trim().lowercase()
            if (t.isEmpty()) continue
            val m = Regex("^(\\d+)\\s*min(ute)?s?\\s*ago$").find(t)
            if (m != null) {
                out.add(Moment(true, m.groupValues[1].toInt()))
                continue
            }
            val s = parseTime(t) ?: return null
            out.add(Moment(false, s))
        }
        return out
    }

    private fun fmt(t: Int) = "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)

    private fun f4(x: Float) = String.format(Locale.US, "%.4f", x)

    private fun cropExpr(r: RectF) =
        "crop=iw*${f4(r.width())}:ih*${f4(r.height())}:iw*${f4(r.left)}:ih*${f4(r.top)}"

    private fun verticalFilter(main: RectF, st: RectF): String =
        "split=2[a][b];" +
            "[a]${cropExpr(main)},scale=1080:1170:force_original_aspect_ratio=increase,crop=1080:1170[t];" +
            "[b]${cropExpr(st)},scale=1080:750:force_original_aspect_ratio=increase,crop=1080:750[c];" +
            "[t][c]vstack,setsar=1"

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

    private fun fetchInfo(url: String): JSONObject {
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--skip-download")
        req.addOption("--no-playlist")
        val json = YoutubeDL.getInstance().execute(req).out
        return JSONObject(json)
    }

    // "3 min ago" jaise moment ko asli second mein badlo
    private fun resolveStart(m: Moment, info: JSONObject?): Int? {
        if (!m.rel) return m.v
        if (info == null) return null
        val live = info.optBoolean("is_live", false)
        if (live) {
            val ts = info.optLong("release_timestamp", info.optLong("timestamp", 0L))
            if (ts <= 0L) return null
            val elapsed = (System.currentTimeMillis() / 1000 - ts).toInt()
            return maxOf(0, elapsed - m.v * 60)
        }
        val dur = info.optDouble("duration", 0.0)
        if (dur <= 0.0) return null
        return maxOf(0, dur.toInt() - m.v * 60)
    }

    private fun downloadAudio(url: String): File? {
        val dir = File(getExternalFilesDir(null), "audio")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val req = YoutubeDLRequest(url)
        req.addOption("-f", "wa[ext=m4a]/wa/ba")
        req.addOption("--no-playlist")
        req.addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req) { progress, _, line ->
            say("Awaaz download: $progress%\n$line")
        }
        return dir.listFiles()?.firstOrNull { it.name.startsWith("audio.") }
    }

    private fun fetchFrame(url: String, startSec: Int, live: Boolean): Bitmap? {
        val dir = File(cacheDir, "frame")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val req = YoutubeDLRequest(url)
        req.addOption("--download-sections", "*${fmt(startSec)}-${fmt(startSec + 3)}")
        req.addOption("-f", "bv*[height<=480][ext=mp4]/bv*[height<=480]/b[height<=480]/b")
        req.addOption("--no-playlist")
        if (live) req.addOption("--live-from-start")
        req.addOption("-o", File(dir, "f.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req)
        val file = dir.listFiles()?.firstOrNull {
            it.name.startsWith("f.") && it.length() > 0 &&
                (it.extension == "mp4" || it.extension == "webm" || it.extension == "mkv")
        } ?: return null
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val bmp = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.getFrameAtTime(0)
        r.release()
        return bmp
    }

    private fun showLayoutDialog(bmp: Bitmap) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(24, 24, 24, 24)
        val tip = TextView(this)
        tip.text = "Peela box = game, pink box = streamer. Box ko kheench ke hilao, kone ke dot se chhota-bada karo."
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.9).toInt()
        val h = (w.toFloat() * bmp.height / bmp.width).toInt()
        val lv = LayoutView(this, bmp)
        box.addView(tip)
        box.addView(lv, LinearLayout.LayoutParams(w, h))
        AlertDialog.Builder(this)
            .setTitle("Layout set karo")
            .setView(box)
            .setPositiveButton("Use this layout") { _, _ ->
                customMain = RectF(lv.main)
                customStreamer = RectF(lv.streamer)
                say("Layout set ho gaya. Ab Make clips dabao.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeClip(
        url: String, start: Int, sec: Int, filter: String?, live: Boolean, label: String
    ): Boolean {
        val dir = File(getExternalFilesDir(null), "clips")
        dir.mkdirs()
        val name = "clip_${System.currentTimeMillis()}"
        val req = YoutubeDLRequest(url)
        req.addOption("--download-sections", "*${fmt(start)}-${fmt(start + sec)}")
        req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
        req.addOption("--no-playlist")
        if (live) req.addOption("--live-from-start")
        if (filter != null) {
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
            say("$label Progress: $progress%\n$line")
        }
        val out = dir.listFiles()?.firstOrNull {
            it.name.startsWith(name) && it.extension == "mp4"
        }
        val ok = out != null && saveToGallery(out)
        out?.delete()
        return ok
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 120, 48, 48)

        val title = TextView(this)
        title.text = "Clipper"
        title.textSize = 28f

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val tabCreate = Button(this)
        tabCreate.text = "Create"
        val tabClips = Button(this)
        tabClips.text = "My clips"
        tabs.addView(tabCreate)
        tabs.addView(tabClips)

        val createBox = LinearLayout(this)
        createBox.orientation = LinearLayout.VERTICAL

        val link = EditText(this)
        link.hint = "YouTube ya Twitch link paste karo"

        val moment = EditText(this)
        moment.hint = "Moment(s): 1:47:42 ya 3:00, 10:00"

        val agoRow = LinearLayout(this)
        agoRow.orientation = LinearLayout.HORIZONTAL
        for (m in listOf(1, 3, 5)) {
            val b = Button(this)
            b.text = "$m min ago"
            b.setOnClickListener { moment.setText("$m min ago") }
            agoRow.addView(b)
        }

        val auto = CheckBox(this)
        auto.text = "Auto: viral moments dhoondo (top 3)"

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

        val layLabel = TextView(this)
        layLabel.text = "Layout"
        val layoutGroup = RadioGroup(this)
        layoutGroup.orientation = RadioGroup.VERTICAL
        val rbAuto = RadioButton(this)
        rbAuto.text = "Auto (vertical, facecam neeche-right maan ke)"
        rbAuto.id = 2001
        val rbCustom = RadioButton(this)
        rbCustom.text = "Set it myself (khud box chuno)"
        rbCustom.id = 2002
        val rbFull = RadioButton(this)
        rbFull.text = "Full frame (seedhi video, bina kaate)"
        rbFull.id = 2003
        layoutGroup.addView(rbAuto)
        layoutGroup.addView(rbCustom)
        layoutGroup.addView(rbFull)
        layoutGroup.check(2001)

        val setBtn = Button(this)
        setBtn.text = "Layout set karo (frame dikhao)"
        setBtn.visibility = View.GONE
        layoutGroup.setOnCheckedChangeListener { _, id ->
            setBtn.visibility = if (id == 2002) View.VISIBLE else View.GONE
        }

        btn = Button(this)
        btn.text = "Make clips"
        btn.isEnabled = false

        result = TextView(this)
        result.textSize = 15f
        result.text = "Engine tayyar ho raha hai..."

        createBox.addView(link)
        createBox.addView(moment)
        createBox.addView(agoRow)
        createBox.addView(auto)
        createBox.addView(lenLabel)
        createBox.addView(group)
        createBox.addView(layLabel)
        createBox.addView(layoutGroup)
        createBox.addView(setBtn)
        createBox.addView(btn)
        createBox.addView(result)

        clipsList = LinearLayout(this)
        clipsList.orientation = LinearLayout.VERTICAL
        clipsList.visibility = View.GONE
        val clipsUi = ClipsUi(this)

        tabCreate.setOnClickListener {
            createBox.visibility = View.VISIBLE
            clipsList.visibility = View.GONE
        }
        tabClips.setOnClickListener {
            createBox.visibility = View.GONE
            clipsList.visibility = View.VISIBLE
            clipsUi.refresh(clipsList)
        }

        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                say("yt-dlp update ho raha hai... 1 minute ruko")
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
                say("Engine tayyar (yt-dlp $ver). Link daalo.")
                setBusy(false)
            } catch (t: Throwable) {
                say("Engine error: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()

        setBtn.setOnClickListener {
            val url = link.text.toString().trim()
            if (url.isEmpty()) {
                say("Pehle link paste karo")
                return@setOnClickListener
            }
            val first = parseMoments(moment.text.toString())?.firstOrNull()
            say("Frame la raha hoon... 1 minute ruko")
            Thread {
                try {
                    var st = 60
                    var live = false
                    if (first != null) {
                        if (first.rel) {
                            val info = fetchInfo(url)
                            live = info.optBoolean("is_live", false)
                            st = resolveStart(first, info) ?: 60
                        } else {
                            st = first.v
                        }
                    }
                    val bmp = fetchFrame(url, st, live)
                    runOnUiThread {
                        if (bmp == null) {
                            result.text = "Frame nahi mila. Doosra moment try karo."
                        } else {
                            showLayoutDialog(bmp)
                        }
                    }
                } catch (t: Throwable) {
                    say("Error: ${t.message}")
                }
            }.start()
        }

        btn.setOnClickListener {
            val url = link.text.toString().trim()
            if (url.isEmpty()) {
                say("Pehle link paste karo")
                return@setOnClickListener
            }
            val sec = group.checkedRadioButtonId - 1000
            val mode = layoutGroup.checkedRadioButtonId
            val isAuto = auto.isChecked
            val parsed = if (isAuto) emptyList() else parseMoments(moment.text.toString())
            if (parsed == null || (!isAuto && parsed.isEmpty())) {
                say("Moment sahi daalo (jaise 1:47:42, 3:00, 10:00 ya 3 min ago), ya Auto tick karo")
                return@setOnClickListener
            }
            val cm = customMain
            val cs = customStreamer
            if (mode == 2002 && (cm == null || cs == null)) {
                say("Pehle 'Layout set karo' dabake box chuno")
                return@setOnClickListener
            }
            val filter: String? = when (mode) {
                2001 -> verticalFilter(RectF(0f, 0f, 1f, 1f), RectF(0.75f, 0.6914f, 1f, 1f))
                2002 -> verticalFilter(cm!!, cs!!)
                else -> null
            }

            setBusy(true)
            say("Kaam shuru... app khula rakho")

            Thread {
                try {
                    val needInfo = isAuto || parsed.any { it.rel }
                    var info: JSONObject? = null
                    if (needInfo) {
                        say("Video ki jaankari le raha hoon...")
                        info = fetchInfo(url)
                    }
                    val live = info?.optBoolean("is_live", false) ?: false

                    var starts: List<Int> = emptyList()
                    if (isAuto) {
                        if (live) {
                            say("Live stream par Auto nahi chalta. '3 min ago' ya time khud daalo.")
                            setBusy(false)
                            return@Thread
                        }
                        say("Pehle Most replayed graph dhoondh raha hoon...")
                        starts = try {
                            Analyzer.peaksFromInfo(info!!, 3, sec)
                        } catch (t: Throwable) {
                            emptyList()
                        }
                        if (starts.isEmpty()) {
                            say("Graph nahi mila. Ab awaaz se dhoondhta hoon (audio ~40 MB, 2 se 5 minute)...")
                            val file = downloadAudio(url)
                            if (file == null) {
                                say("Awaaz download nahi hui. Moment khud daalo.")
                                setBusy(false)
                                return@Thread
                            }
                            say("Awaaz analyse ho rahi hai... ruko")
                            starts = Analyzer.findLoudMoments(file, 3)
                            file.delete()
                        }
                        if (starts.isEmpty()) {
                            say("Koi tez moment nahi mila. Moment khud daalo.")
                            setBusy(false)
                            return@Thread
                        }
                        say("Mile: " + starts.joinToString { fmt(it) })
                    } else {
                        val list = ArrayList<Int>()
                        for (m in parsed) {
                            val s = resolveStart(m, info)
                            if (s == null) {
                                say("Is video ka time nahi mil paya. Seedha time likho (jaise 1:47:42).")
                                setBusy(false)
                                return@Thread
                            }
                            list.add(s)
                        }
                        starts = list
                    }

                    var saved = 0
                    for ((i, st) in starts.withIndex()) {
                        val label = "Clip ${i + 1} of ${starts.size} (${fmt(st)})"
                        if (makeClip(url, st, sec, filter, live, label)) saved++
                    }
                    say("Ho gaya! $saved clips bani. My clips tab mein dekho.")
                    setBusy(false)
                } catch (t: Throwable) {
                    say("Error: ${t.message}")
                    setBusy(false)
                }
            }.start()
        }

        root.addView(title)
        root.addView(tabs)
        root.addView(createBox)
        root.addView(clipsList)

        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
    }
}
