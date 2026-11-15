package com.hopon.car

import android.test.AndroidTestCase
import com.hopon.car.ui.ScreenFlow
import com.hopon.car.ui.BrandText
import org.json.JSONObject

@Suppress("DEPRECATION")
class ScreenFlowTest:AndroidTestCase(){
    fun testCompletionAndDrivingRedirectFromDetails(){
        for(screen in listOf("home","parking","settings","history"))assertEquals("history",ScreenFlow.afterUpdate(screen,"arrived","closed"))
        assertEquals("rooms",ScreenFlow.afterUpdate("rooms","closed","closed"))
        assertEquals("home",ScreenFlow.afterUpdate("prepare","planning","pickup"))
        assertEquals("home",ScreenFlow.afterUpdate("party","pickup","driving"))
        assertEquals("home",ScreenFlow.afterUpdate("requests","pickup","driving"))
        assertEquals("prepare",ScreenFlow.afterUpdate("prepare","closed","planning"))
    }
    fun testBackReturnsToAppropriateRootWithoutChangingPhase(){
        assertEquals("rooms",ScreenFlow.back("prepare","planning",true))
        assertEquals("home",ScreenFlow.back("rooms","pickup",true))
        assertEquals("history",ScreenFlow.back("parking","closed",true))
        assertEquals("rooms",ScreenFlow.back("history","closed",true))
        assertEquals("rooms",ScreenFlow.back("home","closed",true))
        assertNull(ScreenFlow.back("rooms","closed",true))
        assertNull(ScreenFlow.back("rooms","planning",false))
        assertNull(ScreenFlow.back("home","driving",true))
    }
    fun testDisplayBrandingPreservesSourceAndEndpoints(){
        val data=JSONObject().put("title","Hopon 파티").put("member","HOPONDemo123")
        val before=data.toString()
        assertEquals("MOVO 파티",BrandText.display(data.getString("title")))
        assertEquals("MOVO 운전자",BrandText.display(data.getString("member")))
        assertEquals("MOVO 주말 여행",BrandText.display("hopon weekend trip"))
        assertEquals("MOVO http://hopon.local:8793/path",BrandText.display("HopOn http://hopon.local:8793/path"))
        assertEquals(before,data.toString())
    }
}
