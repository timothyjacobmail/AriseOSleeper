package com.example.ariseosleeper

internal interface DictationEngine {
    interface Listener {
        fun ready()
        fun processing()
        fun partial(text: String)
        fun result(text: String)
        fun error(message: String)
    }
    fun start(listener: Listener)
    fun stop()
    fun cancel()
    fun destroy()
}

internal enum class DictationPhase { IDLE, STARTING, LISTENING, PROCESSING, FINISHED }
internal data class DictationState(val target: String? = null, val phase: DictationPhase = DictationPhase.IDLE,
    val message: String = "", val partial: String = "") {
    val active get() = phase in listOf(DictationPhase.STARTING, DictationPhase.LISTENING, DictationPhase.PROCESSING)
}

/** Owns one request at a time. Cancelled or previous requests can never replace an answer. */
internal class VerseDictation(private val factory: () -> DictationEngine,
    private val isCurrent: (String) -> Boolean,
    private val onState: (DictationState) -> Unit,
    private val onResult: (String, String) -> Unit) {
    var state = DictationState()
        private set
    private var generation = 0
    private var engine: DictationEngine? = null

    fun start(target: String, beforeListening: () -> Unit = {}) {
        if (state.active || !isCurrent(target)) return
        val token = ++generation
        try {
            engine = factory() // Check availability before spending an alarm's quiet period.
            update(DictationState(target, DictationPhase.STARTING, "Starting microphone…"))
            beforeListening()
            if (token != generation) return
            engine?.start(object : DictationEngine.Listener {
                private fun valid(): Boolean {
                    if (token != generation || !state.active) return false
                    if (!isCurrent(target)) { cancel("The verse check changed. Tap Dictate again."); return false }
                    return true
                }
                override fun ready() {
                    if (valid() && state.phase != DictationPhase.PROCESSING)
                        update(state.copy(phase = DictationPhase.LISTENING, message = "Listening… Recite the passage."))
                }
                override fun processing() {
                    if (valid()) update(state.copy(phase = DictationPhase.PROCESSING, message = "Finishing dictation…"))
                }
                override fun partial(text: String) {
                    if (valid()) update(state.copy(partial = text.take(12_000)))
                }
                override fun result(text: String) {
                    if (!valid()) return
                    if (text.isBlank()) { error("No speech was recognized. Tap Dictate to try again."); return }
                    if (text.length > 12_000) { error("That dictation is too long. Try a shorter recording."); return }
                    release()
                    update(DictationState(target, DictationPhase.FINISHED, "Dictation ready. Review the words, then check your verse."))
                    onResult(target, text)
                }
                override fun error(message: String) {
                    if (valid()) cancel(message)
                }
            })
        } catch (e: RuntimeException) {
            release()
            update(DictationState(target, DictationPhase.FINISHED,
                if (e is SecurityException) "Microphone access is unavailable. Allow Microphone in Android app permissions."
                else e.message ?: "Could not start dictation. You can still type the passage."))
        }
    }

    fun stop() {
        if (!state.active || state.phase == DictationPhase.PROCESSING) return
        update(state.copy(phase = DictationPhase.PROCESSING, message = "Finishing dictation…"))
        try { engine?.stop() }
        catch (_: RuntimeException) { cancel("Could not finish dictation. Your typed answer is unchanged.") }
    }

    fun cancel(message: String = "Dictation cancelled. Your typed answer is unchanged.") {
        if (!state.active) return
        val target = state.target
        release()
        update(DictationState(target, DictationPhase.FINISHED, message))
    }

    private fun release() {
        generation++ // Invalidate callbacks before cancelling the provider.
        val previous = engine
        engine = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }
    private fun update(next: DictationState) { state = next; onState(next) }
}
