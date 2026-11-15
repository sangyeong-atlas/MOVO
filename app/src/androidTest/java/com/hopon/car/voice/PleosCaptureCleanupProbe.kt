package com.hopon.car.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit clone-only cleanup: the OEM engine can retain a capture after instrumentation exits.
 * Stop the clone's SDK stream and allow its asynchronous binder close to finish before exiting.
 * Does not request microphone capture or touch the original app, native route, or backend. */
@Suppress("DEPRECATION")
class PleosCaptureCleanupProbe:InstrumentationTestCase(){
    fun testReleaseLeftoverCloneCapture()=cleanup()
    private fun cleanup(){
        val context=instrumentation.targetContext
        assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val initialized=CountDownLatch(1);var failure:String?=null
        lateinit var sdk:SpeechToText
        instrumentation.runOnMainSync {
            sdk=SpeechToText(context,Mode.ON_DEVICE)
            sdk.initialize(object:OnInitializeListener {
                override fun onSuccess(){initialized.countDown()}
                override fun onFailure(errorType:ErrorType){failure=errorType.name;initialized.countDown()}
            })
        }
        try {
            assertTrue(initialized.await(12,TimeUnit.SECONDS));assertNull(failure)
            instrumentation.runOnMainSync{sdk.stop()}
            Thread.sleep(1200)
        } finally {
            instrumentation.runOnMainSync{sdk.release()}
            Thread.sleep(500)
        }
    }
}
