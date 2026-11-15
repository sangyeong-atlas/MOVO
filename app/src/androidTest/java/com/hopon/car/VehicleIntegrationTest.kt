package com.hopon.car

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.AndroidTestCase
import com.hopon.car.data.JourneyRepository
import com.hopon.car.data.VehicleApi
import com.hopon.car.data.SnapshotOrder
import com.hopon.car.vehicle.SimulatorVehicleAdapter
import org.json.JSONObject

/** Requires isolated v2 server at host :8793; never touches the real app's preferences. */
@Suppress("DEPRECATION")
class VehicleIntegrationTest : AndroidTestCase() {
    private lateinit var isolated: Context
    override fun setUp() {
        super.setUp()
        isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("instrumentation-$name", mode)
        }
        isolated.getSharedPreferences("hopon-v2", Context.MODE_PRIVATE).edit().clear().commit()
    }
    fun testSnapshotOrderingAcrossPollingAndSse() {
        fun snapshot(id: Long, time: String, trip: String = "R-test") = JSONObject()
            .put("room", JSONObject().put("roomId", trip)).put("eventId", id).put("serverTime", time)
        val current = snapshot(10, "2026-11-10T05:00:00Z")
        assertFalse(SnapshotOrder.accepts(current, snapshot(9, "2026-11-10T05:01:00Z"), "R-test"))
        assertFalse(SnapshotOrder.accepts(current, snapshot(10, "2026-11-10T04:59:00Z"), "R-test"))
        assertFalse(SnapshotOrder.accepts(current, snapshot(11, "2026-11-10T05:01:00Z", "R-other"), "R-test"))
        assertTrue(SnapshotOrder.accepts(current, snapshot(10, "2026-11-10T06:00:01+01:00"), "R-test"))
        assertTrue(SnapshotOrder.accepts(current, snapshot(11, "2026-11-10T04:59:00Z"), "R-test"))
    }
    fun testSimulatorApplicationSurvivesRestartWithoutDuplicateEffects() {
        val prefs = isolated.getSharedPreferences("effects", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val request = JSONObject().put("requestId", "duplicate-check").put("kind", "climate")
            .put("seat", 1).put("feature", "temperature").put("value", 22)
        val result = SimulatorVehicleAdapter(prefs).apply(request)
        assertEquals("applied", result.getString("status"))
        request.put("value", 29)
        assertEquals(result.toString(), SimulatorVehicleAdapter(prefs).apply(request).toString())
        assertEquals("22.0", prefs.getString("simulated-climate-1-temperature", null))
        assertFalse(SimulatorVehicleAdapter(prefs).capabilities().getBoolean("belt"))
    }
    fun testUnsupportedControlDoesNotClaimSuccess() {
        val prefs = isolated.getSharedPreferences("effects-invalid", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val request = JSONObject().put("requestId", "invalid").put("kind", "climate")
            .put("seat", 1).put("feature", "temperature").put("value", 40)
        assertTrue(runCatching { SimulatorVehicleAdapter(prefs).apply(request) }.isFailure)
        assertFalse(prefs.contains("effect-invalid"))
    }
    fun testRepositoryPairSseApplicationAndRestart() {
        val base = "http://10.0.2.2:8793"
        val public = VehicleApi(base)
        val suffix = System.nanoTime().toString().takeLast(10)
        val host = public.request("POST", "/v2/auth/register", JSONObject().put("name", "H$suffix").put("password", "demo-pass")).getString("token")
        val member = public.request("POST", "/v2/auth/register", JSONObject().put("name", "M$suffix").put("password", "demo-pass")).getString("token")
        val h = VehicleApi(base, host); val m = VehicleApi(base, member)
        val room = h.request("POST", "/v2/trips", JSONObject().put("title", "MOVO instrumented journey"))
        val root = "/v2/trips/${room.getString("roomId") }"
        val invite = h.request("POST", "$root/invites", JSONObject()).getString("inviteToken")
        val mid = m.request("POST", "/v2/invites/$invite", JSONObject().put("consent", true)).getString("memberId")
        val code = h.request("POST", "$root/pair", JSONObject()).getString("code")
        var repo = JourneyRepository(isolated) {}
        try {
            repo.start(); repo.pair(base, code)
            await { repo.snapshot != null && repo.online && !repo.busy }
            await { repo.sse }
            m.request("PATCH", "$root/members/$mid", JSONObject().put("seat", 3))
            await { repo.snapshot?.getJSONArray("members")?.getJSONObject(0)?.optInt("seat") == 3 }
            val assignedSeats = com.hopon.car.ui.PartySeatsView(context, repo.snapshot!!.getJSONArray("members"), "Driver", true) {}
            assertTrue(assignedSeats.getChildAt(3).contentDescription.contains("M$suffix"))
            assertFalse(assignedSeats.getChildAt(1).contentDescription.contains("M$suffix"))
            repo.advance(); await { repo.phase() == "pickup" && !repo.busy }
            m.request("PATCH", "$root/members/$mid", JSONObject().put("status", "outside"))
            await { repo.snapshot?.getJSONArray("members")?.getJSONObject(0)?.optString("status") == "outside" }
            repo.board(mid); await { repo.snapshot?.getJSONArray("members")?.getJSONObject(0)?.optString("status") == "boarded" && !repo.busy }
            repo.advance(); await { repo.phase() == "driving" && !repo.busy }
            val q = m.request("POST", "$root/requests", JSONObject().put("kind", "stop").put("place", JSONObject().put("name", "Cafe").put("lat", 37.547).put("lng", 127.043)), "instrumented-stop")
            await { repo.snapshot?.getJSONArray("requests")?.length() == 1 }
            repo.decide(q.getString("requestId"), true)
            await { repo.snapshot?.getJSONArray("requests")?.getJSONObject(0)?.optString("status") == "applied" && !repo.busy }
            repo.destroy()
            repo = JourneyRepository(isolated) {}
            assertEquals("driving", repo.phase())
            assertEquals(3, repo.snapshot!!.getJSONArray("members").getJSONObject(0).getInt("seat"))
            repo.start(); await { repo.online }
            assertEquals(1, repo.snapshot!!.getJSONArray("course").length())
            repo.advance(); await { repo.phase() == "arrived" && !repo.busy }
            await { repo.snapshot?.optJSONObject("telemetry") != null }
            repo.park("B2 instrumentation")
            await { !repo.parkingPending && repo.snapshot?.optJSONObject("parking") != null && !repo.busy }
            repo.advance(); await { repo.phase() == "closed" && !repo.busy }
            assertFalse(repo.authRequired)
            assertEquals("closed", m.request("GET", "$root/snapshot").getJSONObject("room").getString("status"))
        } finally { repo.destroy() }
    }
    fun testPartySeatsFollowServerSeatAndDisableDuringDriving() {
        val members = org.json.JSONArray()
            .put(JSONObject().put("memberId", "rear").put("seat", 3).put("displayName", "Long passenger name").put("status", "outside"))
            .put(JSONObject().put("memberId", "front").put("seat", 1).put("displayName", "Mina").put("status", "boarded"))
        var chosen = ""
        val seats = com.hopon.car.ui.PartySeatsView(context, members, "Driver", true) { chosen = it.getString("memberId") }
        seats.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY))
        seats.layout(0, 0, 400, 900)
        assertEquals(5, seats.childCount)
        assertTrue(seats.getChildAt(1).contentDescription.contains("Mina"))
        assertTrue(seats.getChildAt(3).contentDescription.contains("Long passenger name"))
        assertEquals(seats.getChildAt(0).top, seats.getChildAt(1).top)
        assertTrue(seats.getChildAt(1).top < seats.getChildAt(2).top)
        assertEquals(seats.getChildAt(2).top, seats.getChildAt(4).top)
        assertTrue(seats.getChildAt(2).left < seats.getChildAt(3).left)
        assertTrue(seats.getChildAt(3).left < seats.getChildAt(4).left)
        seats.getChildAt(3).performClick()
        assertEquals("rear", chosen)
        assertFalse(seats.getChildAt(2).isClickable)
        val drivingSeats = com.hopon.car.ui.PartySeatsView(context, members, "Driver", false) { fail("Driving must not expand details") }
        assertFalse(drivingSeats.getChildAt(3).isClickable)
        assertFalse(drivingSeats.getChildAt(3).isFocusable)
        drivingSeats.getChildAt(3).performClick()
    }
    private fun await(predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < until) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for repository state")
    }
}
