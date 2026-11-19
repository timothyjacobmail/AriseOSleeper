package com.example.ariseosleeper

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.R as MaterialR

/** Shared native Material components. Behavioral choices are recorded in docs/design.md. */
internal object UiComponents {
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun add(parent: LinearLayout, child: View, gap: Int = 8) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(parent.context, gap) })
    }
    fun section(c: Context, title: String, children: List<View>): View = MaterialCardView(c).apply {
        radius = UiStyle.dp(c, 16).toFloat()
        cardElevation = 0f
        strokeWidth = UiStyle.dp(c, 1)
        strokeColor = UiStyle.color(c, MaterialR.attr.colorOutlineVariant)
        setCardBackgroundColor(UiStyle.color(c, MaterialR.attr.colorSurfaceContainerLow))
        val body = column(c).apply { val pad = UiStyle.dp(c, 16); setPadding(pad, pad, pad, pad) }
        add(body, UiStyle.label(c, title, 20f))
        children.forEach { add(body, it) }
        addView(body)
    }

    fun page(activity: AppCompatActivity, title: String?, children: List<View>, back: (() -> Unit)? = null): ScrollView {
        val root = column(activity)
        if (title != null) root.addView(MaterialToolbar(activity).apply {
            this.title = title
            if (back != null) {
                setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                navigationContentDescription = "Back"
                setNavigationOnClickListener { back() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        val body = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), UiStyle.dp(context, 720))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            children.forEach { add(this, it, 16) }
        }
        val frame = FrameLayout(activity).apply {
            val pad = UiStyle.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
            addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        }
        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(frame) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        activity.setContentView(root)
        root.requestApplyInsets()
        return scroll
    }

    fun actions(c: Context, buttons: List<View>): LinearLayout {
        val needed = buttons.sumOf {
            val button = it as TextView
            button.paint.measureText(button.text.toString()).toInt() + UiStyle.dp(c, 64)
        } + UiStyle.dp(c, 8 * (buttons.size - 1))
        val available = UiStyle.dp(c, minOf(c.resources.configuration.screenWidthDp, 720) - 72)
        return column(c).apply {
            val horizontal = needed <= available
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(if (horizontal) 0 else -1, -2, if (horizontal) 1f else 0f).apply {
                    if (horizontal && index > 0) marginStart = UiStyle.dp(c, 8)
                    if (!horizontal) bottomMargin = UiStyle.dp(c, 8)
                })
            }
        }
    }

    fun settingSwitch(c: Context, title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        MaterialSwitch(c).apply {
            text = title
            textSize = 16f
            minHeight = UiStyle.dp(c, 56)
            setPadding(0, UiStyle.dp(c, 8), 0, UiStyle.dp(c, 8))
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    fun checkbox(c: Context, title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        MaterialCheckBox(c).apply {
            text = title
            textSize = 16f
            minHeight = UiStyle.dp(c, 48)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    fun radioChoices(c: Context, labels: List<String>, checked: Int, changed: (Int) -> Unit): RadioGroup =
        RadioGroup(c).apply {
            orientation = RadioGroup.VERTICAL
            labels.forEachIndexed { index, label ->
                addView(MaterialRadioButton(c).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 16f
                    minHeight = UiStyle.dp(c, 56)
                    setPadding(0, UiStyle.dp(c, 8), 0, UiStyle.dp(c, 8))
                    isChecked = index == checked
                }, RadioGroup.LayoutParams(-1, -2))
            }
            setOnCheckedChangeListener { group, id ->
                val index = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == id }
                if (index != null) changed(index)
            }
        }

    fun field(c: Context, title: String, value: String = "", helper: String? = null): TextInputLayout =
        TextInputLayout(c).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = title
            helperText = helper
            addView(TextInputEditText(context).apply { setText(value); minHeight = UiStyle.dp(c, 56) })
        }

    fun choiceDialog(c: Context, title: String, labels: List<String>, current: Int, apply: (Int) -> Unit) {
        var selected = current
        val body = radioChoices(c, labels, current) { selected = it }.apply {
            val pad = UiStyle.dp(c, 20); setPadding(pad, 0, pad, 0)
        }
        MaterialAlertDialogBuilder(c).setTitle(title)
            .setView(ScrollView(c).apply { addView(body) })
            .setPositiveButton("Apply") { _, _ -> apply(selected) }.setNegativeButton("Cancel", null).show()
    }

    fun confirm(c: Context, title: String, message: String, actionLabel: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(c).setTitle(title).setMessage(message)
            .setPositiveButton(actionLabel) { _, _ -> action() }.setNegativeButton("Cancel", null).show()
    }
}
