package com.hopon.car.voice

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hopon.car.MainActivity
import org.json.JSONObject
import java.io.File

/** Synthetic recognition in an isolated app package: no microphone, audio, or parking mutation. */
@Suppress("DEPRECATION")
class VoiceUiProbe : InstrumentationTestCase() {
    fun testMemoDraftRequiresConfirmationAndSurvivesTtsFailure() { checkDialog() }
    private fun checkDialog() {
        val context=instrumentation.targetContext
        check(context.packageName=="com.hopon.car.voiceprobe") { "Use the isolated voiceprobe application ID" }
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var dialog:VoiceDialog?=null
        var confirmed:String?=null
        try {
            instrumentation.runOnMainSync {
                dialog=VoiceDialog(activity,true,{
                    VoiceCommands.Context(JSONObject().put("room",JSONObject().put("status","arrived")),true,false,System.currentTimeMillis())
                },{confirmed=it},inputFactory={object:VoiceInput {
                    lateinit var events:VoiceInput.Events
                    override fun initialize(events:VoiceInput.Events){this.events=events;events.ready()}
                    override fun listen(){events.result("지하 3층 기둥 12",true)}
                    override fun close()=Unit
                }},outputFactory={object:VoiceOutput {
                    override fun initialize(events:VoiceOutput.Events){events.failed("ON_DEVICE_ENGINE_ERROR")}
                    override fun speak(text:String)=Unit
                    override fun close()=Unit
                }},audio=SilentVoiceAudio)
                dialog!!.show()
                assertNull(confirmed)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val field=VoiceDialog::class.java.getDeclaredField("dialog").apply{isAccessible=true}
                val window=(field.get(dialog) as AlertDialog).window!!
                fun labels(view:View):List<TextView> = when(view) {
                    is TextView -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap{labels(view.getChildAt(it))}
                    else -> emptyList()
                }
                val decor=window.decorView
                val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(context.filesDir,"voice-dialog.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
                val views=labels(decor)
                assertTrue(views.any{it.text.contains("지하 3층 기둥 12")})
                assertTrue(views.any{it.text.contains("화면의 답변")})
                views.single{it.text.toString()=="메모 확인"}.performClick()
                assertEquals("지하 3층 기둥 12",confirmed)
                // Capture the same completed status answer used in the readability review.
                dialog=VoiceDialog(activity,false,{
                    VoiceCommands.Context(JSONObject().put("room",JSONObject().put("status","arrived")
                        .put("title","음성 주차 메모 테스트")),true,false,System.currentTimeMillis())
                },{},inputFactory={object:VoiceInput {
                    lateinit var events:VoiceInput.Events
                    override fun initialize(events:VoiceInput.Events){this.events=events;events.ready()}
                    override fun listen(){events.result("여정 상태 알려줘",true)}
                    override fun close()=Unit
                }},outputFactory={object:VoiceOutput {
                    lateinit var events:VoiceOutput.Events
                    override fun initialize(events:VoiceOutput.Events){this.events=events;events.ready()}
                    override fun speak(text:String){events.done()}
                    override fun close()=Unit
                }},audio=SilentVoiceAudio)
                dialog!!.show()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val field=VoiceDialog::class.java.getDeclaredField("dialog").apply{isAccessible=true}
                val decor=(field.get(dialog) as AlertDialog).window!!.decorView
                val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(context.filesDir,"voice-answer.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        } finally {instrumentation.runOnMainSync{dialog?.dismiss();activity.finish()}}
    }
}
