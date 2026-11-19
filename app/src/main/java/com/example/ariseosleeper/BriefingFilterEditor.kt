package com.example.ariseosleeper

import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.concurrent.TimeUnit

internal object BriefingFilterEditor {
    fun show(activity: AppCompatActivity) {
        val column = UiComponents.column(activity).apply {
            val pad = UiStyle.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(activity).apply { addView(column) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Notification filters")
            .setView(scroll).setPositiveButton("Done", null).create()
        fun render() {
            val y = scroll.scrollY
            column.removeAllViews()
            UiComponents.add(column, UiStyle.label(activity,
                "Ignore rules take priority. Include-only rules limit the selected app to matching notifications."))
            UiComponents.add(column, UiStyle.button(activity, "Add filter", primary = true) {
                edit(activity, null) { render() }
            })
            val rules = BriefingFilters.read(activity)
            if (rules.isEmpty()) UiComponents.add(column, UiStyle.label(activity,
                "No filters yet. Add a sender, conversation or keyword rule to customize your briefing."))
            rules.forEach { rule ->
                val toggle = UiComponents.settingSwitch(activity, "Filter enabled", rule.enabled) { value ->
                    BriefingFilters.save(activity, BriefingFilters.read(activity).map {
                        if (it.id == rule.id) it.copy(enabled = value) else it
                    })
                }.apply { contentDescription = "Enable filter ${rule.pattern}" }
                val edit = UiStyle.button(activity, "Edit") { edit(activity, rule) { render() } }
                    .apply { contentDescription = "Edit filter ${rule.pattern}" }
                val remove = UiStyle.button(activity, "Remove") {
                    UiComponents.confirm(activity, "Remove filter?", "Remove the rule matching “${rule.pattern}”?", "Remove") {
                        BriefingFilters.save(activity, BriefingFilters.read(activity).filterNot { it.id == rule.id })
                        render()
                    }
                }.apply { contentDescription = "Remove filter ${rule.pattern}" }
                UiComponents.add(column, UiComponents.section(activity, rule.pattern, listOf(
                    UiStyle.label(activity, "${if (rule.include) "Include only" else "Ignore"} · ${rule.app}\n${rule.field} · ${rule.match}"),
                    toggle, UiComponents.actions(activity, listOf(edit, remove)))))
            }
            scroll.post { scroll.scrollTo(0, y) }
        }
        render()
        dialog.show()
    }

    private fun edit(activity: AppCompatActivity, original: BriefingFilter?, saved: () -> Unit) {
        val column = UiComponents.column(activity).apply {
            val pad = UiStyle.dp(activity, 20)
            setPadding(pad, 0, pad, pad)
        }
        var include = original?.include ?: false
        UiComponents.add(column, UiStyle.label(activity, "Action", 20f))
        UiComponents.add(column, UiComponents.radioChoices(activity, listOf(
            "Ignore matches", "Include only matches"), if (include) 1 else 0) { include = it == 1 })
        val appBox = TextInputLayout(activity).apply {
            hint = "App"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val app = MaterialAutoCompleteTextView(activity).apply {
            inputType = InputType.TYPE_NULL
            setSimpleItems(arrayOf("Any app", "WhatsApp", "Gmail"))
            setText(original?.app ?: "Any app", false)
            minHeight = UiStyle.dp(activity, 56)
        }
        appBox.addView(app)
        UiComponents.add(column, appBox)
        val fields = listOf("Sender or conversation", "Message text", "Either")
        var field = fields.indexOf(original?.field ?: fields.first()).coerceAtLeast(0)
        UiComponents.add(column, UiStyle.label(activity, "Match against", 20f))
        UiComponents.add(column, UiComponents.radioChoices(activity, fields, field) { field = it })
        val matches = listOf("Contains", "Equals", "Starts with")
        var match = matches.indexOf(original?.match ?: matches.first()).coerceAtLeast(0)
        UiComponents.add(column, UiStyle.label(activity, "Match type", 20f))
        UiComponents.add(column, UiComponents.radioChoices(activity, matches, match) { match = it })
        val patternBox = UiComponents.field(activity, "Sender, group name or keyword",
            original?.pattern.orEmpty(), "Matching ignores letter case.").apply {
            isCounterEnabled = true
            counterMaxLength = 120
        }
        val pattern = patternBox.editText!!
        pattern.filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        pattern.doAfterTextChanged { if (!it.isNullOrBlank()) patternBox.error = null }
        UiComponents.add(column, patternBox)
        val feedback = UiStyle.label(activity, "").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun rule() = BriefingFilter(id = original?.id ?: java.util.UUID.randomUUID().toString(),
            app = app.text.toString(), field = fields[field], match = matches[match],
            pattern = pattern.text.toString().trim(), include = include, enabled = original?.enabled ?: true)
        fun valid(): Boolean {
            if (pattern.text.isNullOrBlank()) {
                patternBox.error = "Enter text to match"
                pattern.requestFocus()
                return false
            }
            return true
        }
        UiComponents.add(column, UiStyle.button(activity, "Test match") {
            if (!valid()) return@button
            val now = System.currentTimeMillis()
            val messages = BriefingMessages(activity).use { it.read(now - TimeUnit.HOURS.toMillis(12), now) }
            val sample = rule()
            val count = messages.count { sample.applies(it) && sample.matches(it) }
            feedback.text = "Matches $count collected previews from the past 12 hours."
        })
        UiComponents.add(column, feedback)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (original == null) "Add filter" else "Edit filter")
            .setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!valid()) return@setOnClickListener
                val current = BriefingFilters.read(activity)
                val changed = rule()
                val rules = if (original == null) current + changed else current.map { if (it.id == original.id) changed else it }
                BriefingFilters.save(activity, rules)
                saved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
