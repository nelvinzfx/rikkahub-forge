package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [normalizeTags] — the pure tag normalisation used by
 * [MemoryRepository.encodeTags] before persistence, and by
 * [MemoryRepository.decodeTags] consumers on read-back.
 *
 * Pure Kotlin: no Room, no Android Context — runs entirely on the JVM.
 */
class NormalizeTagsTest {

    // ── Rule 1: trim ────────────────────────────────────────────────

    @Test fun `trims surrounding whitespace from each tag`() {
        assertEquals(
            listOf("alpha", "beta"),
            normalizeTags(listOf("  alpha  ", "\tbeta\t")),
        )
    }

    // ── Rule 2: lowercase with Locale.ROOT ──────────────────────────

    @Test fun `lowercases mixed-case tags`() {
        assertEquals(
            listOf("foo", "bar"),
            normalizeTags(listOf("Foo", "BAR")),
        )
    }

    @Test fun `lowercasing uses locale root not turkish locale`() {
        // In Turkish locale, uppercase I -> dotless ı (U+0131).
        // Locale.ROOT maps I -> regular i (U+0069).
        assertEquals(listOf("i"), normalizeTags(listOf("I")))
    }

    // ── Rule 3: discard blanks ─────────────────────────────────────

    @Test fun `discards blank tags`() {
        assertEquals(
            listOf("keep"),
            normalizeTags(listOf("", "  ", "keep", "\t")),
        )
    }

    @Test fun `all blank input returns empty list`() {
        assertTrue(normalizeTags(listOf("", "  ", "\t")).isEmpty())
    }

    @Test fun `empty input returns empty list`() {
        assertTrue(normalizeTags(emptyList()).isEmpty())
    }

    // ── Rule 4: case-insensitive dedup, first-seen order ────────────

    @Test fun `removes duplicates case-insensitively preserving first seen order`() {
        assertEquals(
            listOf("foo", "bar", "baz"),
            normalizeTags(listOf("Foo", "foo", "BAR", "bar", "baz", "BAZ")),
        )
    }

    @Test fun `first seen casing is kept after lowercasing`() {
        // All inputs are lowercased, so "first seen" just means
        // the first occurrence's position wins; casing is always lower.
        assertEquals(
            listOf("alpha", "beta"),
            normalizeTags(listOf("Alpha", "ALPHA", "beta", "Beta")),
        )
    }

    // ── Rule 5: cap at MAX_TAGS ─────────────────────────────────────

    @Test fun `caps result to five tags`() {
        val input = (1..10).map { "tag$it" }
        val result = normalizeTags(input)
        assertEquals(5, result.size)
        assertEquals(
            listOf("tag1", "tag2", "tag3", "tag4", "tag5"),
            result,
        )
    }

    @Test fun `fewer than five tags are all kept`() {
        assertEquals(
            listOf("a", "b", "c"),
            normalizeTags(listOf("a", "b", "c")),
        )
    }

    // ── Rule 6: reject tags longer than MAX_TAG_LENGTH ──────────────

    @Test fun `discards tags longer than max tag length`() {
        val tooLong = "x".repeat(MemoryRepository.MAX_TAG_LENGTH + 1)
        val atLimit = "y".repeat(MemoryRepository.MAX_TAG_LENGTH)
        assertEquals(
            listOf(atLimit),
            normalizeTags(listOf(tooLong, atLimit)),
        )
    }

    @Test fun `tag at exactly max length is kept`() {
        val atLimit = "a".repeat(MemoryRepository.MAX_TAG_LENGTH)
        assertEquals(listOf(atLimit), normalizeTags(listOf(atLimit)))
    }

    // ── Rule 7: namespaced tags preserved ───────────────────────────

    @Test fun `preserves namespaced tags`() {
        assertEquals(
            listOf("device:lenovo-ideapad-300", "project:conversation-recall"),
            normalizeTags(listOf("device:lenovo-ideapad-300", "project:conversation-recall")),
        )
    }

    @Test fun `namespaced tags are lowercased but colon preserved`() {
        assertEquals(
            listOf("device:lenovo-ideapad-300"),
            normalizeTags(listOf("Device:Lenovo-IdeaPad-300")),
        )
    }

    @Test fun `namespaced tag with mixed casing deduplicates correctly`() {
        assertEquals(
            listOf("device:note9"),
            normalizeTags(listOf("Device:Note9", "device:note9", "DEVICE:NOTE9")),
        )
    }

    // ── Encode/decode round trips ───────────────────────────────────

    @Test fun `encode decode round trip preserves normalized tags`() {
        val raw = listOf("  Foo  ", "BAR", "foo", "Device:Lenovo", "  ", "bar")
        val encoded = normalizeTags(raw).joinToString(",")
        val decoded = encoded.split(',').map(String::trim).filter(String::isNotBlank)
        assertEquals(listOf("foo", "bar", "device:lenovo"), decoded)
    }

    @Test fun `encode decode round trip with namespaced tags`() {
        val raw = listOf("Device:Lenovo-IdeaPad-300", "Project:Conversation-Recall")
        val encoded = normalizeTags(raw).joinToString(",")
        val decoded = encoded.split(',').map(String::trim).filter(String::isNotBlank)
        assertEquals(
            listOf("device:lenovo-ideapad-300", "project:conversation-recall"),
            decoded,
        )
    }

    @Test fun `encode of empty list is empty string`() {
        assertEquals("", normalizeTags(emptyList()).joinToString(","))
    }

    @Test fun `decode of empty string is empty list`() {
        assertEquals(
            emptyList<String>(),
            "".split(',').map(String::trim).filter(String::isNotBlank),
        )
    }

    @Test fun `encode decode round trip through all rules`() {
        val raw = listOf(
            "  KeepThis  ",
            "",
            "keepthis",
            "x".repeat(100),
            "Device:Note9",
            "  device:note9  ",
            "Unique",
            "another",
            "more",
            "extra",
            "overflow",
        )
        val encoded = normalizeTags(raw).joinToString(",")
        val decoded = encoded.split(',').map(String::trim).filter(String::isNotBlank)
        assertEquals(
            listOf("keepthis", "device:note9", "unique", "another", "more"),
            decoded,
        )
    }
}
