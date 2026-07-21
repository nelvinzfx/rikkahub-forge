package me.rerere.ai.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.isActive

/**
 * Bridges a synchronous provider callback into a callbackFlow without dropping deltas.
 *
 * Provider callbacks may outpace the collector, so a non-blocking trySend can fail when
 * callbackFlow's bounded channel is full. Blocking here applies backpressure on the provider's
 * dedicated callback thread while preserving every delta and its ordering. Collector cancellation
 * closes the channel, unblocks this call, and cancels the upstream stream through [cancelUpstream].
 */
fun <T> ProducerScope<T>.sendLosslesslyFromCallback(
    value: T,
    cancelUpstream: () -> Unit,
): Boolean {
    val result = trySendBlocking(value)
    if (result.isSuccess) return true

    cancelUpstream()
    val cause = result.exceptionOrNull()
    if (!isActive || cause is CancellationException) return false

    throw IllegalStateException(
        "Streaming callback channel closed before its delta could be delivered",
        cause,
    )
}
