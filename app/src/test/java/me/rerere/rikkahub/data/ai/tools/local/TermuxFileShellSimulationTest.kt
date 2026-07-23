package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

class TermuxFileShellSimulationTest {
    @Test fun extractedScriptsPassBashSyntaxAndContainNoNul() {
        assumeTools()
        listOf(TERMUX_PATH_RESOLVER_SCRIPT, TERMUX_FILE_COMMON_SCRIPT, TERMUX_TEXT_READ_SCRIPT, TERMUX_BYTE_READ_SCRIPT).forEach { script ->
            assertTrue(script.toByteArray().none { it == 0.toByte() })
            val file = kotlin.io.path.createTempFile("termux-file-", ".sh").toFile()
            try { file.writeText(script); assertEquals(0, ProcessBuilder("/bin/bash", "-n", file.path).start().waitFor()) } finally { file.delete() }
        }
    }

    @Test fun resolverPreservesTrailingNewlinesAndRejectsMalformedBase64AndNul() {
        assumeTools()
        withSandbox { home, tmp ->
            fun resolveEncoded(encoded: String) = runScript(
                TERMUX_PATH_RESOLVER_SCRIPT + "\nresolve_termux_path \"${'$'}1\"; rc=${'$'}?; [ ${'$'}rc -eq 0 ] && printf '%s' \"${'$'}actual_path\"; exit ${'$'}rc",
                home, tmp, encoded,
            )
            assertEquals(File(home, "relative").absolutePath, resolveEncoded(b64("relative")).out)
            assertEquals(File(home, "tail\n\n").absolutePath, resolveEncoded(b64("tail\n\n")).out)
            assertEquals(68, resolveEncoded(b64("/tmp/../escape")).code)
            assertEquals(65, resolveEncoded("YQ").code)
            assertEquals(65, resolveEncoded(Base64.getEncoder().encodeToString(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))).code)
        }
    }

    @Test fun textPaginationCoversEmptyExactLinesCapsAndLimits() {
        assumeTools()
        withSandbox { home, tmp ->
            File(home, "empty").writeBytes(byteArrayOf())
            File(home, "crlf").writeBytes("a\r\nb\r\n".toByteArray())
            File(home, "no-final").writeBytes("a\nb".toByteArray())
            File(home, "three").writeBytes("a\nb\nc\n".toByteArray())
            File(home, "later-cap").writeBytes("ok\n123456789\nz\n".toByteArray())
            File(home, "nul-text").writeBytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte(), '\n'.code.toByte()))
            File(home, "huge").writeBytes(("x".repeat(20) + "\nok\n").toByteArray())
            File(home, "huge-first").outputStream().buffered().use { out -> repeat(3 * 1024 * 1024) { out.write('x'.code) }; out.write('\n'.code) }
            File(home, "huge-later").outputStream().buffered().use { out -> out.write("ok\n".toByteArray()); repeat(3 * 1024 * 1024) { out.write('y'.code) }; out.write('\n'.code) }

            val empty = text(home, tmp, "empty", 1, 2, 100)
            assertTrue(empty.out.contains("start_line=1\nend_line=0\ntotal_lines=0\ntruncated=0\nnext_offset="))
            assertTrue(empty.out.contains("sha256=${sha256Hex(byteArrayOf())}"))
            assertTrue(text(home, tmp, "empty", 2, 2, 100).out.contains("error=offset_beyond_eof"))
            assertTrue(text(home, tmp, "crlf", 1, 2, 100).out.contains("content_b64=${b64("a\r\nb\r\n")}"))
            assertTrue(text(home, tmp, "no-final", 2, 1, 100).out.contains("content_b64=${b64("b")}"))
            val limited = text(home, tmp, "three", 1, 1, 100).out
            assertTrue(limited.contains("end_line=1\ntotal_lines=3\ntruncated=1\nnext_offset=2"))
            assertTrue(limited.contains("content_b64=${b64("a\n")}"))
            val cap = text(home, tmp, "later-cap", 1, 3, 5).out
            assertTrue(cap.contains("end_line=1\ntotal_lines=3\ntruncated=1\nnext_offset=2"))
            assertTrue(cap.contains("content_b64=${b64("ok\n")}"))
            assertTrue(text(home, tmp, "huge", 1, 2, 10).out.contains("error=line_too_large"))
            val hugeFirst = text(home, tmp, "huge-first", 1, 1, 1024).out
            assertTrue(hugeFirst.contains("error=line_too_large")); assertTrue(hugeFirst.length < 20_000)
            val hugeLater = text(home, tmp, "huge-later", 1, 2, 1024).out
            assertTrue(hugeLater.contains("end_line=1\ntotal_lines=2\ntruncated=1\nnext_offset=2")); assertTrue(hugeLater.contains("content_b64=${b64("ok\n")}")); assertTrue(hugeLater.length < 20_000)
            assertTrue(text(home, tmp, "nul-text", 1, 1, 100).out.contains("content_b64=${Base64.getEncoder().encodeToString(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte(), '\n'.code.toByte()))}"))
            assertTrue(text(home, tmp, "three", 3, 1, 100).out.contains("truncated=0\nnext_offset="))
        }
    }

    @Test fun bytesMissingDirectorySymlinkAndStableOpenedInode() {
        assumeTools()
        withSandbox { home, tmp ->
            val raw = byteArrayOf(0,1,2,3,-1)
            File(home, "bin").writeBytes(raw)
            File(home, "dir").mkdir()
            java.nio.file.Files.createSymbolicLink(File(home, "link").toPath(), File(home, "bin").toPath())
            val page = bytes(home, tmp, "bin", 1, 3).out
            assertTrue(page.contains("offset=1\nactual_length=3\ntotal_bytes=5")); assertTrue(page.contains("data_b64=${Base64.getEncoder().encodeToString(raw.copyOfRange(1,4))}")); assertTrue(page.contains("sha256=${sha256Hex(raw)}"))
            assertTrue(bytes(home, tmp, "bin", 99, 3).out.contains("offset=5\nactual_length=0\ntotal_bytes=5"))
            assertTrue(bytes(home, tmp, "missing", 0, 1).out.contains("error=not_found"))
            assertTrue(bytes(home, tmp, "dir", 0, 1).out.contains("error=not_regular_file"))
            assertTrue(bytes(home, tmp, "link", 0, 5).out.contains("data_b64=${Base64.getEncoder().encodeToString(raw)}"))

            File(home, "old").writeText("opened")
            val replaceNeedle = "total=${'$'}(wc -c < \"${'$'}fd_path\" | tr -d ' ')"
            val replaceScript = TERMUX_BYTE_READ_SCRIPT.replace(
                replaceNeedle,
                "mv -- \"${'$'}actual_path\" \"${'$'}actual_path.old\"; printf replacement > \"${'$'}actual_path\"\n$replaceNeedle",
            )
            assertTrue(replaceScript != TERMUX_BYTE_READ_SCRIPT)
            val result = runScript(replaceScript, home, tmp, UUID.randomUUID().toString(), b64("old"), "0", "6")
            assertTrue(result.out.contains("data_b64=${b64("opened")}")); assertTrue(!result.out.contains("error=source_changed"))
            File(home, "target-a").writeText("target")
            File(home, "target-b").writeText("swapped")
            java.nio.file.Files.createSymbolicLink(File(home, "swap-link").toPath(), File(home, "target-a").toPath())
            val swapScript = TERMUX_BYTE_READ_SCRIPT.replace(
                replaceNeedle,
                "rm -- \"${'$'}actual_path\"; ln -s \"${'$'}HOME/target-b\" \"${'$'}actual_path\"\n$replaceNeedle",
            )
            assertTrue(swapScript != TERMUX_BYTE_READ_SCRIPT)
            val swapped = runScript(swapScript, home, tmp, UUID.randomUUID().toString(), b64("swap-link"), "0", "6")
            assertTrue(swapped.out.contains("data_b64=${b64("target")}")); assertTrue(!swapped.out.contains("error=source_changed"))
        }
    }

    @Test fun upstreamPipelineFailuresAreNotMasked() {
        assumeTools()
        withSandbox { home, tmp ->
            File(home, "data").writeText("one\ntwo\n")
            val odFailure = TERMUX_TEXT_READ_SCRIPT.replace(
                "od -An -v -t u1 \"${'$'}fd_path\" | awk '",
                "false | awk '",
            )
            assertTrue(odFailure != TERMUX_TEXT_READ_SCRIPT)
            val odResult = runScript(
                odFailure,
                home,
                tmp,
                UUID.randomUUID().toString(),
                b64("data"),
                "1",
                "1",
                "100",
            )
            assertTrue("od failure was masked: ${'$'}{odResult.out}", odResult.code != 0)

            val shaFailure = TERMUX_BYTE_READ_SCRIPT.replace(
                "sha_before=${'$'}(sha256sum -- \"${'$'}fd_path\" | cut -d' ' -f1)",
                "sha_before=${'$'}(false | cut -d' ' -f1)",
            )
            assertTrue(shaFailure != TERMUX_BYTE_READ_SCRIPT)
            val shaResult = runScript(
                shaFailure,
                home,
                tmp,
                UUID.randomUUID().toString(),
                b64("data"),
                "0",
                "4",
            )
            assertTrue("sha256sum failure was masked: ${'$'}{shaResult.out}", shaResult.code != 0)
        }
    }

    private fun text(home: File, tmp: File, path: String, offset: Long, limit: Int, cap: Int) =
        runScript(TERMUX_TEXT_READ_SCRIPT, home, tmp, UUID.randomUUID().toString(), b64(path), offset.toString(), limit.toString(), cap.toString())
    private fun bytes(home: File, tmp: File, path: String, offset: Long, length: Int) =
        runScript(TERMUX_BYTE_READ_SCRIPT, home, tmp, UUID.randomUUID().toString(), b64(path), offset.toString(), length.toString())

    private fun assumeTools() {
        assumeTrue(File("/bin/bash").canExecute())
        listOf("base64", "realpath", "mktemp", "stat", "sha256sum", "awk", "dd", "od").forEach {
            assumeTrue(ProcessBuilder("sh", "-c", "command -v $it >/dev/null").start().waitFor() == 0)
        }
        val version = ProcessBuilder("realpath", "--help").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assumeTrue(version.contains("-z") || version.contains("--zero"))
    }
    private fun withSandbox(block: (File, File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("termux-file-sim-").toFile(); val home = File(root,"home").apply{mkdir()}; val tmp = File(root,"tmp").apply{mkdir()}
        try { block(home,tmp) } finally { root.deleteRecursively() }
    }
    private fun runScript(script: String, home: File, tmp: File, vararg args: String): ProcessResult {
        val process = ProcessBuilder(listOf("/bin/bash","-c",script,"simulation") + args).apply {
            environment()["HOME"] = home.path; environment()["TMPDIR"] = tmp.path
        }.start()
        val out=process.inputStream.readBytes().toString(Charsets.UTF_8); val err=process.errorStream.readBytes().toString(Charsets.UTF_8)
        return ProcessResult(process.waitFor(),out,err)
    }
    private fun b64(value: String)=Base64.getEncoder().encodeToString(value.toByteArray())
    private data class ProcessResult(val code:Int,val out:String,val err:String)
}
