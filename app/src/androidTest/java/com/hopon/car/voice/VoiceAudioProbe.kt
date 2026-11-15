package com.hopon.car.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit isolated-package probe. No microphone, repository, pairing or backend access. */
@Suppress("DEPRECATION")
class VoiceAudioProbe : InstrumentationTestCase() {
    fun testCuesThenRealTtsWithoutOpeningMicrophone() {
        val context=instrumentation.targetContext
        assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val main=Handler(Looper.getMainLooper());val done=CountDownLatch(1);val error=AtomicReference("")
        var session:VoiceSession?=null;var previous=VoiceStage.IDLE
        var initialized=0L;var listened=0L;var finalized=0L;var speaking=0L
        try {
            instrumentation.runOnMainSync {
                session=VoiceSession({object:VoiceInput{
                    lateinit var events:VoiceInput.Events
                    override fun initialize(events:VoiceInput.Events){this.events=events;initialized=SystemClock.elapsedRealtime();events.ready()}
                    override fun listen(){listened=SystemClock.elapsedRealtime();main.postDelayed({finalized=SystemClock.elapsedRealtime();events.result("테스트",true)},100)}
                    override fun close()=Unit
                }},{vehicleVoiceOutput(context)},VoiceTimer{delay,action->
                    val r=Runnable{action()};main.postDelayed(r,delay);val cancel:()->Unit={main.removeCallbacks(r)};cancel
                },{VoiceCommands.Reply("음성 알림음과 오디오 연결 확인입니다.")},{state->
                    if(state.stage==VoiceStage.SPEAKING)speaking=SystemClock.elapsedRealtime()
                    if(state.stage==VoiceStage.ERROR){error.set(state.error);done.countDown()}
                    if(state.stage==VoiceStage.DONE&&previous==VoiceStage.SPEAKING)done.countDown()
                    previous=state.stage
                },AndroidVoiceAudio(context))
                session!!.listen()
            }
            assertTrue("Audio probe timeout",done.await(30,TimeUnit.SECONDS))
            assertEquals("",error.get())
            assertTrue("Mic must wait for the start cue",listened-initialized>=140)
            assertTrue("TTS must wait for the end cue",speaking-finalized>=140)
        }finally{instrumentation.runOnMainSync{session?.close();main.removeCallbacksAndMessages(null)}}
    }
    fun testCallFocusInterruptsAssistant() {
        val context=instrumentation.targetContext
        assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val manager=context.getSystemService(AudioManager::class.java)
        val lost=CountDownLatch(1);val lease=AndroidVoiceAudio(context)
        val call=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({},Handler(Looper.getMainLooper())).build()
        try{
            var acquired=false;var callResult=AudioManager.AUDIOFOCUS_REQUEST_FAILED
            instrumentation.runOnMainSync{
                acquired=lease.acquire(false){lease.release();lost.countDown()}
                callResult=manager.requestAudioFocus(call)
            }
            assertTrue(acquired)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,callResult)
            assertTrue("Assistant must yield to call focus",lost.await(5,TimeUnit.SECONDS))
        }finally{instrumentation.runOnMainSync{lease.release();manager.abandonAudioFocusRequest(call)}}
    }
}
