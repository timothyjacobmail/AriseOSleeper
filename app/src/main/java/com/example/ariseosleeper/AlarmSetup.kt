package com.example.ariseosleeper

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** One source of truth for setup actions and the checks that gate enabling alarms. */
internal enum class AlarmSetupStep(val title: String, val explanation: String) {
    NOTIFICATIONS("Allow notifications", "Show the alarm and its one-minute warning."),
    EXACT_ALARMS("Allow alarms & reminders", "Ring at your chosen time, even while the phone is idle."),
    FULL_SCREEN("Allow full-screen alarms", "Show the verse check over the lock screen when Android permits it."),
    ALARM_CHANNEL("Allow high-priority alarm notifications", "Enable the alarm channel and allow it to pop on screen."),
    WARNING_CHANNEL("Allow one-minute verse warning notifications", "Enable the warning channel and allow it to pop on screen."),
    VOLUME("Raise the alarm volume above zero", "Use the alarm volume slider in Sound settings.");

    fun settingsIntent(c: Context): Intent = when (this) {
        EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))
        FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}"))
        NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        ALARM_CHANNEL, WARNING_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID,
                if (this == ALARM_CHANNEL) VerseAlarmScheduler.CHANNEL else VerseAlarmWarning.CHANNEL)
        VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
    }
}

internal object AlarmSetup {
    fun missing(c: Context): List<AlarmSetupStep> = buildList {
        VerseAlarmScheduler.channel(c)
        val manager = c.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) add(AlarmSetupStep.NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31 && !c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            add(AlarmSetupStep.EXACT_ALARMS)
        if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) add(AlarmSetupStep.FULL_SCREEN)
        if (manager.getNotificationChannel(VerseAlarmScheduler.CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH)
            add(AlarmSetupStep.ALARM_CHANNEL)
        if (manager.getNotificationChannel(VerseAlarmWarning.CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH)
            add(AlarmSetupStep.WARNING_CHANNEL)
        if (c.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0)
            add(AlarmSetupStep.VOLUME)
    }
}
