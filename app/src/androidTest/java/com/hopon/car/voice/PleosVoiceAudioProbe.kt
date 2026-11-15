package com.hopon.car.voice

import android.os.Bundle
import android.test.InstrumentationTestCase
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit fixture probe: place 16 kHz / 16 bit / mono PCM saying "여정 상태 알려줘" in
 * the isolated package's files/voice-fixture.pcm. This never calls request() or opens a microphone.
 */
@Suppress("DEPRECATION")
class PleosVoiceAudioProbe : InstrumentationTestCase() {
    fun testFixedPcmRecognition() { checkFixture() }
    private fun checkFixture() {
        val pcm=File(instrumentation.targetContext.filesDir,"voice-fixture.pcm").readBytes()
        val initialized=CountDownLatch(1); val completed=CountDownLatch(1)
        val error=AtomicReference(""); val recognized=AtomicReference("")
        lateinit var sdk:SpeechToText
        val listener=object:ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = Unit
            override fun onUpdatedEpdData(on:Long,off:Long) = Unit
            override fun onUpdatedRms(rms:Double) = Unit
            override fun onEndedRecognition() = Unit
            override fun onUpdated(stt:String,done:Boolean) { if(done){recognized.set(stt);completed.countDown()} }
            override fun onError(errorType:ErrorType) {error.set(errorType.name);initialized.countDown();completed.countDown()}
        }
        instrumentation.runOnMainSync {
            sdk=SpeechToText(instrumentation.targetContext,Mode.ON_DEVICE)
            sdk.initialize(object:OnInitializeListener {
                override fun onSuccess() { sdk.addListener(listener); sdk.setLocale(Locale.KOREA); initialized.countDown() }
                override fun onFailure(errorType:ErrorType) { error.set(errorType.name); initialized.countDown() }
            })
        }
        try {
            assertTrue("STT initialization timeout",initialized.await(15,TimeUnit.SECONDS))
            assertEquals("",error.get())
            // Stream synthetic PCM at real-time speed according to the SDK guide.
            pcm.asList().chunked(3200).forEach { chunk ->
                instrumentation.runOnMainSync { sdk.sendAudio(chunk.toByteArray()) }; Thread.sleep(100)
            }
            instrumentation.runOnMainSync { sdk.completeAudioSend() }
            completed.await(20,TimeUnit.SECONDS)
            instrumentation.sendStatus(0,Bundle().apply {putString("movoVoiceFixture",recognized.get());putString("movoVoiceError",error.get())})
            assertEquals("",error.get())
            assertEquals("여정상태알려줘",recognized.get().replace(Regex("[\\s.!?]"),""))
        } finally { instrumentation.runOnMainSync { sdk.removeListener(listener); sdk.stop(); sdk.release() } }
    }
}
