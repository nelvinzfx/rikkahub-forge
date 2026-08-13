package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shell-level pin for JOB_RETENTION_LOCKED_SCRIPT: fake job dirs under a sandbox HOME,
 * run the real script via host bash, assert exactly which dirs survive. Also pins the
 * amortization gate (marker file + over-cap bypass) and the non-fatal property.
 */
class TermuxRetentionShellSimulationTest {

    private val jobsRel = ".cache/rikkahub/jobs"
    private val markerName = ".retention_last_run"

    @Test fun retentionScriptPassesBashSyntaxCheck() {
        assumeTools()
        assertFalse(JOB_RETENTION_LOCKED_SCRIPT.toByteArray().any { it == 0.toByte() })
        val file = Files.createTempFile("termux-retention-", ".sh").toFile()
        try {
            file.writeText(JOB_RETENTION_LOCKED_SCRIPT)
            assertEquals(0, ProcessBuilder(bashExecutable(), "-n", file.path).start().waitFor())
        } finally { file.delete() }
    }

    @Test fun missingJobsRootAndEmptyRootAreNoOps() = withSandbox { home ->
        // No jobs dir at all -> exit 0, nothing created.
        assertEquals(0, runRetention(home, maxJobs = 15))
        // Empty jobs root -> exit 0, only the marker may appear.
        jobsRoot(home).mkdirs()
        assertEquals(0, runRetention(home, maxJobs = 15))
        assertEquals(emptySet<String>(), survivingJobIds(home))
    }

    @Test fun underCapAllSurviveAndMarkerIsWritten() = withSandbox { home ->
        val ids = (1..5).map { makeCompletedJob(home, ageSeconds = it * 60L) }
        assertEquals(0, runRetention(home, maxJobs = 15))
        assertEquals(ids.toSet(), survivingJobIds(home))
        assertTrue(File(jobsRoot(home), markerName).isFile)
    }

    @Test fun overCapDeletesOldestCompletedNewestSurvive() = withSandbox { home ->
        val ids = (1..8).map { makeCompletedJob(home, ageSeconds = it * 60L) }
        assertEquals(0, runRetention(home, maxJobs = 5))
        assertEquals(ids.take(5).toSet(), survivingJobIds(home))
    }

    @Test fun protectedJobSurvivesEvenWhenOverCapOrExpired() = withSandbox { home ->
        val protected = makeCompletedJob(home, ageSeconds = 7_200L)
        val others = (1..5).map { makeCompletedJob(home, ageSeconds = it * 60L) }
        assertEquals(0, runRetention(home, maxJobs = 5, ttlSeconds = 3_600L, protectedJob = protected))
        // Protected counts toward the cap: the 4 newest others keep their slots, the oldest is culled.
        assertEquals((others.take(4) + protected).toSet(), survivingJobIds(home))
    }

    @Test fun timerNotDueSkipsPassButOverCapForcesIt() = withSandbox { home ->
        // Fresh marker + under cap: an over-TTL dir must SURVIVE (pass skipped).
        val expired = makeCompletedJob(home, ageSeconds = 900_000L) // > max TTL 604800
        writeMarker(home, ageSeconds = 0L)
        assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
        assertTrue(survivingJobIds(home).contains(expired))
        // Now add dirs until count > maxJobs, keep the fresh marker, rerun -> pass executes.
        val filler = (1..15).map { makeCompletedJob(home, ageSeconds = it * 60L) }
        assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
        assertFalse(survivingJobIds(home).contains(expired))
        assertEquals(filler.toSet(), survivingJobIds(home))
    }

    @Test fun timerDueRunsPassAndRefreshesMarker() = withSandbox { home ->
        val expired = makeCompletedJob(home, ageSeconds = 7_200L)
        writeMarker(home, ageSeconds = 600L)
        val markerFile = File(jobsRoot(home), markerName)
        val oldMarker = markerFile.readText().trim().toLong()
        assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
        assertFalse(survivingJobIds(home).contains(expired))
        assertTrue(markerFile.isFile)
        assertTrue(markerFile.readText().trim().toLong() > oldMarker)
    }

    @Test fun futureMarkerCountsAsInvalidAndForcesPass() = withSandbox { home ->
        // Clock skew: a marker ahead of `now` must not suppress TTL passes.
        val expired = makeCompletedJob(home, ageSeconds = 7_200L)
        writeMarker(home, ageSeconds = -86_400L)
        assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
        assertFalse(survivingJobIds(home).contains(expired))
    }

    @Test fun malformedMetaFallsBackToDirTimestampAndNeverCrashes() = withSandbox { home ->
        fun jobDir(ageSeconds: Long, writeMeta: (File) -> Unit): File {
            val id = UUID.randomUUID().toString()
            val dir = File(jobsRoot(home), id).apply { mkdirs() }
            File(dir, "stdout").writeText(""); File(dir, "stderr").writeText("")
            writeMeta(dir)
            dir.setLastModified((System.currentTimeMillis() / 1000 - ageSeconds) * 1_000L)
            return dir
        }
        val recentGarbage = jobDir(60L) { File(it, "meta").writeText("status=garbage\ncompleted_at=not_a_number\n") }
        val oldGarbage = jobDir(7_200L) { File(it, "meta").writeText("status=garbage\ncompleted_at=not_a_number\n") }
        val binaryJunk = jobDir(60L) { File(it, "meta").writeBytes(byteArrayOf(0, 1, 2, 3, 0x7f)) }
        val equalsStatus = jobDir(60L) {
            File(it, "meta").writeText("status=a=b\ncompleted_at=${System.currentTimeMillis() / 1000 - 60L}\n")
        }
        // Duplicate keys are invalid (old sed emitted multi-line output that failed
        // validation); a stale duplicated completed_at must NOT trigger a TTL delete.
        val duplicateKeys = jobDir(60L) {
            File(it, "meta").writeText("status=completed\ncompleted_at=1\ncompleted_at=1\n")
        }
        assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
        val survivors = survivingJobIds(home)
        assertTrue(survivors.contains(recentGarbage.name))
        assertTrue(survivors.contains(binaryJunk.name))
        assertTrue(survivors.contains(equalsStatus.name))
        assertTrue(survivors.contains(duplicateKeys.name))
        assertFalse(survivors.contains(oldGarbage.name))
    }

    @Test fun symlinkedMetaDirIsSkippedEntirely() = withSandbox { home ->
        val id = UUID.randomUUID().toString()
        val dir = File(jobsRoot(home), id).apply { mkdirs() }
        File(dir, "stdout").writeText(""); File(dir, "stderr").writeText("")
        val target = File(home, "secret-meta-target.txt").apply {
            writeText("status=completed\ncompleted_at=0\n")
            setReadable(false); setWritable(false)
        }
        Files.createSymbolicLink(File(dir, "meta").toPath(), target.toPath())
        assertEquals(0, runRetention(home, maxJobs = 1, ttlSeconds = 3_600L))
        assertTrue(survivingJobIds(home).contains(id))
    }

    @Test fun internalFailureIsContainedToExitZero() = withSandbox { home ->
        val job = makeCompletedJob(home, ageSeconds = 60L)
        val root = jobsRoot(home)
        root.setWritable(false)
        try {
            assertEquals(0, runRetention(home, maxJobs = 15, ttlSeconds = 3_600L))
            assertEquals(setOf(job), survivingJobIds(home))
        } finally {
            root.setWritable(true)
        }
    }

    // ---- helpers (copy pattern from TermuxWrite/CaptureLifecycle simulation tests) ----

    private fun jobsRoot(home: File) = File(home, jobsRel)

    /** Creates a completed job dir with valid meta; returns its job id. */
    private fun makeCompletedJob(home: File, ageSeconds: Long, status: String = "completed"): String {
        val id = UUID.randomUUID().toString()
        val dir = File(jobsRoot(home), id).apply { mkdirs() }
        File(dir, "stdout").writeText(""); File(dir, "stderr").writeText("")
        val completedAt = System.currentTimeMillis() / 1000 - ageSeconds
        File(dir, "meta").writeText(
            "version=2\njob_id=$id\nstatus=$status\nexit_code=0\ncompleted_at=$completedAt\n" +
                "stdout_bytes=0\nstderr_bytes=0\nstdout_output_limited=0\nstderr_output_limited=0\n"
        )
        return id
    }

    private fun writeMarker(home: File, ageSeconds: Long) {
        jobsRoot(home).mkdirs()
        File(jobsRoot(home), markerName).writeText("${System.currentTimeMillis() / 1000 - ageSeconds}\n")
    }

    private fun survivingJobIds(home: File): Set<String> =
        jobsRoot(home).listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()

    private fun runRetention(
        home: File,
        maxJobs: Int,
        ttlSeconds: Long = 86_400L,
        protectedJob: String = "",
    ): Int {
        val process = ProcessBuilder(
            bashExecutable(), "-c", JOB_RETENTION_LOCKED_SCRIPT, "rikka-retention",
            maxJobs.toString(), ttlSeconds.toString(), protectedJob,
        ).apply { environment()["HOME"] = home.absolutePath }.start()
        assertTrue("retention script hung", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun bashExecutable(): String =
        listOf("/bin/bash", "/data/data/com.termux/files/usr/bin/bash").firstOrNull { File(it).canExecute() }
            ?: "bash"

    private fun assumeTools() = assumeTrue(
        listOf("bash", "awk", "sort", "stat", "mktemp").all {
            ProcessBuilder("sh", "-c", "command -v $it").start().waitFor() == 0
        }
    )

    private inline fun withSandbox(block: (File) -> Unit) {
        assumeTools()
        val home = Files.createTempDirectory("termux-retention-sim-").toFile()
        try { block(home) } finally { home.deleteRecursively() }
    }
}
