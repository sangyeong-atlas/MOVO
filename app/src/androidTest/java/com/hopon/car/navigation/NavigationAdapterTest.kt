package com.hopon.car.navigation

import android.test.InstrumentationTestCase

/** Deterministic failure/lifecycle checks; does not connect to Navi Service or the backend. */
@Suppress("DEPRECATION")
class NavigationAdapterTest : InstrumentationTestCase() {
    private class FakeClient : NavigationClient {
        lateinit var connected: () -> Unit
        lateinit var failed: (Throwable) -> Unit
        var events: NavigationClient.Events? = null
        var queries = 0
        var removed = 0
        var released = 0
        override fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit) {
            this.connected = connected; this.failed = failed
        }
        override fun addListener(events: NavigationClient.Events) { this.events = events }
        override fun removeListener() { removed++ }
        override fun query() { queries++ }
        override fun release() { released++ }
    }

    // JUnit3 on this image discovers private void test*$lambda methods generated for SAMs.
    // Keep SAM bodies in helpers whose names do not start with "test".
    fun testQueriesWaitForConnectionAndCloseIsTerminal() { checkConnectionAndClose() }
    private fun checkConnectionAndClose() = instrumentation.runOnMainSync {
        val client = FakeClient()
        val adapter = PleosNavigationAdapter({ client }) {}
        adapter.refresh(); adapter.start(); adapter.start()
        assertEquals(0, client.queries)
        client.connected()
        assertEquals(NavigationConnection.CONNECTED, adapter.snapshot.connection)
        assertEquals(1, client.queries)
        adapter.close(); adapter.close(); adapter.refresh(); adapter.start()
        assertEquals(1, client.removed)
        assertEquals(1, client.released)
        assertEquals(1, client.queries)
        assertEquals(NavigationConnection.RELEASED, adapter.snapshot.connection)
    }

    fun testMissingNullAndEmptyResponsesStayDistinct() { checkMissingNullAndEmpty() }
    private fun checkMissingNullAndEmpty() = instrumentation.runOnMainSync {
        val client = FakeClient()
        val adapter = PleosNavigationAdapter({ client }) {}
        try {
            adapter.start(); client.connected()
            assertNull(adapter.snapshot.location.receivedAtElapsedMs)
            client.events!!.location(null)
            assertNotNull(adapter.snapshot.location.receivedAtElapsedMs)
            assertNull(adapter.snapshot.location.value)
            client.events!!.turns(emptyList())
            assertEquals(emptyList<NavigationTurn>(), adapter.snapshot.turns.value)
            assertEquals("pleos-navigation", adapter.snapshot.source)
            assertNull(adapter.snapshot.destination.value)
        } finally { adapter.close() }
    }

    fun testOldCallbacksCannotOverwriteRetryOrReleasedState() { checkLateCallbacks() }
    private fun checkLateCallbacks() = instrumentation.runOnMainSync {
        val first = FakeClient(); val second = FakeClient()
        var count = 0
        val adapter = PleosNavigationAdapter({ if (count++ == 0) first else second }) {}
        adapter.start(); first.failed(IllegalStateException("test"))
        assertEquals(NavigationConnection.FAILED, adapter.snapshot.connection)
        assertEquals(1, first.released)
        adapter.start(); first.connected()
        assertEquals(NavigationConnection.CONNECTING, adapter.snapshot.connection)
        second.connected()
        val late = second.events!!
        adapter.close()
        late.location(NavigationPosition(37.5, 127.0, 90.0))
        first.failed(IllegalStateException("late"))
        assertEquals(NavigationConnection.RELEASED, adapter.snapshot.connection)
        assertNull(adapter.snapshot.location.value)
    }

    fun testSdkConstructionFailureDoesNotEscapeIntoApp() { checkConstructionFailure() }
    private fun checkConstructionFailure() = instrumentation.runOnMainSync {
        val adapter = PleosNavigationAdapter({ throw IllegalStateException("unavailable") }) {}
        adapter.start()
        assertEquals(NavigationConnection.FAILED, adapter.snapshot.connection)
        assertEquals("IllegalStateException", adapter.snapshot.lastError)
        assertNull(adapter.snapshot.location.value)
        adapter.close()
    }
}
