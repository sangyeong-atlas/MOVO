package com.hopon.car.voice

/** Main-thread audio lease. release also cancels any cue and its completion callback. */
interface VoiceAudio {
    fun acquire(listening: Boolean, interrupted: () -> Unit): Boolean
    fun cue(start: Boolean, completed: () -> Unit)
    fun release()
}

/** Default for pure session tests and clients without platform audio. */
object SilentVoiceAudio : VoiceAudio {
    override fun acquire(listening: Boolean, interrupted: () -> Unit) = true
    override fun cue(start: Boolean, completed: () -> Unit) = completed()
    override fun release() = Unit
}

/** Wrap every app TTS path, including passenger announcements, in the same focus policy. */
class FocusedVoiceOutput(private val delegate: VoiceOutput, private val audio: VoiceAudio) : VoiceOutput {
    private var events: VoiceOutput.Events? = null
    private var closed = false
    private var speaking = false
    override fun initialize(events: VoiceOutput.Events) {
        this.events = events
        delegate.initialize(object : VoiceOutput.Events {
            override fun ready() { if (!closed) events.ready() }
            override fun done() {
                if (closed || !speaking) return
                close(); events.done()
            }
            override fun failed(code: String) { fail(code) }
        })
    }
    override fun speak(text: String) {
        if (closed || speaking) return
        val granted = runCatching { audio.acquire(false) { fail("AUDIO_INTERRUPTED") } }.getOrDefault(false)
        if (closed) return
        if (!granted) { fail("AUDIO_BUSY"); return }
        speaking = true
        runCatching { delegate.speak(text) }.onFailure { fail("AUDIO_ERROR") }
    }
    private fun fail(code: String) {
        if (closed) return
        val callback = events; close(); callback?.failed(code)
    }
    override fun close() {
        if (closed) return
        closed = true; events = null
        // Stop the service before handing focus back to media.
        runCatching { delegate.close() }
        audio.release()
    }
}
