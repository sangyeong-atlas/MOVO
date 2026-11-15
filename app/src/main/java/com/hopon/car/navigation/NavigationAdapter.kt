package com.hopon.car.navigation

/** Independent observations. Never written into JourneyRepository or telemetry. */
interface NavigationAdapter : AutoCloseable {
    val snapshot: NavigationSnapshot
    fun start()
    fun refresh()
    override fun close()
}

enum class NavigationConnection { IDLE, CONNECTING, CONNECTED, FAILED, RELEASED }

/** A missing callback differs from a callback containing no usable data (value == null). */
data class NavigationReading<T>(val value: T? = null, val receivedAtElapsedMs: Long? = null, val receivedAtEpochMs: Long? = null)
data class NavigationPosition(val latitude: Double, val longitude: Double, val headingDegrees: Double?)
data class NavigationRoute(val routing: Boolean, val mode: String, val option: String, val termsAgreed: Boolean)
data class NavigationDestination(
    val name: String, val latitude: Double, val longitude: Double,
    val remainingMeters: Int?, val remainingSeconds: Int?, val address: String = ""
)
data class NavigationTurn(val type: String, val distanceMeters: Double?, val description: String)

data class NavigationTarget(val name: String, val latitude: Double, val longitude: Double,
    val poiId: String, val address: String, val poiSubId: String = "0") {
    fun valid() = name.isNotBlank() && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && address.isNotBlank()
    fun matchesWaypoint(value: NavigationDestination): Boolean =
        if(value.latitude == 0.0 && value.longitude == 0.0)
            name == value.name
        else matches(value)
    fun matches(destination: NavigationDestination?): Boolean {
        destination ?: return false
        // SDK can return a nearby routable entrance instead of the supplied POI center.
        val dy = (latitude - destination.latitude) * 111000
        val dx = (longitude - destination.longitude) * 111000 * kotlin.math.cos(Math.toRadians(latitude))
        return kotlin.math.hypot(dx, dy) <= 100
    }
}
enum class RouteRequestStage { REQUESTED, SEARCHING, AWAITING_CONFIRMATION, CONFIRMED, FAILED, TIMED_OUT, CANCELLED }
data class NavigationRequest(val id: Long, val target: NavigationTarget, val stage: RouteRequestStage,
    val completedCallback: Boolean = false, val cancelRequested: Boolean = false,
    val expectedWaypoints: List<NavigationTarget>? = null, val startedCallback: Boolean = false)
data class NavigationEvent(val name: String, val elapsedMs: Long, val detail: String? = null)

data class NavigationSnapshot(
    val connection: NavigationConnection = NavigationConnection.IDLE,
    // SDK provenance does not mean that an emulator observation is a real vehicle GPS fix.
    val source: String = "pleos-navigation",
    val location: NavigationReading<NavigationPosition> = NavigationReading(),
    val route: NavigationReading<NavigationRoute> = NavigationReading(),
    val destination: NavigationReading<NavigationDestination> = NavigationReading(),
    val waypoints: NavigationReading<List<NavigationDestination>> = NavigationReading(),
    val turns: NavigationReading<List<NavigationTurn>> = NavigationReading(),
    val navigationStatus: String? = null,
    val routeRequest: NavigationRequest? = null,
    val events: List<NavigationEvent> = emptyList(),
    val lastQueryAtElapsedMs: Long? = null,
    val lastError: String? = null
) {
    val activeGuidance: Boolean get() = route.value?.mode in listOf("ROUTE_GUIDING", "SIMULATION_GUIDING")
    /** Derived wall-clock estimate from a received remaining duration, not a native SDK ETA. */
    val estimatedArrivalEpochMs: Long? get() {
        if (!activeGuidance) return null
        val value = destination.value ?: return null
        if (value.name.isBlank()) return null
        val seconds = value.remainingSeconds ?: return null
        return destination.receivedAtEpochMs?.plus(seconds.toLong() * 1000)
    }
}

/** Commands are opt-in on PleosNavigationAdapter. */
internal interface NavigationClient {
    interface Events {
        fun location(value: NavigationPosition?)
        fun route(value: NavigationRoute?)
        fun destination(value: NavigationDestination?)
        fun waypoints(value: List<NavigationDestination>?)
        fun turns(value: List<NavigationTurn>?)
        fun error(code: String)
        fun routeStarted(detail: String) {}
        fun routingCompleted() {}
        fun routeCancelled() {}
        fun destinationChanged() {}
        fun navigationStatus(value: String) {}
    }
    fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit)
    fun addListener(events: Events)
    fun removeListener()
    fun query()
    fun release()
    fun requestDestination(target: NavigationTarget) { error("Route commands unavailable") }
    fun requestRouteWithWaypoints(target: NavigationTarget, waypoints: List<NavigationTarget>) { error("Waypoint commands unavailable") }
    fun cancelRoute() { error("Route commands unavailable") }
    fun showNavigation() { error("Screen commands unavailable") }
}
