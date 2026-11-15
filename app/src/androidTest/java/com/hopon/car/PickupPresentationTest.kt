package com.hopon.car

import android.test.AndroidTestCase
import com.hopon.car.ui.PickupPresentation
import org.json.JSONObject

@Suppress("DEPRECATION")
class PickupPresentationTest:AndroidTestCase(){
    fun testServerOrderAndStableNumbersAfterBoarding(){
        val snapshot=JSONObject("""{"members":[
            {"memberId":"a","seat":1,"status":"preparing","pickup":{"lat":37.5,"lng":127.0}},
            {"memberId":"b","seat":4,"status":"outside","pickup":{"lat":37.6,"lng":127.1}},
            {"memberId":"c","seat":2,"status":"preparing","pickup":{"lat":37.7,"lng":127.2}}
        ],"pickupOrder":["b","a","c"],"route":{"nextMemberId":"b"}}""")
        val original=snapshot.toString()
        assertEquals(listOf("b","a","c"),PickupPresentation.stops(snapshot).map{it.id})
        assertEquals(listOf(1,2,3),PickupPresentation.remaining(snapshot).map{it.number})
        assertEquals(original,snapshot.toString())
        snapshot.getJSONArray("members").getJSONObject(1).put("status","boarded")
        snapshot.getJSONObject("route").put("nextMemberId","a")
        val all=PickupPresentation.stops(snapshot)
        assertEquals(1,all.first().number);assertTrue(all.first().boarded)
        assertEquals(listOf(2,3),PickupPresentation.remaining(snapshot).map{it.number})
        assertEquals("a",all.single{it.next}.id)
    }
    fun testIncompleteOrderAndMissingLocationRemainVisible(){
        val snapshot=JSONObject("""{"members":[
            {"memberId":"a","status":"preparing"},
            {"memberId":"b","status":"outside","pickup":{"lat":37.6,"lng":127.1}},
            {"memberId":"c","status":"preparing","pickup":{"lat":37.7,"lng":127.2}}
        ],"pickupOrder":["c","deleted","c"],"route":{"nextMemberId":"c"}}""")
        val all=PickupPresentation.stops(snapshot)
        assertEquals(listOf("c","a","b"),all.map{it.id})
        assertNull(all[1].number)
        assertEquals(listOf("c","b"),PickupPresentation.remaining(snapshot).map{it.id})
        assertEquals(listOf(1,2),PickupPresentation.remaining(snapshot).map{it.number})
        assertEquals(0,PickupPresentation.stops(JSONObject()).size)
    }
}
