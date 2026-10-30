package com.spop.poverlay.sensor

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BikeServiceReconnectTest {
    @Test fun `failed attempts back off and a working session resets the backoff`() = runBlocking {
        // Two failed attempts, a working session that is later lost, one more failure, then a
        // working session again.
        val outcomes = ArrayDeque(listOf(false, false, true, false, true))
        val attempts = mutableListOf<Int>()
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()

        val emitted = reconnectingFlow<String>(
            log = { logs += it },
            // Record the real backoff but skip the wait, so the test runs instantly.
            delayMillis = { failures -> reconnectDelayMillis(failures).also { waits += it }; 0L },
        ) { consecutiveFailures ->
            attempts += consecutiveFailures
            val delivered = outcomes.removeFirst()
            if (delivered) emit("session ${attempts.size}")
            delivered
        }.take(2).toList()

        assertEquals(listOf("session 3", "session 5"), emitted)
        assertEquals(listOf(0, 1, 2, 0, 1), attempts)
        assertEquals(listOf(2_000L, 5_000L, 1_000L, 2_000L), waits)
        assertEquals("Bike service unavailable (attempt 1); retrying in 0 ms", logs.first())
        assertEquals("Bike service connection lost; reconnecting in 0 ms", logs[2])
    }

    @Test fun `backoff is capped at 30 seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L),
            (0..5).map { reconnectDelayMillis(it) })
    }
}
