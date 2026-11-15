package com.hopon.car.navigation

import android.test.InstrumentationTestCase

/** All commands target a fake client. Safe to include in every regression suite. */
@Suppress("DEPRECATION")
class NavigationRouteTest : InstrumentationTestCase() {
    private val target = NavigationTarget("Test destination", 37.5, 127.0, "test-poi", "Test address")
    private class Fake : NavigationClient {
        lateinit var events: NavigationClient.Events
        var writes = 0
        override fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit) = connected()
        override fun addListener(events: NavigationClient.Events) { this.events = events }
        override fun removeListener() {}
        override fun query() {}
        override fun release() {}
        override fun requestDestination(target: NavigationTarget) { writes++ }
        override fun requestRouteWithWaypoints(target: NavigationTarget, waypoints: List<NavigationTarget>) { writes++ }
        override fun cancelRoute() { writes++ }
        override fun showNavigation() { writes++ }
    }
    fun testWaypointConfirmationRequiresFreshCompleteReadback() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true) {}
        val stop = target.copy(name="Stop", latitude=37.51)
        fun readings(points: List<NavigationDestination>) {
            f.events.destination(NavigationDestination("Test",37.5,127.0,1000,180))
            f.events.route(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true))
            f.events.waypoints(points)
        }
        try {
            a.start(); a.requestRouteWithWaypoints(target,listOf(stop))
            readings(listOf(NavigationDestination("Stop",37.51,127.0,100,30)))
            f.events.routingCompleted()
            assertEquals(RouteRequestStage.AWAITING_CONFIRMATION,a.snapshot.routeRequest!!.stage)
            readings(emptyList())
            assertEquals(RouteRequestStage.AWAITING_CONFIRMATION,a.snapshot.routeRequest!!.stage)
            readings(listOf(NavigationDestination("Wrong",36.0,127.0,100,30)))
            assertEquals(RouteRequestStage.AWAITING_CONFIRMATION,a.snapshot.routeRequest!!.stage)
            readings(listOf(NavigationDestination("Stop",37.51,127.0,100,30)))
            assertEquals(RouteRequestStage.CONFIRMED,a.snapshot.routeRequest!!.stage)
            assertEquals(1,f.writes)
        } finally { a.close() }
    }
    fun testMissingCompletionCallbackRequiresStartedAndFreshMatchingReadback() = instrumentation.runOnMainSync {
        val f=Fake();val a=PleosNavigationAdapter({f},allowRouteChanges=true){}
        val stop=target.copy(latitude=37.51)
        fun read() { f.events.route(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true));f.events.destination(NavigationDestination("Test",37.5,127.0,1,1));f.events.waypoints(listOf(NavigationDestination("Stop",37.51,127.0,1,1))) }
        try {
            a.start();a.requestRouteWithWaypoints(target,listOf(stop));read()
            assertEquals(RouteRequestStage.REQUESTED,a.snapshot.routeRequest!!.stage)
            f.events.routeStarted("search accepted")
            assertEquals(RouteRequestStage.SEARCHING,a.snapshot.routeRequest!!.stage)
            read();assertEquals(RouteRequestStage.CONFIRMED,a.snapshot.routeRequest!!.stage);assertEquals(1,f.writes)
            assertEquals("routeReadbackConfirmed",a.snapshot.events.last().name)
        } finally { a.close() }
    }
    fun testMissingWaypointCoordinatesNeedExactUniqueNames() = instrumentation.runOnMainSync {
        val f=Fake();val a=PleosNavigationAdapter({f},allowRouteChanges=true){}
        try {
            a.start();a.requestRouteWithWaypoints(target,listOf(target.copy(name="Stop",latitude=37.51)))
            f.events.routeStarted("accepted");f.events.route(NavigationRoute(false,"ROUTE_GUIDING","RECOMMENDED",true))
            f.events.destination(NavigationDestination("Test",37.5,127.0,1,1))
            f.events.waypoints(listOf(NavigationDestination("Wrong",0.0,0.0,1,1)))
            assertEquals(RouteRequestStage.SEARCHING,a.snapshot.routeRequest!!.stage)
            f.events.waypoints(listOf(NavigationDestination("Stop",0.0,0.0,1,1)))
            assertEquals(RouteRequestStage.CONFIRMED,a.snapshot.routeRequest!!.stage)
        } finally { a.close() }
    }
    fun testTooManyWaypointsNeverReachSdk() = instrumentation.runOnMainSync {
        val f=Fake(); val a=PleosNavigationAdapter({f},allowRouteChanges=true) {}
        try { a.start(); a.requestRouteWithWaypoints(target,List(3){target}); assertEquals(0,f.writes) } finally { a.close() }
    }
    fun testDefaultAdapterCannotIssueCommands() { checkDefaultGate() }
    private fun checkDefaultGate() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }) {}
        try {
            a.start(); a.requestDestination(target); a.showNavigation(); a.cancelDestination(target)
            assertEquals(0, f.writes); assertEquals("ROUTE_COMMANDS_DISABLED", a.snapshot.lastError)
        } finally { a.close() }
    }
    fun testConfirmationNeedsCallbackMatchingDestinationAndActiveMode() { checkConfirmation() }
    private fun checkConfirmation() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true) {}
        try {
            a.start(); a.requestDestination(target)
            f.events.destination(NavigationDestination("Test", 37.5, 127.0, 1000, 180))
            f.events.route(NavigationRoute(false, "ROUTE_GUIDING", "RECOMMENDED", true))
            assertEquals(RouteRequestStage.REQUESTED, a.snapshot.routeRequest!!.stage)
            f.events.routingCompleted()
            assertEquals(RouteRequestStage.CONFIRMED, a.snapshot.routeRequest!!.stage)
            assertNotNull(a.snapshot.estimatedArrivalEpochMs)
            assertEquals(1, f.writes)
        } finally { a.close() }
    }
    fun testWrongDestinationAndErrorCannotClaimSuccess() { checkFailure() }
    private fun checkFailure() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true) {}
        try {
            a.start(); a.requestDestination(target); f.events.routingCompleted()
            f.events.route(NavigationRoute(false, "ROUTE_GUIDING", "RECOMMENDED", true))
            f.events.destination(NavigationDestination("Other", 36.0, 128.0, 1000, 180))
            assertEquals(RouteRequestStage.AWAITING_CONFIRMATION, a.snapshot.routeRequest!!.stage)
            f.events.error("CAUSE_SEARCH_ROUTE")
            f.events.destination(NavigationDestination("Test", 37.5, 127.0, 1000, 180))
            assertEquals(RouteRequestStage.FAILED, a.snapshot.routeRequest!!.stage)
            a.refresh(); assertEquals("CAUSE_SEARCH_ROUTE", a.snapshot.lastError)
        } finally { a.close() }
    }
    fun testInvalidInputAndRecoveryCannotMutateAnotherDestination() { checkRecovery() }
    private fun checkRecovery() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true) {}
        try {
            a.start(); a.requestDestination(target.copy(latitude = Double.NaN))
            assertEquals(0, f.writes)
            f.events.destination(NavigationDestination("Other", 36.0, 128.0, 1000, 180))
            a.cancelDestination(target); assertEquals(0, f.writes)
            f.events.destination(NavigationDestination("Test", 37.5, 127.0, 1000, 180))
            a.cancelDestination(target); assertEquals(1, f.writes)
        } finally { a.close() }
    }
    fun testNoRouteOrMissingDurationHasNoEta() { checkEmptyEta() }
    private fun checkEmptyEta() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }) {}
        try {
            a.start(); f.events.route(NavigationRoute(false, "SAFE_GUIDING", "RECOMMENDED", true))
            f.events.destination(NavigationDestination("", 0.0, 0.0, 0, 0))
            assertNull(a.snapshot.estimatedArrivalEpochMs)
            f.events.route(NavigationRoute(false, "ROUTE_GUIDING", "RECOMMENDED", true))
            f.events.destination(NavigationDestination("Test", 37.5, 127.0, 1000, null))
            assertNull(a.snapshot.estimatedArrivalEpochMs)
        } finally { a.close() }
    }
    fun testTimeoutDoesNotRetryOrCancel() { checkTimeout() }
    fun testServiceCancellationAndErrorOrderPreservesFailure() { checkServiceErrorOrder() }
    private fun checkServiceErrorOrder() = instrumentation.runOnMainSync {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true) {}
        try {
            a.start(); a.requestDestination(target)
            f.events.routeCancelled(); f.events.error("CAUSE_SEARCH_ROUTE")
            assertEquals(RouteRequestStage.FAILED, a.snapshot.routeRequest!!.stage)
            a.requestDestination(target)
            f.events.error("CAUSE_SEARCH_ROUTE"); f.events.routeCancelled()
            assertEquals(RouteRequestStage.FAILED, a.snapshot.routeRequest!!.stage)
            assertEquals("CAUSE_SEARCH_ROUTE", a.snapshot.lastError)
        } finally { a.close() }
    }
    private fun checkTimeout() {
        val f = Fake(); val a = PleosNavigationAdapter({ f }, allowRouteChanges = true, routeTimeoutMs = 50) {}
        try {
            instrumentation.runOnMainSync { a.start(); a.requestDestination(target) }
            Thread.sleep(150)
            instrumentation.runOnMainSync {
                assertEquals(RouteRequestStage.TIMED_OUT, a.snapshot.routeRequest!!.stage)
                f.events.routingCompleted()
                assertEquals(RouteRequestStage.TIMED_OUT, a.snapshot.routeRequest!!.stage)
                assertEquals(1, f.writes)
            }
        } finally { instrumentation.runOnMainSync { a.close() } }
    }
}
