package com.example.ariseosleeper

import android.graphics.Color
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged

/** Changes only our spans, leaving the cursor, IME composition and answer intact. */
internal class VerseMistakeHighlighter(private val field: EditText, private val expected: String) {
    internal class RedUnderline : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(paint: TextPaint) {
            paint.underlineColor = Color.RED
            paint.underlineThickness = maxOf(1f, paint.density * 2f)
        }
    }

    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    init { field.doAfterTextChanged { update() } }

    private fun update() {
        val text = field.text ?: return
        text.getSpans(0, text.length, RedUnderline::class.java).forEach { text.removeSpan(it) }
        if (enabled) VerseDifferences.ranges(expected, text.toString()).forEach {
            text.setSpan(RedUnderline(), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
