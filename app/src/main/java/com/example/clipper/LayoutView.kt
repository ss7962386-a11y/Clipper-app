package com.example.clipper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

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
