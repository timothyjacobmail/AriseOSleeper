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
        assertTrue(text.contains("Call the office, no due date"))
        assertTrue(text.contains("Submit form, overdue"))
        assertTrue(text.contains("From Immediate Tasks"))
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
