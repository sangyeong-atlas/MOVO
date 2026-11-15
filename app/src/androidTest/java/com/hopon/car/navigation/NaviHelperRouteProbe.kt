package com.hopon.car.navigation

import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import org.json.JSONArray
import org.json.JSONObject

/** Manual device probe. No command runs without both an action and explicit approval argument. */
@Suppress("DEPRECATION")
class NaviHelperRouteProbe : InstrumentationTestCase() {
    // Public official SDK example POI; unrelated to the user's MOVO Journey destination.
    private var target = NavigationTarget("MOVO SDK 테스트 · 42dot", 37.48544722, 127.03636666,
        "36khp37hh8gqcm5nqhe5", "서울시 강남구 남부순환로 2621")

    fun testExplicitlyApprovedRouteAction() { runApprovedAction() }
    private fun runApprovedAction() {
        val args = (instrumentation as InstrumentationTestRunner).arguments
        val action = args.getString("movoRouteAction")
        if (action == null) {
            instrumentation.sendStatus(0, Bundle().apply { putString("movoRoute", "SKIPPED: explicit action required") })
            return
        }
        require(action in listOf("observe", "request", "show", "restore", "restore-mobile", "waypoints"))
        if(action=="restore-mobile") target=NavigationTarget("서울숲",37.5445,127.0374,"","서울숲")
        val writes = action != "observe"
        if (writes) require(args.getString("movoRouteApproval") == "destination-and-restore") { "Route approval argument required" }
        var a = PleosNavigationAdapter(instrumentation.targetContext, allowRouteChanges = writes)
        try {
            a.start()
            await(25000) { a.snapshot.connection in listOf(NavigationConnection.CONNECTED, NavigationConnection.FAILED) }
            assertEquals(NavigationConnection.CONNECTED, a.snapshot.connection)
            awaitReadings(a)
            report("before-$action", a.snapshot)
            when (action) {
                "request" -> {
                    assertTrue("Existing navigation must be preserved; expected no route", emptyNavigation(a.snapshot))
                    a.requestDestination(target)
                    val end = System.nanoTime() + 50_000_000_000L
                    var nextRefresh = 0L
                    while (System.nanoTime() < end) {
                        val state = a.snapshot.routeRequest?.stage
                        if (state in listOf(RouteRequestStage.CONFIRMED, RouteRequestStage.FAILED, RouteRequestStage.TIMED_OUT)) break
                        // Allow a following search-error callback to classify service cancellation.
                        if (state == RouteRequestStage.CANCELLED && SystemClock.elapsedRealtime() - (a.snapshot.events.lastOrNull()?.elapsedMs ?: 0) > 1000) break
                        if (System.nanoTime() >= nextRefresh) { a.refresh(); nextRefresh = System.nanoTime() + 2_000_000_000L }
                        Thread.sleep(100)
                    }
                    report("request-result", a.snapshot)
                    assertEquals("Read route diagnostics; do not assume a void SDK return means success", RouteRequestStage.CONFIRMED, a.snapshot.routeRequest?.stage)
                    awaitReadings(a)
                }
                "waypoints" -> {
                    assertTrue("Existing native navigation must remain untouched", emptyNavigation(a.snapshot))
                    val journey=JSONObject().put("room",JSONObject().put("status","driving").put("destination",
                        JSONObject().put("name",target.name).put("lat",target.latitude).put("lng",target.longitude).put("address",target.address)))
                        .put("course",JSONArray()).put("members",JSONArray())
                    val request=JSONObject().put("kind","stop").put("status","approved").put("place",JSONObject()
                        .put("name","판교 현대백화점").put("lat",37.41275833).put("lng",127.09481944).put("address","경기도 성남시 분당구 판교역로146번길 20"))
                    a.close();instrumentation.waitForIdleSync()
                    var bridgeSnapshot=NavigationSnapshot()
                    try {
                        val result=NativeStopApplier { PleosNavigationAdapter(instrumentation.targetContext,allowRouteChanges=true) { if(it.connection!=NavigationConnection.RELEASED)bridgeSnapshot=it } }.apply(request,journey)
                        report("bridge-confirmed",bridgeSnapshot)
                        a=PleosNavigationAdapter(instrumentation.targetContext,allowRouteChanges=true);a.start()
                        await(10000){a.snapshot.connection==NavigationConnection.CONNECTED}
                        instrumentation.sendStatus(0,Bundle().apply{putString("movoNativeStop",result.toString())})
                        assertEquals("applied",result.getString("status"))
                        awaitReadings(a);report("waypoints-confirmed",a.snapshot)
                        assertEquals(1,a.snapshot.waypoints.value?.size)
                        a.showNavigation();Thread.sleep(2500)
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            java.io.File(instrumentation.targetContext.filesDir,"native-waypoint-route.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            bitmap.recycle()
                        }
                    } finally {
                        report("bridge-result",bridgeSnapshot)
                        if(a.snapshot.connection==NavigationConnection.RELEASED){a=PleosNavigationAdapter(instrumentation.targetContext,allowRouteChanges=true);a.start();await(10000){a.snapshot.connection==NavigationConnection.CONNECTED}}
                        awaitReadings(a);report("waypoints-result",a.snapshot)
                        if(!emptyNavigation(a.snapshot)) {
                            assertTrue("Another destination appeared; do not cancel",target.matches(a.snapshot.destination.value))
                            a.cancelDestination(target)
                            val end=System.nanoTime()+15_000_000_000L
                            while(!emptyNavigation(a.snapshot)&&System.nanoTime()<end){a.refresh();Thread.sleep(500)}
                        }
                        report("waypoints-restored",a.snapshot)
                        assertTrue("Native baseline restoration required",emptyNavigation(a.snapshot))
                    }
                }
                "show" -> {
                    assertTrue("Do not display an unrelated destination as test success", target.matches(a.snapshot.destination.value))
                    a.showNavigation(); Thread.sleep(2000); a.refresh(); Thread.sleep(1000)
                }
                "restore", "restore-mobile" -> {
                    if (!emptyNavigation(a.snapshot)) {
                        assertTrue("Another destination is active; stop instead of cancelling it", target.matches(a.snapshot.destination.value))
                        a.cancelDestination(target)
                        val end = System.nanoTime() + 15_000_000_000L
                        while (!emptyNavigation(a.snapshot) && System.nanoTime() < end) { a.refresh(); Thread.sleep(1000) }
                    }
                    assertTrue("Restore to the recorded no-route baseline was not confirmed", emptyNavigation(a.snapshot))
                }
            }
            report("after-$action", a.snapshot)
        } finally { instrumentation.runOnMainSync { a.close() } }
    }
    private fun emptyNavigation(s: NavigationSnapshot): Boolean = s.route.value?.mode == "SAFE_GUIDING" &&
        s.route.value?.routing == false && s.destination.value?.let { it.name.isBlank() && it.latitude == 0.0 && it.longitude == 0.0 } == true &&
        s.waypoints.value?.isEmpty() == true
    private fun awaitReadings(a: PleosNavigationAdapter) {
        val since = SystemClock.elapsedRealtime()
        a.refresh()
        await(10000) {
            val s = a.snapshot
            listOf(s.route.receivedAtElapsedMs, s.destination.receivedAtElapsedMs,
                s.waypoints.receivedAtElapsedMs, s.turns.receivedAtElapsedMs).all { it != null && it >= since }
        }
    }
    private fun await(ms: Long, condition: () -> Boolean) {
        val until = System.nanoTime() + ms * 1_000_000
        while (!condition() && System.nanoTime() < until) Thread.sleep(100)
        assertTrue("Timed out waiting for SDK observation", condition())
    }
    private fun report(phase: String, s: NavigationSnapshot) {
        fun v(value: Any?) = value ?: JSONObject.NULL
        val d = s.destination.value
        val data = JSONObject().put("phase", phase).put("source", s.source).put("connection", s.connection.name)
            .put("error", v(s.lastError)).put("requestStage", v(s.routeRequest?.stage?.name))
            .put("navigationStatus", v(s.navigationStatus)).put("mode", v(s.route.value?.mode))
            .put("routing", v(s.route.value?.routing)).put("activeGuidance", s.activeGuidance)
            .put("destination", if (d == null) JSONObject.NULL else JSONObject().put("name", d.name).put("latitude", d.latitude)
                .put("longitude", d.longitude).put("remainingMeters", v(d.remainingMeters)).put("remainingSeconds", v(d.remainingSeconds)))
            .put("derivedEtaEpochMs", v(s.estimatedArrivalEpochMs)).put("waypointCount", v(s.waypoints.value?.size))
            .put("waypoints", JSONArray(s.waypoints.value?.map { JSONObject().put("name",it.name).put("lat",it.latitude).put("lng",it.longitude).put("address",it.address) } ?: emptyList<JSONObject>()))
            .put("tbt", if (s.turns.value == null) JSONObject.NULL else JSONArray(s.turns.value!!.map {
                JSONObject().put("type", it.type).put("distanceMeters", v(it.distanceMeters)).put("description", it.description) }))
            .put("events", JSONArray(s.events.map { JSONObject().put("name", it.name).put("detail", v(it.detail)).put("elapsedMs", it.elapsedMs) }))
        instrumentation.sendStatus(0, Bundle().apply { putString("movoRoute", data.toString()) })
    }
}
