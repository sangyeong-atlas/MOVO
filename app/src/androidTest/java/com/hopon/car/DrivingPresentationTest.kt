package com.hopon.car

import android.test.AndroidTestCase
import com.hopon.car.ui.DrivingPresentation
import org.json.JSONObject
import java.time.Instant

@Suppress("DEPRECATION")
class DrivingPresentationTest:AndroidTestCase(){
    fun testArrivalUsesServerTimeAndRejectsUnknownEstimates(){
        val data=JSONObject("""{"serverTime":"2026-11-14T23:59:00+09:00","route":{"destinationSeconds":120}}""")
        assertEquals(Instant.parse("2026-11-14T15:01:00Z"),DrivingPresentation.arrival(data,true))
        assertNull(DrivingPresentation.arrival(data,false))
        data.getJSONObject("route").put("destinationSeconds",JSONObject.NULL)
        assertNull(DrivingPresentation.arrival(data,true))
        data.getJSONObject("route").put("destinationSeconds",-1)
        assertNull(DrivingPresentation.arrival(data,true))
        data.getJSONObject("route").put("destinationSeconds",60)
        data.put("serverTime","bad timestamp")
        assertNull(DrivingPresentation.arrival(data,true))
    }
    fun testPendingAndApplyingStaySeparateFromConfirmedCourse(){
        val data=JSONObject("""{"course":[{"name":"Confirmed"}],"requests":[
            {"requestId":"old","status":"applied","kind":"stop"},
            {"requestId":"a","status":"pending","kind":"stop"},
            {"requestId":"b","status":"approved","kind":"climate"},
            {"requestId":"c","status":"failed","kind":"stop"},
            {"requestId":"d","status":"pending","kind":"stop"}
        ]}""")
        val original=data.toString()
        assertEquals(listOf("a","b","d","c"),DrivingPresentation.requests(data).map{it.getString("requestId")})
        assertEquals(1,data.getJSONArray("course").length())
        assertEquals(original,data.toString())
        data.getJSONArray("requests").getJSONObject(0).put("updatedAt","2026-11-14T12:00:00Z")
        data.getJSONArray("requests").getJSONObject(3).put("updatedAt","2026-11-14T11:00:00Z")
        assertEquals("old",DrivingPresentation.requests(data).last().getString("requestId"))
        assertTrue(DrivingPresentation.requests(JSONObject()).isEmpty())
    }
}
