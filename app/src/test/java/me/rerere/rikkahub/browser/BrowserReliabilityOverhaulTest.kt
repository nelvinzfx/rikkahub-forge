package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.LoopGuard
import me.rerere.rikkahub.data.ai.PriorToolCall
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import me.rerere.rikkahub.data.ai.tools.local.browserScrollByTool
import me.rerere.rikkahub.data.ai.tools.local.browserScrollToTool
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reliability-overhaul regression coverage that runs without a WebView:
 *
 *  - Item D (session isolation): [BrowserController]'s ownership claim/release rules.
 *    All tests run with an UNBOUND controller — ownership is pure bookkeeping state.
 *  - Item A.4 (loop-guard false positives): error-result exclusion and the browser read
 *    tools' membership in the observation set (action resets their repeat count).
 *  - Item C.6 (new tools): catalogue integrity + args-validation short-circuits for
 *    browser_scroll_to / browser_scroll_by (same unbound-controller pattern as
 *    [BrowserToolsTest]).
 */
class BrowserReliabilityOverhaulTest {

    @After fun tearDown() {
        BrowserController.clearTaskWindow()
        BrowserController.releaseOwnership(null)
    }

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.single() as UIMessagePart.Text).text
    }

    private fun errorOf(raw: String): String? =
        Json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content

    // --- Item D: ownership ------------------------------------------------------------

    @Test fun `unowned slot accepts any claim`() {
        BrowserController.releaseOwnership(null)
        assertTrue(BrowserController.claimOwnership("conv-a"))
        assertEquals("conv-a", BrowserController.ownerConvId)
    }

    @Test fun `same conversation re-claims freely`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.startTaskWindow()
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.clearTaskWindow()
    }

    @Test fun `different conversation is rejected while owner task is in flight`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.startTaskWindow()
        assertFalse(BrowserController.claimOwnership("conv-b"))
        assertEquals("conv-a", BrowserController.ownerConvId)
        BrowserController.clearTaskWindow()
    }

    @Test fun `different conversation may take over after task window cleared`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.startTaskWindow()
        BrowserController.clearTaskWindow() // browser_done ran
        assertTrue(BrowserController.claimOwnership("conv-b"))
        assertEquals("conv-b", BrowserController.ownerConvId)
    }

    @Test fun `isOwnedBy never allows a silent cross-conversation read`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        // Even with no task in flight, a non-owner must NOT read the owner's page state.
        assertTrue(BrowserController.isOwnedBy("conv-a"))
        assertFalse(BrowserController.isOwnedBy("conv-b"))
        // Context-less legacy callers bypass the check (documented tradeoff).
        assertTrue(BrowserController.isOwnedBy(null))
    }

    @Test fun `only the owner can release ownership`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.releaseOwnership("conv-b") // non-owner: ignored
        assertEquals("conv-a", BrowserController.ownerConvId)
        BrowserController.releaseOwnership("conv-a") // owner: released
        assertEquals(null, BrowserController.ownerConvId)
    }

    @Test fun `stopCurrentTask frees the ownership slot`() {
        assertTrue(BrowserController.claimOwnership("conv-a"))
        BrowserController.stopCurrentTask()
        assertEquals(null, BrowserController.ownerConvId)
        assertTrue(BrowserController.claimOwnership("conv-b"))
    }

    // --- Item A.4: loop guard ----------------------------------------------------------

    private fun call(name: String, wasError: Boolean = false) =
        PriorToolCall(name, "$name::{}", epochMs = 0L, wasError = wasError)

    @Test fun `error results do not count toward the loop threshold`() {
        // Three identical FAILED calls — retry-after-error is recovery, not a loop.
        val prior = listOf(
            call("browser_get_dom", wasError = true),
            call("browser_get_dom", wasError = true),
            call("browser_get_dom", wasError = true),
        )
        val decision = LoopGuard.evaluate(prior, "browser_get_dom", "browser_get_dom::{}", nowMs = 1L)
        assertFalse("failed calls must not trip the guard", decision.block)
        assertEquals(0, decision.priorOccurrences)
    }

    @Test fun `browser observation repeats around browser actions do not trip`() {
        // open → screenshot → click → screenshot → open → screenshot: classic recovery
        // loop from the field report. browser_screenshot is an observation tool now, so
        // each intervening ACTION (open/click are not in the observation set) resets it.
        val prior = listOf(
            call("browser_open"),
            call("browser_screenshot"),
            call("browser_click"),
            call("browser_screenshot"),
            call("browser_open"),
            call("browser_screenshot"),
        )
        val decision = LoopGuard.evaluate(prior, "browser_screenshot", "browser_screenshot::{}", nowMs = 1L)
        assertFalse("act-observe cycle must not be a loop", decision.block)
    }

    @Test fun `browser observation with no intervening action still trips`() {
        val prior = listOf(
            call("browser_screenshot"),
            call("browser_screenshot"),
            call("browser_screenshot"),
        )
        // nowMs within the 5s freshness TTL of epoch 0 so the TTL bypass does not fire.
        val decision = LoopGuard.evaluate(prior, "browser_screenshot", "browser_screenshot::{}", nowMs = 1L)
        assertTrue("hammering the same screenshot on a frozen page is still a loop", decision.block)
    }

    // --- Item C.6: catalogue + new tools -------------------------------------------------

    @Test fun `catalogue contains the two new scroll tools as write tools`() {
        assertTrue(BrowserToolDefaults.SCROLL_TO in BrowserToolDefaults.ALL_TOOLS)
        assertTrue(BrowserToolDefaults.SCROLL_BY in BrowserToolDefaults.ALL_TOOLS)
        assertTrue(BrowserToolDefaults.SCROLL_TO in BrowserToolDefaults.WRITE_TOOLS)
        assertTrue(BrowserToolDefaults.SCROLL_BY in BrowserToolDefaults.WRITE_TOOLS)
        // Write tools default OFF.
        assertEquals(false, BrowserToolDefaults.DEFAULT_ENABLED[BrowserToolDefaults.SCROLL_TO])
        assertEquals(false, BrowserToolDefaults.DEFAULT_ENABLED[BrowserToolDefaults.SCROLL_BY])
    }

    @Test fun `scroll_to requires a selector`() {
        val out = execText(browserScrollToTool(), "{}")
        assertEquals("missing_selector", errorOf(out))
    }

    @Test fun `scroll_to with valid args short-circuits to browser_not_open when unbound`() {
        val out = execText(browserScrollToTool(), "{\"selector\":\"#main\"}")
        assertEquals("browser_not_open", errorOf(out))
    }

    @Test fun `scroll_by rejects a zero delta`() {
        val out = execText(browserScrollByTool(), "{}")
        assertEquals("missing_delta", errorOf(out))
    }

    @Test fun `scroll_by with valid args short-circuits to browser_not_open when unbound`() {
        val out = execText(browserScrollByTool(), "{\"y\":600}")
        assertEquals("browser_not_open", errorOf(out))
    }

    @Test fun `createBrowserTool dispatches the new names`() {
        for (name in listOf(BrowserToolDefaults.SCROLL_TO, BrowserToolDefaults.SCROLL_BY)) {
            val t = createBrowserTool(name, NULL_CONTEXT)
            assertEquals(name, t!!.name)
        }
    }
}
