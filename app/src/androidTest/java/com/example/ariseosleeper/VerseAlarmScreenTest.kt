package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Run only on an isolated emulator: this exercises the real ringing screen and service. */
class VerseAlarmScreenTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun <T : View> VerseAlarmActivity.findViewWithTag(tag: String): T =
        findViewById<View>(android.R.id.content).findViewWithTag(tag)

    @Test fun pastedBibleShareFillsFieldsAndSavesOnlyReferenceAndPassage() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        val text = "On the third day there was a wedding at Cana in Galilee, and the mother of Jesus was there."
        try {
            prefs.edit().clear().commit()
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    activity.findViewWithTag<View>("add-passage:toggle").performClick()
                    val fields = views().filterIsInstance<EditText>()
                    val reference = fields.first { it.hint == "Reference, e.g. Psalm 23:1" }
                    val passage = fields.first { it.hint == "Paste ESV passage text without verse numbers" }
                    reference.setText("Replace this old draft reference")
                    passage.requestFocus()
                    val connection = passage.onCreateInputConnection(EditorInfo())!!
                    connection.commitText(",“$text ”\nJohn 2:1 ESV\n\nhttps://bible.com/bible/59/jhn.2.1.ESV'", 1)
                    assertEquals("John 2:1", reference.text.toString())
                    assertEquals(text, passage.text.toString())
                    fields.first { it.hint == "Quiet time in seconds (1-3600)" }.setText("75")
                    views().filterIsInstance<TextView>().first { it.text.toString() == "Add passage" }.performClick()
                    val saved = VerseAlarmStore.read(c).verses.single()
                    assertEquals("John 2:1", saved.reference)
                    assertEquals(text, saved.text)
                    assertEquals(75, saved.quietSeconds)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(descendants(activity.findViewById(android.R.id.content))
                        .filterIsInstance<TextView>().any { it.text.startsWith("John 2:1") })
                    val fields = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                    fields.first { it.hint == "Reference, e.g. Psalm 23:1" }.setText("Manual reference")
                    fields.first { it.hint == "Paste ESV passage text without verse numbers" }.setText("Plain passage text still works.")
                    descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                        .first { it.text.toString() == "Add passage" }.performClick()
                    assertEquals(listOf("John 2:1", "Manual reference"), VerseAlarmStore.read(c).verses.map { it.reference })
                }
            }
        } finally {
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }

    @Test fun wrongAnswerStaysVisibleAndQuietPreservesInput() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Original test prose", "Wake up and open the window to begin a new day.", 900)
            val now = AlarmClock.now(c)
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), now.copy(wall = now.wall - 86_400_000))
            VerseAlarmStore.reconcile(c, now)
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    fun click(text: String) = views().filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
                    val entry = views().filterIsInstance<EditText>().single()
                    entry.requestFocus()
                    val editor = EditorInfo()
                    assertNotNull(entry.onCreateInputConnection(editor))
                    assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, editor.inputType and InputType.TYPE_MASK_VARIATION)
                    assertTrue(editor.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                    assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                    assertNull(entry.transformationMethod)
                    entry.setText("wrong answer")
                    click("Check my verse")
                    assertTrue(views().filterIsInstance<TextView>().any { it.text.startsWith("That does not match yet") })
                    assertEquals(0, VerseAlarmStore.read(c).verses.single().successes)
                    click("Quiet for 900 seconds")
                    assertEquals("wrong answer", views().filterIsInstance<EditText>().single().text.toString())
                    assertTrue(VerseAlarmStore.read(c).challenge!!.quietUsed)
                    click("Alarm settings")
                    val verse = VerseAlarmStore.read(c).verses.single()
                    activity.findViewWithTag<View>("verse:${verse.id}:toggle").performClick()
                    assertFalse(views().filterIsInstance<TextView>().any { it.text.toString() == verse.text })
                    assertFalse(views().filterIsInstance<TextView>()
                        .first { it.text.toString() == "Practice ${verse.reference}" }.isEnabled)
                }
            }
        } finally {
            c.stopService(Intent(c, VerseAlarmService::class.java))
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }

    @Test fun settingsSaveCustomSessionsQuietTimeAndMultipleAlarmEdits() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Test passage", "Begin today with a clear mind.")
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(additionalTimes = listOf(VerseAlarmTime(18, 0))))
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    fun click(text: String) = views().filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
                    fun field(label: String): EditText {
                        val id = views().filterIsInstance<TextView>().first { it.text.toString() == label }.labelFor
                        return activity.findViewById(id)
                    }
                    field("Number of sessions per alarm (1-20)").setText("5")
                    click("Remove alarm 1")
                    click("Save alarm settings")
                    assertEquals(5, VerseAlarmStore.read(c).config.sessions)
                    assertEquals(listOf(VerseAlarmTime(18, 0)), VerseAlarmStore.read(c).config.times)
                    val verseId = VerseAlarmStore.read(c).verses.single().id
                    activity.findViewWithTag<View>("verse:$verseId:toggle").performClick()
                    field("Quiet time for Test passage (seconds)").setText("125")
                    click("Save quiet time for Test passage")
                    assertEquals(125, VerseAlarmStore.read(c).verses.single().quietSeconds)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val fields = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                    assertEquals("5", fields.first { it.hint == "Sessions per alarm (1-20)" }.text.toString())
                    assertTrue(fields.any { it.text.toString() == "125" })
                }
            }
        } finally {
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }

    @Test fun passageDropdownsHideClutterAndKeepStateAcrossEditsPracticeAndRecreation() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "First passage", "Begin today with a clear mind.")
            VerseAlarmStore.addVerse(c, "Second passage", "Open the window and welcome the morning.")
            val firstKey = "verse:${VerseAlarmStore.read(c).verses[0].id}"
            val secondKey = "verse:${VerseAlarmStore.read(c).verses[1].id}"
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun body(key: String) = activity.findViewWithTag<View>("$key:content")
                    fun toggle(key: String) = activity.findViewWithTag<View>("$key:toggle").performClick()
                    fun click(text: String) = descendants(activity.findViewById(android.R.id.content))
                        .filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
                    fun quiet(): EditText {
                        val label = descendants(body(firstKey)).filterIsInstance<TextView>()
                            .first { it.text.toString() == "Quiet time for First passage (seconds)" }
                        return activity.findViewById(label.labelFor)
                    }
                    assertEquals(View.GONE, body("add-passage").visibility)
                    assertEquals(View.VISIBLE, body("saved-passages").visibility)
                    assertEquals(View.GONE, body(firstKey).visibility)
                    assertEquals(View.GONE, body(secondKey).visibility)
                    toggle(firstKey)
                    assertTrue(body(firstKey).isShown)
                    assertEquals("Expanded", activity.findViewWithTag<View>("$firstKey:toggle").stateDescription)
                    assertFalse(body(secondKey).isShown)
                    quiet().setText("125")
                    toggle(firstKey)
                    assertFalse(body(firstKey).isShown)
                    toggle(firstKey)
                    assertEquals("125", quiet().text.toString())
                    click("Save quiet time for First passage")
                    assertEquals(125, VerseAlarmStore.read(c).verses.first().quietSeconds)
                    assertTrue(body(firstKey).isShown)
                    assertFalse(body(secondKey).isShown)
                    click("Practice First passage")
                    click("Back to alarm settings")
                    assertTrue(body(firstKey).isShown)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewWithTag<View>("$firstKey:content").isShown)
                    activity.findViewWithTag<View>("saved-passages:toggle").performClick()
                    assertFalse(activity.findViewWithTag<View>("$firstKey:content").isShown)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewWithTag<View>("saved-passages:content").visibility)
                    activity.findViewWithTag<View>("saved-passages:toggle").performClick()
                    assertTrue(activity.findViewWithTag<View>("$firstKey:content").isShown)
                    descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                        .first { it.text.toString() == "Remove First passage" }.performClick()
                    assertEquals(listOf("Second passage"), VerseAlarmStore.read(c).verses.map { it.reference })
                }
            }
        } finally {
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }
}
