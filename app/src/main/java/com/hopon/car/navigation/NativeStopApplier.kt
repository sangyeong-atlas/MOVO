package com.hopon.car.navigation

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

/** Executed on the repository's serial worker. SDK itself dispatches onto the main looper. */
class NativeStopApplier internal constructor(private val factory: () -> PleosNavigationAdapter) {
    constructor(context: Context): this({ PleosNavigationAdapter(context.applicationContext, allowRouteChanges = true) })

    fun showNavigation() {
        val adapter=factory()
        try {
            adapter.start()
            await(12000) { adapter.snapshot.connection in listOf(NavigationConnection.CONNECTED, NavigationConnection.FAILED) }
            check(adapter.snapshot.connection==NavigationConnection.CONNECTED) { "내비게이션에 연결하지 못했어요. 기본 내비 앱을 확인해 주세요." }
            adapter.showNavigation()
            Thread.sleep(300)
        } finally { adapter.close() }
    }
    fun apply(request: JSONObject, journey: JSONObject): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(request.optString("status") == "approved" && request.optString("kind") == "stop")
        val adapter = factory()
        try {
            adapter.start()
            await(12000) { adapter.snapshot.connection in listOf(NavigationConnection.CONNECTED, NavigationConnection.FAILED) }
            check(adapter.snapshot.connection == NavigationConnection.CONNECTED) { "내비게이션에 연결하지 못했어요. 기본 내비 앱을 확인해 주세요." }
            val since = SystemClock.elapsedRealtime()
            adapter.refresh()
            await(10000) {
                val s = adapter.snapshot
                listOf(s.route.receivedAtElapsedMs, s.destination.receivedAtElapsedMs, s.waypoints.receivedAtElapsedMs).all { it != null && it >= since }
            }
            val plan = NativeStopPlan.create(journey, request.getJSONObject("place"), adapter.snapshot)
            adapter.requestRouteWithWaypoints(plan.destination, plan.waypoints)
            var nextRead = SystemClock.elapsedRealtime() + 1000
            await(48000) {
                if(SystemClock.elapsedRealtime() >= nextRead) {
                    adapter.refresh() // Read only: never repeat a route command on missing callbacks.
                    nextRead = SystemClock.elapsedRealtime() + 1000
                }
                adapter.snapshot.routeRequest?.stage in setOf(RouteRequestStage.CONFIRMED, RouteRequestStage.FAILED, RouteRequestStage.CANCELLED, RouteRequestStage.TIMED_OUT)
            }
            val s = adapter.snapshot
            check(s.routeRequest?.stage == RouteRequestStage.CONFIRMED) {
                android.util.Log.w("MovoNativeStop", "Route result: ${s.routeRequest?.stage}, error: ${s.lastError}")
                "내비게이션 경로 적용을 확인하지 못했어요. 기본 내비에서 경로를 확인해 주세요. 자동 재요청하지 않아요."
            }
            return JSONObject().put("status", "applied").put("reason", "Pleos navigation confirmed destination and waypoints")
        } finally { adapter.close() }
    }
    private fun await(ms: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while(!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        check(condition()) { "내비게이션 응답 시간이 초과됐어요. 기본 내비에서 경로를 확인해 주세요. 자동 재요청하지 않아요." }
    }
}
