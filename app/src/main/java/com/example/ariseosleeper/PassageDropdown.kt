package com.example.ariseosleeper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MaterialR

/** A compact, accessible disclosure for passage forms and saved-passage details. */
internal class PassageDropdown(
    context: Context,
    key: String,
    title: String,
    summary: String,
    expanded: Boolean,
    children: List<View>,
    onExpandedChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val body = LinearLayout(context).apply {
        tag = "$key:content"
        orientation = VERTICAL
        val pad = UiStyle.dp(context, 12)
        setPadding(pad, pad, pad, 0)
        children.forEach { child ->
            addView(child, LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) })
        }
    }
    private var isExpanded = expanded
    private val header = UiStyle.button(context, title) {
        isExpanded = !isExpanded
        updateExpansion()
        onExpandedChanged(isExpanded)
    }.apply {
        tag = "$key:toggle"
        text = SpannableString("$title\n$summary").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.82f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 16f
        setTypeface(typeface, Typeface.NORMAL)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        cornerRadius = UiStyle.dp(context, 12)
        insetTop = 0
        insetBottom = 0
        iconSize = UiStyle.dp(context, 24)
        iconGravity = MaterialButton.ICON_GRAVITY_END
        iconTint = ColorStateList.valueOf(UiStyle.color(context, MaterialR.attr.colorOnSurfaceVariant))
        backgroundTintList = ColorStateList.valueOf(UiStyle.color(context, MaterialR.attr.colorSurfaceContainer))
        setTextColor(UiStyle.color(context, MaterialR.attr.colorOnSurface))
        strokeColor = ColorStateList.valueOf(UiStyle.color(context, MaterialR.attr.colorOutlineVariant))
        strokeWidth = UiStyle.dp(context, 1)
        isAccessibilityHeading = true
    }

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
        updateExpansion()
    }

    private fun updateExpansion() {
        if (!isExpanded) body.findFocus()?.let { focused ->
            focused.clearFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        body.visibility = if (isExpanded) VISIBLE else GONE
        header.icon = AppCompatResources.getDrawable(context,
            if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        ViewCompat.setStateDescription(header, if (isExpanded) "Expanded" else "Collapsed")
    }
}
