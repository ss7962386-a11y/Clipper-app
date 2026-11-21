package com.example.clipper

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.Locale

class Moment(val rel: Boolean, val v: Int)

// Do box kheenchne wala view: peela = game, pink = streamer
class LayoutView(context: Context, private val bmp: Bitmap) : View(context) {
    val main = RectF(0.03f, 0.03f, 0.78f, 0.97f)
    val streamer = RectF(0.78f, 0.62f, 0.99f, 0.97f)
    private val yellowP = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val pinkP = Paint().apply {
        color = Color.rgb(255, 64, 160); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val textP = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val imgRect = RectF()
    private var active = 0
    private var lastX = 0f
    private var lastY = 0f

    private fun toView(r: RectF) = RectF(
        imgRect.left + r.left * imgRect.width(),
        imgRect.top + r.top * imgRect.height(),
        imgRect.left + r.right * imgRect.width(),
        imgRect.top + r.bottom * imgRect.height()
    )

    private fun drawBox(canvas: Canvas, r: RectF, p: Paint, label: String) {
        val v = toView(r)
        canvas.drawRect(v, p)
        val fill = Paint(p)
        fill.style = Paint.Style.FILL
        canvas.drawCircle(v.right, v.bottom, 22f, fill)
        canvas.drawText(label, v.left + 12f, v.top + 38f, textP)
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (vw - w) / 2
        val top = (vh - h) / 2
        imgRect.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, null, imgRect, null)
        drawBox(canvas, main, yellowP, "MAIN (game)")
        drawBox(canvas, streamer, pinkP, "STREAMER")
    }

    private fun near(v: RectF, x: Float, y: Float) =
        Math.abs(x - v.right) < 60 && Math.abs(y - v.bottom) < 60

    private fun hit(x: Float, y: Float): Int {
        val s = toView(streamer)
        val m = toView(main)
        if (near(s, x, y)) return 4
        if (s.contains(x, y)) return 3
        if (near(m, x, y)) return 2
        if (m.contains(x, y)) return 1
        return 0
    }

    private fun move(r: RectF, dx: Float, dy: Float) {
        val w = r.width()
        val h = r.height()
        val nl = (r.left + dx).coerceIn(0f, 1f - w)
        val nt = (r.top + dy).coerceIn(0f, 1f - h)
        r.set(nl, nt, nl + w, nt + h)
    }

    private fun resize(r: RectF, dx: Float, dy: Float) {
        r.right = (r.right + dx).coerceIn(r.left + 0.05f, 1f)
        r.bottom = (r.bottom + dy).coerceIn(r.top + 0.05f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(e.x, e.y)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (active != 0 && imgRect.width() > 0) {
                    val dx = (e.x - lastX) / imgRect.width()
                    val dy = (e.y - lastY) / imgRect.height()
                    when (active) {
                        1 -> move(main, dx, dy)
                        2 -> resize(main, dx, dy)
                        3 -> move(streamer, dx, dy)
                        4 -> resize(streamer, dx, dy)
                    }
                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = 0
        }
        return true
    }
}

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

    // ---------- chhote helper ----------

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

    // ---------- video ki jaankari ----------

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

    // ---------- viral moments ----------

    private fun peaksFromInfo(obj: JSONObject, count: Int, sec: Int): List<Int> {
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

    private fun findLoudMoments(file: File, count: Int): List<Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        val fm: MediaFormat = format ?: run {
            extractor.release()
            return emptyList()
        }
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fm.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fm, null, null, 0)
        codec.start()

        var rate = fm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val perSec = ArrayList<Double>()
        var sumSq = 0.0
        var n = 0L
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false

        while (!outDone) {
            if (!inDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val v = sb.get() / 32768.0
                        sumSq += v * v
                        n++
                        if (n >= rate.toLong() * ch) {
                            perSec.add(Math.sqrt(sumSq / n))
                            sumSq = 0.0
                            n = 0
                        }
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val len = perSec.size
        if (len < 90) return emptyList()

        val db = DoubleArray(len) { maxOf(-60.0, 20 * Math.log10(perSec[it] + 1e-5)) }
        val median = db.sorted()[len / 2]
        val pre = DoubleArray(len + 1)
        val pre2 = DoubleArray(len + 1)
        for (i in 0 until len) {
            pre[i + 1] = pre[i] + db[i]
            pre2[i + 1] = pre2[i] + db[i] * db[i]
        }

        val w = 10
        val scores = ArrayList<Pair<Int, Double>>()
        for (i in 45..(len - w)) {
            val mean = (pre[i + w] - pre[i]) / w
            if (mean < median) continue
            val variance = (pre2[i + w] - pre2[i]) / w - mean * mean
            val std = Math.sqrt(maxOf(variance, 0.0))
            val from = maxOf(0, i - 60)
            val prev = (pre[i] - pre[from]) / (i - from)
            val rise = mean - prev
            scores.add(Pair(i, rise + 1.5 * std))
        }
        if (scores.isEmpty()) return emptyList()

        val sorted = scores.sortedByDescending { it.second }
        val picks = ArrayList<Int>()
        for (p in sorted) {
            val s = maxOf(0, p.first - 3)
            if (picks.none { Math.abs(it - s) < 90 }) picks.add(s)
            if (picks.size >= count) break
        }
        return picks.sorted()
    }

    // ---------- layout ke liye frame ----------

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

    // ---------- clip banana ----------

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

    // ---------- My clips ----------

    private fun refreshClips() {
        clipsList.removeAllViews()
        if (Build.VERSION.SDK_INT < 29) {
            val t = TextView(this)
            t.text = "Is Android version par My clips nahi chalta. Gallery mein Movies/Clipper dekho."
            clipsList.addView(t)
            return
        }
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        var count = 0
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel,
            arrayOf("Movies/Clipper%"), "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "clip"
                val size = c.getLong(2)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                clipsList.addView(clipRow(uri, name, size))
                count++
            }
        }
        if (count == 0) {
            val t = TextView(this)
            t.text = "Abhi koi clip nahi hai."
            clipsList.addView(t)
        }
    }

    private fun clipRow(uri: Uri, name: String, size: Long): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, 24, 0, 24)
        val t = TextView(this)
        t.text = "$name  (${size / 1024 / 1024} MB)"
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL

        val play = Button(this)
        play.text = "Play"
        play.setOnClickListener {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "video/mp4")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(this, "Play nahi hua", Toast.LENGTH_SHORT).show()
            }
        }
        val share = Button(this)
        share.text = "Share"
        share.setOnClickListener {
            val i = Intent(In
