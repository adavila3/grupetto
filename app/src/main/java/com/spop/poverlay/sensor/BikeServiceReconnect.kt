package com.spop.poverlay.sensor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Ends a sensor session whose bike service went away, so the caller binds again. */
internal class BikeServiceLostException(reason: String) : IllegalStateException(reason)

/**
 * Delay before the next connection attempt. A session that worked and then dropped (the service
 * crashed or its package was updated) reconnects after a second; while the service stays
 * unavailable, attempts back off to one every 30 s.
 */
internal fun reconnectDelayMillis(consecutiveFailures: Int): Long = when (consecutiveFailures) {
    0 -> 1_000
    1 -> 2_000
    2 -> 5_000
    3 -> 10_000
    else -> 30_000
}

/**
 * Runs [session] again whenever it ends, for as long as the flow is collected. A session emits
 * its sensors and returns true if it delivered a working connection; previously a single bind
 * failure or a replaced service process left the sensors silent until the app was restarted.
 * The session receives the number of consecutive failed attempts before it (0 on a fresh start
 * or after a working connection), e.g. to collect heavier diagnostics only once per outage.
 */
internal fun <T> reconnectingFlow(
    log: (String) -> Unit,
    delayMillis: (consecutiveFailures: Int) -> Long = ::reconnectDelayMillis,
    session: suspend FlowCollector<T>.(consecutiveFailures: Int) -> Boolean,
): Flow<T> = flow {
    var failures = 0
    while (true) {
        val delivered = session(failures)
        failures = if (delivered) 0 else failures + 1
        val wait = delayMillis(failures)
        log(
            if (delivered) "Bike service connection lost; reconnecting in $wait ms"
            else "Bike service unavailable (attempt $failures); retrying in $wait ms"
        )
        delay(wait)
    }
}
