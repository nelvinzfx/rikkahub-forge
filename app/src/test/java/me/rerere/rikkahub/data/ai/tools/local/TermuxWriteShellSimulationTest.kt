package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class TermuxWriteShellSimulationTest {
    private data class Result(val code: Int, val out: String, val err: String)

    @Test fun scriptsPassBashSyntaxAndCoreutilsSupportsFullDirectorySync() {
        assumeTools()
        listOf(TERMUX_TRANSFER_COMMON_SCRIPT, TERMUX_STAGE_CHUNK_SCRIPT, TERMUX_TRANSFER_CLEANUP_SCRIPT, TERMUX_ATOMIC_WRITE_SCRIPT).forEach { script ->
            assertFalse(script.toByteArray().any { it == 0.toByte() })
            val file = Files.createTempFile("termux-write-", ".sh").toFile()
            try { file.writeText(script); assertEquals(0, ProcessBuilder("/bin/bash", "-n", file.path).start().waitFor()) } finally { file.delete() }
        }
        withSandbox { home, _ -> assertEquals(0, ProcessBuilder("sync", home.absolutePath).start().waitFor()) }
    }

    @Test fun exactContentParentsModeExpectedAndAppendSemantics() = withSandbox { home, tmp ->
        val raw = ("α\u0000β\r\n" + "🙂line\r\n".repeat(12_000) + "tail").toByteArray()
        assertTrue(raw.size > TERMUX_TRANSFER_CHUNK_BYTES * 3)
        val first = transfer(home, tmp, "nested/deep/value", raw, "write")
        assertSuccess(first, "write", raw); assertArrayEquals(raw, File(home, "nested/deep/value").readBytes())
        Files.setPosixFilePermissions(File(home, "nested/deep/value").toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val oldSha = sha256Hex(raw); val replacement = "no-final".toByteArray()
        assertSuccess(transfer(home, tmp, "nested/deep/value", replacement, "write", oldSha), "write", replacement)
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(home, "nested/deep/value").toPath())))
        assertTrue(transfer(home, tmp, "nested/deep/value", "bad".toByteArray(), "write", oldSha).out.contains("error=stale_source"))
        assertTrue(transfer(home, tmp, "missing-write", byteArrayOf(), "write", EMPTY).out.contains("error=stale_source"))
        assertSuccess(transfer(home, tmp, "empty", byteArrayOf(), "write"), "write", byteArrayOf())
        assertSuccess(transfer(home, tmp, "missing-append", "x\u0000y".toByteArray(), "append", EMPTY), "append", "x\u0000y".toByteArray())
        assertSuccess(transfer(home, tmp, "missing-empty-append", byteArrayOf(), "append", EMPTY), "append", byteArrayOf())
        File(home, "append").writeText("old")
        val appended = transfer(home, tmp, "append", "+new".toByteArray(), "append", sha256Hex("old".toByteArray()))
        assertSuccess(appended, "append", "+new".toByteArray()); assertEquals("old+new", File(home, "append").readText())
    }

    @Test fun createOnlyAndConcurrentGuardedWritersHaveOneWinner() = withSandbox { home, tmp ->
        // Force the Android/F2FS fallback even on hosts where hard links work.
        File(home, "exists").writeText("keep")
        assertTrue(transfer(home, tmp, "exists", "lose".toByteArray(), "write", createOnly = true).out.contains("error=already_exists"))
        Files.createSymbolicLink(File(home, "dangling").toPath(), File(home, "nowhere").toPath())
        assertTrue(transfer(home, tmp, "dangling", "lose".toByteArray(), "write", createOnly = true).out.contains("error=symlink_rejected"))
        val forced = TERMUX_ATOMIC_WRITE_SCRIPT.replace("if ! ln -T -- \"${'$'}temp\" \"${'$'}actual_path\" 2>/dev/null; then", "if ! false; then")
        assertTrue(forced != TERMUX_ATOMIC_WRITE_SCRIPT)
        val absentId = stage(home, tmp, "fallback".toByteArray())
        val absent = runFinalScript(home, tmp, forced, absentId, "write", "forced-absent", null, true)
        assertTrue(absent.out.contains("operation=write")); assertEquals("fallback", File(home, "forced-absent").readText())
        fun racedTarget(kind: String, setup: String): Result {
            val script = forced.replace(
                "mv -nT -- \"${'$'}temp\" \"${'$'}actual_path\" || path_error publish_failed",
                "$setup\n            mv -nT -- \"${'$'}temp\" \"${'$'}actual_path\" || path_error publish_failed",
            )
            assertTrue(script != forced)
            val id = stage(home, tmp, "blocked".toByteArray())
            return runFinalScript(home, tmp, script, id, "write", "forced-$kind", null, true)
        }
        val regular = racedTarget("regular", "printf keep > \"${'$'}actual_path\"")
        assertTrue(regular.out.contains("error=already_exists")); assertEquals("keep", File(home, "forced-regular").readText())
        val symlink = racedTarget("symlink", "ln -s -- \"${'$'}HOME/redirect-target\" \"${'$'}actual_path\"")
        assertTrue(symlink.out.contains("error=already_exists")); assertTrue(Files.isSymbolicLink(File(home, "forced-symlink").toPath()))
        val directory = racedTarget("directory", "mkdir -- \"${'$'}actual_path\"")
        assertTrue(directory.out.contains("error=already_exists")); assertTrue(File(home, "forced-directory").isDirectory)
        assertTrue(File(home, "forced-directory").listFiles().orEmpty().isEmpty())
        val id1 = stage(home, tmp, "one".toByteArray()); val id2 = stage(home, tmp, "two".toByteArray())
        val results = listOf(startFinalScript(home, tmp, forced, id1, "write", "race", null, true), startFinalScript(home, tmp, forced, id2, "write", "race", null, true)).map(::finish)
        assertEquals(1, results.count { it.out.contains("operation=write") }); assertEquals(1, results.count { it.out.contains("error=already_exists") })
        assertTrue(File(home, "race").readText() in setOf("one", "two"))
        val brokenProbe = forced.replace("mv -nT -- \"${'$'}probe_src\" \"${'$'}probe_absent\" || return 1", "false || return 1")
        assertTrue(brokenProbe != forced)
        val brokenId = stage(home, tmp, "never".toByteArray())
        val broken = runFinalScript(home, tmp, brokenProbe, brokenId, "write", "probe-broken", null, true)
        assertTrue(broken.out.contains("error=noreplace_unsupported")); assertFalse(File(home, "probe-broken").exists())
        File(home, "append-race").writeText("base"); val expected = sha256Hex("base".toByteArray())
        val a = stage(home, tmp, "+A".toByteArray()); val b = stage(home, tmp, "+B".toByteArray())
        val appends = listOf(startFinal(home, tmp, a, "append", "append-race", expected, false), startFinal(home, tmp, b, "append", "append-race", expected, false)).map(::finish)
        assertEquals(1, appends.count { it.out.contains("operation=append") }); assertEquals(1, appends.count { it.out.contains("error=stale_source") })
    }

    @Test fun noContainerPublicationResistsInjectedDirectoryAndSymlinkSubstitution() = withSandbox { home, tmp ->
        val payload = "payload".toByteArray()
        fun injected(path: String, command: String): Result {
            val id = stage(home, tmp, payload)
            val needle = "if [ \"${'$'}create_only\" = 1 ]; then\n        if ! ln -T"
            val script = TERMUX_ATOMIC_WRITE_SCRIPT.replace(needle, "$command\n    $needle")
            assertTrue(script != TERMUX_ATOMIC_WRITE_SCRIPT)
            return runFinalScript(home, tmp, script, id, "write", path, null, false)
        }
        val dir = File(home, "swap-dir")
        val directory = injected("swap-dir", "mkdir -- \"${'$'}actual_path\"")
        assertTrue(directory.out.contains("error=publish_failed")); assertTrue(dir.isDirectory)
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(".rikkahub-write") }); assertFalse(File(dir, "${dir.name}").exists())
        File(home, "redirect").writeText("safe")
        val symlink = injected("swap-link", "ln -s -- \"${'$'}HOME/redirect\" \"${'$'}actual_path\"")
        assertTrue(symlink.out.contains("operation=write"))
        assertEquals("safe", File(home, "redirect").readText())
        assertFalse(Files.isSymbolicLink(File(home, "swap-link").toPath()))
        assertEquals("payload", File(home, "swap-link").readText())
    }

    @Test fun chmodAndSameSecondMutationAfterOpenFailSourceChanged() = withSandbox { home, tmp ->
        fun mutate(command: String): Result {
            File(home, "source").writeText("same")
            val id = stage(home, tmp, "new".toByteArray())
            val needle = "# Closest possible pre-publication recheck."
            val script = TERMUX_ATOMIC_WRITE_SCRIPT.replace(needle, "$command\n    $needle")
            return runFinalScript(home, tmp, script, id, "write", "source", sha256Hex("same".toByteArray()), false)
        }
        assertTrue(mutate("chmod 400 -- \"${'$'}actual_path\"").out.contains("error=source_changed"))
        assertTrue(mutate("printf SAME > \"${'$'}actual_path\"; touch -d @${'$'}(date +%s) \"${'$'}actual_path\"").out.contains("error=source_changed"))
    }

    @Test fun transferRejectsMalformedOrderDuplicateUnknownAndSymlinkStateAndCleansTtl() = withSandbox { home, tmp ->
        val raw = ByteArray(TERMUX_TRANSFER_CHUNK_BYTES + 1) { 7 }; val sha = sha256Hex(raw); val id = UUID.randomUUID().toString()
        assertTrue(run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, "1", "2", raw.size.toString(), sha, b64(byteArrayOf(7))).out.contains("error=missing_transfer"))
        val first = raw.copyOfRange(0, TERMUX_TRANSFER_CHUNK_BYTES)
        assertTrue(run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, "0", "2", raw.size.toString(), sha, b64(first)).out.contains("index=0"))
        assertTrue(run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, "0", "2", raw.size.toString(), sha, b64(first)).out.contains("error=duplicate_chunk"))
        assertTrue(run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, UUID.randomUUID().toString(), "0", "1", "1", sha256Hex(byteArrayOf(1)), "AQ").out.contains("error=invalid_chunk_base64"))
        File(home, ".cache/rikkahub/transfers/$id/unknown").writeText("x")
        assertTrue(run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, "1", "2", raw.size.toString(), sha, b64(byteArrayOf(7))).out.contains("error=unsafe_transfer_state"))
        val symlinkId = stage(home, tmp, ByteArray(TERMUX_TRANSFER_CHUNK_BYTES + 1) { 1 })
        val dir = File(home, ".cache/rikkahub/transfers/$symlinkId"); File(dir, "payload").delete()
        Files.createSymbolicLink(File(dir, "payload").toPath(), File(home, "redirect").toPath())
        val secondChunk = run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, symlinkId, "1", "2", "${TERMUX_TRANSFER_CHUNK_BYTES + 1}", sha256Hex(ByteArray(TERMUX_TRANSFER_CHUNK_BYTES + 1) { 1 }), "AQ==")
        assertTrue(secondChunk.out.contains("error=unsafe_transfer_state"))
        val oldId = UUID.randomUUID().toString(); val old = File(home, ".cache/rikkahub/transfers/$oldId"); old.mkdirs(); old.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        stage(home, tmp, "fresh".toByteArray()); assertFalse(old.exists())
    }

    @Test fun upstreamAndFullSyncFailuresAreNotMaskedAndCleanupIsComplete() = withSandbox { home, tmp ->
        fun injected(needle: String, replacement: String, path: String, initial: String? = null): Result {
            initial?.let { File(home, path).writeText(it) }
            val id = stage(home, tmp, "payload".toByteArray()); val script = TERMUX_ATOMIC_WRITE_SCRIPT.replace(needle, replacement)
            assertTrue(script != TERMUX_ATOMIC_WRITE_SCRIPT)
            val result = runFinalScript(home, tmp, script, id, "write", path, initial?.let { sha256Hex(it.toByteArray()) }, false)
            assertFalse(File(home, ".cache/rikkahub/transfers/$id").exists())
            assertTrue(File(home).walkTopDown().none { it.name.startsWith(".rikkahub-write.$id.") })
            return result
        }
        val pre = injected("sync \"${'$'}temp\" || path_error data_sync_failed", "false || path_error data_sync_failed", "pre-sync", "old")
        assertTrue(pre.out.contains("error=data_sync_failed")); assertEquals("old", File(home, "pre-sync").readText())
        val post = injected("sync \"${'$'}actual_path\" || path_error post_publish_sync_failed", "false || path_error post_publish_sync_failed", "post-sync", "old")
        assertTrue(post.out.contains("error=post_publish_sync_failed")); assertEquals("payload", File(home, "post-sync").readText())
        val parentPost = injected("sync \"${'$'}parent\" || path_error post_publish_sync_failed", "false || path_error post_publish_sync_failed", "parent-post-sync", "old")
        assertTrue(parentPost.out.contains("error=post_publish_sync_failed")); assertEquals("payload", File(home, "parent-post-sync").readText())
        val catFailure = TERMUX_STAGE_CHUNK_SCRIPT.replace("cat -- \"${'$'}chunk_tmp\" >>", "false | cat -- \"${'$'}chunk_tmp\" >>")
        val result = run(home, tmp, catFailure, UUID.randomUUID().toString(), "0", "1", "1", sha256Hex(byteArrayOf(1)), "AQ==")
        assertTrue(result.code != 0)
    }

    private fun assertSuccess(result: Result, operation: String, content: ByteArray) {
        assertEquals("${result.out}\n${result.err}", 0, result.code); assertTrue(result.out.contains("operation=$operation"))
        assertTrue(result.out.contains("content_sha256=${sha256Hex(content)}")); assertTrue(result.out.contains("bytes_written=${content.size}"))
    }
    private fun transfer(home: File, tmp: File, path: String, raw: ByteArray, operation: String, expected: String? = null, createOnly: Boolean = false): Result =
        runFinalScript(home, tmp, TERMUX_ATOMIC_WRITE_SCRIPT, stage(home, tmp, raw), operation, path, expected, createOnly)
    private fun stage(home: File, tmp: File, raw: ByteArray): String {
        val id = UUID.randomUUID().toString(); val count = maxOf(1, (raw.size + TERMUX_TRANSFER_CHUNK_BYTES - 1) / TERMUX_TRANSFER_CHUNK_BYTES); val sha = sha256Hex(raw)
        repeat(count) { index ->
            val from = index * TERMUX_TRANSFER_CHUNK_BYTES; val to = minOf(raw.size, from + TERMUX_TRANSFER_CHUNK_BYTES)
            val result = run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, "$index", "$count", "${raw.size}", sha, b64(raw.copyOfRange(from, to)))
            assertEquals("${result.out} ${result.err}", 0, result.code); assertTrue(result.out.contains("index=$index"))
        }
        return id
    }
    private fun startFinalScript(home: File, tmp: File, script: String, id: String, operation: String, path: String, expected: String?, createOnly: Boolean): Process =
        process(home, tmp, script, id, operation, b64(path), expected ?: "-", if (createOnly) "1" else "0", sha256Hex(path.toByteArray())).start()
    private fun startFinal(home: File, tmp: File, id: String, operation: String, path: String, expected: String?, createOnly: Boolean): Process =
        process(home, tmp, TERMUX_ATOMIC_WRITE_SCRIPT, id, operation, b64(path), expected ?: "-", if (createOnly) "1" else "0", sha256Hex(path.toByteArray())).start()
    private fun runFinalScript(home: File, tmp: File, script: String, id: String, operation: String, path: String, expected: String?, createOnly: Boolean): Result =
        run(home, tmp, script, id, operation, b64(path), expected ?: "-", if (createOnly) "1" else "0", sha256Hex(path.toByteArray()))
    private fun finish(process: Process): Result { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); return Result(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText()) }
    private fun run(home: File, tmp: File, script: String, vararg args: String): Result = finish(process(home, tmp, script, *args).start())
    private fun process(home: File, tmp: File, script: String, vararg args: String) = ProcessBuilder("/bin/bash", "-c", script, "rikka-test", *args).apply { environment()["HOME"] = home.absolutePath; environment()["TMPDIR"] = tmp.absolutePath }
    private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)
    private fun b64(text: String) = b64(text.toByteArray())
    private fun assumeTools() = assumeTrue(listOf("bash", "base64", "sha256sum", "flock", "realpath", "sync", "ln", "mv").all { command -> ProcessBuilder("sh", "-c", "command -v $command").start().waitFor() == 0 })
    private inline fun withSandbox(block: (File, File) -> Unit) { assumeTools(); val root = Files.createTempDirectory("termux-write-sim-").toFile(); val home = File(root, "home"); val tmp = File(root, "tmp"); home.mkdir(); tmp.mkdir(); try { block(home, tmp) } finally { root.deleteRecursively() } }
    private companion object { const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }
}
