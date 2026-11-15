package com.hopon.car.navigation

import android.os.Bundle
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

/** Explicit device probe. Never creates a Journey, calls a mutation API, or launches an Activity. */
@Suppress("DEPRECATION")
class NaviHelperReadOnlyProbe : InstrumentationTestCase() {
    fun testReadOnlyServiceConnectionAndObservations() {
        val adapter = PleosNavigationAdapter(instrumentation.targetContext)
        try {
            adapter.start()
            val until = System.nanoTime() + 25_000_000_000L
            while (adapter.snapshot.connection in listOf(NavigationConnection.IDLE, NavigationConnection.CONNECTING) && System.nanoTime() < until) Thread.sleep(100)
            if (adapter.snapshot.connection == NavigationConnection.CONNECTED) {
                // Observe asynchronous replies without requiring an active destination/TBT.
                repeat(3) { adapter.refresh(); Thread.sleep(4000) }
            }
            val s = adapter.snapshot
            fun value(v: Any?) = v ?: JSONObject.NULL
            fun <T> reading(r: NavigationReading<T>, encode: (T) -> Any) = JSONObject()
                .put("callbackReceived", r.receivedAtElapsedMs != null)
                .put("receivedAtElapsedMs", value(r.receivedAtElapsedMs))
                .put("value", r.value?.let(encode) ?: JSONObject.NULL)
            fun destination(d: NavigationDestination) = JSONObject().put("name", d.name)
                .put("latitude", d.latitude).put("longitude", d.longitude)
                .put("remainingMeters", value(d.remainingMeters)).put("remainingSeconds", value(d.remainingSeconds))
            val report = JSONObject().put("source", s.source).put("connection", s.connection.name)
                .put("error", value(s.lastError)).put("lastQueryAtElapsedMs", value(s.lastQueryAtElapsedMs))
                .put("location", reading(s.location) { JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("headingDegrees", value(it.headingDegrees)) })
                .put("route", reading(s.route) { JSONObject().put("routing", it.routing).put("mode", it.mode).put("option", it.option).put("termsAgreed", it.termsAgreed) })
                .put("destination", reading(s.destination, ::destination))
                .put("waypoints", reading(s.waypoints) { JSONArray(it.map(::destination)) })
                .put("tbt", reading(s.turns) { JSONArray(it.map { t -> JSONObject().put("type", t.type).put("distanceMeters", value(t.distanceMeters)).put("description", t.description) }) })
            instrumentation.sendStatus(0, Bundle().apply { putString("movoNavigation", report.toString()) })
            assertEquals("SDK connection failed; see movoNavigation diagnostic", NavigationConnection.CONNECTED, s.connection)
            assertNotNull(s.lastQueryAtElapsedMs)
        } finally {
            instrumentation.runOnMainSync { adapter.close() }
            assertEquals(NavigationConnection.RELEASED, adapter.snapshot.connection)
        }
    }
}
