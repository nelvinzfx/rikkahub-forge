package me.rerere.ai.provider.providers

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LosslessCallbackFlowTest {
    private sealed interface Delta {
        data class Reasoning(val value: String) : Delta
        data class Text(val value: String) : Delta
        data class ToolArgument(val callId: String, val value: String) : Delta
    }

    @Test
    fun `slow collector receives every ordered delta beyond callbackFlow capacity`() = runBlocking {
        val expected = buildList {
            repeat(400) { index ->
                add(Delta.Reasoning("r$index|"))
                add(Delta.Text("t$index|"))
                add(Delta.ToolArgument("call-${index % 2}", "a$index|"))
            }
        }
        val producerFailure = AtomicReference<Throwable?>()
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val callbackDispatcher = callbackExecutor.asCoroutineDispatcher()

        try {
            val actual = callbackFlow {
                callbackExecutor.execute {
                    try {
                        expected.forEach { delta ->
                            if (!sendLosslesslyFromCallback(delta) {}) return@execute
                        }
                        close()
                    } catch (t: Throwable) {
                        producerFailure.set(t)
                        close(t)
                    }
                }
                awaitClose { }
            }.onEach {
                // Keep the collector slower than the synchronous callback throughout the stream.
                delay(1)
            }.toList(mutableListOf())

            assertEquals(expected, actual)
            assertEquals(
                expected.filterIsInstance<Delta.Reasoning>().joinToString("") { it.value },
                actual.filterIsInstance<Delta.Reasoning>().joinToString("") { it.value },
            )
            assertEquals(
                expected.filterIsInstance<Delta.Text>().joinToString("") { it.value },
                actual.filterIsInstance<Delta.Text>().joinToString("") { it.value },
            )
            listOf("call-0", "call-1").forEach { callId ->
                assertEquals(
                    expected.filterIsInstance<Delta.ToolArgument>()
                        .filter { it.callId == callId }
                        .joinToString("") { it.value },
                    actual.filterIsInstance<Delta.ToolArgument>()
                        .filter { it.callId == callId }
                        .joinToString("") { it.value },
                )
            }
            assertEquals(null, producerFailure.get())
        } finally {
            callbackDispatcher.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun `collector cancellation cancels callback producer instead of hanging`() = runBlocking {
        val upstreamCancelled = AtomicBoolean(false)
        val producerStopped = AtomicBoolean(false)
        val producerFailure = AtomicReference<Throwable?>()
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val callbackDispatcher = callbackExecutor.asCoroutineDispatcher()

        try {
            val received = callbackFlow {
                callbackExecutor.execute {
                    try {
                        repeat(10_000) { index ->
                            if (!sendLosslesslyFromCallback(index) { upstreamCancelled.set(true) }) {
                                producerStopped.set(true)
                                return@execute
                            }
                        }
                        close()
                    } catch (t: Throwable) {
                        producerFailure.set(t)
                        close(t)
                    }
                }
                awaitClose { upstreamCancelled.set(true) }
            }.take(1).toList()

            assertEquals(listOf(0), received)
            repeat(100) {
                if (producerStopped.get()) return@repeat
                Thread.sleep(1)
            }
            assertTrue(upstreamCancelled.get())
            assertTrue(producerStopped.get())
            assertFalse(producerFailure.get()?.toString().orEmpty(), producerFailure.get() != null)
        } finally {
            callbackDispatcher.close()
            callbackExecutor.shutdownNow()
        }
    }
}
