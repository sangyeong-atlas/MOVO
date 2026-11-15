package com.hopon.car.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import com.hopon.car.data.JourneyRepository
import com.hopon.car.data.VehicleApi
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** New synthetic Journey on :8793, unique preferences, isolated application package only. */
@Suppress("DEPRECATION")
class PassengerAnnouncementIntegrationTest : InstrumentationTestCase() {
    fun testPassengerMessagesReachVehicleAndCompleteTtsOnce() {
        val context=instrumentation.targetContext
        assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val suffix=System.nanoTime().toString()
        val isolated=object:ContextWrapper(context){
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
                super.getSharedPreferences("announcement-$suffix-$name",mode)
        }
        val base="http://127.0.0.1:8793"
        fun account(name:String)=VehicleApi(base,VehicleApi(base).request("POST","/v2/auth/register",
            JSONObject().put("name","$name-$suffix").put("password","test-only-password")).getString("token"))
        val host=account("voice-host");val member=account("voice-member")
        val room=host.request("POST","/v2/trips",JSONObject().put("title","동승자 음성 격리 테스트"))
        val root="/v2/trips/${room.getString("roomId")}"
        val invite=host.request("POST","$root/invites",JSONObject()).getString("inviteToken")
        val mid=member.request("POST","/v2/invites/$invite",JSONObject().put("consent",true)).getString("memberId")
        val code=host.request("POST","$root/pair",JSONObject()).getString("code")
        val spoken=CopyOnWriteArrayList<String>();val completed=CopyOnWriteArrayList<String>();val failures=CopyOnWriteArrayList<String>()
        val seen=mutableMapOf<String,Set<String>>();val handler=Handler(Looper.getMainLooper())
        lateinit var repo:JourneyRepository
        val announcements=PassengerAnnouncements({
            object:VoiceOutput{
                val real=PleosVoiceOutput(context);var current=""
                override fun initialize(events:VoiceOutput.Events)=real.initialize(object:VoiceOutput.Events{
                    override fun ready()=events.ready()
                    override fun done(){completed.add(current);events.done()}
                    override fun failed(code:String){failures.add(code);events.failed(code)}
                })
                override fun speak(text:String){current=text;spoken.add(text);real.speak(text)}
                override fun close()=real.close()
            }
        },VoiceTimer{delay,action->val r=Runnable{action()};handler.postDelayed(r,delay);val cancel:()->Unit={handler.removeCallbacks(r)};cancel},
            {seen[it]},{key,value->seen[key]=value},unavailable={failures.add("unavailable")})
        repo=JourneyRepository(isolated){announcements.update("$base|${repo.tripId}",repo.snapshot,repo.online&&!repo.authRequired)}
        try{
            repo.start();repo.pair(base,code);await{repo.online&&!repo.busy&&repo.sse}
            repo.advance();await{repo.phase()=="pickup"&&!repo.busy}
            val late=JSONObject().put("type","late_5min")
            member.request("POST","$root/messages",late,"late-one")
            await{completed.size==1||failures.isNotEmpty()};assertTrue(failures.toString(),failures.isEmpty())
            assertTrue(spoken.single().endsWith("5분 늦어요."))
            member.request("POST","$root/messages",late,"late-one")
            member.request("PATCH","$root/members/$mid",JSONObject().put("status","outside"))
            await{completed.size==2||failures.isNotEmpty()};assertTrue(failures.toString(),failures.isEmpty())
            assertTrue(spoken.last().endsWith("나왔어요."))
            member.request("PATCH","$root/members/$mid",JSONObject().put("status","outside"))
            repo.retry();await{!repo.busy&&repo.online}
            assertEquals(2,spoken.size)
            assertEquals(2,host.request("GET","$root/snapshot").getJSONArray("messages").length())
        }finally{
            repo.destroy();instrumentation.runOnMainSync{announcements.stop()}
        }
    }
    private fun await(condition:()->Boolean){
        val until=System.nanoTime()+30_000_000_000L
        while(!condition()&&System.nanoTime()<until)Thread.sleep(100)
        assertTrue("Timed out waiting for isolated passenger message/TTS",condition())
    }
}
