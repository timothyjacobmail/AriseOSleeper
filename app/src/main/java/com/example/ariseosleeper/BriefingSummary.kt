package com.example.ariseosleeper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.content.ContentUris
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BriefingEvent(val title: String, val start: Long, val end: Long, val allDay: Boolean)

internal object BriefingSummary {
    fun events(c: Context, now: Long): List<BriefingEvent> {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        // Include enough boundary room for all-day UTC timestamps in either hemisphere.
        ContentUris.appendId(uri, day.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        ContentUris.appendId(uri, day.plusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        return c.contentResolver.query(uri.build(), arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY),
            "visible=1 AND (eventStatus IS NULL OR eventStatus!=2) AND (selfAttendeeStatus IS NULL OR selfAttendeeStatus!=2)",
            null, "begin ASC")?.use { cur ->
            buildList {
                val seen = mutableSetOf<String>()
                while (cur.moveToNext()) {
                    val start = cur.getLong(2)
                    if (seen.add("${cur.getLong(0)}:$start"))
                        add(BriefingEvent(cur.getString(1) ?: "Untitled event", start, cur.getLong(3), cur.getInt(4) != 0))
                }
            }
        } ?: emptyList()
    }

    fun calendarText(events: List<BriefingEvent>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..1L).joinToString(" ") { offset ->
            val day = today.plusDays(offset)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val matches = events.filter { event ->
                if (event.allDay) {
                    val first = Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate()
                    val last = Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate()
                    day >= first && day < last
                } else event.start < end && event.end > maxOf(start, now)
            }
            val label = if (offset == 0L) "Today" else "Tomorrow"
            if (matches.isEmpty()) "$label, no upcoming calendar events."
            else "$label, ${matches.size} calendar events. " + matches.take(6).joinToString(". ") { event ->
                val time = when {
                    event.allDay -> "all day"
                    event.start < maxOf(start, now) -> "already in progress"
                    else -> "at " + Instant.ofEpochMilli(event.start).atZone(zone)
                        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
                }
                "${event.title.take(160)} $time"
            } + "." + if (matches.size > 6) " And ${matches.size - 6} more." else ""
        }
    }

    fun messagesText(
        messages: List<BriefingMessage>, source: String,
        options: NotificationReadingOptions = NotificationReadingOptions()
    ): String {
        val selected = messages.filter { it.app == source }
        return notificationText(selected, selected.size, source, options.copy(collected = true))
    }

    fun currentNotificationText(
        notifications: List<BriefingActiveNotification>?,
        source: String,
        options: NotificationReadingOptions,
        rules: List<BriefingFilter>
    ): String {
        if (notifications == null) return "$source notifications could not be checked. Check notification access in settings."
        val selected = notifications.filter { it.app == source }
            .filter { item -> item.messages.any { BriefingFilters.allows(it, rules) } }
        val messages = selected.flatMap { it.messages }.filter { BriefingFilters.allows(it, rules) }
        return notificationText(messages, selected.size, source, options.copy(collected = false))
    }

    private fun notificationText(
        messages: List<BriefingMessage>, count: Int, source: String, options: NotificationReadingOptions
    ): String {
        if (!options.anySelected) return ""
        if (count == 0) return if (options.count)
            if (options.collected) "$source, 0 notification previews in the briefing window." else "$source, 0 notifications waiting."
            else if (options.collected) "No collected $source notifications in the briefing window." else "No $source notifications waiting."
        val intro = if (!options.count) "$source." else if (options.collected)
            "$source, $count notification previews." else
            "$source, $count ${if (count == 1) "notification" else "notifications"} waiting."
        if (options.previews) {
            val previews = messages.distinctBy { listOf(it.sender.trim().lowercase(Locale.ROOT), it.text, it.conversation) }
            return "$intro " + previews.take(4).joinToString(". ") {
                val name = if (options.senders) "${it.sender.trim().ifBlank { source }}: " else ""
                name + it.text.trim().take(180).ifBlank { "Preview unavailable" }
            } + "." + if (previews.size > 4) {
                if (options.count) " And ${previews.size - 4} more previews." else " More previews are available."
            } else ""
        }
        if (!options.senders) return intro
        val senders = messages.map { it.sender.trim() }
            .filter { it.isNotEmpty() && !it.equals(source, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (senders.isEmpty()) return "$intro Sender names are unavailable in these notifications."
        return "$intro Senders. " + senders.take(10).joinToString(". ") + "." +
            if (senders.size > 10) {
                if (options.count) " And ${senders.size - 10} more senders." else " More sender names are available."
            } else ""
    }

    fun build(c: Context, now: Long): String = buildString {
        append("Good morning. Here is your morning briefing. ")
        val p = MorningBriefing.prefs(c)
        val notificationsReady = c.packageName in NotificationManagerCompat.getEnabledListenerPackages(c)
        val since = maxOf(p.getLong("notifications_through", 0), now - 12 * 3_600_000)
        BriefingMessages(c).use { store ->
            val rules = BriefingFilters.read(c)
            val messages = store.read(since, now).filter { BriefingFilters.allows(it, rules) }
            val current = BriefingNotificationListener.currentNotifications()
            for (source in listOf("WhatsApp", "Gmail")) {
                if (MorningBriefing.allowed(c, source)) {
                    val options = NotificationReadingOptions.read(c, source)
                    append(when {
                        !notificationsReady -> "$source notification access is off. Enable it in Morning briefing settings."
                        options.collected -> messagesText(messages, source, options)
                        else -> currentNotificationText(current, source, options, rules)
                    })
                    append(" ")
                }
            }
        }
        if (MorningBriefing.allowed(c, "calendar")) {
            append(if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                runCatching { calendarText(events(c, now), now) }.getOrDefault("Calendar could not be read.")
            else "Calendar permission is off. Enable it in Morning briefing settings.")
            append(" ")
        }
        if (MorningBriefing.allowed(c, "tasks")) {
            val tasks = BriefingTasks.fetch(c)
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            append(BriefingTasks.summary(tasks, today))
        }
        append(" That is your briefing.")
    }
}
