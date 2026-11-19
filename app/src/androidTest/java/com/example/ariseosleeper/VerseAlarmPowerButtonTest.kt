package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs a brief audible alarm. Use only on an authorized device with no active routine. */
class VerseAlarmPowerButtonTest {
    @Test fun powerScreenOffStartsQuietWithoutAnActivityAndCannotExtendIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        val original = VerseAlarmStore.read(c)
        assumeTrue("Do not interrupt an active routine", original.challenge == null && original.pending == null)
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val saved = prefs.getString("state", null)
        val power = c.getSystemService(PowerManager::class.java)
        val wasInteractive = power.isInteractive
        val audio = c.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        fun key(name: String) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent $name").close()
        }
        fun awaitCondition(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(message, predicate())
        }
        fun sync() = instrumentation.runOnMainSync { VerseAlarmScheduler.sync(c) }
        try {
            key("KEYCODE_WAKEUP")
            awaitCondition("Phone must wake for the power-button check") { power.isInteractive }
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Power button test", "Wake up and welcome the new day.", 60)
            val now = AlarmClock.now(c)
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), now.copy(wall = now.wall - 86_400_000))
            val ringing = VerseAlarmStore.reconcile(c, now).challenge!!
            assertTrue(ringing.ringing(now))
            sync()
            // Service creation runs on the main looper before the barrier returns.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            key("KEYCODE_POWER")
            awaitCondition("Power screen-off should spend quiet time") {
                VerseAlarmStore.read(c).challenge?.quietUsed == true
            }
            val quiet = VerseAlarmStore.read(c).challenge!!
            assertEquals(ringing.id, quiet.id)
            assertEquals(60_000L, quiet.quietDurationMs)
            assertFalse(quiet.ringing(AlarmClock.now(c)))
            assertEquals(0, VerseAlarmStore.read(c).verses.single().successes)
            key("KEYCODE_WAKEUP")
            awaitCondition("Phone must wake for the repeat check") { power.isInteractive }
            key("KEYCODE_POWER")
            awaitCondition("Second power press should turn screen off") { !power.isInteractive }
            assertEquals(quiet, VerseAlarmStore.read(c).challenge)
        } finally {
            instrumentation.runOnMainSync { c.stopService(Intent(c, VerseAlarmService::class.java)) }
            instrumentation.waitForIdleSync()
            prefs.edit().clear().putString("state", saved).commit()
            sync()
            instrumentation.waitForIdleSync()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            key(if (wasInteractive) "KEYCODE_WAKEUP" else "KEYCODE_SLEEP")
            assertEquals(original, VerseAlarmStore.read(c))
        }
    }
}
