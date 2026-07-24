package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class TermuxToolUIModelsTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun registryResolvesAllNativeTermuxMutationRenderers() {
        assertEquals("termux_write_file", ToolUIRegistry.resolve("termux_write_file").toolName)
        assertEquals("termux_append_file", ToolUIRegistry.resolve("termux_append_file").toolName)
        assertEquals("termux_edit_file", ToolUIRegistry.resolve("termux_edit_file").toolName)
        assertEquals("termux_edit_files", ToolUIRegistry.resolve("termux_edit_files").toolName)
    }

    @Test
    fun writeModelUsesInputContentAndValidatesCompleteSuccessEnvelope() {
        val path = "src/Main.kt"
        val text = "fun main() {}"
        val input = json("""{"path":"$path","content":"$text"}""")
        val pending = parseTermuxWriteUIModel("termux_write_file", input, null)!!
        assertEquals(text, pending.content)
        assertFalse(pending.append)
        assertTrue(pending.badges.isEmpty())

        val applied = parseTermuxWriteUIModel(
            "termux_write_file",
            input,
            json(
                """{"success":true,"ok":true,"operation":"write","actual_path":"/home/u/src/Main.kt","path_request_sha256":"${sha(path)}","content_sha256":"${sha(text)}","bytes_written":13,"total_bytes":13,"sha256":"${sha(text)}","error":null}""",
            ),
        )!!
        assertEquals(13L, applied.bytesWritten)
        assertEquals(13L, applied.totalBytes)
        assertEquals(listOf(TermuxUIBadge.APPLIED), applied.badges)

        val contradictory = parseTermuxWriteUIModel(
            "termux_write_file",
            input,
            json(
                """{"success":true,"ok":false,"operation":"write","actual_path":"/home/u/src/Main.kt","path_request_sha256":"${sha(path)}","content_sha256":"${sha(text)}","bytes_written":13,"total_bytes":13,"sha256":"${sha(text)}","error":null}""",
            ),
        )
        assertNull(contradictory)
        val quotedNumbers = parseTermuxWriteUIModel(
            "termux_write_file",
            input,
            json(
                """{"success":true,"ok":true,"operation":"write","actual_path":"/home/u/src/Main.kt","path_request_sha256":"${sha(path)}","content_sha256":"${sha(text)}","bytes_written":"13","total_bytes":"13","sha256":"${sha(text)}","error":null}""",
            ),
        )
        assertNull(quotedNumbers)
        assertNull(parseTermuxWriteUIModel("termux_write_file", json("""{"path":7,"content":true}"""), null))
        assertNull(parseTermuxWriteUIModel("other", input, JsonNull))
    }

    @Test
    fun writeErrorPreservesRecoveryDetailAndCurrentSha() {
        val input = json("""{"path":"a.txt","content":"next"}""")
        val current = "a".repeat(64)
        val failed = parseTermuxWriteUIModel(
            "termux_write_file",
            input,
            json(
                """{"success":false,"ok":false,"path":"a.txt","actual_path":"/home/u/a.txt","current_sha256":"$current","error":"stale_source","detail":"changed concurrently","recovery":"Re-read and retry."}""",
            ),
        )!!
        assertEquals("stale_source", failed.error)
        assertEquals("changed concurrently", failed.detail)
        assertEquals("Re-read and retry.", failed.recovery)
        assertEquals(current, failed.currentSha256)
        assertEquals(listOf(TermuxUIBadge.ERROR), failed.badges)
    }

    @Test
    fun singleEditUsesMetadataDiffAndValidatesDryRunEnvelope() {
        val diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new"
        val source = "a".repeat(64)
        val result = "b".repeat(64)
        val model = parseTermuxEditUIModel(
            toolName = "termux_edit_file",
            arguments = json("""{"path":"a.txt","dry_run":true,"edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]}"""),
            content = json(
                """{"success":true,"ok":true,"applied":false,"changed":true,"dry_run":true,"batch_aborted":false,"state":"dry_run","diff_truncated":true,"path":"a.txt","actual_path":"/home/u/a.txt","replacements":1,"source_sha256":"$source","result_sha256":"$result","diff":"--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new","results":[{"index":0,"mode":"replace_match","status":"applied","matched":true,"strategy":"exact"}],"error":null}""",
            ),
            metadataDiff = diff,
        )!!
        assertTrue(model.single)
        assertEquals(diff, model.diff)
        assertEquals(diff, model.files.single().diff)
        assertEquals(listOf(TermuxUIBadge.DRY_RUN, TermuxUIBadge.TRUNCATED), model.badges)
    }

    @Test
    fun multiEditPreservesPerFileDiffRollbackStatesAndDiagnostics() {
        val old = "b".repeat(64)
        val new = "c".repeat(64)
        val model = parseTermuxEditUIModel(
            toolName = "termux_edit_files",
            arguments = json("""{"files":[{"path":"a.kt","edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]},{"path":"b.kt","edits":[{"mode":"replace_match","match_text":"x","write_text":"y"}]}]}"""),
            content = json(
                """
                {
                  "success":false,"ok":false,"applied":false,"changed":true,"dry_run":false,
                  "batch_aborted":true,"state":"error","error":"publication_changed","detail":"publication changed",
                  "diff_truncated":false,
                  "files":[
                    {"path":"a.kt","actual_path":"/home/u/a.kt","source_sha256":"$old","result_sha256":"$new","state":"rolled_back","changed":true,"applied":false,"dry_run":false,"rollback_restored":true,"diff":"--- a/a.kt\n+++ b/a.kt\n-old\n+new","edits":[{"index":0,"mode":"replace_match","status":"applied","matched":true,"strategy":"fuzzy"}]},
                    {"path":"b.kt","actual_path":"/home/u/b.kt","source_sha256":"$old","result_sha256":"$new","state":"rollback_failed","changed":true,"applied":false,"dry_run":false,"rollback_restored":false,"diff":"--- a/b.kt\n+++ b/b.kt\n-x\n+y","edits":[{"index":0,"mode":"replace_match","status":"applied","matched":true,"strategy":"exact"}]}
                  ]
                }
                """.trimIndent(),
            ),
            metadataDiff = "combined",
        )!!
        assertFalse(model.single)
        assertEquals("publication_changed", model.error)
        assertEquals("publication changed", model.detail)
        assertEquals(listOf(TermuxUIBadge.ERROR), model.badges)
        assertEquals(listOf(TermuxUIBadge.ROLLED_BACK), model.files[0].badges)
        assertEquals(listOf(TermuxUIBadge.ROLLBACK_FAILED), model.files[1].badges)
        assertEquals("#1 replace_match: applied [matched] via fuzzy", model.files[0].diagnostics.single())
        assertTrue(model.diff!!.contains("a/a.kt"))
        assertTrue(model.diff!!.contains("a/b.kt"))
    }

    @Test
    fun contradictoryOrMalformedEditOutputFallsBack() {
        val singleArgs = json("""{"path":"a.txt","edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]}""")
        assertNull(
            parseTermuxEditUIModel(
                "termux_edit_file",
                singleArgs,
                json("""{"success":false,"ok":false,"applied":true,"changed":true,"dry_run":false,"state":"error","error":"failed"}"""),
                null,
            )
        )

        val multiArgs = json("""{"files":[{"path":"a.txt","edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]},{"path":"b.txt","edits":[{"mode":"replace_match","match_text":"x","write_text":"y"}]}]}""")
        assertNull(
            parseTermuxEditUIModel(
                "termux_edit_files",
                multiArgs,
                json("""{"success":true,"ok":true,"applied":true,"changed":true,"dry_run":false,"batch_aborted":false,"state":"applied","diff_truncated":false,"error":null,"files":[{"path":"b.txt"},{"path":"a.txt"}]}"""),
                null,
            )
        )
        assertNull(parseTermuxEditUIModel("termux_edit_file", json("""{"path":4,"edits":[]}"""), null, null))
        assertNull(parseTermuxEditUIModel("termux_edit_files", json("""{"files":[{"path":"a"},7]}"""), null, null))
        assertNull(parseTermuxEditUIModel("termux_edit_files", json("""{"files":[]}"""), null, null))
    }

    @Test
    fun earlyEditErrorDoesNotRequireValidEditSpecs() {
        val pathless = parseTermuxEditUIModel(
            "termux_edit_files",
            json("""{"files":"bad"}"""),
            json("""{"success":false,"ok":false,"applied":false,"changed":false,"dry_run":false,"state":"error","error":"files_must_be_array","crash_atomic":false}"""),
            null,
        )!!
        assertTrue(pathless.files.isEmpty())
        assertEquals("files_must_be_array", pathless.error)

        val targeted = parseTermuxEditUIModel(
            "termux_edit_files",
            json("""{"files":[{"path":"a.kt"},{"path":"b.kt"}]}"""),
            json("""{"success":false,"ok":false,"applied":false,"changed":false,"dry_run":false,"state":"error","path":"b.kt","error":"source_invalid_utf8","crash_atomic":false}"""),
            null,
        )!!
        assertNull(targeted.files[0].state)
        assertEquals("error", targeted.files[1].state)
    }

    @Test
    fun contradictoryRollbackAndDiagnosticsFallBack() {
        val old = "b".repeat(64)
        val new = "c".repeat(64)
        val args = json("""{"files":[{"path":"a.kt","edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]}]}""")
        val badRollback = json(
            """{"success":false,"ok":false,"applied":false,"changed":true,"dry_run":false,"batch_aborted":true,"state":"error","error":"publish_failed","diff_truncated":false,"files":[{"path":"a.kt","actual_path":"/a.kt","source_sha256":"$old","result_sha256":"$new","state":"rolled_back","changed":true,"applied":false,"dry_run":false,"rollback_restored":false,"diff":null,"edits":[]}]}""",
        )
        assertNull(parseTermuxEditUIModel("termux_edit_files", args, badRollback, null))

        val badDiagnostic = json(
            """{"success":false,"ok":false,"applied":false,"changed":true,"dry_run":false,"batch_aborted":true,"state":"error","error":"publish_failed","diff_truncated":false,"files":[{"path":"a.kt","actual_path":"/a.kt","source_sha256":"$old","result_sha256":"$new","state":"rolled_back","changed":true,"applied":false,"dry_run":false,"rollback_restored":true,"diff":null,"edits":[{"index":0,"mode":"unknown","status":"applied","matched":true}]}]}""",
        )
        assertNull(parseTermuxEditUIModel("termux_edit_files", args, badDiagnostic, null))
    }

    @Test
    fun dryRunFlagMustMatchRequest() {
        val sha = "a".repeat(64)
        val args = json("""{"files":[{"path":"a.kt","edits":[{"mode":"replace_match","match_text":"old","write_text":"new"}]}]}""")
        val contradictory = json(
            """{"success":true,"ok":true,"applied":false,"changed":false,"dry_run":true,"batch_aborted":false,"state":"dry_run","diff_truncated":false,"error":null,"files":[{"path":"a.kt","actual_path":"/a.kt","source_sha256":"$sha","result_sha256":"$sha","state":"no_change","changed":false,"applied":false,"dry_run":true,"diff":null,"edits":[{"index":0,"mode":"replace_match","status":"matched_no_change","matched":true,"strategy":"exact"}]}]}""",
        )
        assertNull(parseTermuxEditUIModel("termux_edit_files", args, contradictory, null))
    }

    @Test
    fun rollbackRequiresChangedSingleFile() {
        val sha = "a".repeat(64)
        val model = parseTermuxEditUIModel(
            "termux_edit_file",
            json("""{"path":"a.kt","edits":[{"mode":"replace_match","match_text":"old","write_text":"old"}]}"""),
            json("""{"success":false,"ok":false,"applied":false,"changed":false,"dry_run":false,"batch_aborted":true,"state":"error","error":"publish_failed","diff_truncated":false,"path":"a.kt","actual_path":"/a.kt","replacements":0,"source_sha256":"$sha","result_sha256":"$sha","rollback_restored":true,"diff":null,"results":[{"index":0,"mode":"replace_match","status":"matched_no_change","matched":true,"strategy":"exact"}]}"""),
            null,
        )
        assertNull(model)
    }

    @Test
    fun earlyErrorAndDiagnosticCorrelationsRejectContradictions() {
        val wrongPath = parseTermuxEditUIModel(
            "termux_edit_files",
            json("""{"files":[{"path":"a.kt"}]}"""),
            json("""{"success":false,"ok":false,"applied":false,"changed":false,"dry_run":false,"state":"error","path":"b.kt","error":"source_invalid_utf8"}"""),
            null,
        )
        assertNull(wrongPath)

        val wrongDryRun = parseTermuxEditUIModel(
            "termux_edit_file",
            json("""{"path":"a.kt","dry_run":true}"""),
            json("""{"success":false,"ok":false,"applied":false,"changed":false,"dry_run":false,"state":"error","path":"a.kt","error":"invalid_request"}"""),
            null,
        )
        assertNull(wrongDryRun)
    }

    @Test
    fun diffPreviewBudgetIsSharedAcrossFiles() {
        val first = (1..500).joinToString("\n") { "+first$it" }
        val second = (1..500).joinToString("\n") { "+second$it" }
        val bounded = boundedDiffPreviews(listOf(first, second), 1_000_000, 600)
        assertTrue(bounded.truncated)
        assertEquals(500, bounded.previews[0]!!.text.lines().size)
        assertEquals(100, bounded.previews[1]!!.text.lines().size)
        assertTrue(bounded.previews[1]!!.truncated)
        val chars = boundedDiffPreviews(listOf("a".repeat(100), "b".repeat(100)), 120, 1_000)
        assertEquals(100, chars.previews[0]!!.text.length)
        assertEquals(20, chars.previews[1]!!.text.length)
        assertTrue(chars.previews[1]!!.truncated)
    }

    @Test
    fun boundedPreviewRespectsLinesCrLfAndSurrogatePairs() {
        assertEquals(BoundedTextPreview("a\r\nb", false), boundedTextPreview("a\r\nb", 4, 2))
        assertEquals(BoundedTextPreview("a", true), boundedTextPreview("a\r\nb", 4, 1))
        val emoji = "a\uD83D\uDE00b"
        val cut = boundedTextPreview(emoji, 2)
        assertEquals("a", cut.text)
        assertTrue(cut.truncated)
        assertEquals(BoundedTextPreview("", true), boundedTextPreview("x", 0))
        val manyLines = (1..1_000).joinToString("\n") { "line$it" }
        val bounded = boundedTextPreview(manyLines, 64 * 1024, 400)
        assertTrue(bounded.truncated)
        assertEquals(400, bounded.text.lines().size)
    }
}
