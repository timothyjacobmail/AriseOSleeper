package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class SharedVerseParserTest {
    private val passage = "On the third day there was a wedding at Cana in Galilee, and the mother of Jesus was there."

    @Test fun parsesUserExampleWithCurlyQuotesAndTrailingLink() {
        val raw = ",“$passage ”\nJohn 2:1 ESV\n\nhttps://bible.com/bible/59/jhn.2.1.ESV'"
        assertEquals(SharedVerse("John 2:1", passage), SharedVerseParser.parse(raw))
    }

    @Test fun handlesWindowsLineEndingsFormattingMarksAndNumberedBooks() {
        val raw = "\u202A\"Let us love one another.\"\r\n1   John 4 : 7 – 8 (ESV)\u202C\r\nhttps://www.bible.com/bible/59/1JN.4.7-8.ESV"
        assertEquals(SharedVerse("1 John 4:7-8", "Let us love one another."), SharedVerseParser.parse(raw))
    }

    @Test fun preservesPunctuationParagraphsAndNestedQuotes() {
        val raw = "“He said, ‘Come!’\n\nThey replied, ‘We will!’”\nMatthew 4:19-5:2 ESV"
        assertEquals(SharedVerse("Matthew 4:19-5:2", "He said, ‘Come!’\n\nThey replied, ‘We will!’"), SharedVerseParser.parse(raw))
    }

    @Test fun supportsInlineReferenceAndNoTranslation() {
        assertEquals(SharedVerse("Psalm 23:1", "The Lord is my shepherd."),
            SharedVerseParser.parse("The Lord is my shepherd. Psalm 23:1"))
    }

    @Test fun doesNotTreatPlainTextReferenceOrLinkAsASharedPassage() {
        assertNull(SharedVerseParser.parse(passage))
        assertNull(SharedVerseParser.parse("John 2:1 ESV"))
        assertNull(SharedVerseParser.parse("https://bible.com/bible/59/jhn.2.1.ESV"))
        assertNull(SharedVerseParser.parse("$passage\nNotABook 2:1 ESV"))
        assertNull(SharedVerseParser.parse("$passage\nJohn 0:1 ESV"))
        assertNull(SharedVerseParser.parse("   "))
    }

    @Test fun doesNotSilentlyImportAnotherTranslationAsEsv() {
        assertNull(SharedVerseParser.parse("$passage\nJohn 2:1 NIV\nhttps://bible.com/bible/111/jhn.2.1.NIV"))
    }

    @Test fun retainsVerseNumbersInPassageForManualReview() {
        assertEquals(SharedVerse("John 2:1", "1 $passage"), SharedVerseParser.parse("1 $passage\nJohn 2:1 ESV"))
    }

    @Test fun handlesVerseListsAndCaseInsensitiveMetadata() {
        assertEquals(SharedVerse("John 2:1,3-5", passage),
            SharedVerseParser.parse("$passage\njohn 2:1, 3-5 esv\nhttps://BIBLE.COM/bible/59/jhn.2.1.ESV"))
    }
}
