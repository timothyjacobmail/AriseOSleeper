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
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingOptions.fromLegacy("COUNT_ONLY"), emptyList())
        assertEquals("Gmail, 3 notifications waiting.", text)
        assertFalse(text.contains("Alice"))
        assertFalse(text.contains("Private"))
        assertEquals("WhatsApp, 1 notification waiting.",
            BriefingSummary.currentNotificationText(notifications, "WhatsApp", NotificationReadingOptions.fromLegacy("COUNT_ONLY"), emptyList()))
    }

    @Test fun senderOnlyDeduplicatesNamesAndNeverIncludesSubjectsOrBodies() {
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingOptions.fromLegacy("SENDERS_ONLY"), emptyList())
        assertEquals("Gmail. Senders. Alice. Bob.", text)
        assertFalse(text.contains("Private"))
    }

    @Test fun filtersApplyBeforeCountingAndNamingAndMissingListenerIsExplicit() {
        val rules = listOf(BriefingFilter(app = "Gmail", pattern = "Alice", match = "Equals"))
        assertEquals("Gmail, 1 notification waiting. Senders. Bob.",
            BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingOptions.fromLegacy("COUNT_AND_SENDERS"), rules))
        assertTrue(BriefingSummary.currentNotificationText(null, "Gmail", NotificationReadingOptions.fromLegacy("COUNT_ONLY"), rules)
            .contains("could not be checked"))
        assertEquals("No Gmail notifications waiting.",
            BriefingSummary.currentNotificationText(emptyList(), "Gmail", NotificationReadingOptions.fromLegacy("SENDERS_ONLY"), rules))
    }

    @Test fun countAndNamesDoesNotReadMessageBodies() {
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail", NotificationReadingOptions.fromLegacy("COUNT_AND_SENDERS"), emptyList())
        assertEquals("Gmail, 3 notifications waiting. Senders. Alice. Bob.", text)
        assertFalse(text.contains("Private"))
    }

    @Test fun combinedModeAssociatesPreviewsWithNamesAndFiltersBeforeSpeaking() {
        val rules = listOf(BriefingFilter(app = "Gmail", pattern = "Bob", match = "Equals"))
        val text = BriefingSummary.currentNotificationText(notifications, "Gmail",
            NotificationReadingOptions.fromLegacy("COUNT_SENDERS_AND_PREVIEWS"), rules)
        assertEquals("Gmail, 2 notifications waiting. Alice: Private subject.", text)
        assertFalse(text.contains("Bob"))
        assertFalse(text.contains("Private message body"))
    }

    @Test fun combinedModeDeduplicatesRepeatedPreviewsCapsSpeechAndHandlesRedaction() {
        val items = (1..6).map { BriefingMessage("WhatsApp", "Person $it", "Message $it") }
        val cards = items.map { BriefingActiveNotification("WhatsApp", listOf(it, it)) }
        val text = BriefingSummary.currentNotificationText(cards, "WhatsApp",
            NotificationReadingOptions.fromLegacy("COUNT_SENDERS_AND_PREVIEWS"), emptyList())
        assertTrue(text.startsWith("WhatsApp, 6 notifications waiting."))
        assertTrue(text.contains("Person 4: Message 4"))
        assertFalse(text.contains("Message 5"))
        assertTrue(text.endsWith("And 2 more previews."))
        val redacted = listOf(BriefingActiveNotification("WhatsApp", listOf(BriefingMessage("WhatsApp", "", ""))))
        assertEquals("WhatsApp, 1 notification waiting. WhatsApp: Preview unavailable.",
            BriefingSummary.currentNotificationText(redacted, "WhatsApp", NotificationReadingOptions.fromLegacy("COUNT_SENDERS_AND_PREVIEWS"), emptyList()))
    }

    @Test fun allNonEmptySelectionsIndependentlyControlSpokenParts() {
        for (mask in 1..7) {
            val options = NotificationReadingOptions(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, false)
            val text = BriefingSummary.currentNotificationText(notifications, "Gmail", options, emptyList())
            assertEquals("count mask $mask", options.count, text.contains("3 notifications waiting."))
            assertEquals("senders mask $mask", options.senders, text.contains("Alice"))
            assertEquals("previews mask $mask", options.previews, text.contains("Private subject"))
            val collected = BriefingSummary.messagesText(listOf(a, b), "Gmail", options)
            assertEquals("collected count mask $mask", options.count, collected.contains("2 notification previews."))
            assertEquals("collected senders mask $mask", options.senders, collected.contains("Alice"))
            assertEquals("collected previews mask $mask", options.previews, collected.contains("Private subject"))
            assertEquals(options, NotificationReadingOptions.fromParts(options.parts, options.collected))
        }
    }

    @Test fun legacySettingsRetainTheirPartsAndWindow() {
        assertEquals(NotificationReadingOptions(), NotificationReadingOptions.fromLegacy("PREVIEWS"))
        assertEquals(NotificationReadingOptions(true, false, false, false), NotificationReadingOptions.fromLegacy("COUNT_ONLY"))
        assertEquals(NotificationReadingOptions(false, true, false, false), NotificationReadingOptions.fromLegacy("SENDERS_ONLY"))
        assertEquals(NotificationReadingOptions(true, true, false, false), NotificationReadingOptions.fromLegacy("COUNT_AND_SENDERS"))
        assertEquals(NotificationReadingOptions(true, true, true, false), NotificationReadingOptions.fromLegacy("COUNT_SENDERS_AND_PREVIEWS"))
        assertFalse(NotificationReadingOptions(false, false, false).anySelected)
    }

    @Test fun hiddenSenderDoesNotInventANameOrSpeakACountInNamesOnlyMode() {
        val hidden = listOf(BriefingActiveNotification("Gmail", listOf(a.copy(sender = "", text = ""))))
        assertEquals("Gmail. Sender names are unavailable in these notifications.",
            BriefingSummary.currentNotificationText(hidden, "Gmail", NotificationReadingOptions.fromLegacy("SENDERS_ONLY"), emptyList()))
    }
}
