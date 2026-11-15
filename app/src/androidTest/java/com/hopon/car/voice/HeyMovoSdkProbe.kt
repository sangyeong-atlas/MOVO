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

/** Synthetic PCM only. No app session, backend, Journey, microphone or navigation commands. */
@Suppress("DEPRECATION")
class HeyMovoSdkProbe:InstrumentationTestCase(){
    fun testKoreanWakeAndNonWakeSpeech(){
        assertEquals("com.hopon.car.voiceprobe",instrumentation.targetContext.packageName)
        val samples=listOf("wake-ko" to true,"nonwake" to false,"similar" to false)
        val results=mutableListOf<Pair<Boolean,Boolean>>()
        for((name,expected) in samples){
            val ready=CountDownLatch(1);val done=CountDownLatch(1)
            val error=AtomicReference("");val transcript=AtomicReference("")
            lateinit var sdk:SpeechToText
            val listener=object:ResultListener {
                override fun onReady()=Unit
                override fun onStartedRecognition()=Unit
                override fun onUpdatedEpdData(on:Long,off:Long)=Unit
                override fun onUpdatedRms(rms:Double)=Unit
                override fun onEndedRecognition()=Unit
                override fun onUpdated(stt:String,completed:Boolean){if(completed){transcript.set(stt);done.countDown()}}
                override fun onError(errorType:ErrorType){error.set(errorType.name);ready.countDown();done.countDown()}
            }
            instrumentation.runOnMainSync{
                sdk=SpeechToText(instrumentation.targetContext,Mode.ON_DEVICE)
                sdk.initialize(object:OnInitializeListener{
                    override fun onSuccess(){sdk.addListener(listener);sdk.setLocale(Locale.KOREA);ready.countDown()}
                    override fun onFailure(errorType:ErrorType){error.set(errorType.name);ready.countDown()}
                })
            }
            try{
                assertTrue(ready.await(15,TimeUnit.SECONDS));assertEquals("",error.get())
                val pcm=File(instrumentation.targetContext.filesDir,"$name.pcm").readBytes()
                for(offset in pcm.indices step 3200){
                    instrumentation.runOnMainSync{sdk.sendAudio(pcm.copyOfRange(offset,minOf(pcm.size,offset+3200)))}
                    Thread.sleep(100)
                }
                instrumentation.runOnMainSync{sdk.completeAudioSend()}
                assertTrue(done.await(20,TimeUnit.SECONDS));assertEquals("",error.get())
                val matched=WakePhrase.matches(transcript.get())
                instrumentation.sendStatus(0,Bundle().apply{putString("movoWakeFixture","$name: ${transcript.get()} -> $matched")})
                results += expected to matched
            }finally{instrumentation.runOnMainSync{sdk.removeListener(listener);sdk.stop();sdk.release()}}
            Thread.sleep(400)
        }
        assertTrue("Wake/non-wake fixtures differ from expected results",results.all{it.first==it.second})
    }
}
