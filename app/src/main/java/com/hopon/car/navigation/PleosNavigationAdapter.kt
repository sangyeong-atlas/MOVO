package com.hopon.car.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Opt-in component: no Activity hooks, storage, polling, networking or journey side effects.
 * All SDK access and state delivery are serialized on the main looper. close() is terminal.
 * Connection means initial binding succeeded, not that navigation data is fresh or available.
 */
class PleosNavigationAdapter internal constructor(
    private val factory: () -> NavigationClient,
    private val allowRouteChanges: Boolean = false,
    private val routeTimeoutMs: Long = 45000,
    private val changed: (NavigationSnapshot) -> Unit
) : NavigationAdapter {
    constructor(context: Context, changed: (NavigationSnapshot) -> Unit = {}) :
        this({ PleosReadClient(context.applicationContext) }, changed = changed)
    constructor(context: Context, allowRouteChanges: Boolean, changed: (NavigationSnapshot) -> Unit = {}) :
        this({ PleosReadClient(context.applicationContext) }, allowRouteChanges = allowRouteChanges, changed = changed)

    private val main = Handler(Looper.getMainLooper())
    private var client: NavigationClient? = null
    private var generation = 0
    private var closed = false
    private var requestSequence = 0L
    private var requestTimeout: Runnable? = null
    @Volatile override var snapshot = NavigationSnapshot(); private set

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post { block() }
    }
    private fun publish(next: NavigationSnapshot) {
        snapshot = next
        // A diagnostic observer must not take down the existing vehicle application.
        runCatching { changed(next) }
    }
    private fun dispose() {
        requestTimeout?.let(main::removeCallbacks)
        requestTimeout = null
        val old = client
        client = null
        if (old != null) {
            runCatching { old.removeListener() }
            runCatching { old.release() }
        }
    }
    private fun fail(error: Throwable) {
        generation++
        dispose()
        publish(snapshot.copy(connection = NavigationConnection.FAILED, lastError = error.javaClass.simpleName,
            routeRequest = snapshot.routeRequest?.copy(stage = RouteRequestStage.FAILED)))
    }

    private fun <T> reading(value: T?) = NavigationReading(value, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    private fun record(s: NavigationSnapshot, name: String, detail: String? = null) = s.copy(
        events = (s.events + NavigationEvent(name, SystemClock.elapsedRealtime(), detail)).takeLast(64))
    private fun confirm(s: NavigationSnapshot): NavigationSnapshot {
        val q = s.routeRequest ?: return s
        val completionEvidence = q.completedCallback || (q.expectedWaypoints != null && q.startedCallback && s.route.value?.routing == false)
        if (q.stage !in pendingStages || !completionEvidence || !s.activeGuidance || !q.target.matches(s.destination.value)) return s
        q.expectedWaypoints?.let { expected ->
            val actual = s.waypoints.value ?: return s
            // Some Pleos services omit waypoint coordinates (0/0). In that case require
            // unique names in exact order. Native reverse-geocoded addresses may differ.
            if(actual.any { it.latitude == 0.0 && it.longitude == 0.0 } && expected.map { it.name }.distinct().size != expected.size) return s
            if (actual.size != expected.size || expected.indices.any { !expected[it].matchesWaypoint(actual[it]) }) return s
        }
        requestTimeout?.let(main::removeCallbacks); requestTimeout = null
        return record(s.copy(routeRequest = q.copy(stage = RouteRequestStage.CONFIRMED)), if(q.completedCallback) "destinationConfirmed" else "routeReadbackConfirmed")
    }
    private val pendingStages = setOf(RouteRequestStage.REQUESTED, RouteRequestStage.SEARCHING, RouteRequestStage.AWAITING_CONFIRMATION)

    override fun start() = dispatch {
        if (closed || snapshot.connection in listOf(NavigationConnection.CONNECTING, NavigationConnection.CONNECTED)) return@dispatch
        val gen = ++generation
        publish(NavigationSnapshot(connection = NavigationConnection.CONNECTING))
        if (closed || gen != generation) return@dispatch
        fun receive(update: (NavigationSnapshot) -> NavigationSnapshot) = dispatch {
            if (!closed && gen == generation && snapshot.connection == NavigationConnection.CONNECTED) publish(confirm(update(snapshot)))
        }
        val events = object : NavigationClient.Events {
            override fun location(value: NavigationPosition?) = receive {
                it.copy(location = reading(value))
            }
            override fun route(value: NavigationRoute?) = receive {
                it.copy(route = reading(value))
            }
            override fun destination(value: NavigationDestination?) = receive {
                it.copy(destination = reading(value))
            }
            override fun waypoints(value: List<NavigationDestination>?) = receive {
                it.copy(waypoints = reading(value?.toList()))
            }
            override fun turns(value: List<NavigationTurn>?) = receive {
                it.copy(turns = reading(value?.toList()))
            }
            override fun error(code: String) = receive {
                val q = it.routeRequest
                // This emulator reports route cancellation before the search error.
                val failedRequest = q?.stage in pendingStages || (q?.stage == RouteRequestStage.CANCELLED && !q.cancelRequested)
                record(it.copy(lastError = code, routeRequest = if (failedRequest) q?.copy(stage = RouteRequestStage.FAILED) else q), "onError", code)
            }
            override fun routeStarted(detail: String) = receive {
                val q = it.routeRequest
                val next = it.copy(routeRequest = if (q?.stage in pendingStages && q?.completedCallback != true) q?.copy(stage = RouteRequestStage.SEARCHING, startedCallback = true) else q)
                record(if(q?.expectedWaypoints != null && q.stage in pendingStages) next.copy(route = NavigationReading(), destination = NavigationReading(), waypoints = NavigationReading()) else next, "onRouteStarted", detail)
            }
            override fun routingCompleted() {
                receive {
                    val q = it.routeRequest
                    val next = it.copy(routeRequest = if (q?.stage in pendingStages) q?.copy(stage = RouteRequestStage.AWAITING_CONFIRMATION, completedCallback = true) else q)
                    // A waypoint write needs read-back after completion, not cached pre-search data.
                    record(if(q?.expectedWaypoints != null && q.stage in pendingStages) next.copy(route = NavigationReading(), destination = NavigationReading(), waypoints = NavigationReading()) else next, "onRoutingCompleted")
                }
                dispatch { if (!closed && gen == generation) refresh() }
            }
            override fun destinationChanged() = receive { record(it, "onDestinationChanged") }
            override fun routeCancelled() = receive {
                val q = it.routeRequest
                record(it.copy(routeRequest = if (q?.stage in listOf(RouteRequestStage.FAILED, RouteRequestStage.TIMED_OUT)) q else q?.copy(stage = RouteRequestStage.CANCELLED)), "onRouteCancelled")
            }
            override fun navigationStatus(value: String) = receive { record(it.copy(navigationStatus = value), "onNavigationStatus", value) }
        }
        runCatching {
            val created = factory()
            client = created
            created.initialize(connected = {
                dispatch {
                    if (!closed && gen == generation) {
                        runCatching {
                            created.addListener(events)
                            publish(snapshot.copy(connection = NavigationConnection.CONNECTED))
                            if (!closed && gen == generation) refresh()
                        }.onFailure { if (!closed && gen == generation) fail(it) }
                    }
                }
            }, failed = { error -> dispatch { if (!closed && gen == generation) fail(error) } })
        }.onFailure { if (!closed && gen == generation) fail(it) }
    }

    override fun refresh() = dispatch {
        if (closed || snapshot.connection != NavigationConnection.CONNECTED) return@dispatch
        val current = client ?: return@dispatch
        publish(snapshot.copy(lastQueryAtElapsedMs = SystemClock.elapsedRealtime()))
        if (!closed && current === client) runCatching { current.query() }.onFailure { fail(it) }
    }

    /** Caller must obtain approval before invoking. A successful return is not route success. */
    fun requestDestination(target: NavigationTarget) = requestRoute(target, null)
    fun requestRouteWithWaypoints(target: NavigationTarget, waypoints: List<NavigationTarget>) = requestRoute(target, waypoints.toList())
    private fun requestRoute(target: NavigationTarget, waypoints: List<NavigationTarget>?) = dispatch {
        if (!commandReady()) return@dispatch
        if (!target.valid() || (waypoints != null && (waypoints.size !in 1..2 || waypoints.any { !it.valid() }))) { publish(snapshot.copy(lastError = "INVALID_DESTINATION")); return@dispatch }
        if (snapshot.routeRequest?.stage in pendingStages) { publish(snapshot.copy(lastError = "ROUTE_REQUEST_PENDING")); return@dispatch }
        val id = ++requestSequence
        publish(snapshot.copy(routeRequest = NavigationRequest(id, target, RouteRequestStage.REQUESTED, expectedWaypoints = waypoints),
            route = NavigationReading(), destination = NavigationReading(), waypoints = NavigationReading(), turns = NavigationReading(),
            navigationStatus = null, lastError = null))
        if (closed) return@dispatch
        requestTimeout?.let(main::removeCallbacks)
        requestTimeout = Runnable {
            val q = snapshot.routeRequest
            if (!closed && q?.id == id && q.stage in pendingStages) publish(record(
                snapshot.copy(routeRequest = q.copy(stage = RouteRequestStage.TIMED_OUT), lastError = "ROUTE_RESULT_UNCERTAIN"), "routeTimeout"))
        }.also { main.postDelayed(it, routeTimeoutMs) }
        runCatching { if(waypoints == null) client!!.requestDestination(target) else client!!.requestRouteWithWaypoints(target, waypoints) }.onFailure {
            requestTimeout?.let(main::removeCallbacks); requestTimeout = null
            publish(record(snapshot.copy(routeRequest = snapshot.routeRequest?.copy(stage = RouteRequestStage.FAILED),
                lastError = it.javaClass.simpleName), "requestException", it.javaClass.simpleName))
        }
    }

    /** Recovery only after a fresh read confirms that the approved test destination is still set. */
    fun cancelDestination(expected: NavigationTarget) = dispatch {
        if (!commandReady()) return@dispatch
        val at = snapshot.destination.receivedAtElapsedMs
        if (at == null || SystemClock.elapsedRealtime() - at > 10000 || !expected.matches(snapshot.destination.value)) {
            publish(snapshot.copy(lastError = "CANCEL_DESTINATION_MISMATCH_OR_STALE")); return@dispatch
        }
        publish(record(snapshot.copy(routeRequest = snapshot.routeRequest?.copy(cancelRequested = true)), "cancelRequested"))
        if (closed) return@dispatch
        runCatching { client!!.cancelRoute() }.onFailure { publish(snapshot.copy(lastError = it.javaClass.simpleName)) }
    }

    fun showNavigation() = dispatch {
        if (commandReady()) runCatching { client!!.showNavigation() }.onFailure { publish(snapshot.copy(lastError = it.javaClass.simpleName)) }
    }

    private fun commandReady(): Boolean {
        if (closed) return false
        val error = when {
            !allowRouteChanges -> "ROUTE_COMMANDS_DISABLED"
            snapshot.connection != NavigationConnection.CONNECTED || client == null -> "NAVI_NOT_CONNECTED"
            else -> return true
        }
        publish(snapshot.copy(lastError = error)); return false
    }

    override fun close() = dispatch {
        if (closed) return@dispatch
        closed = true
        generation++
        dispose()
        publish(NavigationSnapshot(connection = NavigationConnection.RELEASED))
    }
}

/** Information getters never issue implicit route changes; commands are separate entry points. */
private class PleosReadClient(context: Context) : NavigationClient {
    private val helper = NaviHelper(context)
    private var listener: NaviHelperEventListener? = null

    override fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit) =
        helper.initialize(onConnected = connected, onFailed = failed)

    override fun addListener(events: NavigationClient.Events) {
        val next = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                val p = currentLocationInfo
                events.location(if (validCoordinate(p.latitude, p.longitude))
                    NavigationPosition(p.latitude, p.longitude, p.heading.takeIf { it.isFinite() && it in 0.0..360.0 }) else null)
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                val r = routeStateInfo
                events.route(NavigationRoute(r.isRouting, r.naviMode.name, r.routeOption.name, r.termsAgreement))
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                val d = destinationInfo
                events.destination(destination(d.name, d.latitude, d.longitude, d.remainDistance, d.remainTime))
            }
            override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
                events.waypoints(waypointInfo.mapNotNull {
                    val useDisplay = it.latitude == 0.0 && it.longitude == 0.0 && it.displayLatitude != null && it.displayLongitude != null
                    destination(it.name, if(useDisplay) it.displayLatitude!! else it.latitude,
                        if(useDisplay) it.displayLongitude!! else it.longitude, it.remainDistance, it.remainTime)?.copy(address=it.address)
                })
            }
            override fun onTBTInfo(tbtInfo: List<TBTInfo>) {
                events.turns(tbtInfo.map { NavigationTurn(it.type.name, it.distance.takeIf { d -> d.isFinite() && d >= 0 }, it.description) })
            }
            override fun onError(errorCode: NaviErrorCode) = events.error(errorCode.name)
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = events.routeStarted(
                "distanceMeters=${routeStartInfo.distance};durationSeconds=${routeStartInfo.duration}")
            override fun onRoutingCompleted() = events.routingCompleted()
            override fun onDestinationChanged() = events.destinationChanged()
            override fun onRouteCancelled() = events.routeCancelled()
            override fun onNavigationStatus(navigationStatus: NavigationStatus) = events.navigationStatus(navigationStatus.name)
        }
        listener = next
        helper.addListener(next)
    }
    override fun removeListener() {
        listener?.let { helper.removeListener(it) }
        listener = null
    }
    override fun query() {
        helper.getRouteStateInfo()
        helper.getCurrentLocationInfo()
        helper.getDestinationInfo()
        helper.getWaypointInfo()
        helper.getTBTInfo()
    }
    override fun release() = helper.release()
    override fun requestDestination(target: NavigationTarget) = helper.requestRoute(RouteInfo(
        latitude = target.latitude, longitude = target.longitude, poiName = target.name,
        poiId = target.poiId, address = target.address, poiSubId = target.poiSubId))
    override fun requestRouteWithWaypoints(target: NavigationTarget, waypoints: List<NavigationTarget>) = helper.requestRouteWithWaypoints(
        RouteWithWaypointsRequest(
            destination = RouteInfo(latitude = target.latitude, longitude = target.longitude, poiName = target.name,
                poiId = target.poiId, address = target.address, poiSubId = target.poiSubId),
            waypoints = waypoints.mapIndexed { index, p -> RequestWaypointInfo(latitude = p.latitude, longitude = p.longitude,
                poiName = p.name, waypointIndex = if(index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                poiId = p.poiId, address = p.address, poiSubId = p.poiSubId) }))
    override fun cancelRoute() = helper.cancelRoute()
    override fun showNavigation() = helper.requestNavigationScreen(NavigationScreenRequest.Foreground)

    private fun validCoordinate(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    private fun destination(name: String, lat: Double, lng: Double, meters: Int, seconds: Int) =
        if (validCoordinate(lat, lng)) NavigationDestination(name, lat, lng, meters.takeIf { it >= 0 }, seconds.takeIf { it >= 0 }) else null
}
