package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Alarm lifecycle tests must run only on an isolated emulator. */
class VerseMistakeHighlighterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val passage = "Open the window today."

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun EditText.marks() = text.getSpans(0, text.length, VerseMistakeHighlighter.RedUnderline::class.java)
        .map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }

    @Test fun redUnderlinePreservesTextCursorCompositionAndUnrelatedSpans() {
        instrumentation.runOnMainSync {
            val entry = EditText(instrumentation.targetContext)
            val highlighter = VerseMistakeHighlighter(entry, passage)
            entry.setText("Open the door today.")
            entry.setSelection(10)
            val unrelated = StyleSpan(android.graphics.Typeface.BOLD)
            entry.text.setSpan(unrelated, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val composing = BaseInputConnection(entry, true)
            composing.setComposingRegion(9, 13)
            assertTrue(entry.marks().isEmpty())
            highlighter.enabled = true
            assertEquals(listOf("door"), entry.marks())
            assertEquals("Open the door today.", entry.text.toString())
            assertEquals(10, entry.selectionStart)
            assertEquals(9, BaseInputConnection.getComposingSpanStart(entry.text))
            assertEquals(13, BaseInputConnection.getComposingSpanEnd(entry.text))
            assertEquals(0, entry.text.getSpanStart(unrelated))
            val paint = TextPaint().apply { color = Color.WHITE; density = 2f }
            entry.text.getSpans(0, entry.length(), VerseMistakeHighlighter.RedUnderline::class.java)
                .single().updateDrawState(paint)
            assertEquals(Color.RED, paint.underlineColor)
            assertEquals(Color.WHITE, paint.color)
            entry.text.replace(9, 13, "window")
            assertTrue(entry.marks().isEmpty())
            entry.setText("Wrong answer")
            assertTrue(entry.marks().isNotEmpty())
            highlighter.enabled = false
            assertTrue(entry.marks().isEmpty())
        }
    }

    @Test fun quietExpiryEnablesLiveFeedbackInLearningAndMemoryAndNextCheckResetsIt() {
        val c = instrumentation.targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            for (fromMemory in listOf(false, true)) {
                prefs.edit().clear().commit()
                VerseAlarmStore.addVerse(c, "Test reference", passage, 1)
                if (fromMemory) {
                    val state = org.json.JSONObject(prefs.getString("state", null)!!)
                    state.getJSONArray("verses").getJSONObject(0).put("successes", 3)
                    prefs.edit().putString("state", state.toString()).commit()
                }
                val now = AlarmClock.now(c)
                VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), now.copy(wall = now.wall - 86_400_000))
                VerseAlarmStore.reconcile(c, now)
                ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                    fun views(activity: VerseAlarmActivity) = descendants(activity.findViewById(android.R.id.content))
                    scenario.onActivity { activity ->
                        assertEquals(fromMemory, VerseAlarmStore.read(c).challenge!!.fromMemory)
                        var entry = views(activity).filterIsInstance<EditText>().single()
                        entry.setText("Open the door today.")
                        assertTrue(entry.marks().isEmpty())
                        views(activity).filterIsInstance<TextView>().first { it.text.toString() == "Quiet for 1 seconds" }.performClick()
                        entry = views(activity).filterIsInstance<EditText>().single()
                        assertEquals("Open the door today.", entry.text.toString())
                        assertTrue(entry.marks().isEmpty())
                    }
                    // Wait off the UI thread for the deadline and the screen's normal timer.
                    android.os.SystemClock.sleep(2200)
                    scenario.onActivity { activity ->
                        val entry = views(activity).filterIsInstance<EditText>().single()
                        assertEquals(listOf("door"), entry.marks())
                        if (fromMemory) assertFalse(views(activity).filterIsInstance<TextView>().any { it.text.toString() == passage })
                        entry.setText("OPEN the window today!")
                        assertTrue(entry.marks().isEmpty())
                        views(activity).filterIsInstance<TextView>().first { it.text.toString() == "Check my verse" }.performClick()
                        val time = AlarmClock.now(c)
                        val state = org.json.JSONObject(prefs.getString("state", null)!!)
                        state.getJSONObject("challenge").put("wall", time.wall).put("elapsed", time.elapsed)
                        prefs.edit().putString("state", state.toString()).commit()
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertFalse(VerseAlarmStore.read(c).challenge!!.quietUsed)
                        val entry = views(activity).filterIsInstance<EditText>().single()
                        entry.setText("Open the door today.")
                        assertTrue(entry.marks().isEmpty())
                    }
                }
                c.stopService(Intent(c, VerseAlarmService::class.java))
            }
        } finally {
            c.stopService(Intent(c, VerseAlarmService::class.java))
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }
}
