package com.spop.poverlay.sensor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class BoundBikeService(
    val binder: IBinder,
    private val release: () -> Unit,
    val componentName: ComponentName? = null,
    private val lost: CompletableDeferred<String> = CompletableDeferred(),
) : AutoCloseable {
    /**
     * Suspends until the service goes away and returns why: its process died (a crash, or the
     * package being updated) or Android dropped the binding. The binder is unusable afterwards,
     * so the caller must close this binding and bind again.
     */
    suspend fun awaitLoss(): String = lost.await()

    override fun close() = release()
}

internal suspend fun bindBikeService(context: Context, intentFactory: () -> Intent): BoundBikeService =
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val lost = CompletableDeferred<String>()
        val deathRecipient = IBinder.DeathRecipient { lost.complete("Bike service process died") }
        var linkedBinder: IBinder? = null
        lateinit var connection: ServiceConnection
        fun release() {
            if (released.compareAndSet(false, true)) {
                linkedBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
                runCatching { context.unbindService(connection) }
                    .onFailure { Timber.w(it, "Failed to unbind bike service") }
            }
        }
        fun fail(message: String) {
            if (completed.compareAndSet(false, true)) {
                release()
                continuation.resumeWithException(IllegalStateException(message))
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    fail("Bike service returned a null binder")
                } else if (completed.compareAndSet(false, true)) {
                    // A binder that is already dead can't be linked; report it as lost right away.
                    try {
                        binder.linkToDeath(deathRecipient, 0)
                        linkedBinder = binder
                    } catch (e: RemoteException) {
                        lost.complete("Bike service process died while connecting")
                    }
                    continuation.resume(BoundBikeService(binder, ::release, name, lost)) { release() }
                }
            }
            override fun onNullBinding(name: ComponentName?) = fail("Bike service returned a null binding")
            override fun onBindingDied(name: ComponentName?) {
                // Before the first connection this is a failed bind; afterwards the binding is
                // permanently dead (e.g. the service package was updated) and must be rebound.
                if (completed.get()) lost.complete("Bike service binding died") else fail("Bike service binding died")
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Timber.w("Bike sensor service disconnected: %s", name)
                lost.complete("Bike service disconnected")
            }
        }
        if (!context.bindService(intentFactory(), connection, Context.BIND_AUTO_CREATE)) {
            fail("Bike service bindService returned false")
        }
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) release()
        }
    }
