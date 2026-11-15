package com.hopon.car

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import com.hopon.car.data.JourneyRepository
import com.hopon.car.data.VehicleApi
import com.hopon.car.ui.BrandText
import org.json.JSONObject

/** Run with adb reverse tcp:8793 tcp:8793 and an isolated backend DB. */
@Suppress("DEPRECATION")
class PartySelectionTest : AndroidTestCase() {
    private lateinit var isolated: Context
    private val base="http://127.0.0.1:8793"
    override fun setUp(){
        super.setUp()
        isolated=object:ContextWrapper(context){
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences = super.getSharedPreferences("party-test-$name",mode)
        }
        isolated.getSharedPreferences("hopon-v2",0).edit().clear().commit()
    }
    private fun host():VehicleApi {
        val token=VehicleApi(base).request("POST","/v2/auth/register",JSONObject().put("name","garage-${System.nanoTime()}").put("password","test-only-pass")).getString("token")
        return VehicleApi(base,token)
    }
    private fun trip(h:VehicleApi,title:String)=h.request("POST","/v2/trips",JSONObject().put("title",title)).getString("roomId")
    private fun code(h:VehicleApi,id:String)=h.request("POST","/v2/trips/$id/pair",JSONObject()).getString("code")
    private fun await(done:()->Boolean){
        val until=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<until){if(done())return;Thread.sleep(100)}
        fail("Timed out waiting for repository")
    }
    fun testSwitchRestartSseAndGuards(){
        val h=host();val solo=trip(h,"월요일 출근");val group=trip(h,"금요일 모임")
        var repo=JourneyRepository(isolated){}
        try {
            repo.pair(base,code(h,solo),"solo");await{!repo.busy&&repo.tripId==solo&&repo.online}
            repo.pair(base,code(h,group),"together");await{!repo.busy&&repo.tripId==group&&repo.online}
            assertEquals(2,repo.parties().size)
            assertFalse(repo.parties().any{it.has("token")||it.has("vehicle")})
            repo.selectParty("$base|$solo");await{!repo.busy&&repo.tripId==solo}
            assertEquals("solo",repo.partyMode)
            repo.destroy();repo=JourneyRepository(isolated){}
            assertEquals(2,repo.parties().size);assertEquals(solo,repo.tripId);assertEquals("solo",repo.partyMode)
            repo.start();await{repo.sse}
            repo.selectParty("$base|$group");await{!repo.busy&&repo.tripId==group&&repo.sse}
            assertEquals(group,repo.snapshot!!.getJSONObject("room").getString("roomId"))
            val prefs=isolated.getSharedPreferences("hopon-v2",0)
            prefs.edit().putString("parking-$group","{}").commit()
            assertFalse(repo.canSwitchParty)
            repo.selectParty("$base|$solo");await{!repo.busy};assertEquals(group,repo.tripId)
            prefs.edit().remove("parking-$group").commit()
            repo.advance();await{!repo.busy&&repo.phase()=="pickup"}
            repo.advance();await{!repo.busy&&repo.phase()=="driving"}
            assertFalse(repo.canSwitchParty)
            repo.selectParty("$base|$solo");await{!repo.busy};assertEquals(group,repo.tripId)
        } finally {repo.destroy()}
    }
    fun testListingDuringRepeatedSwitchesNeverPublishesMissingSnapshot(){
        val h=host();val a=trip(h,"아침");val b=trip(h,"저녁")
        val repo=JourneyRepository(isolated){}
        try {
            repo.pair(base,code(h,a),"solo");await{!repo.busy&&repo.tripId==a}
            repo.pair(base,code(h,b));await{!repo.busy&&repo.tripId==b}
            repeat(20){i->
                val target=if(i%2==0)a else b
                repo.selectParty("$base|$target")
                val end=System.currentTimeMillis()+10000
                while(repo.busy && System.currentTimeMillis()<end){
                    repo.parties().forEach{assertNotNull(it.optJSONObject("snapshot"));assertFalse(it.has("token"))}
                }
                await{!repo.busy};assertEquals(target,repo.tripId)
            }
        } finally {repo.destroy()}
    }
    fun testRevokedTargetLeavesCurrentSessionAndBrandAliases(){
        val h=host();val solo=trip(h,"월요일 출근");val group=trip(h,"금요일 모임")
        val repo=JourneyRepository(isolated){}
        try {
            repo.pair(base,code(h,solo),"solo");await{!repo.busy&&repo.tripId==solo}
            repo.pair(base,code(h,group));await{!repo.busy&&repo.tripId==group}
            VehicleApi(base).request("POST","/v2/pair/exchange",JSONObject().put("code",code(h,solo)))
            repo.selectParty("$base|$solo");await{!repo.busy}
            assertEquals(group,repo.tripId);assertFalse(repo.authRequired);assertTrue(repo.error.contains("다시 연결"))
            repo.retry();await{!repo.busy&&repo.online};assertEquals(group,repo.tripId)
            assertEquals("MOVO 주말 여행",BrandText.display("HopOn Weekend Trip"))
            assertEquals("MOVO 운전자",BrandText.display("HopOnDemo20261110"))
            assertEquals("My MOVO Club",BrandText.display("My HopOn Club"))
        } finally {repo.destroy()}
    }
}
