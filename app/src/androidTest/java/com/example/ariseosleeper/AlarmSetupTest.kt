package com.example.ariseosleeper

import android.app.Activity
import android.app.AlarmManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run only on an isolated emulator. Never changes permissions on a real phone. */
class AlarmSetupTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun setupIntentsTargetThisAppAndTheCorrectNotificationChannels() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val exact = AlarmSetupStep.EXACT_ALARMS.settingsIntent(c)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, exact.action)
        assertEquals("package:${c.packageName}", exact.dataString)
        val fullScreen = AlarmSetupStep.FULL_SCREEN.settingsIntent(c)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, fullScreen.action)
        assertEquals("package:${c.packageName}", fullScreen.dataString)
        val notifications = AlarmSetupStep.NOTIFICATIONS.settingsIntent(c)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(c.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        listOf(AlarmSetupStep.ALARM_CHANNEL to VerseAlarmScheduler.CHANNEL,
            AlarmSetupStep.WARNING_CHANNEL to VerseAlarmWarning.CHANNEL).forEach { (step, channel) ->
            val intent = step.settingsIntent(c)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(channel, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals(c.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
    }

    @Test fun missingExactAlarmAccessHasAnActionAndEnablingOrSavingOpensSetup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        assumeTrue(!c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        // Block system Settings during this test; verify the outgoing intent instead.
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            addDataScheme("package")
        },
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Setup test", "Begin the day with a clear mind.")
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    AlarmSetup.missing(c).forEach { step ->
                        assertTrue(views().any { it.tag == "alarm-setup:${step.name}" && it.isShown })
                    }
                    views().first { it.tag == "alarm-setup:EXACT_ALARMS" }.performClick()
                    assertEquals(1, monitor.hits)
                    // When notifications are already granted, exact-alarm access is the next step.
                    if (AlarmSetup.missing(c).first() == AlarmSetupStep.EXACT_ALARMS) {
                        views().filterIsInstance<CheckBox>().first { it.text == "Daily alarms enabled" }.isChecked = true
                        assertEquals(2, monitor.hits)
                        views().filterIsInstance<TextView>().first { it.text == "Save alarm settings" }.performClick()
                        assertEquals(3, monitor.hits)
                        assertFalse(VerseAlarmStore.read(c).config.enabled)
                    }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }
}
