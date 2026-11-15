package com.hopon.car.voice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import com.hopon.car.MainActivity
import com.hopon.car.data.JourneyRepository
import org.json.JSONObject
import java.io.File

/** Injects a recognized final result at the SDK boundary; PCM recognition is tested separately. */
@Suppress("DEPRECATION")
class HeyMovoUiProbe:InstrumentationTestCase(){
    fun testWakeResultOpensExistingVoiceDialog()=exercise()
    private fun exercise(){
        val ctx=instrumentation.targetContext;assertEquals("com.hopon.car.voiceprobe",ctx.packageName)
        val a=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val preferenceField=MainActivity::class.java.getDeclaredField("preferences\$delegate").apply{isAccessible=true}
        val prefs=(preferenceField.get(a) as Lazy<*>).value as android.content.SharedPreferences
        val previous=prefs.getBoolean("hey-movo",false)
        var service:WakeListeningService?=null
        lateinit var wake:WakeWordController
        val dialogField=MainActivity::class.java.getDeclaredField("voiceDialog").apply{isAccessible=true}
        try {
            instrumentation.runOnMainSync {
                val repo=MainActivity::class.java.getDeclaredField("repository").apply{isAccessible=true}.get(a) as JourneyRepository
                repo.stop()
                JourneyRepository::class.java.getDeclaredField("snapshot").apply{isAccessible=true}.set(repo,
                    JSONObject().put("room",JSONObject().put("status","pickup").put("title","호출어 UI 검증")))
                prefs.edit().putBoolean("hey-movo",true).commit()
                MainActivity::class.java.getDeclaredMethod("updateWake").apply{isAccessible=true}.invoke(a)
            }
            await{WakeListeningService.current!=null}
            service=WakeListeningService.current!!
            wake=WakeListeningService::class.java.getDeclaredField("wake").apply{isAccessible=true}.get(service) as WakeWordController
            await{wake.state==WakeState.LISTENING}
            instrumentation.runOnMainSync {
                val detector=WakeWordController::class.java.getDeclaredField("detector").apply{isAccessible=true}.get(wake) as SpeechWakeDetector
                val input=SpeechWakeDetector::class.java.getDeclaredField("input").apply{isAccessible=true}.get(detector) as PleosVoiceInput
                val events=PleosVoiceInput::class.java.getDeclaredField("events").apply{isAccessible=true}.get(input) as VoiceInput.Events
                events.result("헤이 무브",true)
            }
            await{dialogField.get(a)!=null}
            val dialog=dialogField.get(a) as VoiceDialog
            val session=VoiceDialog::class.java.getDeclaredField("session").apply{isAccessible=true}.get(dialog) as VoiceSession
            await{session.state.stage==VoiceStage.LISTENING || session.state.stage==VoiceStage.ERROR}
            assertEquals(VoiceStage.LISTENING,session.state.stage)
            instrumentation.runOnMainSync {
                val window=(VoiceDialog::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(dialog) as android.app.AlertDialog).window!!
                val v=window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
                File(ctx.filesDir,"hey-movo-dialog.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
                prefs.edit().putBoolean("hey-movo",false).commit();dialog.dismiss()
            }
        }finally{instrumentation.runOnMainSync{prefs.edit().putBoolean("hey-movo",previous).commit();service?.shutdown();a.finish()}}
    }
    private fun await(condition:()->Boolean){val end=System.nanoTime()+15_000_000_000L;while(!condition()&&System.nanoTime()<end)Thread.sleep(50);assertTrue("Timed out waiting for wake UI",condition())}
}
