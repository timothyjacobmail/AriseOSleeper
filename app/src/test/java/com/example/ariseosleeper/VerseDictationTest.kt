package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class VerseDictationTest {
    private class Engine : DictationEngine {
        lateinit var listener: DictationEngine.Listener
        var starts = 0
        var stops = 0
        var cancels = 0
        var destroys = 0
        override fun start(listener: DictationEngine.Listener) { this.listener = listener; starts++ }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }
    private class Fixture {
        var target = "alarm:one"
        val engines = mutableListOf<Engine>()
        val results = mutableListOf<Pair<String, String>>()
        var quietCalls = 0
        val dictation = VerseDictation(factory = { Engine().also { engines.add(it) } },
            isCurrent = { it == target }, onState = {}, onResult = { id, text -> results.add(id to text) })
        fun start() = dictation.start(target) { quietCalls++ }
        val engine get() = engines.last()
    }

    @Test fun partialsStayPreviewOnlyAndFinalResultIsDeliveredOnce() {
        val f = Fixture()
        f.start()
        f.engine.listener.ready()
        f.engine.listener.partial("Begin the morning")
        assertTrue(f.dictation.state.active)
        assertEquals("Begin the morning", f.dictation.state.partial)
        assertTrue(f.results.isEmpty())
        f.engine.listener.result("Begin the morning with a clear mind.")
        f.engine.listener.result("Duplicate callback")
        assertEquals(listOf("alarm:one" to "Begin the morning with a clear mind."), f.results)
        assertFalse(f.dictation.state.active)
        assertEquals(1, f.engine.destroys)
    }

    @Test fun stopWaitsForResultsAndIgnoresDuplicateStartsAndStops() {
        val f = Fixture()
        f.start(); f.start()
        f.dictation.stop(); f.dictation.stop()
        assertEquals(1, f.engines.size)
        assertEquals(1, f.engine.stops)
        assertEquals(1, f.quietCalls)
        assertTrue(f.dictation.state.active)
        assertEquals(DictationPhase.PROCESSING, f.dictation.state.phase)
        f.engine.listener.ready()
        assertEquals(DictationPhase.PROCESSING, f.dictation.state.phase)
        f.engine.listener.result("Finished words")
        assertFalse(f.dictation.state.active)
    }

    @Test fun cancelReleasesMicrophoneAndDiscardsLaterCallbacks() {
        val f = Fixture()
        f.start()
        f.dictation.cancel()
        f.engine.listener.partial("Discard this")
        f.engine.listener.result("Discard this too")
        assertTrue(f.results.isEmpty())
        assertEquals(1, f.engine.cancels)
        assertEquals(1, f.engine.destroys)
        assertEquals("", f.dictation.state.partial)
    }

    @Test fun previousRequestCannotOverwriteNewRequestForTheSameVerse() {
        val f = Fixture()
        f.start()
        val previous = f.engine
        f.dictation.cancel()
        f.start()
        previous.listener.result("Old recording")
        assertTrue(f.dictation.state.active)
        assertTrue(f.results.isEmpty())
        f.engine.listener.result("New recording")
        assertEquals(listOf("alarm:one" to "New recording"), f.results)
    }

    @Test fun changedChallengeRejectsSpeechWithoutGivingAnswerCredit() {
        val f = Fixture()
        f.start()
        f.target = "alarm:two"
        f.engine.listener.result("Words from the previous check")
        assertTrue(f.results.isEmpty())
        assertFalse(f.dictation.state.active)
        assertEquals(1, f.engine.destroys)
    }

    @Test fun providerFailureDoesNotSpendQuietTime() {
        var quietCalls = 0
        val dictation = VerseDictation(factory = { error("Speech service unavailable") },
            isCurrent = { true }, onState = {}, onResult = { _, _ -> fail() })
        dictation.start("alarm:one") { quietCalls++ }
        assertEquals(0, quietCalls)
        assertFalse(dictation.state.active)
        assertEquals("Speech service unavailable", dictation.state.message)
    }

    @Test fun errorReleasesTheProviderAndAllowsRetry() {
        val f = Fixture()
        f.start()
        f.engine.listener.error("No microphone access")
        assertEquals("No microphone access", f.dictation.state.message)
        assertFalse(f.dictation.state.active)
        assertEquals(1, f.engine.destroys)
        f.start()
        assertTrue(f.dictation.state.active)
        assertEquals(2, f.engines.size)
    }

    @Test fun blankAndOversizedResultsNeverReplaceAnAnswer() {
        val f = Fixture()
        f.start()
        f.engine.listener.result("  ")
        f.start()
        f.engine.listener.result("a".repeat(12_001))
        assertTrue(f.results.isEmpty())
        assertFalse(f.dictation.state.active)
    }
}
