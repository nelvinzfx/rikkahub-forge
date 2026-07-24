package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxEditEngineTest {
    private fun edit(mode: TermuxEditMode, match: String, write: String) = TermuxEditSpec(mode, match, write)

    @Test fun exactAdjacentAndLiteralInsertionsResolveAgainstOriginalSource() {
        val result = applyTermuxEdits("abcd", listOf(
            edit(TermuxEditMode.REPLACE, "ab", "AB"),
            edit(TermuxEditMode.REPLACE, "cd", "CD"),
        ))
        assertTrue(result.success)
        assertEquals("ABCD", result.edited)
        assertEquals(listOf("exact", "exact"), result.diagnostics.map { it.strategy })

        val inserted = applyTermuxEdits("anchor", listOf(edit(TermuxEditMode.AFTER, "anchor", "literal")))
        assertEquals("anchorliteral", inserted.edited)


        val literalMixedEndingInsert = applyTermuxEdits(
            "a\r\nb",
            listOf(edit(TermuxEditMode.AFTER, "a\n", "a\n")),
        )
        assertTrue(literalMixedEndingInsert.success)
        assertEquals("a\r\na\nb", literalMixedEndingInsert.edited)


        val indentSource = "    begin\n        body\n    end\n"
        val indentAnchor = "begin\n  body\nend"
        val before = applyTermuxEdits(indentSource, listOf(edit(TermuxEditMode.BEFORE, indentAnchor, "X\n")))
        assertTrue(before.success)
        assertEquals("X\n$indentSource", before.edited)
        val after = applyTermuxEdits(indentSource, listOf(edit(TermuxEditMode.AFTER, indentAnchor, "\nY")))
        assertTrue(after.success)
        assertEquals("    begin\n        body\n    end\nY\n", after.edited)
    }

    @Test fun fuzzyProjectionMapsNfkcQuotesSpacesDashesAndTrailingWhitespaceToExactSpans() {
        val source = "left ﬁ “quoted”\u00a0— value   \nRIGHT"
        val result = applyTermuxEdits(source, listOf(
            edit(TermuxEditMode.REPLACE, "fi \"quoted\" - value\n", "changed\n"),
        ))
        assertTrue(result.success)
        assertEquals("left changed\nRIGHT", result.edited)
        assertEquals("fuzzy", result.diagnostics.single().strategy)

        val hangul = applyTermuxEdits("가", listOf(edit(TermuxEditMode.REPLACE, "가", "ok")))
        assertTrue(hangul.success)
        assertEquals("ok", hangul.edited)


        val partialLigature = applyTermuxEdits("ﬁx", listOf(edit(TermuxEditMode.REPLACE, "f", "F")))
        assertFalse(partialLigature.success)
        assertEquals("ﬁx", partialLigature.edited)


        val partialCombining = applyTermuxEdits("e\u0301x", listOf(edit(TermuxEditMode.REPLACE, "\u0301", "!")))
        assertFalse(partialCombining.success)
        assertEquals("e\u0301x", partialCombining.edited)
    }

    @Test fun indentFallbackReindentsEachWrittenLine() {
        val source = "fun x() {\n        if (ready) {\n            run()\n        }\n}\n"
        val result = applyTermuxEdits(source, listOf(edit(
            TermuxEditMode.REPLACE,
            "if (ready) {\n    run()\n}",
            "if (done) {\n    stop()\n}",
        )))
        assertTrue(result.success)
        assertEquals("fun x() {\n        if (done) {\n            stop()\n        }\n}\n", result.edited)
        assertEquals("indent", result.diagnostics.single().strategy)
    }

    @Test fun ambiguityOverlapAndSamePositionAbortWholeFileWithBoundedDiagnostics() {
        val ambiguous = applyTermuxEdits("same\nsame\n", listOf(edit(TermuxEditMode.REPLACE, "same", "x")))
        assertFalse(ambiguous.success)
        assertEquals("ambiguous_match", ambiguous.diagnostics.single().reason)
        assertTrue(ambiguous.diagnostics.single().candidateLines.size <= 5)

        val overlap = applyTermuxEdits("abcdef", listOf(
            edit(TermuxEditMode.REPLACE, "abcd", "x"),
            edit(TermuxEditMode.REPLACE, "cdef", "y"),
        ))
        assertFalse(overlap.success)
        assertEquals("abcdef", overlap.edited)
        assertTrue(overlap.diagnostics.all { it.reason == "overlapping_or_same_position" })

        val samePosition = applyTermuxEdits("a", listOf(
            edit(TermuxEditMode.BEFORE, "a", "1"),
            edit(TermuxEditMode.BEFORE, "a", "2"),
        ))
        assertFalse(samePosition.success)
    }

    @Test fun noOpIsSuccessfulAndMissingMatchDiagnosticIsBounded() {
        val noOp = applyTermuxEdits("same\n", listOf(edit(TermuxEditMode.REPLACE, "same", "same")))
        assertTrue(noOp.success)
        assertFalse(noOp.changed)
        assertEquals("matched_no_change", noOp.diagnostics.single().status)


        val mixed = applyTermuxEdits("left right", listOf(
            edit(TermuxEditMode.REPLACE, "left", "left"),
            edit(TermuxEditMode.REPLACE, "right", "RIGHT"),
        ))
        assertTrue(mixed.success)
        assertEquals("left RIGHT", mixed.edited)
        assertEquals(listOf("matched_no_change", "applied"), mixed.diagnostics.map { it.status })

        val missing = applyTermuxEdits((1..20).joinToString("\n") { "line $it" }, listOf(edit(TermuxEditMode.REPLACE, "line xx", "x")))
        assertFalse(missing.success)
        assertTrue((missing.diagnostics.single().nearbyText?.length ?: 0) <= MAX_TERMUX_EDIT_DIAGNOSTIC_CHARS)
    }

    @Test fun strictBytesPreserveBomMixedSeparatorsFinalNewlineAndNoOpIdentity() {
        val original = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\r\nb\nc\rd\r\n".toByteArray()
        val source = requireNotNull(decodeTermuxEditSource(original))
        assertTrue(source.bom)
        assertEquals("MIXED", source.lineEnding)
        assertEquals("a\r\nb\nc\rd\r\n", source.text)
        val result = applyTermuxEdits(source.text, listOf(edit(TermuxEditMode.REPLACE, "b\n", "tail\n")))
        assertTrue(result.success)
        val encoded = requireNotNull(encodeTermuxEditResult(source, result.edited))
        assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\r\ntail\nc\rd\r\n".toByteArray(), encoded)


        val bareNewline = applyTermuxEdits("a\r\nb", listOf(edit(TermuxEditMode.REPLACE, "\n", "X")))
        assertTrue(bareNewline.success)
        assertEquals("aXb", bareNewline.edited)

        val noOp = applyTermuxEdits(source.text, listOf(edit(TermuxEditMode.REPLACE, "a\n", "a\n")))
        assertFalse(noOp.changed)
        assertArrayEquals(original, requireNotNull(encodeTermuxEditResult(source, noOp.edited)))
        assertNull(decodeTermuxEditSource(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun loneCrAndEmptySourcesAreDecodedAndPreservedWithoutInventingNewlines() {
        val cr = requireNotNull(decodeTermuxEditSource("a\rb\r".toByteArray()))
        assertEquals("CR", cr.lineEnding)
        assertEquals("a\rb\r", cr.text)
        val changed = applyTermuxEdits(cr.text, listOf(edit(TermuxEditMode.REPLACE, "b\n", "tail")))
        assertArrayEquals("a\rtail".toByteArray(), requireNotNull(encodeTermuxEditResult(cr, changed.edited)))

        val empty = requireNotNull(decodeTermuxEditSource(byteArrayOf()))
        assertEquals("", empty.text)
        assertArrayEquals(byteArrayOf(), requireNotNull(encodeTermuxEditResult(empty, empty.text)))
    }

    @Test fun workBudgetFailsDeterministicallyAndOrdinaryMaximumEditCountStillRuns() {
        val source = (0 until 100).joinToString("\n") { "unique-$it=value" }
        val edits = (0 until 100).map { edit(TermuxEditMode.REPLACE, "unique-$it=value", "changed-$it=value") }
        val ordinary = applyTermuxEdits(source, edits)
        assertTrue(ordinary.success)
        assertEquals(100, ordinary.diagnostics.size)

        val exhausted = applyTermuxEdits("x".repeat(16_384), listOf(edit(TermuxEditMode.REPLACE, "missing", "y")), maxWorkUnits = 1)
        assertFalse(exhausted.success)
        assertEquals("work_budget_exceeded", exhausted.error)
        assertEquals("work_budget_exceeded", exhausted.diagnostics.single().reason)


        val hugeIndent = " ".repeat(64 * 1024)
        val indentedSource = "${hugeIndent}anchor\n${hugeIndent}second"
        val intended = "    anchor\n    second"
        val replacement = (1..100).joinToString("\n") { "    line-$it" }
        val expansion = applyTermuxEdits(
            indentedSource,
            listOf(edit(TermuxEditMode.REPLACE, intended, replacement)),
        )
        assertFalse(expansion.success)
        assertEquals("result_too_large", expansion.error)
        assertEquals("result_too_large", expansion.diagnostics.single().reason)
    }

    @Test fun adjacentBoundariesMatchReferenceConflictSemantics() {
        val boundary = applyTermuxEdits("abcd", listOf(
            edit(TermuxEditMode.REPLACE, "ab", "AB"),
            edit(TermuxEditMode.BEFORE, "cd", "|"),
        ))
        assertTrue(boundary.success)
        assertEquals("AB|cd", boundary.edited)

        val sameStart = applyTermuxEdits("abcd", listOf(
            edit(TermuxEditMode.REPLACE, "ab", "AB"),
            edit(TermuxEditMode.BEFORE, "ab", "|"),
        ))
        assertFalse(sameStart.success)
    }

    @Test fun parserAcceptsOnlyDocumentedAliasesAndCanonicalizesModes() {
        val json = Json.parseToJsonElement("""{
            "path":"x","dry_run":true,"edits":[{
              "mode":"insert_before_match","matchText":"a","writeText":"b"
            }]
        }""")
        val parsed = parseTermuxEditRequest(json, single = true) as PublicInputResult.Ok
        assertTrue(parsed.value.dryRun)
        assertEquals(TermuxEditMode.BEFORE, parsed.value.files.single().edits.single().mode)
        assertFalse(isSafeTermuxEditDiffPath("bad\npath"))
        assertFalse(isSafeTermuxEditDiffPath("bad\rpath"))
        assertTrue(isSafeTermuxEditDiffPath("normal/path"))

        fun error(body: String, single: Boolean = true): String =
            (parseTermuxEditRequest(Json.parseToJsonElement(body), single) as PublicInputResult.Error).value.code
        assertEquals("edits[0].unsupported_fields:old_text", error("""{"path":"x","edits":[{"mode":"replace_match","old_text":"a","write_text":"b"}]}"""))
        assertEquals("edits[0].conflicting_match_text_alias", error("""{"path":"x","edits":[{"mode":"replace_match","match_text":"a","matchText":"b","write_text":"c"}]}"""))
        assertEquals("edits[0].match_text_must_be_string", error("""{"path":"x","edits":[{"mode":"replace_match","match_text":1,"write_text":"c"}]}"""))
        assertEquals("edits[0].empty_match_text", error("""{"path":"x","edits":[{"mode":"replace_match","match_text":"","write_text":"c"}]}"""))
        assertEquals("edits[0].unknown_fields:extra", error("""{"path":"x","edits":[{"mode":"replace_match","match_text":"a","write_text":"c","extra":1}]}"""))
        assertEquals("duplicate_path", error("""{"files":[
          {"path":"x","edits":[{"mode":"replace_match","match_text":"a","write_text":"b"}]},
          {"path":"./x","edits":[{"mode":"replace_match","match_text":"a","write_text":"b"}]}
        ]}""", single = false))
    }

    @Test fun diffPrefixNeverSplitsSurrogatePairs() {
        val value = "a\uD83D\uDE00b"
        assertEquals("a", takeTermuxEditDiffPrefix(value, 2))
        assertEquals("a\uD83D\uDE00", takeTermuxEditDiffPrefix(value, 3))
        assertEquals("", takeTermuxEditDiffPrefix(value, 0))
    }

    @Test fun parserEnforcesArrayAndAggregateBoundsAndStrictUtf8() {
        val emptyFiles = JsonObject(mapOf("files" to JsonArray(emptyList())))
        assertEquals("files_count_out_of_range", (parseTermuxEditRequest(emptyFiles, false) as PublicInputResult.Error).value.code)
        val tooMany = Json.parseToJsonElement("""{"files":[${
            (0..MAX_TERMUX_EDIT_FILES).joinToString(",") { i ->
                "{\"path\":\"$i\",\"edits\":[{\"mode\":\"replace_match\",\"match_text\":\"a\",\"write_text\":\"b\"}]}"
            }
        }]}""")
        assertEquals("files_count_out_of_range", (parseTermuxEditRequest(tooMany, false) as PublicInputResult.Error).value.code)

        val badUtf16 = JsonObject(mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("x"),
            "edits" to JsonArray(listOf(JsonObject(mapOf(
                "mode" to kotlinx.serialization.json.JsonPrimitive("replace_match"),
                "match_text" to kotlinx.serialization.json.JsonPrimitive("\ud800"),
                "write_text" to kotlinx.serialization.json.JsonPrimitive("x"),
            )))),
        ))
        assertEquals("edit_invalid_utf8", (parseTermuxEditRequest(badUtf16, true) as PublicInputResult.Error).value.code)
    }
}
