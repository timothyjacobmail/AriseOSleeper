package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Synthetic data only. Never run the alarm instrumentation suite on a personal phone. */
class UiReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun views(): List<View> = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun click(text: String) {
        main { views().filterIsInstance<TextView>().last { it.isShown && it.text.toString() == text }.performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun reveal(text: String) {
        main {
            val view = views().filterIsInstance<TextView>().last { it.isShown && it.text.toString() == text }
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val context = instrumentation.targetContext
        val suffix = InstrumentationRegistry.getArguments().getString("uiVariant", "light")
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(folder, "$suffix-$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
    }

    @Test fun reviewScreensAndSelectionBehavior() {
        assumeTrue("Use an isolated emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val context = instrumentation.targetContext
        val alarm = context.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val originalAlarm = alarm.getString("state", null)
        val briefing = MorningBriefing.prefs(context)
        val originalBriefing = briefing.all
        try {
            alarm.edit().clear().commit()
            briefing.edit().clear().putBoolean("enabled", true)
                .putBoolean("tasks_connected", false)
                .putString("task_list_id", "immediate")
                .putString("task_list_title", "Immediate tasks")
                .putString("task_lists", """[{"id":"immediate","title":"Immediate tasks"},{"id":"personal","title":"Personal and household"},{"id":"work","title":"Work"},{"id":"later","title":"Things to do someday"}]""")
                .putString("last_summary", "Good morning. You have three WhatsApp notifications and two Gmail notifications. Today, project planning is at 10 AM. Tomorrow, call the dentist. In Immediate tasks, prepare your notes and book a service appointment.")
                .commit()
            VerseAlarmStore.addVerse(context, "Morning passage", "Begin today with a clear mind.")
            VerseAlarmStore.addVerse(context, "Evening passage", "Open the window and welcome a new day.")
            ActivityScenario.launch<VerseAlarmActivity>(Intent(context, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val all = descendants(activity.findViewById(android.R.id.content))
                    assertTrue(all.any { it is MaterialCheckBox })
                    all.filterIsInstance<EditText>().first { it.hint == "Sessions per alarm (1-20)" }.setText("7")
                }
                screenshot("alarm")
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val fields = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                    assertEquals("7", fields.first { it.hint == "Sessions per alarm (1-20)" }.text.toString())
                    fields.first { it.hint == "Sessions per alarm (1-20)" }.setText("0")
                }
                click("Save alarm settings")
                scenario.onActivity { activity ->
                    val entry = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                        .first { it.hint == "Sessions per alarm (1-20)" }
                    assertNotNull(entry.error)
                    assertEquals(3, VerseAlarmStore.read(context).config.sessions)
                    entry.setText("3")
                    entry.clearFocus()
                }
                reveal("Your ESV passages")
                screenshot("passages")
            }
            ActivityScenario.launch<BriefingActivity>(Intent(context, BriefingActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val all = descendants(activity.findViewById(android.R.id.content))
                    assertTrue(all.any { it is MaterialSwitch && it.text == "Morning briefing enabled" })
                    assertEquals(4, all.filterIsInstance<MaterialCheckBox>().size)
                }
                screenshot("briefing")
                val defaultReading = NotificationReadingOptions().label
                fun part(title: String) = views().filterIsInstance<MaterialCheckBox>().last { it.text.toString() == title }
                fun namesOnly() {
                    part("Notification count").performClick()
                    part("Message previews").performClick()
                    views().filterIsInstance<RadioButton>().last { it.text.toString() == "Waiting notifications" }.performClick()
                }
                reveal("Gmail reading\n$defaultReading")
                screenshot("notifications")
                click("Gmail reading\n$defaultReading")
                main { namesOnly() }
                screenshot("reading-choice")
                click("Cancel")
                assertEquals(NotificationReadingOptions(), NotificationReadingOptions.read(context, "Gmail"))
                click("Gmail reading\n$defaultReading")
                main { namesOnly() }
                click("Apply")
                assertEquals(NotificationReadingOptions(false, true, false, false), NotificationReadingOptions.read(context, "Gmail"))
                reveal("WhatsApp reading\n$defaultReading")
                click("WhatsApp reading\n$defaultReading")
                main {
                    part("Notification count").performClick()
                    part("Sender names").performClick()
                    part("Message previews").performClick()
                    val apply = views().filterIsInstance<TextView>().last { it.text.toString() == "Apply" && it.isShown }
                    assertFalse(apply.isEnabled)
                    part("Sender names").performClick()
                    part("Message previews").performClick()
                    assertTrue(apply.isEnabled)
                    views().filterIsInstance<RadioButton>().last { it.text.toString() == "Waiting notifications" }.performClick()
                }
                screenshot("notification-multi-select")
                click("Apply")
                assertEquals(NotificationReadingOptions(false, true, true, false), NotificationReadingOptions.read(context, "WhatsApp"))
                reveal("Task lists\nImmediate tasks")
                screenshot("tasks")
                click("Task lists\nImmediate tasks")
                main { views().filterIsInstance<EditText>().last { it.hint == "Search lists" }.setText("personal") }
                screenshot("task-search")
                main {
                    val list = views().filterIsInstance<android.widget.ListView>().last { it.isShown }
                    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                }
                click("Apply")
                assertEquals(setOf("immediate", "personal"), BriefingTasks.selectedIds(briefing))
                click("Task lists\nImmediate tasks, Personal and household")
                main {
                    val list = views().filterIsInstance<android.widget.ListView>().last { it.isShown }
                    assertTrue(list.isItemChecked(1))
                    assertTrue(list.isItemChecked(2))
                    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                    assertTrue(list.isItemChecked(3))
                    list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
                    assertFalse(list.isItemChecked(0))
                    assertFalse(list.isItemChecked(1))
                    assertTrue(list.isItemChecked(2))
                }
                click("Cancel")
                assertEquals(setOf("immediate", "personal"), BriefingTasks.selectedIds(briefing))
                reveal("Task recency\nAll update times")
                click("Task recency\nAll update times")
                main {
                    assertEquals(0, briefing.getInt("tasks_updated_days", 0))
                    views().filterIsInstance<RadioButton>().last { it.text.toString() == "Only recently updated tasks" }.performClick()
                    views().filterIsInstance<EditText>().last { it.hint == "Number of days" }.setText("0")
                }
                click("Apply")
                main {
                    assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == "Enter a number from 1 to 365" })
                    views().filterIsInstance<EditText>().last { it.hint == "Number of days" }.setText("2")
                }
                screenshot("task-recency")
                click("Cancel")
                assertEquals(0, briefing.getInt("tasks_updated_days", 0))
                click("Task recency\nAll update times")
                main {
                    views().filterIsInstance<RadioButton>().last { it.text.toString() == "Only recently updated tasks" }.performClick()
                }
                click("Apply")
                assertEquals(2, briefing.getInt("tasks_updated_days", 0))
                reveal("Notification filters\nNo rules")
                click("Notification filters\nNo rules")
                screenshot("filters-empty")
                click("Add filter")
                screenshot("filter-form")
                click("Save")
                main {
                    assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == "Enter text to match" })
                    views().filterIsInstance<EditText>().last { it.hint == "Sender, group name or keyword" }.setText("Promotions")
                }
                main {
                    val field = views().filterIsInstance<EditText>().last { it.hint == "Sender, group name or keyword" }
                    field.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(field.windowToken, 0)
                }
                click("Save")
                assertEquals("Promotions", BriefingFilters.read(context).single().pattern)
                screenshot("filter-saved")
                click("Done")
            }
        } finally {
            context.stopService(Intent(context, VerseAlarmService::class.java))
            alarm.edit().clear().putString("state", originalAlarm).commit()
            val editor = briefing.edit().clear()
            originalBriefing.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
            VerseAlarmScheduler.reschedule(context)
        }
    }
}
