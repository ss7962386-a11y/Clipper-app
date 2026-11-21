package com.example.clipper

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ClipsUi(private val act: Activity) {

    fun refresh(list: LinearLayout) {
        list.removeAllViews()
        if (Build.VERSION.SDK_INT < 29) {
            val t = TextView(act)
            t.text = "Is Android version par My clips nahi chalta. Gallery mein Movies/Clipper dekho."
            list.addView(t)
            return
        }
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        var count = 0
        act.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel,
            arrayOf("Movies/Clipper%"), "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "clip"
                val size = c.getLong(2)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                list.addView(row(list, uri, name, size))
                count++
            }
        }
        if (count == 0) {
            val t = TextView(act)
            t.text = "Abhi koi clip nahi hai."
            list.addView(t)
        }
    }

    private fun row(list: LinearLayout, uri: Uri, name: String, size: Long): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, 24, 0, 24)
        val t = TextView(act)
        t.text = "$name  (${size / 1024 / 1024} MB)"
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL

        val play = Button(act)
        play.text = "Play"
        play.setOnClickListener {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "video/mp4")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                act.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(act, "Play nahi hua", Toast.LENGTH_SHORT).show()
            }
        }
        val share = Button(act)
        share.text = "Share"
        share.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "video/mp4"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(i, "Share clip"))
        }
        val del = Button(act)
        del.text = "Delete"
        del.setOnClickListener {
            try {
                act.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Toast.makeText(act, "Delete nahi hua", Toast.LENGTH_SHORT).show()
            }
            refresh(list)
        }
        bar.addView(play)
        bar.addView(share)
        bar.addView(del)
        row.addView(t)
        row.addView(bar)
        return row
    }
}
