package com.example.clipper

import android.app.Activity
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
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
        result.textSize = 16f

        btn.setOnClickListener {
            val sec = group.checkedRadioButtonId - 1000
            result.text = "Link: ${link.text}\nMoment: ${moment.text}\nLength: $sec sec"
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
