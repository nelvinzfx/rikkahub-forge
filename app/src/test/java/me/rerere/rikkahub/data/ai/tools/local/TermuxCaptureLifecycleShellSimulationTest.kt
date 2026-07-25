package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shell-level regression coverage for the public capture lifecycle. The foreground shell is the
 * completion authority: a descendant that inherits stdout must not delay finalization, and that
 * descendant must survive successful root completion. Timeout cleanup remains covered by the
 * process-group scripts and on-device acceptance run.
 */
class TermuxCaptureLifecycleShellSimulationTest {
    @Test
    fun rootExitCaptureCompletesWithoutWaitingForInheritedDescriptorHolder() {
        assumeTools()
        withSandbox { home ->
            val jobId = UUID.randomUUID().toString()
            val command = "sleep 20 & child=\$!; printf '%s\\n' \"\$child\" > \"\$HOME/child.pid\"; echo root-finished"
            val process = launch(home, jobId, command)

            assertTrue("capture wrapper did not finish after the foreground shell exited", process.waitFor(5, TimeUnit.SECONDS))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertEquals(stderr, 0, process.exitValue())
            assertTrue(stdout.contains("RIKKAHUB_JOB_V2"))
            assertTrue(stdout.contains("status=completed"))
            assertTrue(stdout.contains("stdout_head_b64="))

            val childPid = File(home, "child.pid").readText().trim().toLong()
            assertTrue("background descendant was killed after successful root completion", ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
            ProcessHandle.of(childPid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun rootExitCaptureSnapshotsOutputAndLeavesNoMutableFinalFile() {
        assumeTools()
        withSandbox { home ->
            val jobId = UUID.randomUUID().toString()
            val command = "echo before; (sleep 1; echo descendant-late) & echo after"
            val process = launch(home, jobId, command)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())

            val jobDir = File(home, ".cache/rikkahub/jobs/$jobId")
            val finalOutput = File(jobDir, "stdout")
            val initial = finalOutput.readText()
            assertTrue(initial.contains("before"))
            assertTrue(initial.contains("after"))
            Thread.sleep(1_500)
            assertEquals("terminal result changed after metadata publication", initial, finalOutput.readText())
            assertFalse(initial.contains("descendant-late"))
        }
    }

    @Test
    fun timeoutCleanupStillKillsCorrelatedRootAndChildGroup() {
        assumeTools()
        withSandbox { home ->
            val jobId = UUID.randomUUID().toString()
            val process = launch(
                home,
                jobId,
                """echo ${'$'}${'$'} > "${'$'}HOME/root.pid"; sleep 30 & echo ${'$'}! > "${'$'}HOME/child.pid"; echo before-timeout; wait"""
            )
            val state = File(home, ".cache/rikkahub/jobs/$jobId/state")
            val childFile = File(home, "child.pid")
            waitUntil { state.isFile && childFile.isFile }

            val cleanup = ProcessBuilder(
                bashExecutable(), "-c", SPOOL_CLEANUP_SCRIPT, "rikka-cleanup", jobId, "timed_out",
            ).apply { environment()["HOME"] = home.absolutePath }.start()
            assertTrue(cleanup.waitFor(5, TimeUnit.SECONDS))
            assertTrue(process.waitFor(8, TimeUnit.SECONDS))

            val rootPid = File(home, "root.pid").readText().trim().toLong()
            val childPid = childFile.readText().trim().toLong()
            assertFalse(ProcessHandle.of(rootPid).map { it.isAlive }.orElse(false))
            assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
            val meta = File(home, ".cache/rikkahub/jobs/$jobId/meta").readText()
            assertTrue(meta.contains("status=timed_out"))
            assertTrue(meta.contains("exit_code=124"))
            assertTrue(File(home, ".cache/rikkahub/jobs/$jobId/stdout").readText().contains("before-timeout"))
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("timed out waiting for capture state", predicate())
    }

    private fun launch(home: File, jobId: String, command: String): Process {
        val args = listOf(
            "/bin/bash", "-c", SPOOL_CAPTURE_SCRIPT, "rikka-spool", jobId,
            "49152", "12288", "50", "86400",
            JOB_RETENTION_LOCKED_SCRIPT,
            SPOOL_CAPTURE_LEADER_SCRIPT,
            SPOOL_OUTPUT_LIMITER_SCRIPT,
            "1",
            bashExecutable(), "-c", command,
        )
        return ProcessBuilder(args).apply {
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = File(home, "tmp").apply { mkdirs() }.absolutePath
        }.start()
    }

    private fun bashExecutable(): String =
        listOf("/bin/bash", "/data/data/com.termux/files/usr/bin/bash").firstOrNull { File(it).canExecute() }
            ?: "bash"

    private fun withSandbox(block: (File) -> Unit) {
        val home = kotlin.io.path.createTempDirectory("termux-capture-").toFile()
        try {
            block(home)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun assumeTools() = assumeTrue(
        listOf("bash", "base64", "dd", "flock", "setsid", "stat", "awk", "head", "truncate").all {
            ProcessBuilder("sh", "-c", "command -v $it >/dev/null").start().waitFor() == 0
        }
    )
}
