package com.example.ariseosleeper

import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

internal object BriefingFilterEditor {
    fun show(activity: AppCompatActivity) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UiStyle.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Notification filters")
            .setView(ScrollView(activity).apply { addView(column) }).setPositiveButton("Done", null).create()
        fun render() {
            column.removeAllViews()
            column.addView(UiStyle.label(activity,
                "Choose which previews are spoken in your briefing. Matching ignores letter case. Include-only rules limit the chosen app to matches. Ignore rules take priority. Rules also apply to already collected previews."))
            column.addView(UiStyle.button(activity, "Add filter") {
                edit(activity, null) { render() }
            })
            val rules = BriefingFilters.read(activity)
            if (rules.isEmpty()) column.addView(UiStyle.label(activity, "No filters added."))
            rules.forEach { rule ->
                column.addView(CheckBox(activity).apply {
                    text = "${if (rule.include) "Include only" else "Ignore"} · ${rule.app}\n${rule.field} · ${rule.match} · ${rule.pattern}"
                    isChecked = rule.enabled
                    setOnCheckedChangeListener { _, value ->
                        BriefingFilters.save(activity, BriefingFilters.read(activity).map { if (it.id == rule.id) it.copy(enabled = value) else it })
                    }
                })
                column.addView(UiStyle.button(activity, "Edit filter") { edit(activity, rule) { render() } })
                column.addView(UiStyle.button(activity, "Remove filter") {
                    BriefingFilters.save(activity, BriefingFilters.read(activity).filterNot { it.id == rule.id })
                    render()
                })
            }
        }
        render()
        dialog.show()
    }

    private fun edit(activity: AppCompatActivity, original: BriefingFilter?, saved: () -> Unit) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UiStyle.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
        }
        fun spinner(label: String, values: List<String>, current: String): Spinner {
            column.addView(UiStyle.label(activity, label))
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(values.indexOf(current).coerceAtLeast(0))
                column.addView(this)
            }
        }
        val action = spinner("Action", listOf("Ignore", "Include only"), if (original?.include == true) "Include only" else "Ignore")
        val app = spinner("App", listOf("Any app", "WhatsApp", "Gmail"), original?.app ?: "Any app")
        val field = spinner("Match against", listOf("Sender or conversation", "Message text", "Either"),
            original?.field ?: "Sender or conversation")
        val match = spinner("Match type", listOf("Contains", "Equals", "Starts with"), original?.match ?: "Contains")
        val pattern = EditText(activity).apply {
            hint = "Sender, group name, or keyword"
            setText(original?.pattern.orEmpty())
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        column.addView(pattern)
        val feedback = UiStyle.label(activity, "")
        column.addView(feedback)
        fun rule() = BriefingFilter(id = original?.id ?: java.util.UUID.randomUUID().toString(),
            app = app.selectedItem.toString(), field = field.selectedItem.toString(),
            match = match.selectedItem.toString(), pattern = pattern.text.toString().trim(),
            include = action.selectedItemPosition == 1, enabled = original?.enabled ?: true)
        column.addView(UiStyle.button(activity, "Test match") {
            if (pattern.text.isNullOrBlank()) { pattern.error = "Enter text to match"; return@button }
            val now = System.currentTimeMillis()
            val messages = BriefingMessages(activity).use { it.read(now - TimeUnit.HOURS.toMillis(12), now) }
            val sample = rule()
            val count = messages.count { sample.applies(it) && sample.matches(it) }
            feedback.text = "Matches $count collected previews from the past 12 hours."
        })
        val dialog = AlertDialog.Builder(activity).setTitle(if (original == null) "Add filter" else "Edit filter")
            .setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pattern.text.isNullOrBlank()) { pattern.error = "Enter text to match"; return@setOnClickListener }
                val rules = BriefingFilters.read(activity).filterNot { it.id == original?.id } + rule()
                BriefingFilters.save(activity, rules)
                saved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
