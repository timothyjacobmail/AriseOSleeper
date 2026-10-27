package com.example.ariseosleeper

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run only on an isolated emulator with notification permission initially denied. */
class NotificationPermissionTest {
    @Test fun notificationSetupRequestsAndroidPermissionAndDenialDoesNotEnableAlarm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        val setupPrefs = c.getSharedPreferences("alarm_setup", Context.MODE_PRIVATE)
        val askedBefore = setupPrefs.getBoolean("askedNotifications", false)
        // Intercept the platform request and simulate denial without depending on system UI rendering.
        val monitor = instrumentation.addMonitor(IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            prefs.edit().clear().commit()
            setupPrefs.edit().putBoolean("askedNotifications", false).commit()
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(android.R.id.content)
                        .findViewWithTag<View>("alarm-setup:NOTIFICATIONS").performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals(1, monitor.hits)
                assertEquals(PackageManager.PERMISSION_DENIED, c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                assertTrue(AlarmSetup.missing(c).contains(AlarmSetupStep.NOTIFICATIONS))
                assertFalse(VerseAlarmStore.read(c).config.enabled)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            setupPrefs.edit().putBoolean("askedNotifications", askedBefore).commit()
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }
}
