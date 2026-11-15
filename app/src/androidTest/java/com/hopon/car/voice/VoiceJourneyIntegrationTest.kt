package com.hopon.car.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import com.hopon.car.data.JourneyRepository
import com.hopon.car.data.VehicleApi
import org.json.JSONObject

/** Uses only a new test Journey on :8793 and a unique SharedPreferences namespace. */
@Suppress("DEPRECATION")
class VoiceJourneyIntegrationTest : AndroidTestCase() {
    fun testVoiceMemoDraftConfirmationAndRestoration() {
        val suffix=System.nanoTime().toString()
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
                super.getSharedPreferences("voice-$suffix-$name",mode)
        }
        val base="http://10.0.2.2:8793"
        val token=VehicleApi(base).request("POST","/v2/auth/register",JSONObject()
            .put("name","voice-$suffix").put("password","test-only-password")).getString("token")
        val host=VehicleApi(base,token)
        val trip=host.request("POST","/v2/trips",JSONObject().put("title","음성 격리 테스트"))
        val root="/v2/trips/${trip.getString("roomId")}"
        val code=host.request("POST","$root/pair",JSONObject()).getString("code")
        var repo=JourneyRepository(isolated){}
        fun voiceContext()=VoiceCommands.Context(repo.snapshot,repo.online,repo.authRequired,repo.lastSync,
            parkingPending=repo.parkingPending,busy=repo.busy)
        try {
            repo.start(); repo.pair(base,code); await {repo.online&&!repo.busy}
            assertTrue(VoiceCommands.answer("여정 상태 알려줘",voiceContext()).text.contains("출발 준비"))
            assertNull(VoiceCommands.answer("주차 메모 지하 3층",voiceContext()).memo)
            repo.advance(); await {repo.phase()=="pickup"&&!repo.busy}
            repo.advance(); await {repo.phase()=="driving"&&!repo.busy}
            assertTrue(VoiceCommands.answer("여정 상태 알려줘",voiceContext()).text.contains("이동 중"))
            repo.advance(); await {repo.phase()=="arrived"&&!repo.busy}
            await {repo.snapshot?.optJSONObject("telemetry")!=null}
            val draft=VoiceCommands.answer("주차 메모 지하 3층 기둥 12",voiceContext())
            assertEquals("지하 3층 기둥 12",draft.memo)
            assertNull(host.request("GET","$root/snapshot").optJSONObject("parking"))
            // Only the explicit confirmation path uses the existing durable queue/ACK implementation.
            repo.park(draft.memo!!)
            await {!repo.busy&&!repo.parkingPending&&repo.snapshot?.optJSONObject("parking")!=null}
            assertEquals(draft.memo,host.request("GET","$root/snapshot").getJSONObject("parking").getString("memo"))
            repo.destroy(); repo=JourneyRepository(isolated){}
            assertEquals(draft.memo,repo.snapshot!!.getJSONObject("parking").getString("memo"))
        } finally {repo.destroy()}
    }
    private fun await(condition:()->Boolean) {
        val until=System.nanoTime()+20_000_000_000L
        while(!condition()&&System.nanoTime()<until)Thread.sleep(100)
        assertTrue("Timed out waiting for isolated Journey",condition())
    }
}
