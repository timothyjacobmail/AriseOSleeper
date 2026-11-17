package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class NotificationReadingTest {
    private val a = BriefingMessage("Gmail", "Alice", "Private subject", "Alice")
    private val b = BriefingMessage("Gmail", "Bob", "Private message body", "Bob")
    private val notifications = listOf(
        BriefingActiveNotification("Gmail", listOf(a, a)),
        BriefingActiveNotification("Gmail", listOf(a.copy(sender = "ALICE"))),
        BriefingActiveNotification("Gmail", listOf(b)),
        BriefingActiveNotification("WhatsApp", listOf(BriefingMessage("WhatsApp", "Family", "Private chat"))))

    @Test fun countOnlyCountsNotificationCardsAndNeverSpeaksNamesOrContents() {
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingMode.COUNT_ONLY, emptyList())
        assertEquals("Gmail, 3 notifications waiting.", text)
        assertFalse(text.contains("Alice"))
        assertFalse(text.contains("Private"))
        assertEquals("WhatsApp, 1 notification waiting.",
            BriefingSummary.currentNotificationText(notifications, "WhatsApp", NotificationReadingMode.COUNT_ONLY, emptyList()))
    }

    @Test fun senderOnlyDeduplicatesNamesAndNeverIncludesSubjectsOrBodies() {
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingMode.SENDERS_ONLY, emptyList())
        assertEquals("Gmail, 3 notifications waiting. Senders. Alice. Bob.", text)
        assertFalse(text.contains("Private"))
    }

    @Test fun filtersApplyBeforeCountingAndNamingAndMissingListenerIsExplicit() {
        val rules = listOf(BriefingFilter(app = "Gmail", pattern = "Alice", match = "Equals"))
        assertEquals("Gmail, 1 notification waiting. Senders. Bob.",
            BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingMode.SENDERS_ONLY, rules))
        assertTrue(BriefingSummary.currentNotificationText(null, "Gmail", NotificationReadingMode.COUNT_ONLY, rules)
            .contains("could not be checked"))
        assertEquals("Gmail, 0 notifications waiting.",
            BriefingSummary.currentNotificationText(emptyList(), "Gmail", NotificationReadingMode.SENDERS_ONLY, rules))
    }

    @Test fun hiddenSenderStillCountsWithoutInventingAName() {
        val hidden = listOf(BriefingActiveNotification("Gmail", listOf(a.copy(sender = "", text = ""))))
        assertEquals("Gmail, 1 notification waiting. Sender names are unavailable in these notifications.",
            BriefingSummary.currentNotificationText(hidden, "Gmail", NotificationReadingMode.SENDERS_ONLY, emptyList()))
    }
}
