package com.hopon.car.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import com.hopon.car.data.JourneyRepository
import com.hopon.car.data.VehicleApi
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Only a new synthetic Journey on :8793 and unique preferences. No native navigation commands. */
@Suppress("DEPRECATION")
class VoiceStopIntegrationTest:AndroidTestCase(){
    fun testSearchProposalRestartRejectAndDriverApproval(){
        assertEquals("com.hopon.car.voiceprobe",context.packageName)
        val suffix=System.nanoTime().toString()
        val isolated=object:ContextWrapper(context){
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=
                super.getSharedPreferences("stop-$suffix-$name",mode)
        }
        val base="http://127.0.0.1:8793"
        val token=VehicleApi(base).request("POST","/v2/auth/register",JSONObject()
            .put("name","voice-stop-$suffix").put("password","test-only-password")).getString("token")
        val host=VehicleApi(base,token)
        val trip=host.request("POST","/v2/trips",JSONObject().put("title","음성 경유지 격리 테스트"))
        val id=trip.getString("roomId");val root="/v2/trips/$id";val key="$base|$id"
        val code=host.request("POST","$root/pair",JSONObject()).getString("code")
        var repo=JourneyRepository(isolated){}
        fun snapshot()=host.request("GET","$root/snapshot")
        try{
            repo.start();repo.pair(base,code);await{repo.online&&!repo.busy}
            repo.advance();await{repo.phase()=="pickup"&&!repo.busy&&repo.snapshot?.optJSONObject("telemetry")!=null}
            val answer=VoiceCommands.answer("가는 길에 카페 들르자",VoiceCommands.Context(repo.snapshot,repo.online,false,repo.lastSync))
            assertEquals("카페",answer.stopQuery)
            val done=CountDownLatch(1);val found=AtomicReference<Result<JSONObject>>()
            repo.findVoiceStops(answer.stopQuery!!,key){found.set(it);done.countDown()}
            assertTrue(done.await(15,TimeUnit.SECONDS));val candidates=found.get().getOrThrow()
            val place=candidates.getJSONArray("places").getJSONObject(0)
            assertEquals(0,snapshot().getJSONArray("requests").length())
            assertEquals(0,snapshot().getJSONArray("course").length())
            repo.proposeVoiceStop(place,candidates.getInt("revision"),candidates.getString("source"),key)
            await{!repo.busy&&!repo.voiceStopPending&&(repo.snapshot?.optJSONArray("requests")?.length()?:0)==1}
            assertEquals("pending",snapshot().getJSONArray("requests").getJSONObject(0).getString("status"))
            assertEquals(0,snapshot().getJSONArray("course").length())
            repo.destroy();repo=JourneyRepository(isolated){};repo.start();repo.retry();await{!repo.busy&&repo.online}
            assertEquals(0,snapshot().getJSONArray("course").length())
            val rejected=snapshot().getJSONArray("requests").getJSONObject(0).getString("requestId")
            repo.decide(rejected,false);await{!repo.busy&&repo.snapshot!!.getJSONArray("requests").getJSONObject(0).getString("status")=="rejected"}
            assertEquals(0,snapshot().getJSONArray("course").length())
            repo.proposeVoiceStop(place,candidates.getInt("revision"),candidates.getString("source"),key)
            await{!repo.busy&&(repo.snapshot?.optJSONArray("requests")?.length()?:0)==2}
            val approved=snapshot().getJSONArray("requests").getJSONObject(1).getString("requestId")
            repo.decide(approved,true);await{!repo.busy&&repo.snapshot!!.getJSONArray("course").length()==1}
            repo.retry();await{!repo.busy};assertEquals(1,snapshot().getJSONArray("course").length())
            assertEquals("applied",snapshot().getJSONArray("requests").getJSONObject(1).getString("status"))
            repo.proposeVoiceStop(place,1,candidates.getString("source"),"wrong-session")
            await{!repo.busy};assertEquals(2,snapshot().getJSONArray("requests").length())
        }finally{repo.destroy()}
    }
    private fun await(condition:()->Boolean){
        val until=System.nanoTime()+25_000_000_000L
        while(!condition()&&System.nanoTime()<until)Thread.sleep(100)
        assertTrue("Timed out waiting for isolated voice stop",condition())
    }
}
