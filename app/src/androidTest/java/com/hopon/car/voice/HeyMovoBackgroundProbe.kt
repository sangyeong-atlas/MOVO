package com.hopon.car.voice

import android.content.ComponentName
import android.content.Intent
import android.test.InstrumentationTestCase
import com.hopon.car.MainActivity
import com.hopon.car.data.JourneyRepository
import org.json.JSONObject

/** Explicit clone-only test. Native navigation is opened without creating or changing a route.
 * Uses real background SDK microphone sessions; injects only the final recognized phrase. */
@Suppress("DEPRECATION")
class HeyMovoBackgroundProbe:InstrumentationTestCase(){
    fun testNativeNavigationWakeAndBackgroundStop()=exercise()
    private fun exercise(){
        val ctx=instrumentation.targetContext
        assertEquals("com.hopon.car.voiceprobe",ctx.packageName)
        assertTrue("Grant clone microphone and overlay permissions first",WakeListeningService.permissionsReady(ctx))
        val prefs=ctx.getSharedPreferences("hopon-ui-v3",0)
        val before=prefs.getBoolean("hey-movo",false)
        val a=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val foreground=MainActivity::class.java.getDeclaredField("foreground").apply{isAccessible=true}
        val dialogField=MainActivity::class.java.getDeclaredField("voiceDialog").apply{isAccessible=true}
        fun native(){ctx.startActivity(Intent().setComponent(ComponentName("ai.umos.maps.android.navigation.app","ai.umos.maps.android.navigation.app.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        fun controller()=WakeListeningService::class.java.getDeclaredField("wake").apply{isAccessible=true}.get(WakeListeningService.current) as WakeWordController
        fun events():VoiceInput.Events {
            val detector=WakeWordController::class.java.getDeclaredField("detector").apply{isAccessible=true}.get(controller()) as SpeechWakeDetector
            val input=SpeechWakeDetector::class.java.getDeclaredField("input").apply{isAccessible=true}.get(detector) as PleosVoiceInput
            return PleosVoiceInput::class.java.getDeclaredField("events").apply{isAccessible=true}.get(input) as VoiceInput.Events
        }
        try {
            instrumentation.runOnMainSync {
                val repo=MainActivity::class.java.getDeclaredField("repository").apply{isAccessible=true}.get(a) as JourneyRepository
                repo.stop()
                JourneyRepository::class.java.getDeclaredField("snapshot").apply{isAccessible=true}.set(repo,
                    JSONObject().put("room",JSONObject().put("status","pickup").put("title","백그라운드 호출어 검증")))
                prefs.edit().putBoolean("hey-movo",true).commit()
                MainActivity::class.java.getDeclaredMethod("updateWake").apply{isAccessible=true}.invoke(a)
            }
            await{WakeListeningService.current?.state==WakeState.LISTENING}
            native();await{!foreground.getBoolean(a)}
            Thread.sleep(24000) // crosses an SDK listening cycle while native navigation owns the screen
            assertEquals(WakeState.LISTENING,WakeListeningService.current?.state)
            assertNull(dialogField.get(a))
            instrumentation.runOnMainSync {events().result("헤이 모바일",true)}
            Thread.sleep(1500)
            assertFalse("Similar phrase must not steal native navigation",foreground.getBoolean(a))
            await{WakeListeningService.current?.state==WakeState.LISTENING}
            instrumentation.runOnMainSync {events().result("헤이 무브",true)}
            await{foreground.getBoolean(a)&&dialogField.get(a)!=null}
            val dialog=dialogField.get(a) as VoiceDialog
            val session=VoiceDialog::class.java.getDeclaredField("session").apply{isAccessible=true}.get(dialog) as VoiceSession
            await{session.state.stage in listOf(VoiceStage.LISTENING,VoiceStage.ERROR)}
            assertEquals("Wake must hand microphone to command STT",VoiceStage.LISTENING,session.state.stage)
            instrumentation.runOnMainSync{dialog.dismiss()}
            await{WakeListeningService.current?.state==WakeState.LISTENING}
            native();await{!foreground.getBoolean(a)}
            lateinit var stale:VoiceInput.Events
            instrumentation.runOnMainSync{stale=events();ctx.startService(Intent(ctx,WakeListeningService::class.java).setAction("com.hopon.car.STOP_WAKE"))}
            await{WakeListeningService.current==null}
            assertFalse(prefs.getBoolean("hey-movo",true))
            instrumentation.runOnMainSync{stale.result("헤이 무브",true)}
            Thread.sleep(1000)
            assertFalse("Stopped wake must not reopen MOVO",foreground.getBoolean(a))
        } finally {
            instrumentation.runOnMainSync{prefs.edit().putBoolean("hey-movo",before).commit();WakeListeningService.current?.shutdown();a.finish()}
        }
    }
    private fun await(condition:()->Boolean){val end=System.nanoTime()+15_000_000_000L;while(!condition()&&System.nanoTime()<end)Thread.sleep(50);assertTrue("Timed out waiting for background wake",condition())}
}
