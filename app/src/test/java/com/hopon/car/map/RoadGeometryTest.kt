package com.hopon.car.map

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoadGeometryTest {
    @Test fun denseRoadVerticesAreNotStopMarkers() {
        val snapshot=JSONObject("""{"route":{"source":"naver","status":"ready","polyline":[[37,127],[37,127.001],[37.001,127.001],[37.001,127.002]],"stops":[[37,127],[37.001,127.002]]}}""")
        assertEquals(4,RoadGeometry.path(snapshot).size)
        assertEquals(2,RoadGeometry.stops(snapshot).size)
    }
    @Test fun legacyAndFailedGeometryAreNeverShownAsRoads() {
        assertTrue(RoadGeometry.path(JSONObject("""{"route":{"source":"demo-distance","polyline":[[37,127],[38,128]]}}""")).isEmpty())
        assertTrue(RoadGeometry.path(JSONObject("""{"route":{"source":"naver","status":"pending","polyline":[]}}""")).isEmpty())
    }
    @Test fun simulatorContinuesBeyondFirstDenseVertexAndFollowsCorner() {
        val path=listOf(37.0 to 127.0,37.0 to 127.0001,37.001 to 127.0001)
        val next=RoadGeometry.advance(37.0,127.0,path)!!
        assertTrue(next.first>37.0)
        assertEquals(127.0001,next.second,.0000001)
        val later=RoadGeometry.advance(next.first,next.second,path)!!
        assertTrue(later.first>next.first)
        assertEquals(127.0001,later.second,.0000001)
    }
    @Test fun offlineStorageKeepsJourneyButDropsProviderResult() {
        val snapshot=JSONObject("""{"room":{"status":"driving"},"requests":[{"status":"approved"}],"route":{"source":"naver","status":"ready","polyline":[[37,127],[38,128]],"stops":[[37,127],[38,128]],"destinationSeconds":30,"remainingMeters":100}}""")
        val saved=RoadGeometry.forStorage(snapshot)
        assertEquals("driving",saved.getJSONObject("room").getString("status"))
        assertEquals("approved",saved.getJSONArray("requests").getJSONObject(0).getString("status"))
        assertEquals(0,saved.getJSONObject("route").getJSONArray("polyline").length())
        assertTrue(saved.getJSONObject("route").isNull("destinationSeconds"))
        assertEquals(2,snapshot.getJSONObject("route").getJSONArray("polyline").length())
    }
    @Test fun simulatorWaitsForRouteIfFarAway() {
        assertNull(RoadGeometry.advance(36.0,126.0,listOf(37.0 to 127.0,37.1 to 127.1)))
        assertNull(RoadGeometry.advance(37.0,127.0,emptyList()))
    }
}
