package com.example.ariseosleeper

/** Word edits mapped back to UTF-16 offsets in the unchanged answer. */
internal object VerseDifferences {
    data class Range(val start: Int, val end: Int)
    private data class Word(val value: String, val range: Range)
    private val token = Regex("[\\p{L}\\p{N}'’]+")

    fun ranges(expected: String, answer: String): List<Range> {
        val source = VerseAlarmRules.words(expected)
        val typed = token.findAll(answer).mapNotNull { match ->
            VerseAlarmRules.words(match.value).singleOrNull()?.let {
                Word(it, Range(match.range.first, match.range.last + 1))
            }
        }.toList()
        if (typed.isEmpty()) return emptyList()
        var start = 0
        while (start < minOf(source.size, typed.size) && source[start] == typed[start].value) start++
        var sourceEnd = source.size
        var typedEnd = typed.size
        while (sourceEnd > start && typedEnd > start && source[sourceEnd - 1] == typed[typedEnd - 1].value) {
            sourceEnd--
            typedEnd--
        }
        val marked = sortedSetOf<Int>()
        // An omitted word has no characters to underline: mark the following word,
        // or the last typed word for an omission at the end.
        fun missing(at: Int) { marked.add(at.coerceAtMost(typed.lastIndex)) }
        val height = sourceEnd - start
        val width = typedEnd - start
        if (height == 0) {
            marked.addAll(start until typedEnd)
        } else if (width == 0) {
            missing(start)
        } else if ((height + 1L) * (width + 1L) > 1_000_000) {
            // Keep live typing responsive for very large, entirely different drafts.
            // Prefix and suffix matches remain unmarked; mark the differing block.
            marked.addAll(start until typedEnd)
        } else {
            val stride = width + 1
            val edits = ByteArray((height + 1) * stride)
            var previous = IntArray(stride) { it }
            for (j in 1..width) edits[j] = 2 // extra typed word
            for (i in 1..height) {
                val row = IntArray(stride)
                row[0] = i
                edits[i * stride] = 1 // missing word
                for (j in 1..width) {
                    val same = source[start + i - 1] == typed[start + j - 1].value
                    val diagonal = previous[j - 1] + if (same) 0 else 1
                    val deletion = previous[j] + 1
                    val insertion = row[j - 1] + 1
                    row[j] = minOf(diagonal, deletion, insertion)
                    edits[i * stride + j] = when (row[j]) {
                        diagonal -> if (same) 0 else 3
                        deletion -> 1
                        else -> 2
                    }
                }
                previous = row
            }
            var i = height
            var j = width
            while (i > 0 || j > 0) {
                when (edits[i * stride + j].toInt()) {
                    0 -> { i--; j-- }
                    1 -> { missing(start + j); i-- }
                    2 -> { marked.add(start + j - 1); j-- }
                    3 -> { marked.add(start + j - 1); i--; j-- }
                }
            }
        }
        return marked.map { typed[it].range }
    }
}
