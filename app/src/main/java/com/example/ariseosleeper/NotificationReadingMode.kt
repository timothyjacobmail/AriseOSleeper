package com.example.ariseosleeper

import android.content.Context

internal enum class NotificationReadingMode(val label: String, val description: String) {
    PREVIEWS("Message previews", "Count, senders and collected previews from the briefing window"),
    COUNT_ONLY("Count only", "Number of notification cards currently waiting, without names or text"),
    SENDERS_ONLY("Sender names only", "Unique sender names from waiting notifications, without a count or message text"),
    COUNT_AND_SENDERS("Count + sender names", "Waiting notification count and unique sender names, without message text"),
    COUNT_SENDERS_AND_PREVIEWS("Count + sender names + previews", "Waiting notification count and up to four previews with their sender names");

    companion object {
        fun read(c: Context, source: String): NotificationReadingMode {
            val saved = MorningBriefing.prefs(c).getString("notification_mode_$source", PREVIEWS.name)
            return values().firstOrNull { it.name == saved } ?: PREVIEWS
        }
    }
}
