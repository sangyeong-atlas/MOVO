package com.hopon.car.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import java.util.Locale

/** The service owns microphone capture and audio playback. No audio buffers or credentials are stored. */
class PleosVoiceInput(context: Context) : VoiceInput {
    private val main = Handler(Looper.getMainLooper())
    private val sdk = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
    private var closed = false
    private var events: VoiceInput.Events? = null
    private fun deliver(action: () -> Unit) { main.post { if(!closed) action() } }
    private val listener = object : ai.pleos.playground.stt.listener.ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) = deliver { events?.result(stt, completed) }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = deliver { events?.ended() }
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) = deliver { events?.failed(errorType.name) }
    }
    override fun initialize(events: VoiceInput.Events) {
        this.events = events
        sdk.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() = deliver {
                runCatching { sdk.addListener(listener); sdk.setLocale(Locale.KOREA); events.ready() }
                    .onFailure { events.failed("UNAVAILABLE") }
            }
            override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = deliver { events.failed(errorType.name) }
        })
    }
    override fun listen() { if(!closed) sdk.request() }
    override fun close() {
        if(closed) return
        closed = true; events = null; main.removeCallbacksAndMessages(null)
        runCatching { sdk.removeListener(listener) }
        runCatching { sdk.stop() }
        runCatching { sdk.release() }
    }
}

class PleosVoiceOutput(context: Context) : VoiceOutput {
    private val main = Handler(Looper.getMainLooper())
    private val sdk = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
    private var closed = false
    private var events: VoiceOutput.Events? = null
    private fun deliver(action: () -> Unit) { main.post { if(!closed) action() } }
    private val listener = object : ai.pleos.playground.tts.listener.EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = deliver { events?.done() }
        override fun onStop() = deliver { events?.failed("AUDIO_ERROR") }
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = deliver { events?.failed(errorType.name) }
    }
    override fun initialize(events: VoiceOutput.Events) {
        this.events = events
        sdk.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
            override fun onSuccess() = deliver {
                runCatching {
                    sdk.addEventListener(listener); sdk.setLocale(Locale.KOREA)
                    sdk.setAudioAttributes(voiceAudioAttributes())
                    events.ready()
                }.onFailure { events.failed("UNAVAILABLE") }
            }
            override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) = deliver { events.failed(errorType.name) }
        })
    }
    override fun speak(text: String) { if(!closed) sdk.speak(text, true) }
    override fun close() {
        if(closed) return
        closed = true; events = null; main.removeCallbacksAndMessages(null)
        runCatching { sdk.removeEventListener(listener) }
        runCatching { sdk.stop() }
        runCatching { sdk.release() }
    }
}
