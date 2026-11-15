package com.hopon.car.voice

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import com.hopon.car.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit manual microphone probe on the isolated app. No transcript logging or storage. */
@Suppress("DEPRECATION")
class HeyMovoIdleProbe:InstrumentationTestCase(){
    fun testForegroundWaitAndMicrophoneRelease(){
        val ctx=instrumentation.targetContext;assertEquals("com.hopon.car.voiceprobe",ctx.packageName)
        val activity=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val handler=Handler(Looper.getMainLooper());val ready=CountDownLatch(1);val released=CountDownLatch(1)
        val starts=AtomicInteger();val errors=AtomicInteger();val hits=AtomicInteger()
        lateinit var detector:SpeechWakeDetector
        instrumentation.runOnMainSync {
            detector=SpeechWakeDetector({PleosVoiceInput(ctx)},VoiceTimer{delay,action->val task=Runnable{action()};handler.postDelayed(task,delay);val cancel:()->Unit={handler.removeCallbacks(task)};cancel})
            detector.start({starts.incrementAndGet();ready.countDown()},{hits.incrementAndGet()},{errors.incrementAndGet();ready.countDown()})
        }
        try {
            assertTrue(ready.await(15,TimeUnit.SECONDS));assertEquals(0,errors.get())
            Thread.sleep(24000)
            assertEquals("Wake wait failed",0,errors.get())
            assertTrue("Expected listening session",starts.get()>=1)
            instrumentation.runOnMainSync{detector.stop{released.countDown()}}
            assertTrue("Mic release handoff timeout",released.await(3,TimeUnit.SECONDS))
            val before=starts.get();Thread.sleep(1000);assertEquals(before,starts.get())
        } finally {instrumentation.runOnMainSync{detector.stop{};activity.finish()}}
    }
}
