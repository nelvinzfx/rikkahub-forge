package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentCriticalRemediationTest {
    private fun run(
        id: String,
        assistant: String = "assistant-a",
        parentChat: String? = "chat-a",
        ownerChat: String? = parentChat,
        parentRun: String? = null,
        rootRun: String? = null,
        conversation: String? = null,
        status: SubAgentStatus = SubAgentStatus.RUNNING,
    ) = SubAgentRun(
        id = id,
        parentChatId = parentChat,
        parentAssistantId = assistant,
        ownerChatId = ownerChat,
        conversationId = conversation,
        label = id,
        task = "task-$id",
        modelId = null,
        tools = null,
        runInBackground = true,
        timeoutSeconds = 60,
        maxTrips = 4,
        status = status,
        startedAtMs = 1L,
        parentRunId = parentRun,
        orchestratorRunId = rootRun,
    )

    @Test
    fun `run handle cancels actual generation and supervisor idempotently`() {
        val handle = SubAgentExecutionHandle()
        val supervisor = Job()
        val generation = Job()
        handle.attachSupervisor(supervisor)
        handle.attachGeneration(generation)

        assertTrue(handle.requestStop())
        assertTrue(supervisor.isCancelled)
        assertTrue(generation.isCancelled)
        assertFalse(handle.requestStop())
    }

    @Test
    fun `stop requested before attach cancels both late jobs exactly once`() {
        val handle = SubAgentExecutionHandle()
        assertTrue(handle.requestStop())
        assertFalse(handle.requestStop())

        val lateSupervisor = Job()
        val lateGeneration = Job()
        handle.attachSupervisor(lateSupervisor)
        handle.attachGeneration(lateGeneration)

        assertTrue(lateSupervisor.isCancelled)
        assertTrue(lateGeneration.isCancelled)
    }

    @Test
    fun `terminal cleanup stops generation before publishing terminal state`() =
        kotlinx.coroutines.runBlocking {
            val events = mutableListOf<String>()
            SubAgentTerminalCleanup.stopThenPublish(
                stop = { events += "stopGeneration" },
                publish = { events += "terminal" },
            )
            assertEquals(listOf("stopGeneration", "terminal"), events)
        }

    @Test
    fun `terminal cleanup skips publication when stop cleanup fails`() =
        kotlinx.coroutines.runBlocking {
            val events = mutableListOf<String>()
            try {
                SubAgentTerminalCleanup.stopThenPublish(
                    stop = {
                        events += "stopGeneration"
                        error("cleanup failed")
                    },
                    publish = { events += "terminal" },
                )
            } catch (_: IllegalStateException) {
                // Cleanup failure must leave the run non-terminal; publication is skipped.
            }
            assertEquals(listOf("stopGeneration"), events)
        }

    @Test
    fun `generation-only stop preserves supervisor for timeout cleanup`() {
        val handle = SubAgentExecutionHandle()
        val supervisor = Job()
        val generation = Job()
        handle.attachSupervisor(supervisor)
        handle.attachGeneration(generation)

        assertTrue(handle.requestGenerationStop())
        assertTrue(generation.isCancelled)
        assertFalse(supervisor.isCancelled)
        assertFalse(handle.requestStop())
    }

    @Test
    fun `registry cancellation atomically blocks late success`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("atomic"), handle)

        assertTrue(registry.requestCancel("atomic"))
        assertFalse(registry.transitionTerminal("atomic", SubAgentStatus.SUCCEEDED) {
            it.copy(result = "late")
        })
        assertEquals(SubAgentStatus.STOPPING, registry.get("atomic")?.status)
    }

    @Test
    fun `cancellation intent blocks racing success transition`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("r"), handle)
        handle.requestStop()

        assertFalse(registry.transitionTerminal("r", SubAgentStatus.SUCCEEDED) {
            it.copy(result = "too late")
        })
        // Direct handle use records stop intent but only the registry API publishes STOPPING.
        assertEquals(SubAgentStatus.RUNNING, registry.get("r")?.status)
    }

    @Test
    fun `requested terminal reason rejects unrelated failure transition`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("terminal-reason"), handle)
        assertTrue(registry.requestCancel("terminal-reason"))
        assertFalse(registry.transitionTerminal("terminal-reason", SubAgentStatus.FAILED) { it })
        assertTrue(registry.transitionTerminal("terminal-reason", SubAgentStatus.CANCELLED) { it })
    }

    @Test
    fun `late success cannot overwrite terminal cancellation`() {
        val registry = SubAgentRegistry()
        registry.addPending(run("r"))
        assertTrue(registry.transitionTerminal("r", SubAgentStatus.CANCELLED) {
            it.copy(finishedAtMs = 10L)
        })

        registry.update("r") {
            it.copy(status = SubAgentStatus.SUCCEEDED, result = "late")
        }

        assertEquals(SubAgentStatus.CANCELLED, registry.get("r")?.status)
        assertNull(registry.get("r")?.result)
        assertEquals(10L, registry.get("r")?.finishedAtMs)
        assertFalse(registry.transitionTerminal("r", SubAgentStatus.SUCCEEDED) { it })
    }

    @Test
    fun `explicit tool subset is exact and unknown names reject`() {
        val eligible = setOf("web_fetch", "get_time_info", "subagent_get")
        val subset = SubAgentToolAllowlist.resolve(
            listOf("get_time_info", "get_time_info"), eligible
        ) as ToolAllowlistResult.Ok
        assertEquals(setOf("get_time_info"), subset.names)

        val unknown = SubAgentToolAllowlist.resolve(
            listOf("get_time_info", "not_enabled"), eligible
        ) as ToolAllowlistResult.Reject
        assertEquals(setOf("not_enabled"), unknown.unknown)
    }

    @Test
    fun `null inherits while empty means no tools`() {
        val eligible = setOf("web_fetch")
        assertNull((SubAgentToolAllowlist.resolve(null, eligible) as ToolAllowlistResult.Ok).names)
        assertEquals(emptySet<String>(),
            (SubAgentToolAllowlist.resolve(emptyList(), eligible) as ToolAllowlistResult.Ok).names)
    }

    @Test
    fun `first stop reason wins and stopping remains active`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("reason"), handle)

        assertTrue(registry.requestTimeout("reason"))
        assertFalse(registry.requestCancel("reason"))
        assertEquals(SubAgentStatus.TIMED_OUT, registry.requestedTerminalStatus("reason"))
        assertEquals(SubAgentStatus.STOPPING, registry.get("reason")?.status)
        assertEquals(1, registry.list(activeOnly = true).size)
    }

    @Test
    fun `parent stop fence blocks new workers and owner lookup survives pruned roots`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("root"), handle)
        assertEquals(1, registry.cancelAllForParent("chat-a"))
        assertTrue(registry.isOwnerStopping("chat-a"))
        assertFalse(registry.addPending(run("late"), SubAgentExecutionHandle()))
        registry.clearOwnerStopFence("chat-a")
        assertTrue(registry.addPending(run("next"), SubAgentExecutionHandle()))
    }

    @Test
    fun `owner quiescence follows execution completion after terminal status`() =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.coroutineScope {
                val registry = SubAgentRegistry()
                val handle = SubAgentExecutionHandle()
                registry.addPending(run("quiescence"), handle)
                assertTrue(registry.transitionTerminal("quiescence", SubAgentStatus.SUCCEEDED) { it })
                assertTrue(registry.hasActiveOwnerRuns("chat-a"))

                val waiter = async { registry.awaitOwnerQuiescent("chat-a") }
                kotlinx.coroutines.yield()
                assertFalse(waiter.isCompleted)
                registry.clearExecution("quiescence")
                waiter.await()
                assertFalse(registry.hasActiveOwnerRuns("chat-a"))
            }
        }

    @Test
    fun `atomic owner guard blocks wake action after stop fence`() {
        val registry = SubAgentRegistry()
        var invoked = false
        assertTrue(registry.runIfOwnerActive("chat-a") { invoked = true; true })
        assertTrue(invoked)
        registry.cancelAllForParent("chat-a")
        invoked = false
        assertFalse(registry.runIfOwnerActive("chat-a") { invoked = true; true })
        assertFalse(invoked)
    }

    @Test
    fun `parent stop epoch absorbs repeated queued jobs before close`() =
        kotlinx.coroutines.runBlocking {
            val epoch = SubAgentParentStopEpoch()
            val first = Job()
            assertTrue(epoch.addAndCancel(first))
            val firstSnapshot = epoch.snapshot()
            val second = Job()
            assertTrue(epoch.addAndCancel(second))
            assertFalse(epoch.tryClose(firstSnapshot.revision))
            first.join()
            second.join()
            assertTrue(epoch.tryClose(epoch.snapshot().revision))
            epoch.complete()
            epoch.completion.await()
        }

    @Test
    fun `closed epoch rejects job so caller can migrate it to a fresh epoch`() =
        kotlinx.coroutines.runBlocking {
            val closed = SubAgentParentStopEpoch()
            assertTrue(closed.tryClose(closed.snapshot().revision))
            val queued = Job()
            assertFalse(closed.addAndCancel(queued))
            assertFalse(queued.isCancelled)

            val fresh = SubAgentParentStopEpoch()
            assertTrue(fresh.addAndCancel(queued))
            assertTrue(queued.isCancelled)
            queued.join()
        }

    @Test
    fun `owner remains active while any child is stopping`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        registry.addPending(run("stopping-owner"), handle)
        assertTrue(registry.requestCancel("stopping-owner"))
        assertTrue(registry.hasActiveOwnerRuns("chat-a"))
        assertTrue(registry.transitionTerminal("stopping-owner", SubAgentStatus.CANCELLED) { it })
        assertTrue(registry.hasActiveOwnerRuns("chat-a"))
        registry.clearExecution("stopping-owner")
        assertFalse(registry.hasActiveOwnerRuns("chat-a"))
    }

    @Test
    fun `depth policy disables zero and exposes child dispatch only below cap`() {
        assertFalse(SubAgentDepthPolicy.canDispatch(0, 0))
        assertTrue(SubAgentDepthPolicy.canDispatch(0, 1))
        assertFalse(SubAgentDepthPolicy.canDispatch(1, 1))
        assertTrue(SubAgentDepthPolicy.canSpawnChild(0, 2))
        assertFalse(SubAgentDepthPolicy.canSpawnChild(0, 1))
    }

    @Test
    fun `parent conversation sees only its assistant orchestration descendants`() {
        val registry = SubAgentRegistry()
        registry.addPending(run("root"))
        registry.addPending(run("child", parentChat = "worker-root", ownerChat = "chat-a", parentRun = "root", rootRun = "root"))
        registry.addPending(run("other-chat", parentChat = "chat-b"))
        registry.addPending(run("other-assistant", assistant = "assistant-b"))

        val scope = SubAgentAccessScope("assistant-a", "chat-a", null)
        assertEquals(setOf("root", "child"), registry.listScoped(false, scope).map { it.id }.toSet())
        assertNull(registry.getScoped("other-chat", scope))
        assertNull(registry.getScoped("other-assistant", scope))
    }

    @Test
    fun `root owner remains authorized after parent run is evicted from ancestry`() {
        val registry = SubAgentRegistry()
        registry.addPending(run("descendant", parentChat = "missing-worker", ownerChat = "chat-a", parentRun = "evicted-root"))
        val scope = SubAgentAccessScope("assistant-a", "chat-a", null)
        assertEquals("descendant", registry.getScoped("descendant", scope)?.id)
    }

    @Test
    fun `worker scope can manage own subtree but not sibling`() {
        val registry = SubAgentRegistry()
        registry.addPending(run("root"))
        registry.addPending(run("worker", parentChat = "worker-root", ownerChat = "chat-a", parentRun = "root", rootRun = "root"))
        registry.addPending(run("descendant", parentChat = "worker", ownerChat = "chat-a", parentRun = "worker", rootRun = "root"))
        registry.addPending(run("sibling", parentChat = "worker-root", ownerChat = "chat-a", parentRun = "root", rootRun = "root"))

        val workerScope = SubAgentAccessScope("assistant-a", "worker-conversation", "worker")
        assertEquals(setOf("worker", "descendant"),
            registry.listScoped(false, workerScope).map { it.id }.toSet())
        assertNull(registry.getScoped("sibling", workerScope))
    }

    @Test
    fun `parent cascade requests cancellation for descendant generations`() {
        val registry = SubAgentRegistry()
        val rootHandle = SubAgentExecutionHandle()
        val childHandle = SubAgentExecutionHandle()
        val rootGeneration = Job()
        val childGeneration = Job()
        rootHandle.attachGeneration(rootGeneration)
        childHandle.attachGeneration(childGeneration)
        registry.addPending(run("root"), rootHandle)
        registry.addPending(
            run("child", parentChat = "worker-root", ownerChat = "chat-a", parentRun = "root", rootRun = "root"),
            childHandle,
        )

        assertEquals(2, registry.cancelAllForParent("chat-a"))
        assertTrue(rootGeneration.isCancelled)
        assertTrue(childGeneration.isCancelled)
    }

    @Test
    fun `parent cascade finds active descendant after root eviction`() {
        val registry = SubAgentRegistry()
        val handle = SubAgentExecutionHandle()
        val generation = Job()
        handle.attachGeneration(generation)
        registry.addPending(
            run(
                "orphan-descendant",
                parentChat = "missing-worker",
                ownerChat = "chat-a",
                parentRun = "evicted-root",
                rootRun = "evicted-root",
            ),
            handle,
        )

        assertEquals(1, registry.cancelAllForParent("chat-a"))
        assertTrue(generation.isCancelled)
        assertEquals(SubAgentStatus.STOPPING, registry.get("orphan-descendant")?.status)
    }

    @Test
    fun `active continuation exclusively leases worker conversation`() {
        val registry = SubAgentRegistry()
        assertTrue(registry.addPending(run("first", conversation = "worker-conv")))
        assertFalse(registry.addPending(run("second", conversation = "worker-conv")))

        registry.transitionTerminal("first", SubAgentStatus.FAILED) { it.copy(finishedAtMs = 2L) }
        assertTrue(registry.addPending(run("second", conversation = "worker-conv")))
    }

    @Test
    fun `continuation policy requires owned terminal source and idle conversation`() {
        val terminal = run("terminal", status = SubAgentStatus.FAILED)
        val active = run("active", status = SubAgentStatus.RUNNING)

        assertEquals(
            ContinuationPolicyResult.UNKNOWN_OR_UNAUTHORIZED,
            SubAgentContinuationPolicy.validate(null, authorized = false, conversationActive = false),
        )
        assertEquals(
            ContinuationPolicyResult.UNKNOWN_OR_UNAUTHORIZED,
            SubAgentContinuationPolicy.validate(terminal, authorized = false, conversationActive = false),
        )
        assertEquals(
            ContinuationPolicyResult.SOURCE_NOT_TERMINAL,
            SubAgentContinuationPolicy.validate(active, authorized = true, conversationActive = false),
        )
        assertEquals(
            ContinuationPolicyResult.CONVERSATION_ACTIVE,
            SubAgentContinuationPolicy.validate(terminal, authorized = true, conversationActive = true),
        )
        assertEquals(
            ContinuationPolicyResult.OK,
            SubAgentContinuationPolicy.validate(terminal, authorized = true, conversationActive = false),
        )
    }

    @Test
    fun `attempt boundary usage excludes old tokens and trips`() {
        data class Item(val usage: Pair<Long, Long>?, val assistant: Boolean)
        val result = SubAgentAttemptBoundary.usageAfter(
            items = listOf(
                Item(100L to 200L, true),
                Item(null, false),
                Item(3L to 5L, false),
                Item(7L to 11L, true),
            ),
            boundary = 2,
            usageOf = Item::usage,
            isAssistant = Item::assistant,
        )
        assertEquals(Triple(10L, 16L, 1), result)
    }

    @Test
    fun `reasoning level parser rejects invalid values instead of inheriting`() {
        assertEquals(me.rerere.ai.core.ReasoningLevel.HIGH, parseSubAgentReasoningLevel("high"))
        assertNull(parseSubAgentReasoningLevel(null))
        try {
            parseSubAgentReasoningLevel("ultra")
            throw AssertionError("invalid reasoning level was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("reasoning_level"))
        }
    }

    @Test
    fun `encoded run preserves inherited versus explicit empty tools`() {
        val inherited = encodeRun(run("inherited").copy(tools = null))
        val none = encodeRun(run("none").copy(tools = emptyList()))
        assertFalse(inherited.containsKey("tools"))
        assertTrue(none.containsKey("tools"))
        assertEquals("[]", none["tools"].toString())
    }

    @Test
    fun `continuation boundary excludes stale prior attempt values`() {
        assertEquals(listOf("new-user", "new-assistant"),
            SubAgentAttemptBoundary.after(
                listOf("old-user", "old-assistant", "new-user", "new-assistant"), 2
            ))
        assertTrue(SubAgentAttemptBoundary.after(listOf("old"), 1).isEmpty())
    }
}
