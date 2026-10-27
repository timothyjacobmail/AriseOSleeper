package com.example.ariseosleeper

internal data class SharedVerse(val reference: String, val passage: String)

/** Extracts copied Bible share metadata locally. URLs are never fetched. */
internal object SharedVerseParser {
    private val books = listOf(
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth",
        "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra",
        "Nehemiah", "Esther", "Job", "Psalms", "Psalm", "Proverbs", "Ecclesiastes", "Song of Solomon",
        "Song of Songs", "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel",
        "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai",
        "Zechariah", "Malachi", "Matthew", "Mark", "Luke", "John", "Acts", "Romans",
        "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians",
        "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon",
        "Hebrews", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"
    )
    private val bookNames = books.joinToString("|") { name ->
        name.split(' ').joinToString("\\s+") { Regex.escape(it) }
    }
    private val reference = Regex(
        "(?:^|\\s)($bookNames)\\s+([1-9]\\d*\\s*:\\s*[1-9]\\d*" +
            "(?:\\s*[-–—,]\\s*[1-9]\\d*(?:\\s*:\\s*[1-9]\\d*)?)*)" +
            "(?:\\s+\\(?([A-Za-z][A-Za-z0-9]*)\\)?)?[\\s\"'‘’“”«»]*$",
        RegexOption.IGNORE_CASE
    )
    private val link = Regex("https?://(?:www\\.)?bible\\.com/\\S+", RegexOption.IGNORE_CASE)
    private val formatting = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val spaces = Regex("\\s+")
    private val quotes = mapOf('“' to '”', '‘' to '’', '"' to '"', '\'' to '\'', '«' to '»')

    fun parse(raw: String): SharedVerse? {
        if (raw.length > 12_000) return null
        val text = link.replace(formatting.replace(raw, ""), "").trim()
        val match = reference.find(text) ?: return null
        val translation = match.groupValues[3]
        if (translation.isNotEmpty() && !translation.equals("ESV", ignoreCase = true)) return null
        var passage = text.substring(0, match.range.first).trim()
        // Some copied shares have an extra comma before the opening quotation mark.
        if (passage.startsWith(',') && passage.drop(1).trimStart().firstOrNull() in quotes)
            passage = passage.drop(1).trimStart()
        val closingQuote = quotes[passage.firstOrNull()]
        if (passage.length >= 2 && closingQuote != null && passage.last() == closingQuote)
            passage = passage.substring(1, passage.lastIndex).trim()
        if (passage.isBlank()) return null // A reference or URL alone cannot supply passage text.
        val book = books.first { it.equals(match.groupValues[1].replace(spaces, " "), true) }
        val verses = match.groupValues[2].replace(spaces, "").replace('–', '-').replace('—', '-')
        return SharedVerse("$book $verses", passage)
    }
}
