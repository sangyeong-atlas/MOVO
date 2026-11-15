package com.hopon.car.voice

import android.os.Bundle
import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit probe only. STT initializes without opening the microphone; TTS speaks one fixed test sentence.
 * Run under an isolated application ID, not against the user's live application/session.
 */
@Suppress("DEPRECATION")
class PleosVoiceProbe : InstrumentationTestCase() {
    fun testSttInitializationWithoutRecording() { checkInput() }
    private fun checkInput() {
        val result=AtomicReference("TIMEOUT"); val done=CountDownLatch(1)
        var client:VoiceInput?=null
        try {
            instrumentation.runOnMainSync {
                runCatching {
                    client=PleosVoiceInput(instrumentation.targetContext)
                    client!!.initialize(object:VoiceInput.Events {
                        override fun ready() { result.set("READY"); done.countDown() }
                        override fun result(text:String,final:Boolean) = Unit
                        override fun ended() = Unit
                        override fun failed(code:String) { result.set(code); done.countDown() }
                    })
                }.onFailure { result.set(it.javaClass.simpleName); done.countDown() }
            }
            done.await(15,TimeUnit.SECONDS)
            instrumentation.sendStatus(0,Bundle().apply{putString("movoVoiceStt",result.get())})
            assertEquals("STT service must initialize; no microphone capture was requested", "READY",result.get())
        } finally { instrumentation.runOnMainSync { client?.close() } }
    }
    fun testTtsPlaybackCompletion() { checkOutput() }
    private fun checkOutput() {
        val result=AtomicReference("TIMEOUT"); val done=CountDownLatch(1)
        var client:VoiceOutput?=null
        try {
            instrumentation.runOnMainSync {
                runCatching {
                    client=PleosVoiceOutput(instrumentation.targetContext)
                    client!!.initialize(object:VoiceOutput.Events {
                        override fun ready() {
                            runCatching { client!!.speak("모보 음성 연결 확인입니다.") }
                                .onFailure { result.set(it.javaClass.simpleName); done.countDown() }
                        }
                        override fun done() { result.set("PLAYBACK_COMPLETED"); done.countDown() }
                        override fun failed(code:String) { result.set(code); done.countDown() }
                    })
                }.onFailure { result.set(it.javaClass.simpleName); done.countDown() }
            }
            done.await(20,TimeUnit.SECONDS)
            instrumentation.sendStatus(0,Bundle().apply{putString("movoVoiceTts",result.get())})
            assertEquals("TTS completion is separate from SDK initialization", "PLAYBACK_COMPLETED",result.get())
        } finally { instrumentation.runOnMainSync { client?.close() } }
    }
}
