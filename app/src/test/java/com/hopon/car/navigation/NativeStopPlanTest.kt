package com.hopon.car.navigation

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeStopPlanTest {
    private fun place(name:String,lat:Double)=JSONObject().put("name",name).put("lat",lat).put("lng",127.0).put("id","foreign-kakao-id")
    private fun journey()=JSONObject().put("room",JSONObject().put("status","pickup").put("destination",place("End",37.5))).put("course",JSONArray()).put("members",JSONArray())
    private fun empty()=NavigationSnapshot(route=NavigationReading(NavigationRoute(false,"SAFE_GUIDING","RECOMMENDED",true)),destination=NavigationReading(NavigationDestination("",0.0,0.0,0,0)),waypoints=NavigationReading(emptyList()))
    @Test fun keepsCourseAndDestinationWithoutPassingForeignPoiId(){
        val j=journey().put("course",JSONArray().put(place("Existing",37.6)))
        val p=NativeStopPlan.create(j,place("New",37.7),empty())
        assertEquals(listOf("Existing","New"),p.waypoints.map{it.name});assertEquals("End",p.destination.name)
        assertTrue(p.waypoints.all{it.poiId.isEmpty()});assertEquals(1,j.getJSONArray("course").length())
    }
    @Test fun activeRoutePreservesRemainingWaypointsAndDoesNotRevisitPastCourse(){
        val j=journey().put("course",JSONArray().put(place("Already passed",37.8)))
        val n=empty().copy(route=NavigationReading(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true)),destination=NavigationReading(NavigationDestination("End",37.5,127.0,1,1)),waypoints=NavigationReading(listOf(NavigationDestination("Remaining",37.6,127.0,1,1))))
        assertEquals(listOf("Remaining","New"),NativeStopPlan.create(j,place("New",37.7),n).waypoints.map{it.name})
    }
    @Test fun unrelatedNativeDestinationIsPreserved(){
        val n=empty().copy(route=NavigationReading(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true)),destination=NavigationReading(NavigationDestination("Other",36.0,128.0,1,1)))
        assertThrows(IllegalArgumentException::class.java){NativeStopPlan.create(journey(),place("New",37.7),n)}
    }
    @Test fun tooManyStopsFailWithoutTruncation(){
        val j=journey().put("course",JSONArray().put(place("One",37.6)).put(place("Two",37.7)))
        assertThrows(IllegalArgumentException::class.java){NativeStopPlan.create(j,place("Third",37.8),empty())}
        assertEquals(2,j.getJSONArray("course").length())
    }
    @Test fun pickupsFollowFixedOrderAndBoardedMembersAreExcluded(){
        val j=journey().put("pickupOrder",JSONArray().put("b").put("a"))
        j.put("members",JSONArray().put(JSONObject().put("memberId","a").put("status","boarded").put("pickup",place("A",37.6))).put(JSONObject().put("memberId","b").put("displayName","B").put("status","outside").put("pickup",place("B",37.7))))
        assertEquals(listOf("B 픽업","New"),NativeStopPlan.create(j,place("New",37.8),empty()).waypoints.map{it.name})
    }
    @Test fun nearbyDistinctPlacesAreNotDropped(){
        val j=journey().put("course",JSONArray().put(place("One",37.6)))
        assertEquals(2,NativeStopPlan.create(j,place("Two",37.6005),empty()).waypoints.size)
    }
    @Test fun missingCoordinatesUseOnlyUnambiguousSavedCourse(){
        val n=empty().copy(route=NavigationReading(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true)),destination=NavigationReading(NavigationDestination("End",37.5,127.0,1,1)),waypoints=NavigationReading(listOf(NavigationDestination("Saved",0.0,0.0,1,1))))
        assertThrows(IllegalArgumentException::class.java){NativeStopPlan.create(journey(),place("New",37.7),n)}
        val j=journey().put("course",JSONArray().put(place("Saved",37.6)))
        assertEquals(37.6,NativeStopPlan.create(j,place("New",37.7),n).waypoints[0].latitude,0.00001)
    }
    @Test fun missingNativeReadbackAndEndedJourneyCannotWrite(){
        assertThrows(IllegalArgumentException::class.java){NativeStopPlan.create(journey(),place("New",37.7),NavigationSnapshot())}
        val j=journey();j.getJSONObject("room").put("status","arrived")
        assertThrows(IllegalArgumentException::class.java){NativeStopPlan.create(j,place("New",37.7),empty())}
    }
}
