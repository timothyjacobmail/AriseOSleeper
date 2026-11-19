package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class VerseDifferencesTest {
    private fun marked(expected: String, answer: String) =
        VerseDifferences.ranges(expected, answer).map { answer.substring(it.start, it.end) }

    @Test fun usesAnswerNormalizationAndOriginalOffsets() {
        assertEquals(emptyList<String>(), marked("Don't give up, today!", "DON’T\n give up today."))
        assertEquals(emptyList<String>(), marked("a''b 123 café", "AB, 123 CAFÉ"))
        assertEquals(listOf("wrong"), marked("A clear day.", "😀 A wrong day!"))
        assertEquals(listOf("bad"), marked("Café 123", "CAFÉ bad"))
    }

    @Test fun substitutionAndInsertionDoNotShiftLaterMatches() {
        assertEquals(listOf("door"), marked("Open the window today", "Open the door today"))
        assertEquals(listOf("wide"), marked("Open the window today", "Open the wide window today"))
        assertEquals(listOf("extra"), marked("Open the window", "Open the window extra"))
        assertEquals(listOf("Open"), marked("Open the window", "Open Open the window"))
    }

    @Test fun missingWordsMarkTheAdjacentWordWithoutCascading() {
        assertEquals(listOf("window"), marked("Open the window today", "Open window today"))
        assertEquals(listOf("the"), marked("Open the window today", "the window today"))
        assertEquals(listOf("window"), marked("Open the window today", "Open the window"))
        assertEquals(listOf("Open"), marked("Open the window today", "Open"))
    }

    @Test fun reorderedWordsAreMarkedAndCorrectionsClearTheMarks() {
        assertEquals(listOf("window", "the"), marked("Open the window today", "Open window the today"))
        assertEquals(emptyList<String>(), marked("Open the window today", "Open the window today"))
        assertEquals(emptyList<String>(), marked("Open the window today", ""))
        assertEquals(emptyList<String>(), marked("Open the window today", "… ’"))
    }

    @Test fun largeDifferentDraftKeepsMatchingEdgesAndValidRanges() {
        val expected = "start " + (1..1100).joinToString(" ") { "a$it" } + " end"
        val answer = "start " + (1..1100).joinToString(" ") { "b$it" } + " end"
        val result = marked(expected, answer)
        assertEquals(1100, result.size)
        assertEquals("b1", result.first())
        assertEquals("b1100", result.last())
    }
}
