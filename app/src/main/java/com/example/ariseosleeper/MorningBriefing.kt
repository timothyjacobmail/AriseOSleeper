package com.example.ariseosleeper

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import java.time.Instant
import java.time.ZoneId

internal object MorningBriefing {
    fun prefs(c: Context) = c.getSharedPreferences("morning_briefing", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun allowed(c: Context, source: String) = enabled(c) && prefs(c).getBoolean(source, true)
    fun storageAvailable(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked
    fun unlocked(c: Context) = storageAvailable(c) &&
        !c.getSystemService(KeyguardManager::class.java).isDeviceLocked

    fun eligible(completedAt: Long, now: Long, lastDay: String): Boolean {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate().toString()
        return completedAt > 0 && now >= completedAt && now - completedAt < 3_600_000 &&
            day == Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString() && day != lastDay
    }

    @Synchronized fun maybeStart(activity: Activity) {
        if (!storageAvailable(activity) || !enabled(activity)) return
        val state = VerseAlarmStore.read(activity)
        val id = state.completedRoutineId ?: return
        if (state.challenge != null) return
        val prefs = prefs(activity)
        val now = System.currentTimeMillis()
        if (prefs.getString("routine", "") == id ||
            !eligible(state.completedRoutineAt, now, prefs.getString("day", "").orEmpty())) return
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        // Claim before launch so activity recreation cannot speak twice.
        if (!prefs.edit().putString("routine", id).putString("day", day).commit()) return
        try {
            activity.startForegroundService(Intent(activity, BriefingService::class.java).setAction("BUILD"))
        } catch (_: RuntimeException) {
            prefs.edit().remove("routine").remove("day").commit()
        }
    }
}
