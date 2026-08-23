package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxEditEngineTest {
    private fun edit(
        mode: TermuxEditMode,
        match: String,
        write: String,
        occurrence: TermuxEditOccurrence? = null,
    ) = TermuxEditSpec(mode, match, write, occurrence)

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

    // BUG 1 regression pins (live-session evidence): insert modes must never emit the anchor
    // twice. The splice math keeps the anchor exactly once for exact, indent, and fuzzy
    // strategies, single-line and multi-line anchors, insert_before and insert_after.
    @Test fun insertModesNeverDuplicateTheAnchor() {
        val before = applyTermuxEdits("AAA\nBBB\nCCC", listOf(edit(TermuxEditMode.BEFORE, "BBB", "X\n")))
        assertTrue(before.success)
        assertEquals("AAA\nX\nBBB\nCCC", before.edited)

        val after = applyTermuxEdits("AAA\nBBB\nCCC", listOf(edit(TermuxEditMode.AFTER, "BBB", "\nX")))
        assertTrue(after.success)
        assertEquals("AAA\nBBB\nX\nCCC", after.edited)

        // Evidence A shape: indented single-line anchor, insert_before (exact strategy).
        val jsx = "    <main>\n      {/* Inspector inline at >=1280px */}\n      <div>body</div>\n    </main>\n"
        val anchor = "{/* Inspector inline at >=1280px */}"
        val insertedBefore = applyTermuxEdits(jsx, listOf(edit(TermuxEditMode.BEFORE, anchor, "{open && (<aside/>)}\n      ")))
        assertTrue(insertedBefore.success)
        assertEquals(1, Regex(Regex.escape(anchor)).findAll(insertedBefore.edited).count())
        assertEquals(
            "    <main>\n      {open && (<aside/>)}\n      {/* Inspector inline at >=1280px */}\n      <div>body</div>\n    </main>\n",
            insertedBefore.edited,
        )

        // Evidence B shape: multi-line block ending in ");", insert_after via the indent strategy.
        val routes = "  router.get(\n    '/items',\n    handler,\n  );\n  next();\n"
        val insertedAfter = applyTermuxEdits(routes, listOf(edit(
            TermuxEditMode.AFTER,
            "router.get(\n  '/items',\n  handler,\n);",
            "\n  router.delete(\n    '/items/:id',\n    remover,\n  );",
        )))
        assertTrue(insertedAfter.success)
        assertEquals("indent", insertedAfter.diagnostics.single().strategy)
        assertEquals(1, Regex(Regex.escape("router.get(")).findAll(insertedAfter.edited).count())
        assertEquals(
            "  router.get(\n    '/items',\n    handler,\n  );\n  router.delete(\n    '/items/:id',\n    remover,\n  );\n  next();\n",
            insertedAfter.edited,
        )

        // Fuzzy strategy: NBSP in source, plain space in match text; multi-line anchor.
        val fuzzySource = "  start  \n  middle\u00a0x\n  end\n  tail\n"
        val fuzzy = applyTermuxEdits(fuzzySource, listOf(edit(TermuxEditMode.AFTER, "  start\n  middle x\n  end", "\n  INSERTED")))
        assertTrue(fuzzy.success)
        assertEquals("fuzzy", fuzzy.diagnostics.single().strategy)
        assertEquals(1, Regex("start").findAll(fuzzy.edited).count())
        assertEquals("  start  \n  middle\u00a0x\n  end\n  INSERTED\n  tail\n", fuzzy.edited)
    }

    @Test fun occurrenceSelectorsPickFirstLastNthAndReplaceAll() {
        val source = "a=1\nb=2\na=1\nc=3\na=1\n"

        val all = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9", TermuxEditOccurrence.All)))
        assertTrue(all.success)
        assertEquals("a=9\nb=2\na=9\nc=3\na=9\n", all.edited)
        assertEquals(3, all.diagnostics.single().occurrencesApplied)
        assertEquals("applied", all.diagnostics.single().status)

        val first = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9", TermuxEditOccurrence.First)))
        assertTrue(first.success)
        assertEquals("a=9\nb=2\na=1\nc=3\na=1\n", first.edited)
        assertEquals(1, first.diagnostics.single().occurrencesApplied)

        val last = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9", TermuxEditOccurrence.Last)))
        assertTrue(last.success)
        assertEquals("a=1\nb=2\na=1\nc=3\na=9\n", last.edited)

        val second = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9", TermuxEditOccurrence.Nth(2))))
        assertTrue(second.success)
        assertEquals("a=1\nb=2\na=9\nc=3\na=1\n", second.edited)

        // Insert modes work with occurrence too and never duplicate anchors.
        val insertAll = applyTermuxEdits(source, listOf(edit(TermuxEditMode.BEFORE, "a=1", "# note\n", TermuxEditOccurrence.All)))
        assertTrue(insertAll.success)
        assertEquals("# note\na=1\nb=2\n# note\na=1\nc=3\n# note\na=1\n", insertAll.edited)
        assertEquals(3, insertAll.diagnostics.single().occurrencesApplied)

        // Default (no occurrence) stays strictly ambiguous.
        val strict = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9")))
        assertFalse(strict.success)
        assertEquals("ambiguous_match", strict.diagnostics.single().reason)
        assertNull(strict.diagnostics.single().occurrencesApplied)

        // Unique match with an occurrence selector still succeeds and reports the count.
        val unique = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "b=2", "b=8", TermuxEditOccurrence.All)))
        assertTrue(unique.success)
        assertEquals(1, unique.diagnostics.single().occurrencesApplied)
    }

    @Test fun occurrenceOutOfRangeFailsWithTotalCountAndSelfOverlapIsNonGreedy() {
        val source = "a=1\nb=2\na=1\n"
        val outOfRange = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "a=1", "a=9", TermuxEditOccurrence.Nth(3))))
        assertFalse(outOfRange.success)
        val diagnostic = outOfRange.diagnostics.single()
        assertEquals("occurrence_out_of_range", diagnostic.reason)
        assertEquals(2, diagnostic.occurrenceTotal)
        assertTrue(diagnostic.nearbyText!!.contains("only 2 match(es)"))
        assertEquals(source, outOfRange.edited)

        // "aa" in "aaaa": all-mode keeps non-overlapping matches only (replace-all semantics).
        val selfOverlap = applyTermuxEdits("aaaa", listOf(edit(TermuxEditMode.REPLACE, "aa", "b", TermuxEditOccurrence.All)))
        assertTrue(selfOverlap.success)
        assertEquals("bb", selfOverlap.edited)
        assertEquals(2, selfOverlap.diagnostics.single().occurrencesApplied)
    }

    @Test fun occurrenceEditsCoexistWithStrictEditsInOneBatch() {
        val source = "key=old\nrepeat\nmiddle\nrepeat\n"
        val result = applyTermuxEdits(source, listOf(
            edit(TermuxEditMode.REPLACE, "key=old", "key=new"),
            edit(TermuxEditMode.REPLACE, "repeat", "twice", TermuxEditOccurrence.All),
        ))
        assertTrue(result.success)
        assertEquals("key=new\ntwice\nmiddle\ntwice\n", result.edited)
        assertNull(result.diagnostics[0].occurrencesApplied)
        assertEquals(2, result.diagnostics[1].occurrencesApplied)

        // A strict edit that is ambiguous still fails the batch even when another edit
        // uses an occurrence selector.
        val mixedFailure = applyTermuxEdits(source, listOf(
            edit(TermuxEditMode.REPLACE, "repeat", "once"),
            edit(TermuxEditMode.REPLACE, "key=old", "key=new", TermuxEditOccurrence.First),
        ))
        assertFalse(mixedFailure.success)
        assertEquals("ambiguous_match", mixedFailure.diagnostics[0].reason)
        assertEquals(source, mixedFailure.edited)
    }

    @Test fun occurrenceParserAcceptsKeywordsAndPositiveIntegersOnly() {
        fun parse(occurrence: String): Any = parseTermuxEditRequest(
            Json.parseToJsonElement("""{"path":"x","edits":[{"mode":"replace_match","match_text":"a","write_text":"b","occurrence":$occurrence}]}"""),
            single = true,
        )
        fun spec(occurrence: String): TermuxEditOccurrence? =
            (parse(occurrence) as PublicInputResult.Ok<TermuxEditRequest>).value.files.single().edits.single().occurrence
        fun errorCode(occurrence: String): String = (parse(occurrence) as PublicInputResult.Error).value.code

        assertEquals(TermuxEditOccurrence.First, spec("\"first\""))
        assertEquals(TermuxEditOccurrence.Last, spec("\"last\""))
        assertEquals(TermuxEditOccurrence.All, spec("\"all\""))
        assertEquals(TermuxEditOccurrence.Nth(2), spec("2"))
        assertEquals(TermuxEditOccurrence.Nth(7), spec("\"7\""))
        assertNull(spec("null"))
        assertEquals("edits[0].invalid_occurrence", errorCode("0"))
        assertEquals("edits[0].invalid_occurrence", errorCode("-1"))
        assertEquals("edits[0].invalid_occurrence", errorCode("1.5"))
        assertEquals("edits[0].invalid_occurrence", errorCode("true"))
        assertEquals("edits[0].invalid_occurrence", errorCode("\"everything\""))
        assertEquals("edits[0].invalid_occurrence", errorCode("[1]"))
    }

    @Test fun occurrenceDiagnosticJsonReportsAppliedCountAndOutOfRangeTotal() {
        val applied = applyTermuxEdits("x\nx\n", listOf(edit(TermuxEditMode.REPLACE, "x", "y", TermuxEditOccurrence.All)))
        assertTrue(applied.success)
        val appliedJson = diagnosticJson(applied.diagnostics.single())
        assertEquals(2, appliedJson["occurrences_applied"]!!.jsonPrimitive.int)

        val outOfRange = applyTermuxEdits("x\nx\n", listOf(edit(TermuxEditMode.REPLACE, "x", "y", TermuxEditOccurrence.Nth(9))))
        assertFalse(outOfRange.success)
        val failedJson = diagnosticJson(outOfRange.diagnostics.single())
        assertEquals("occurrence_out_of_range", failedJson["reason"]!!.jsonPrimitive.content)
        assertEquals(2, failedJson["occurrence_total"]!!.jsonPrimitive.int)
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

    @Test fun missingMatchReportsCandidateRegionAndFirstDifferingLine() {
        val source = "alpha\nbeta\ngamma\ndelta\n"
        val result = applyTermuxEdits(source, listOf(edit(TermuxEditMode.REPLACE, "beta\ngama\ndelta", "x")))
        assertFalse(result.success)
        val diagnostic = result.diagnostics.single()
        assertEquals("match_not_found", diagnostic.reason)
        assertEquals(2, diagnostic.candidateStartLine)
        assertEquals(4, diagnostic.candidateEndLine)
        assertEquals(3, diagnostic.firstDiffLine)
        assertEquals("gama", diagnostic.firstDiffExpected)
        assertEquals("gamma", diagnostic.firstDiffActual)
        assertEquals(false, diagnostic.firstDiffInvisiblesOnly)

        // Postmortem case: match text over-escapes a quote (\\' in match vs \' in file).
        val escaped = applyTermuxEdits(
            "header\nprint('\\'')\nfooter\n",
            listOf(edit(TermuxEditMode.REPLACE, "header\nprint('\\\\'')\nfooter", "x")),
        )
        assertFalse(escaped.success)
        val escapedDiagnostic = escaped.diagnostics.single()
        assertEquals("match_not_found", escapedDiagnostic.reason)
        assertEquals(2, escapedDiagnostic.firstDiffLine)
        assertEquals("print('\\\\\\\\'')", escapedDiagnostic.firstDiffExpected)
        assertEquals("print('\\\\'')", escapedDiagnostic.firstDiffActual)
    }

    @Test fun invisiblesOnlyMismatchRendersOffendingBytesVisibly() {
        val result = applyTermuxEdits("foo\u200bbar\n", listOf(edit(TermuxEditMode.REPLACE, "foo bar", "x")))
        assertFalse(result.success)
        val diagnostic = result.diagnostics.single()
        assertEquals("match_not_found", diagnostic.reason)
        assertEquals(1, diagnostic.firstDiffLine)
        assertEquals(true, diagnostic.firstDiffInvisiblesOnly)
        assertEquals("foo\\u0020bar", diagnostic.firstDiffExpected)
        assertEquals("foo\\u200bbar", diagnostic.firstDiffActual)

        assertEquals("a\\tb\\\\c", renderTermuxEditVisibleLine("a\tb\\c"))
        assertEquals("a\\u0020b", renderTermuxEditVisibleLine("a b", escapeSpaces = true))
        assertEquals("nbsp\\u00a0end", renderTermuxEditVisibleLine("nbsp\u00a0end"))
    }

    @Test fun batchFailureEnumerationListsValidatedAndFailedEdits() {
        val healthy = applyTermuxEdits("alpha beta", listOf(edit(TermuxEditMode.REPLACE, "alpha", "A")))
        assertTrue(healthy.success)
        val failing = applyTermuxEdits("gamma\ndelta\n", listOf(
            edit(TermuxEditMode.REPLACE, "gamma", "G"),
            edit(TermuxEditMode.REPLACE, "gama\ndelta", "M"),
        ))
        assertFalse(failing.success)

        val envelope = buildJsonObject {
            appendEditFailureEnumeration(listOf("a.kt" to healthy.diagnostics, "b.kt" to failing.diagnostics))
        }
        val failed = envelope["failed_edits"]!!.jsonArray
        assertEquals(1, failed.size)
        val entry = failed.single().jsonObject
        assertEquals("b.kt", entry["path"]!!.jsonPrimitive.content)
        assertEquals(1, entry["edit_index"]!!.jsonPrimitive.int)
        assertEquals("match_not_found", entry["reason"]!!.jsonPrimitive.content)
        assertEquals(1, entry["first_diff_line"]!!.jsonPrimitive.int)
        assertEquals(2, envelope["validated_edit_count"]!!.jsonPrimitive.int)
        assertEquals(1, envelope["failed_edit_count"]!!.jsonPrimitive.int)

        val diagnosticEnvelope = diagnosticJson(failing.diagnostics[1])
        assertEquals("match_not_found", diagnosticEnvelope["reason"]!!.jsonPrimitive.content)
        assertEquals(1, diagnosticEnvelope["first_diff_line"]!!.jsonPrimitive.int)
        assertEquals("gama", diagnosticEnvelope["first_diff_expected"]!!.jsonPrimitive.content)
        assertEquals("gamma", diagnosticEnvelope["first_diff_actual"]!!.jsonPrimitive.content)
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
