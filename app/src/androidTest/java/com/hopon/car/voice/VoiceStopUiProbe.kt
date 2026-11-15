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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Synthetic speech/candidates; no mic, audio or backend writes. */
@Suppress("DEPRECATION")
class VoiceStopUiProbe:InstrumentationTestCase(){
    fun testSpeechAndCandidateSelectionRequireSeparateTaps(){
        val context=instrumentation.targetContext;assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var voice:VoiceDialog?=null;var stops:VoiceStopDialog?=null;var selected=0;var searched=0;var valid=true
        val data=JSONObject().put("source","sample-fallback").put("revision",0).put("searchedAt",Instant.now().toString())
            .put("places",JSONArray().put(JSONObject().put("name","성수 온실 카페").put("address","시연용 샘플 장소").put("lat",37.5474).put("lng",127.0437)))
        fun createStops()=VoiceStopDialog(activity,"카페",{valid},{callback->searched++;callback(Result.success(data))},{_,_,_->selected++})
        try{
            instrumentation.runOnMainSync{
                voice=VoiceDialog(activity,false,{
                    VoiceCommands.Context(JSONObject().put("room",JSONObject().put("status","driving")),true,false,System.currentTimeMillis())
                },{},inputFactory={object:VoiceInput{
                    lateinit var events:VoiceInput.Events
                    override fun initialize(events:VoiceInput.Events){this.events=events;events.ready()}
                    override fun listen(){events.result("가는 길에 카페 들르자",true)}
                    override fun close()=Unit
                }},outputFactory={object:VoiceOutput{
                    lateinit var events:VoiceOutput.Events
                    override fun initialize(events:VoiceOutput.Events){this.events=events;events.ready()}
                    override fun speak(text:String){events.done()}
                    override fun close()=Unit
                }},audio=SilentVoiceAudio,stopReady={stops=createStops().also{it.show()}})
                voice!!.show();assertEquals(0,searched);assertEquals(0,selected)
                labels(decor(voice!!)).single{it.text.toString()=="장소 후보 보기"}.performClick()
                assertEquals(1,searched);assertEquals(0,selected)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{
                val view=decor(stops!!)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(context.filesDir,"voice-stop-candidates.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                labels(view).single{it.text.toString()=="이 장소 제안"}.performClick();assertEquals(1,selected)
                stops=createStops().also{it.show()};labels(decor(stops!!)).single{it.text.toString()=="취소"}.performClick();assertEquals(1,selected)
                stops=createStops().also{it.show()};valid=false
                labels(decor(stops!!)).single{it.text.toString()=="이 장소 제안"}.performClick();assertEquals(1,selected)
            }
        }finally{instrumentation.runOnMainSync{voice?.dismiss();stops?.dismiss();activity.finish()}}
    }
    fun testCandidateDismissalClearsOnlyTheMatchingActivityDialog()=checkDismissalOwnership()
    private fun checkDismissalOwnership(){
        val context=instrumentation.targetContext;assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val field=MainActivity::class.java.getDeclaredField("voiceStopDialog").apply{isAccessible=true}
        var first:VoiceStopDialog?=null;var second:VoiceStopDialog?=null
        val dismissed=MainActivity::class.java.getDeclaredMethod("onVoiceStopsDismissed",VoiceStopDialog::class.java).apply{isAccessible=true}
        val data=JSONObject().put("source","sample-fallback").put("searchedAt",Instant.now().toString()).put("places",JSONArray())
        fun create()=VoiceStopDialog(activity,"카페",{true},{it(Result.success(data))},{_,_,_->},{closed->dismissed.invoke(activity,closed)})
        try {
            instrumentation.runOnMainSync {
                val repository=MainActivity::class.java.getDeclaredField("repository").apply{isAccessible=true}.get(activity) as com.hopon.car.data.JourneyRepository
                repository.stop()
                first=create();field.set(activity,first);first!!.show();first!!.dismiss()
                second=create();field.set(activity,second);second!!.show()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertSame("Late dismissal of the first dialog must preserve the second",second,field.get(activity))
                second!!.dismiss()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {assertNull("Dismissed candidates must no longer pause wake listening",field.get(activity))}
        } finally {instrumentation.runOnMainSync{first?.dismiss();second?.dismiss();activity.finish()}}
    }
    private fun decor(value:Any):View{
        val f=value.javaClass.getDeclaredField("dialog").apply{isAccessible=true}
        return (f.get(value) as AlertDialog).window!!.decorView
    }
    private fun labels(v:View):List<TextView> = when(v){
        is TextView->listOf(v)
        is ViewGroup->(0 until v.childCount).flatMap{labels(v.getChildAt(it))}
        else->emptyList()
    }
}
