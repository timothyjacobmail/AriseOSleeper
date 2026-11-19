package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Run only on an isolated emulator: this exercises the real alarm screen and service. */
class VerseAlarmVolumeKeyTest {
    private val passage = "Wake up and open the window to begin a new day."

    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private fun withAlarm(quietSeconds: Int = 900, beforeLaunch: (Context) -> Unit = {},
        check: (Context, VerseAlarmActivity) -> Unit) {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Test passage", passage, quietSeconds)
            val now = AlarmClock.now(c)
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), now.copy(wall = now.wall - 86_400_000))
            VerseAlarmStore.reconcile(c, now)
            beforeLaunch(c)
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { check(c, it) }
            }
        } finally {
            c.stopService(Intent(c, VerseAlarmService::class.java))
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }

    @Test fun volumeButtonsStartOneQuietPeriodAndPreserveAnswer() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            withAlarm { c, activity ->
                fun views() = descendants(activity.findViewById(android.R.id.content))
                views().filterIsInstance<EditText>().single().setText("unfinished answer")
                val now = SystemClock.uptimeMillis()
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 1)))
                assertFalse(VerseAlarmStore.read(c).challenge!!.quietUsed)
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                val quiet = VerseAlarmStore.read(c).challenge!!
                assertTrue(quiet.quietUsed)
                assertFalse(quiet.ringing(AlarmClock.now(c)))
                assertEquals(900_000L, quiet.quietDurationMs)
                assertTrue(quiet.remaining(AlarmClock.now(c)) in 1..900_000L)
                assertEquals("unfinished answer", views().filterIsInstance<EditText>().single().text.toString())
                assertTrue(views().filterIsInstance<TextView>().any { it.text.startsWith("Quiet time:") })
                assertFalse(views().filterIsInstance<TextView>().first { it.text.toString() == "Quiet for 900 seconds" }.isEnabled)
                for (otherKey in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, otherKey)))
                }
                assertEquals(quiet, VerseAlarmStore.read(c).challenge)
                assertEquals(0, VerseAlarmStore.read(c).verses.single().successes)
            }
        }
    }

    @Test fun volumeButtonsDoNotSpendQuietTimeDuringWarningsOrFollowUpWaits() {
        for (remaining in listOf(30_000L, VerseAlarmRules.FOLLOW_UP_MS)) {
            withAlarm(beforeLaunch = { c ->
                val now = AlarmClock.now(c)
                val offset = VerseAlarmRules.FOLLOW_UP_MS - remaining
                assertTrue(VerseAlarmStore.answer(c, VerseAlarmStore.read(c).challenge!!.id, passage, false,
                    now.copy(wall = now.wall - offset, elapsed = now.elapsed - offset)))
            }) { c, activity ->
                val waiting = VerseAlarmStore.read(c).challenge!!
                assertFalse(waiting.ringing(AlarmClock.now(c)))
                for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                }
                assertEquals(waiting, VerseAlarmStore.read(c).challenge)
                assertFalse(VerseAlarmStore.read(c).challenge!!.quietUsed)
            }
        }
    }

    @Test fun volumeButtonsCannotRestartQuietTimeAfterRingingResumes() {
        withAlarm(quietSeconds = 1, beforeLaunch = { c ->
            assertTrue(VerseAlarmStore.quiet(c, VerseAlarmStore.read(c).challenge!!.id))
            SystemClock.sleep(1500)
        }) { c, activity ->
            val expiredQuiet = VerseAlarmStore.read(c).challenge!!
            assertTrue(expiredQuiet.quietUsed)
            assertTrue(expiredQuiet.ringing(AlarmClock.now(c)))
            for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
            }
            assertEquals(expiredQuiet, VerseAlarmStore.read(c).challenge)
            assertTrue(VerseAlarmStore.read(c).challenge!!.ringing(AlarmClock.now(c)))
        }
    }
}
