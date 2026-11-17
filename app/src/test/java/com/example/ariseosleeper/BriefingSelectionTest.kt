package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BriefingSelectionTest {
    @Test fun defaultListMatchesTheRequestedNameAndExplicitIdsSurviveRenames() {
        val lists = listOf(BriefingTaskList("a", "Work"), BriefingTaskList("b", " Immediate Tasks "))
        assertEquals("b", BriefingTasks.selectLists(lists, "").single().id)
        assertEquals("b", BriefingTasks.selectLists(listOf(BriefingTaskList("b", "Renamed")), "b").single().id)
        assertTrue(BriefingTasks.selectLists(lists, "deleted").isEmpty())
        assertEquals(2, BriefingTasks.selectLists(lists, BriefingTasks.ALL_LISTS).size)
    }

    @Test fun undatedAndOverdueImmediateTasksAreIncludedInSpeech() {
        val today = LocalDate.of(2026, 11, 17)
        val tasks = TaskBriefing(listOf(BriefingTask("Call the office", null),
            BriefingTask("Submit form", today.minusDays(1))), listTitle = "Immediate Tasks")
        val text = BriefingTasks.summary(tasks, today)
        assertTrue(text.contains("Call the office. Submit form, overdue"))
        assertFalse(text.contains("no due date"))
        assertFalse(text.contains("Call the office,"))
        assertTrue(text.contains("Submit form, overdue"))
        assertTrue(text.contains("From Immediate Tasks"))
    }

    @Test fun undatedTaskHasNoSpokenDateSuffix() {
        assertEquals("From Immediate tasks, 1 incomplete task. Call the office.",
            BriefingTasks.summary(TaskBriefing(listOf(BriefingTask("Call the office", null)), listTitle = "Immediate tasks"),
                LocalDate.of(2026, 11, 17)))
    }

    @Test fun updateAgeIsOptionalAndUsesAnExactRollingWindow() {
        val now = java.time.Instant.parse("2026-11-17T04:00:00Z")
        assertNull(BriefingTasks.updatedCutoff(now, 0))
        assertTrue(BriefingTasks.updatedSince("", null))
        val cutoff = BriefingTasks.updatedCutoff(now, 2)
        assertEquals(java.time.Instant.parse("2026-11-15T04:00:00Z"), cutoff)
        assertTrue(BriefingTasks.updatedSince("2026-11-15T04:00:00Z", cutoff))
        assertFalse(BriefingTasks.updatedSince("2026-11-15T03:59:59Z", cutoff))
        assertTrue(BriefingTasks.updatedSince("2026-11-17T03:00:00Z", cutoff))
        assertFalse(BriefingTasks.updatedSince("", cutoff))
        assertFalse(BriefingTasks.updatedSince("not-a-date", cutoff))
    }

    @Test fun includeRulesAreScopedToTheirAppAndIgnoreRulesWin() {
        val personal = BriefingMessage("WhatsApp", "Alice", "Good morning", "Family group")
        val promo = personal.copy(text = "SALE today")
        val email = BriefingMessage("Gmail", "Office", "New message")
        val allow = BriefingFilter(app = "WhatsApp", field = "Sender or conversation", pattern = "family", include = true)
        val ignore = BriefingFilter(field = "Message text", pattern = "sale")
        assertTrue(BriefingFilters.allows(personal, listOf(allow, ignore)))
        assertFalse(BriefingFilters.allows(promo, listOf(allow, ignore)))
        assertFalse(BriefingFilters.allows(personal.copy(conversation = "Work"), listOf(allow)))
        assertTrue(BriefingFilters.allows(email, listOf(allow)))
        assertTrue(BriefingFilters.allows(promo, listOf(ignore.copy(enabled = false))))
    }

    @Test fun matchingSupportsCaseInsensitiveExactAndPrefixWithLegacyEmptyConversation() {
        val message = BriefingMessage("Gmail", "Billing Department", "Invoice 123")
        assertTrue(BriefingFilter(pattern = "billing", match = "Starts with").matches(message))
        assertFalse(BriefingFilter(pattern = "billing", match = "Equals").matches(message))
        assertTrue(BriefingFilter(pattern = "BILLING DEPARTMENT", match = "Equals").matches(message))
        assertTrue(BriefingFilter(pattern = "invoice", field = "Either").matches(message))
        assertFalse(BriefingFilter(pattern = "invoice", field = "Sender or conversation").matches(message))
    }
}
