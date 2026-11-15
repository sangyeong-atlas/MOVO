package com.hopon.car.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hopon.car.vehicle.SimulatorVehicleAdapter
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** One serial state owner. SSE, polling and writes cannot race or roll state backwards. */
class JourneyRepository(context: Context, private val changed: () -> Unit) {
    private val prefs = context.getSharedPreferences("hopon-v2", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val commandRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val eventWorker = Executors.newSingleThreadScheduledExecutor()
    private val adapter = SimulatorVehicleAdapter(prefs)
    private val nativeStops = com.hopon.car.navigation.NativeStopApplier(context)
    @Volatile var snapshot: JSONObject? = prefs.getString("snapshot", null)?.let { runCatching { JSONObject(it) }.getOrNull() }; private set
    @Volatile var base: String = prefs.getString("base", "http://10.0.2.2:8787")!!; private set
    @Volatile var tripId: String = prefs.getString("trip", "")!!; private set
    private var token = prefs.getString("token", "")!!
    private var vehicleId = prefs.getString("vehicle", "")!!
    @Volatile var online = false; private set
    @Volatile var busy = false; private set
    @Volatile var authRequired = false; private set
    @Volatile var error = ""; private set
    @Volatile var lastSync = prefs.getLong("last-sync", 0); private set
    @Volatile var sse = false; private set
    @Volatile var active = false
    @Volatile private var generation = 0
    @Volatile private var api = VehicleApi(base, token)
    private var capabilityPublished = false
    private var lastEventAt = 0L
    private var lastPoll = 0L
    private var sequence = prefs.getLong("sequence", 0)
    private var tickFuture: java.util.concurrent.ScheduledFuture<*>? = null
    val voiceStopPending: Boolean get() = prefs.contains("voice-stop-${sessionKey()}")
    val parkingPending: Boolean get() = prefs.contains("parking-$tripId")
    val queuedParking: JSONObject? get() = prefs.getString("parking-$tripId", null)?.let { runCatching { JSONObject(it).getJSONObject("body") }.getOrNull() }
    val closeUncertain: Boolean get() = prefs.getBoolean("closing-$tripId", false) && authRequired
    @Volatile var partyMode: String = prefs.getString("party-mode", "together")!!; private set
    val canSwitchParty: Boolean get() = !busy && !parkingPending && phase() in listOf("planning", "pickup", "closed") &&
        (snapshot?.optJSONArray("requests")?.let { a -> (0 until a.length()).none { a.getJSONObject(it).optString("status") == "approved" } } ?: true)
    private fun sessionKey() = "$base|$tripId"
    private fun savedSessions() = JSONObject(prefs.getString("party-sessions", "{}")!!)
    private fun rememberParty() {
        if (tripId.isBlank() || snapshot == null) return
        val all = savedSessions()
        all.put(sessionKey(), JSONObject().put("base",base).put("trip",tripId).put("token",token)
            .put("vehicle",vehicleId).put("mode",partyMode).put("snapshot",snapshot?.let { com.hopon.car.map.RoadGeometry.forStorage(it) }))
        check(prefs.edit().putString("party-sessions",all.toString()).commit())
    }
    /** Only display data leaves the session store; credentials never enter view models. */
    fun parties(): List<JSONObject> {
        val all = savedSessions()
        val currentSnapshot=snapshot
        val currentKey=sessionKey()
        if (tripId.isNotBlank() && currentSnapshot != null && currentSnapshot.optJSONObject("room")?.optString("roomId")==tripId)
            all.put(currentKey, JSONObject().put("mode",partyMode).put("snapshot",currentSnapshot))
        return all.keys().asSequence().mapNotNull { key ->
            val entry=all.getJSONObject(key)
            val saved=entry.optJSONObject("snapshot") ?: return@mapNotNull null
            JSONObject().put("key",key).put("mode",entry.optString("mode","together"))
                .put("snapshot",saved).put("current",key==currentKey)
        }.toList().sortedWith(compareBy<JSONObject> { it.getJSONObject("snapshot").getJSONObject("room").optString("status")=="closed" }.thenBy { it.getJSONObject("snapshot").getJSONObject("room").optString("title") })
    }
    fun selectParty(key: String) = command {
        require(!parkingPending && phase() in listOf("planning","pickup","closed")) { "진행 중인 여정을 마친 뒤 파티방을 선택해 주세요." }
        require(snapshot?.optJSONArray("requests")?.let { a -> (0 until a.length()).none { a.getJSONObject(it).optString("status")=="approved" } } != false) { "요청 처리 완료 후 파티방을 선택해 주세요." }
        if (key == sessionKey()) { retryCurrentParty(); return@command }
        val entry=savedSessions().getJSONObject(key)
        val target=VehicleApi(entry.getString("base"),entry.getString("token"))
        // Validate the target before changing the active session. A revoked target cannot break the current one.
        val incoming=try { target.request("GET","/v2/trips/${entry.getString("trip")}/snapshot") }
        catch (e: ApiException) { throw IllegalArgumentException("이 파티방을 다시 연결해 주세요. 모바일에서 새 코드를 발급받을 수 있어요.") }
        require(incoming.getJSONObject("room").optString("status") != "closed") { "완료된 여정은 새 연결 코드로 다시 시작해 주세요." }
        rememberParty(); generation++; api.cancelEvents()
        base=entry.getString("base");tripId=entry.getString("trip");token=entry.getString("token");vehicleId=entry.getString("vehicle")
        partyMode=entry.optString("mode","together");snapshot=null;online=false;authRequired=false;error="";sse=false
        capabilityPublished=false;lastEventAt=0;lastPoll=0;lastSync=0;api=target
        check(prefs.edit().putString("base",base).putString("trip",tripId).putString("token",token).putString("vehicle",vehicleId)
            .putString("party-mode",partyMode).putString("snapshot",com.hopon.car.map.RoadGeometry.forStorage(incoming).toString()).putLong("last-sync",0).commit())
        accept(incoming);publishCapabilities();startEvents()
    }
    private fun retryCurrentParty() {
        if (phase() != "closed") accept(api.request("GET",path("snapshot")))
    }
    fun phase() = snapshot?.optJSONObject("room")?.optString("status") ?: "planning"
    private fun path(tail: String) = "/v2/trips/$tripId/$tail"
    private fun publish() { main.post { changed() } }

    fun start() {
        if (active) return
        active = true
        tickFuture = worker.scheduleWithFixedDelay({ if (active) tick() }, 0, 2500, TimeUnit.MILLISECONDS)
        startEvents()
    }
    fun stop() {
        active = false; generation++; tickFuture?.cancel(false); api.cancelEvents(); sse = false
    }
    fun destroy() { stop(); worker.shutdownNow(); eventWorker.shutdownNow() }

    private fun startEvents() {
        if (!active || tripId.isBlank() || authRequired || phase() == "closed") return
        val gen = ++generation
        val client = api
        val route = path("events")
        fun connect(delay: Long) {
            if (eventWorker.isShutdown) return
            eventWorker.schedule({
                if (!active || gen != generation || authRequired) return@schedule
                try {
                    client.events(route, { active && gen == generation && !authRequired }) { incoming ->
                        if (!worker.isShutdown) worker.execute {
                            if (active && gen == generation && !authRequired) {
                                sse = true; lastEventAt = System.currentTimeMillis(); accept(incoming)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!worker.isShutdown) worker.execute {
                        if (gen == generation && active) {
                            sse = false
                            if (e is ApiException && e.status in listOf(401, 403)) fail(e)
                            else publish() // polling determines availability while SSE reconnects
                        }
                    }
                }
                if (active && gen == generation && !authRequired && phase() != "closed") connect(3000)
            }, delay, TimeUnit.MILLISECONDS)
        }
        connect(0)
    }

    private fun accept(data: JSONObject) {
        if (!SnapshotOrder.accepts(snapshot, data, tripId)) return
        snapshot = data
        online = true; authRequired = false; error = ""; lastSync = System.currentTimeMillis()
        prefs.edit().putString("snapshot", com.hopon.car.map.RoadGeometry.forStorage(data).toString()).putLong("last-sync", lastSync).apply()
        if (phase() == "closed") { generation++; api.cancelEvents(); sse = false }
        rememberParty()
        publish()
    }

    private fun fail(e: Exception) {
        online = false; sse = false
        authRequired = e is ApiException && e.status in listOf(401, 403)
        error = e.message ?: "Connection failed"
        if (authRequired) { generation++; api.cancelEvents() }
        publish()
    }

    fun pair(address: String, code: String, mode: String = "together") = command {
        require(mode in listOf("solo","together"))
        require(phase() in listOf("planning","pickup","closed") || authRequired) { "진행 중인 여정을 마친 뒤 파티방을 연결해 주세요." }
        require(snapshot?.optJSONArray("requests")?.let { a -> (0 until a.length()).none { a.getJSONObject(it).optString("status")=="approved" } } != false || authRequired) { "요청 처리 완료 후 연결해 주세요." }
        val url = java.net.URI(address.trim().trimEnd('/'))
        require(url.scheme in listOf("http", "https") && url.host != null && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null) { "Enter an HTTP(S) backend address" }
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code" }
        require(!parkingPending || authRequired) { "Sync queued parking before pairing another journey" }
        val result = try { VehicleApi(url.toString()).request("POST", "/v2/pair/exchange", JSONObject().put("code", code)) }
        catch (e: ApiException) { if (e.status in listOf(400, 401, 403, 409)) throw IllegalArgumentException(e.message); throw e }
        require(!parkingPending || result.getString("tripId") == tripId) { "Queued parking belongs to the previous journey. Pair that journey first." }
        rememberParty()
        generation++; api.cancelEvents()
        partyMode=mode
        base = url.toString(); tripId = result.getString("tripId"); token = result.getString("token"); vehicleId = result.getString("vehicleId")
        snapshot = null; online=false; error=""; lastPoll=0; authRequired = false; capabilityPublished = false; lastEventAt = 0; sse = false
        check(prefs.edit().putString("base", base).putString("trip", tripId).putString("token", token).putString("vehicle", vehicleId).putString("party-mode",partyMode).remove("snapshot").commit())
        api = VehicleApi(base, token)
        accept(api.request("GET", path("snapshot")))
        publishCapabilities()
        startEvents()
    }

    private fun command(task: () -> Unit) {
        if (worker.isShutdown || !commandRunning.compareAndSet(false, true)) return
        busy = true; publish()
        worker.execute {
            try { task() } catch (e: Exception) {
                if (e is IllegalArgumentException || (e is ApiException && e.status !in listOf(401, 403) && e.status < 500)) { error = e.message ?: "Request rejected"; publish() }
                else fail(e)
            } finally { busy = false; commandRunning.set(false); publish() }
        }
    }
    fun retry() = command {
        if (!authRequired && tripId.isNotEmpty() && phase() != "closed") {
            accept(api.request("GET", path("snapshot"))); flushParking(); flushVoiceStop(); reconcileRequests()
        }
    }

    private fun tick() {
        if (tripId.isEmpty() || authRequired || phase() == "closed") return
        try {
            val now = System.currentTimeMillis()
            if (!sse || now - lastEventAt > 6000 || now - lastPoll > 15000) {
                accept(api.request("GET", path("snapshot"))); lastPoll = now
            }
            publishCapabilities()
            flushParking()
            flushVoiceStop()
            reconcileRequests()
            if (phase() in listOf("pickup", "driving", "arrived") && snapshot?.optBoolean("consent", true) != false) sendTelemetry()
        } catch (e: Exception) { fail(e) }
    }
    private fun publishCapabilities() {
        if (capabilityPublished || phase() == "closed") return
        api.request("PUT", path("vehicle-state"), JSONObject().put("capabilities", adapter.capabilities()).put("seats", JSONObject()).put("source", "simulator"))
        capabilityPublished = true
    }

    fun board(memberId: String) = command {
        api.request("PATCH", path("members/$memberId"), JSONObject().put("status", "boarded"))
        accept(api.request("GET", path("snapshot")))
    }
    fun advance() = command {
        val next = mapOf("planning" to "pickup", "pickup" to "driving", "driving" to "arrived", "arrived" to "closed")[phase()] ?: return@command
        require(!parkingPending) { "Wait for parking acknowledgement" }
        if (next == "closed") {
            require(snapshot?.optJSONObject("parking") != null) { "Save parking before completing the journey" }
            reconcileRequests()
            check(prefs.edit().putBoolean("closing-$tripId", true).commit())
        }
        val result = api.request("PATCH", path("phase"), JSONObject().put("status", next))
        if (next == "closed") {
            // Original mobile server revokes credentials immediately. The mutation ACK is authoritative.
            val final = result.optJSONObject("finalSnapshot") ?: JSONObject(snapshot.toString()).apply {
                put("room", result); put("telemetry", JSONObject.NULL); put("freshness", "unknown")
                put("eventId", optLong("eventId") + 1); put("serverTime", Instant.now().toString())
            }
            accept(final); prefs.edit().remove("closing-$tripId").apply()
        } else accept(api.request("GET", path("snapshot")))
    }
    /** Read-only search bound to the displayed session; never changes the route or creates a request. */
    fun findVoiceStops(query: String, expectedSession: String, completed: (Result<JSONObject>) -> Unit) {
        if (worker.isShutdown) return
        worker.execute {
            val result = runCatching {
                require(sessionKey() == expectedSession && !authRequired && online && phase() in listOf("pickup", "driving")) { "여정 상태가 바뀌었어요. 다시 검색해 주세요." }
                api.request("GET", path("stop-candidates") + "?query=" + java.net.URLEncoder.encode(query, "UTF-8"))
            }
            main.post { completed(result) }
        }
    }
    /** Selection creates only a pending request. The existing driver approval is a separate action. */
    fun proposeVoiceStop(place: JSONObject, revision: Int, source: String, expectedSession: String) = command {
        require(sessionKey() == expectedSession && !authRequired && online && phase() in listOf("pickup", "driving")) { "여정 상태가 바뀌었어요. 다시 검색해 주세요." }
        require(snapshot?.optInt("revision") == revision) { "경로가 바뀌었어요. 장소를 다시 검색해 주세요." }
        require(!voiceStopPending) { "이전 경유지 요청의 전송을 확인하고 있어요. 잠시 기다려 주세요." }
        val body = JSONObject().put("kind", "stop").put("source", "voice").put("place", JSONObject(place.toString()))
            .put("expectedRevision", revision).put("searchSource", source)
        val queue = JSONObject().put("body", body).put("key", UUID.randomUUID().toString())
        check(prefs.edit().putString("voice-stop-${sessionKey()}", queue.toString()).commit())
        publish(); flushVoiceStop()
        accept(api.request("GET", path("snapshot")))
    }
    private fun flushVoiceStop() {
        val key = "voice-stop-${sessionKey()}"
        val queued = prefs.getString(key, null)?.let { JSONObject(it) } ?: return
        if (phase() !in listOf("pickup", "driving")) {
            prefs.edit().remove(key).commit()
            error = "여정 단계가 바뀌어 경유지 요청을 전송하지 않았어요."; publish(); return
        }
        try {
            api.request("POST", path("requests"), queued.getJSONObject("body"), queued.getString("key"))
            check(prefs.edit().remove(key).commit())
        } catch (e: ApiException) {
            if (e.status in listOf(400, 404, 409, 422)) prefs.edit().remove(key).commit()
            throw e
        }
    }

    fun showNativeNavigation() = command { nativeStops.showNavigation() }

    fun decide(requestId: String, approve: Boolean) = command {
        if(approve) require(phase() in listOf("pickup", "driving")) { "픽업 또는 주행 중에 요청을 승인해 주세요." }
        val body = JSONObject().put("status", if (approve) "approved" else "rejected")
            .put("reason", if (approve) "Driver approved; awaiting adapter confirmation" else "Declined by driver")
        api.request("PATCH", path("requests/$requestId"), body)
        accept(api.request("GET", path("snapshot")))
        if (approve) { reconcileRequests(); accept(api.request("GET", path("snapshot"))) }
    }
    private fun reconcileRequests() {
        val requests = snapshot?.optJSONArray("requests") ?: return
        for (i in 0 until requests.length()) {
            val q = requests.getJSONObject(i)
            if (q.optString("status") != "approved") continue
            val key = "request-$tripId-${q.getString("requestId") }"
            val old = prefs.getString(key, null)?.let { JSONObject(it) }
            val result = if (old != null) {
                if (old.optString("status") == "started") JSONObject().put("status", "failed").put("reason", "Interrupted application; not repeated automatically") else old
            } else {
                check(prefs.edit().putString(key, JSONObject().put("status", "started").toString()).commit())
                try { if(q.optString("kind") == "stop") nativeStops.apply(q, snapshot ?: error("No Journey snapshot")) else adapter.apply(q) } catch (e: Exception) { JSONObject().put("status", "failed").put("reason", e.message ?: "Vehicle adapter failure") }
            }
            check(prefs.edit().putString(key, result.toString()).commit())
            api.request("PATCH", path("requests/${q.getString("requestId")}"), result)
        }
    }

    private fun sendTelemetry() {
        val s = snapshot ?: return
        val previous = prefs.getString("position-$tripId", null)?.let { JSONObject(it) }
            ?: s.optJSONObject("telemetry") ?: s.optJSONObject("origin") ?: return
        var lat = previous.getDouble("lat"); var lng = previous.getDouble("lng")
        val destination = s.getJSONObject("room").getJSONObject("destination")
        var speed = 0.0
        if (phase() == "driving") {
            val next = com.hopon.car.map.RoadGeometry.advance(lat, lng, com.hopon.car.map.RoadGeometry.path(s))
            if (next != null) {
                if (next.first != lat || next.second != lng) speed = 8.0
                lat = next.first; lng = next.second
            }
        }
        if (phase() == "arrived") { lat = destination.getDouble("lat"); lng = destination.getDouble("lng") }
        sequence = maxOf(sequence + 1, System.currentTimeMillis(), s.optJSONObject("telemetry")?.optLong("sequence", 0)?.plus(1) ?: 0)
        val t = JSONObject().put("tripId", tripId).put("vehicleId", vehicleId).put("lat", lat).put("lng", lng)
            .put("headingDeg", JSONObject.NULL).put("speedMps", speed).put("accuracyM", 8)
            .put("observedAt", Instant.now().toString()).put("sequence", sequence).put("source", "simulator")
        check(prefs.edit().putLong("sequence", sequence).putString("position-$tripId", t.toString()).commit())
        api.request("POST", path("telemetry"), t)
    }

    fun park(memo: String) = command {
        require(phase() == "arrived") { "Arrive before saving parking" }
        require(!parkingPending) { "Parking is already queued" }
        val position = prefs.getString("position-$tripId", null)?.let { JSONObject(it) }
            ?: snapshot?.optJSONObject("telemetry") ?: error("No observed simulator position yet")
        val body = JSONObject().put("position", JSONObject().put("lat", position.getDouble("lat")).put("lng", position.getDouble("lng")))
            .put("memo", memo.take(160)).put("source", "manual").put("simulation", true)
            .put("observedAt", position.getString("observedAt")).put("accuracyM", position.opt("accuracyM") ?: JSONObject.NULL)
        val queue = JSONObject().put("key", UUID.randomUUID().toString()).put("body", body)
        check(prefs.edit().putString("parking-$tripId", queue.toString()).commit())
        publish(); flushParking(); accept(api.request("GET", path("snapshot")))
    }
    private fun flushParking() {
        val queue = prefs.getString("parking-$tripId", null)?.let { JSONObject(it) } ?: return
        api.request("PUT", path("parking"), queue.getJSONObject("body"), queue.getString("key"))
        check(prefs.edit().remove("parking-$tripId").commit())
        accept(api.request("GET", path("snapshot")))
    }
}
