package com.example.clipper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

object Analyzer {

    // YouTube ka Most replayed graph
    fun peaksFromInfo(obj: JSONObject, count: Int, sec: Int): List<Int> {
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

    // Awaaz sunke jhatke wali (chillana/hasna) jagah dhoondo
    fun findLoudMoments(file: File, count: Int): List<Int> {
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
}
