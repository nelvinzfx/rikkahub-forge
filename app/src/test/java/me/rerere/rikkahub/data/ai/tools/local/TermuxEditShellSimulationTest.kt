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

class TermuxEditShellSimulationTest {
    private data class Result(val code: Int, val out: String, val err: String)
    private data class Snapshot(val id: String, val actual: List<String>, val sha: List<String>, val mode: List<String>, val identity: List<String>)

    @Test fun scriptsPassBashSyntaxAndSnapshotPreservesEmptyFiles() = withSandbox { home, tmp ->
        listOf(TERMUX_EDIT_SNAPSHOT_SCRIPT, TERMUX_EDIT_READ_SNAPSHOT_SCRIPT, TERMUX_EDIT_PUBLISH_SCRIPT).forEach { script ->
            val file = Files.createTempFile("termux-edit-", ".sh").toFile()
            try { file.writeText(script); assertEquals(0, ProcessBuilder("/bin/bash", "-n", file.path).start().waitFor()) } finally { file.delete() }
        }
        val empty = File(home, "empty").apply { writeBytes(byteArrayOf()) }
        val snap = snapshot(home, tmp, listOf(empty), listOf(null))
        assertEquals(sha256Hex(byteArrayOf()), snap.sha.single())
        val chunk = run(home, tmp, TERMUX_EDIT_READ_SNAPSHOT_SCRIPT, snap.id, "0", "0", "0")
        val chunkFields = chunk.out.removeSuffix("\n").lineSequence().drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(setOf("request_id", "index", "offset", "length", "data_b64"), chunkFields.keys)
        assertEquals(snap.id, chunkFields["request_id"]); assertEquals("0", chunkFields["index"])
        assertEquals("0", chunkFields["offset"]); assertEquals("0", chunkFields["length"]); assertEquals("", chunkFields["data_b64"])
        cleanup(home, tmp, snap.id); assertFalse(transfer(home, snap.id).exists())
    }

    @Test fun successfulBatchPreservesModeBomCrlfAndCleansEveryTransfer() = withSandbox { home, tmp ->
        val first = File(home, "first"); first.writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\r\nb\r\n".toByteArray())
        val second = File(home, "second").apply { writeText("old") }
        Files.setPosixFilePermissions(first.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val snap = snapshot(home, tmp, listOf(first, second), listOf(null, null))
        val one = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\r\ntail".toByteArray(); val two = "new".toByteArray()
        val stageOne = stage(home, tmp, one); val stageTwo = stage(home, tmp, two)
        val result = publish(home, tmp, snap, listOf(stageOne to one, stageTwo to two))
        val items = protocolItems(result.out)
        assertEquals(listOf("published", "published"), items.map { it[4] })
        assertArrayEquals(one, first.readBytes()); assertArrayEquals(two, second.readBytes())
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(first.toPath())))
        listOf(snap.id, stageOne, stageTwo).forEach { cleanup(home, tmp, it); assertFalse(transfer(home, it).exists()) }
    }

    @Test fun snapshotRejectsHardLinksParentSymlinkAliasesDirectoriesAndSymlinksWithCleanup() = withSandbox { home, tmp ->
        val original = File(home, "original").apply { writeText("same") }
        val hard = File(home, "hard"); Files.createLink(hard.toPath(), original.toPath())
        val hardResult = snapshotRaw(home, tmp, listOf(original, hard), listOf(null, null))
        assertEquals(0, hardResult.code)
        assertEquals("duplicate_identity", protocolError(hardResult.out, "RIKKAHUB_EDIT_SNAPSHOT_V1"))
        assertFalse(transferDirs(home).any())

        val realParent = File(home, "real-parent").apply { mkdir() }
        val child = File(realParent, "child").apply { writeText("same") }
        val parentAlias = File(home, "parent-alias")
        Files.createSymbolicLink(parentAlias.toPath(), realParent.toPath())
        val aliasResult = snapshotRaw(home, tmp, listOf(child, File(parentAlias, "child")), listOf(null, null))
        assertEquals("duplicate_identity", protocolError(aliasResult.out, "RIKKAHUB_EDIT_SNAPSHOT_V1"))
        assertFalse(transferDirs(home).any())

        val directSymlink = File(home, "direct-link")
        Files.createSymbolicLink(directSymlink.toPath(), child.toPath())
        assertEquals("symlink_rejected", protocolError(snapshotRaw(home, tmp, listOf(directSymlink), listOf(null)).out, "RIKKAHUB_EDIT_SNAPSHOT_V1"))
        assertEquals("not_regular_file", protocolError(snapshotRaw(home, tmp, listOf(realParent), listOf(null)).out, "RIKKAHUB_EDIT_SNAPSHOT_V1"))
        assertFalse(transferDirs(home).any())
    }

    @Test fun trailingNewlineFilenamePublishesAndRollsBackExactly() = withSandbox { home, tmp ->
        val first = File(home, "trailing-name\n").apply { writeText("old-one") }
        val second = File(home, "second").apply { writeText("old-two") }
        val snap = snapshot(home, tmp, listOf(first, second), listOf(null, null))
        assertEquals(first.absolutePath, snap.actual[0])
        val one = "new-one".toByteArray()
        val two = "new-two".toByteArray()
        val stageOne = stage(home, tmp, one)
        val stageTwo = stage(home, tmp, two)
        val failNeedle = "if ! mv -Tf -- \"${'$'}temp\" \"${'$'}actual_path\"; then publish_error=publish_failed; break; fi"
        val injected = TERMUX_EDIT_PUBLISH_SCRIPT.replace(
            failNeedle,
            "if [ \"${'$'}i\" -eq 1 ]; then publish_error=publish_failed; break; fi\n            $failNeedle",
        )
        assertTrue(injected != TERMUX_EDIT_PUBLISH_SCRIPT)
        val result = publish(home, tmp, snap, listOf(stageOne to one, stageTwo to two), injected)
        assertEquals("publish_failed", protocolError(result.out, "RIKKAHUB_EDIT_PUBLISH_V1"))
        assertEquals(listOf("rolled_back", "not_published"), protocolItems(result.out).map { it[4] })
        assertEquals("old-one", first.readText())
        assertEquals("old-two", second.readText())
        listOf(snap.id, stageOne, stageTwo).forEach { cleanup(home, tmp, it); assertFalse(transfer(home, it).exists()) }
    }

    @Test fun publicationPreparationFailureCleansCurrentTemp() = withSandbox { home, tmp ->
        val file = File(home, "prep-failure").apply { writeText("old") }
        val snap = snapshot(home, tmp, listOf(file), listOf(null))
        val bytes = "new".toByteArray()
        val stage = stage(home, tmp, bytes)
        val needle = "cat -- \"${'$'}stage\" > \"${'$'}temp\" || bare_fail copy_failed"
        val injected = TERMUX_EDIT_PUBLISH_SCRIPT.replace(needle, "false || bare_fail copy_failed")
        assertTrue(injected != TERMUX_EDIT_PUBLISH_SCRIPT)
        val result = publish(home, tmp, snap, listOf(stage to bytes), injected)
        assertEquals("copy_failed", protocolError(result.out, "RIKKAHUB_EDIT_PUBLISH_V1"))
        assertEquals("old", file.readText())
        assertFalse(home.walkTopDown().any { it.name.startsWith(".rikkahub-edit.") })
        listOf(snap.id, stage).forEach { cleanup(home, tmp, it) }
    }

    @Test fun staleGuardAndSourceRaceFailBeforePublication() = withSandbox { home, tmp ->
        val file = File(home, "source").apply { writeText("old") }
        val stale = snapshotRaw(home, tmp, listOf(file), listOf("0".repeat(64)))
        assertEquals("stale_source", protocolError(stale.out, "RIKKAHUB_EDIT_SNAPSHOT_V1")); assertFalse(transferDirs(home).any())

        val snap = snapshot(home, tmp, listOf(file), listOf(null)); val stage = stage(home, tmp, "new".toByteArray())
        file.writeText("raced")
        val raced = publish(home, tmp, snap, listOf(stage to "new".toByteArray()))
        assertEquals("source_changed", protocolError(raced.out, "RIKKAHUB_EDIT_PUBLISH_V1")); assertEquals("raced", file.readText())
        listOf(snap.id, stage).forEach { cleanup(home, tmp, it) }
    }

    @Test fun secondPublicationFailureRollsBackFirstWithAccurateIndexAndCleanup() = withSandbox { home, tmp ->
        val first = File(home, "first").apply { writeText("one") }; val second = File(home, "second").apply { writeText("two") }
        val snap = snapshot(home, tmp, listOf(first, second), listOf(null, null)); val one = "ONE".toByteArray(); val two = "TWO".toByteArray()
        val stageOne = stage(home, tmp, one); val stageTwo = stage(home, tmp, two)
        val needle = "if ! mv -Tf -- \"${'$'}temp\" \"${'$'}actual_path\"; then publish_error=publish_failed; break; fi"
        val injected = TERMUX_EDIT_PUBLISH_SCRIPT.replace(needle, "if [ \"${'$'}i\" -eq 1 ]; then publish_error=publish_failed; break; fi\n            $needle")
        assertTrue(injected != TERMUX_EDIT_PUBLISH_SCRIPT)
        val result = publish(home, tmp, snap, listOf(stageOne to one, stageTwo to two), injected)
        assertEquals("publish_failed", protocolError(result.out, "RIKKAHUB_EDIT_PUBLISH_V1"))
        assertEquals(listOf("rolled_back", "not_published"), protocolItems(result.out).map { it[4] })
        assertEquals("one", first.readText()); assertEquals("two", second.readText())
        listOf(snap.id, stageOne, stageTwo).forEach { cleanup(home, tmp, it); assertFalse(transfer(home, it).exists()) }
    }

    @Test fun rollbackRaceLeavesInterveningUpdateUntouchedAndCleansTemporary() = withSandbox { home, tmp ->
        val first = File(home, "first-race").apply { writeText("one") }
        val second = File(home, "second-race").apply { writeText("two") }
        val snap = snapshot(home, tmp, listOf(first, second), listOf(null, null))
        val one = "ONE".toByteArray(); val two = "TWO".toByteArray()
        val stageOne = stage(home, tmp, one); val stageTwo = stage(home, tmp, two)
        val failNeedle = "if ! mv -Tf -- \"${'$'}temp\" \"${'$'}actual_path\"; then publish_error=publish_failed; break; fi"
        val rollbackNeedle = "rollback_temp=${'$'}(mktemp \"${'$'}parent/.rikkahub-rollback.${'$'}request_id.XXXXXXXX\")"
        var injected = TERMUX_EDIT_PUBLISH_SCRIPT.replace(
            failNeedle,
            "if [ \"${'$'}i\" -eq 1 ]; then publish_error=publish_failed; break; fi\n            $failNeedle",
        )
        injected = injected.replace(
            rollbackNeedle,
            "replacement=${'$'}(mktemp \"${'$'}parent/.external-replacement.XXXXXXXX\")\n                printf '%s' ONE > \"${'$'}replacement\"\n                chmod \"${'$'}{modes[${'$'}j]}\" -- \"${'$'}replacement\"\n                mv -Tf -- \"${'$'}replacement\" \"${'$'}actual_path\"\n                $rollbackNeedle",
        )
        assertTrue(injected != TERMUX_EDIT_PUBLISH_SCRIPT)
        val result = publish(home, tmp, snap, listOf(stageOne to one, stageTwo to two), injected)
        assertEquals("publish_failed", protocolError(result.out, "RIKKAHUB_EDIT_PUBLISH_V1"))
        val items = protocolItems(result.out)
        assertEquals("rollback_failed", items[0][4])
        assertEquals("not_published", items[1][4])
        assertEquals("ONE", first.readText())
        assertEquals("two", second.readText())
        assertFalse(home.walkTopDown().any { it.name.startsWith(".rikkahub-rollback.") })


        val postRenameNeedle = "mv -Tf -- \"${'$'}rollback_temp\" \"${'$'}actual_path\" && { rollback_temp=; true; }"
        var postRenameInjected = TERMUX_EDIT_PUBLISH_SCRIPT.replace(
            failNeedle,
            "if [ \"${'$'}i\" -eq 1 ]; then publish_error=publish_failed; break; fi\n            $failNeedle",
        )
        postRenameInjected = postRenameInjected.replace(
            postRenameNeedle,
            "$postRenameNeedle && replacement=${'$'}(mktemp \"${'$'}parent/.external-post-rename.XXXXXXXX\") && printf '%s' one > \"${'$'}replacement\" && chmod \"${'$'}{modes[${'$'}j]}\" -- \"${'$'}replacement\" && mv -Tf -- \"${'$'}replacement\" \"${'$'}actual_path\"",
        )
        assertTrue(postRenameInjected != TERMUX_EDIT_PUBLISH_SCRIPT)
        first.writeText("one")
        second.writeText("two")
        val postSnap = snapshot(home, tmp, listOf(first, second), listOf(null, null))
        val postStageOne = stage(home, tmp, one)
        val postStageTwo = stage(home, tmp, two)
        val postResult = publish(home, tmp, postSnap, listOf(postStageOne to one, postStageTwo to two), postRenameInjected)
        assertEquals("rollback_failed", protocolItems(postResult.out)[0][4])
        assertEquals("one", first.readText())
        listOf(postSnap.id, postStageOne, postStageTwo).forEach { cleanup(home, tmp, it) }
        listOf(snap.id, stageOne, stageTwo).forEach { cleanup(home, tmp, it) }
    }

    @Test fun postPublishSyncFailureAlsoRollsBack() = withSandbox { home, tmp ->
        val file = File(home, "sync").apply { writeText("old") }; val snap = snapshot(home, tmp, listOf(file), listOf(null)); val bytes = "new".toByteArray(); val stage = stage(home, tmp, bytes)
        val injected = TERMUX_EDIT_PUBLISH_SCRIPT.replace("if ! sync \"${'$'}actual_path\" || ! sync \"${'$'}parent\"; then", "if ! false || ! sync \"${'$'}parent\"; then")
        val result = publish(home, tmp, snap, listOf(stage to bytes), injected)
        assertEquals("post_publish_sync_failed", protocolError(result.out, "RIKKAHUB_EDIT_PUBLISH_V1"))
        assertEquals("rolled_back", protocolItems(result.out).single()[4]); assertEquals("old", file.readText())
        listOf(snap.id, stage).forEach { cleanup(home, tmp, it) }
    }

    private fun protocolError(output: String, marker: String): String {
        val lines = output.removeSuffix("\n").split('\n')
        assertEquals(marker, lines.first())
        val errors = lines.filter { it.startsWith("error=") }
        assertEquals(1, errors.size)
        return errors.single().substringAfter('=')
    }
    private fun protocolItems(output: String): List<List<String>> = output.lineSequence()
        .filter { it.startsWith("item=") }.map { it.substringAfter('=').split(',', limit = 5) }.toList()
    private fun snapshot(home: File, tmp: File, files: List<File>, expected: List<String?>): Snapshot {
        val result = snapshotRaw(home, tmp, files, expected); assertEquals(result.err, 0, result.code)
        assertFalse(result.out.lineSequence().any { it.startsWith("error=") })
        val id = result.out.lineSequence().first { it.startsWith("request_id=") }.substringAfter('=')
        val items = result.out.lineSequence().filter { it.startsWith("item=") }.map { it.substringAfter('=').split(',', limit = 7) }.toList()
        return Snapshot(id, items.map { String(Base64.getDecoder().decode(it[1])) }, items.map { it[3] }, items.map { it[4] }, items.map { it[5] })
    }
    private fun snapshotRaw(home: File, tmp: File, files: List<File>, expected: List<String?>): Result {
        val id = UUID.randomUUID().toString(); val args = mutableListOf(id, files.size.toString())
        files.forEachIndexed { index, file -> val path = file.absolutePath.toByteArray(); args += b64(path); args += expected[index] ?: "-"; args += sha256Hex(path) }
        return run(home, tmp, TERMUX_EDIT_SNAPSHOT_SCRIPT, *args.toTypedArray())
    }
    private fun publish(home: File, tmp: File, snap: Snapshot, staged: List<Pair<String, ByteArray>>, script: String = TERMUX_EDIT_PUBLISH_SCRIPT): Result {
        val args = mutableListOf(snap.id, snap.actual.size.toString())
        snap.actual.indices.forEach { i -> val path = snap.actual[i].toByteArray(); args += b64(path); args += sha256Hex(path); args += snap.sha[i]; args += snap.mode[i]; args += snap.identity[i]; args += staged[i].first; args += sha256Hex(staged[i].second); args += "1" }
        return run(home, tmp, script, *args.toTypedArray())
    }
    private fun stage(home: File, tmp: File, raw: ByteArray): String {
        val id = UUID.randomUUID().toString(); val chunks = splitTermuxTransferBytes(raw)
        chunks.forEach { chunk ->
            val result = run(home, tmp, TERMUX_STAGE_CHUNK_SCRIPT, id, chunk.index.toString(), chunk.count.toString(), chunk.totalBytes.toString(), chunk.fullSha256, chunk.canonicalBase64)
            assertEquals(result.err, 0, result.code)
            val fields = result.out.removeSuffix("\n").lineSequence().drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(setOf("request_id", "index"), fields.keys)
            assertEquals(id, fields["request_id"]); assertEquals(chunk.index.toString(), fields["index"])
        }
        return id
    }
    private fun cleanup(home: File, tmp: File, id: String) { run(home, tmp, TERMUX_TRANSFER_CLEANUP_SCRIPT, id) }
    private fun transfer(home: File, id: String) = File(home, ".cache/rikkahub/transfers/$id")
    private fun transferDirs(home: File) = File(home, ".cache/rikkahub/transfers").listFiles().orEmpty().asSequence()
    private fun run(home: File, tmp: File, script: String, vararg args: String): Result { val p = ProcessBuilder("/bin/bash", "-c", script, "rikka-test", *args).apply { environment()["HOME"] = home.absolutePath; environment()["TMPDIR"] = tmp.absolutePath }.start(); assertTrue(p.waitFor(30, TimeUnit.SECONDS)); return Result(p.exitValue(), p.inputStream.bufferedReader().readText(), p.errorStream.bufferedReader().readText()) }
    private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)
    private fun assumeTools() = assumeTrue(listOf("bash", "base64", "sha256sum", "flock", "realpath", "sync", "mv", "stat").all { ProcessBuilder("sh", "-c", "command -v $it").start().waitFor() == 0 })
    private inline fun withSandbox(block: (File, File) -> Unit) { assumeTools(); val root = Files.createTempDirectory(File(System.getProperty("user.home")).toPath(), "termux-edit-sim-").toFile(); val home = File(root, "home"); val tmp = File(root, "tmp"); home.mkdir(); tmp.mkdir(); try { block(home, tmp) } finally { root.deleteRecursively() } }
}
