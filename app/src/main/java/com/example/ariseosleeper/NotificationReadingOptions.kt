package com.example.ariseosleeper

import android.content.Context

/** Independent spoken parts. Old presets are read lazily without changing stored preferences. */
internal data class NotificationReadingOptions(
    val count: Boolean = true,
    val senders: Boolean = true,
    val previews: Boolean = true,
    val collected: Boolean = true
) {
    val parts: Set<String> get() = buildSet {
        if (count) add("count")
        if (senders) add("senders")
        if (previews) add("previews")
    }
    val anySelected: Boolean get() = count || senders || previews
    val label: String get() = buildList {
        if (count) add("Count")
        if (senders) add("Sender names")
        if (previews) add("Previews")
    }.joinToString(" + ")

    companion object {
        fun fromLegacy(name: String?): NotificationReadingOptions = when (name) {
            "COUNT_ONLY" -> NotificationReadingOptions(true, false, false, false)
            "SENDERS_ONLY" -> NotificationReadingOptions(false, true, false, false)
            "COUNT_AND_SENDERS" -> NotificationReadingOptions(true, true, false, false)
            "COUNT_SENDERS_AND_PREVIEWS" -> NotificationReadingOptions(true, true, true, false)
            else -> NotificationReadingOptions()
        }
        fun fromParts(parts: Set<String>, collected: Boolean) = NotificationReadingOptions(
            "count" in parts, "senders" in parts, "previews" in parts, collected)

        fun read(c: Context, source: String): NotificationReadingOptions {
            val prefs = MorningBriefing.prefs(c)
            if (!prefs.contains("notification_parts_$source"))
                return fromLegacy(prefs.getString("notification_mode_$source", "PREVIEWS"))
            return fromParts(prefs.getStringSet("notification_parts_$source", emptySet()).orEmpty(),
                prefs.getBoolean("notification_collected_$source", true))
        }
    }
}
