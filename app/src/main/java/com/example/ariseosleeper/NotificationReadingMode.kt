package com.example.ariseosleeper

import android.content.Context

internal enum class NotificationReadingMode(val label: String) {
    PREVIEWS("Message previews"),
    COUNT_ONLY("Count only"),
    SENDERS_ONLY("Sender names only");

    companion object {
        fun read(c: Context, source: String): NotificationReadingMode {
            val saved = MorningBriefing.prefs(c).getString("notification_mode_$source", PREVIEWS.name)
            return values().firstOrNull { it.name == saved && (source == "Gmail" || it != SENDERS_ONLY) } ?: PREVIEWS
        }
    }
}
