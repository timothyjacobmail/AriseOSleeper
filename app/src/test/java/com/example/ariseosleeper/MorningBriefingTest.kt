package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class MorningBriefingTest {
    @Test fun completionRunsOnceOnSameDayAndExpiresAfterAnHour() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        try {
            val completed = Instant.parse("2026-11-17T02:00:00Z").toEpochMilli()
            assertTrue(MorningBriefing.eligible(completed, completed + 1000, "2026-11-16"))
            assertFalse(MorningBriefing.eligible(completed, completed + 1000, "2026-11-17"))
            assertFalse(MorningBriefing.eligible(completed, completed + 3_600_000, ""))
            assertFalse(MorningBriefing.eligible(completed, completed - 1000, ""))
            assertFalse(MorningBriefing.eligible(0, completed, ""))
            val midnight = Instant.parse("2026-11-17T19:59:50Z").toEpochMilli()
            assertFalse(MorningBriefing.eligible(midnight, midnight + 20_000, ""))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun calendarUsesLocalDaysAndUtcDatesForAllDayEvents() {
        fun millis(value: String) = Instant.parse(value).toEpochMilli()
        val now = millis("2026-11-17T02:00:00Z")
        val events = listOf(
            BriefingEvent("Already ended", millis("2026-11-17T00:00:00Z"), millis("2026-11-17T01:00:00Z"), false),
            BriefingEvent("Night shift", millis("2026-11-16T23:00:00Z"), millis("2026-11-17T03:00:00Z"), false),
            BriefingEvent("Holiday", millis("2026-11-17T00:00:00Z"), millis("2026-11-18T00:00:00Z"), true),
            BriefingEvent("Next day", millis("2026-11-17T20:30:00Z"), millis("2026-11-17T21:00:00Z"), false))
        val text = BriefingSummary.calendarText(events, now, ZoneId.of("Asia/Dubai"))
        assertFalse(text.contains("Already ended"))
        assertTrue(text.contains("Today, 2 calendar events."))
        assertTrue(text.contains("Night shift already in progress"))
        assertTrue(text.contains("Holiday all day"))
        assertTrue(text.contains("Tomorrow, 1 calendar events."))
        assertTrue(text.contains("Next day"))
    }

    @Test fun calendarHandlesDaylightSavingBoundaryAndExclusiveAllDayEnd() {
        val now = Instant.parse("2026-11-01T07:30:00Z").toEpochMilli()
        val holiday = BriefingEvent("Weekend", Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-02T00:00:00Z").toEpochMilli(), true)
        val text = BriefingSummary.calendarText(listOf(holiday), now, ZoneId.of("America/New_York"))
        assertTrue(text.contains("Today, 1 calendar events."))
        assertTrue(text.contains("Tomorrow, no upcoming calendar events."))
    }

    @Test fun notificationSummaryCapsSpeechAndSeparatesApplications() {
        val messages = (1..8).map { BriefingMessage("WhatsApp", "Person $it", "Message $it") } +
            BriefingMessage("Gmail", "Email sender", "Email subject")
        val text = BriefingSummary.messagesText(messages, "WhatsApp")
        assertTrue(text.contains("8 notification previews"))
        assertTrue(text.contains("And 4 more"))
        assertFalse(text.contains("Message 5"))
        assertFalse(text.contains("Email subject"))
    }
}
