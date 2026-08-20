# Browser Reliability Overhaul — Fix Report

> ## Runtime addendum (agent.27 on-device verification, Aug 20 2026)
>
> Verified live by driving the browser tools against a local probe page (`http.server` on
> 127.0.0.1, page that prints `innerWidth`/`devicePixelRatio`/`scrollY` into the DOM):
>
> - **E (viewport): PASS.** `innerWidth=521` on a 720px-wide WebView at dpr 1.38125 —
>   exactly `viewWidthPx / density`. Layout width is now deterministic.
> - **A.1 (coherence): PASS** for fresh loads — `browser_open` returns the new URL + new
>   title atomically; no cross-page contamination observed.
> - **A.2 (blank screenshot): PASS** — viewport capture immediately after open rendered
>   content, no white frame.
> - **7 (full_page): PASS** — 720×6319 bitmap for a 4557 CSS px document (×1.38125 scale),
>   full document rendered top to bottom.
> - **A.3 (fragment scroll): FEATURE PASSES, but it exposed a capture bug.** Live probe
>   reported `scrollY=3352` after opening `#target` while the viewport screenshot showed
>   the document top. Root cause: `enableSlowWholeDocumentDraw()` changes
>   `WebView.draw(canvas)` semantics — it renders the document from its origin and
>   **ignores the scroll offset**. The initial full_page implementation translated the
>   canvas by `+scrollY` under the opposite assumption (harmless only because it was tested
>   at scrollY=0). **Fix (this addendum's patch):** viewport captures translate by
>   `-scrollY`; full_page captures use no translation. Applied to both capture paths
>   (`browserScreenshotTool` and `streamScreenshotIfHeadless`).
> - **Incident — renderer wedge (open):** once during testing, after a full_page capture
>   followed by fragment navigations, the WebView renderer became unresponsive: network
>   loads kept reaching the server (HTTP 200s) but every `evaluateJavascript` stopped
>   answering (all reads returned `selector_not_found`, title fell back to the raw URL,
>   `draw()` kept showing the last frame). `browser_done` + reopen did NOT recover it;
>   an app process restart did. No logcat access from Termux, so the exact trigger is
>   unconfirmed — prime suspect is raster load from slow-whole-document draws on a tall
>   page. If it recurs, capture `adb logcat` around `chromium`/`RenderProcess` and consider
>   handling `WebViewClient.onRenderProcessGone` by recreating the WebView.
> - **Minor:** the very first `browser_open` after installing the build returned success
>   but the two immediately-following parallel calls got `browser_not_open` (bind race on
>   first session creation; self-recovered on retry). Also: closing the browser UI with
>   the X button mid-session tears down the WebView — subsequent read calls return
>   `js_no_result` until the next `browser_open`. Both are survivable but worth knowing.

---

## Original report

Branch: `fix/browser-reliability` (base master `d6976f21`, v2.3.1-agent.26).
Scope: browser tool layer only. No git mutations performed; no local builds run (CI-only per policy).

---

## Root causes found

### E. Pages rendered "stretched wide sideways"
`BrowserWebViewConfig.configureWebViewForRikka` set `useWideViewPort = true` **and**
`loadWithOverviewMode = true` with no initial scale. In that combination the WebView lays the
page out against its "wide viewport" heuristic and then picks its own zoom-out factor, which
produces a CSS layout width decoupled from the physical device width. The field data point
(bitmap 1080 px wide, `window.innerWidth` = 782 CSS px instead of ~393 at density 2.75)
matches exactly: the page was laid out ~2× too wide and zoomed. Fix: `loadWithOverviewMode =
false` + `setInitialScale(density * 100)` — Chrome's 100 % zoom — which makes CSS width
deterministic (`viewWidthPx / density`) on every density.

### A.1 Stale title/DOM after browser_open (cross-page contamination)
The old wait was `awaitReadyState()`, which polls `document.readyState`. Immediately after
`loadUrl(newUrl)` the **previous** document is still the current document and its readyState
is already `"complete"`, so the wait returned instantly and the tool read the old page's
title/DOM. This also explains the "wait_for found text, get_dom right after said
selector_not_found" incoherence — the two calls straddled the document swap.
Fix: a pre-navigation stamp (`window.__rikkaNavStamp = true`) written into the *current*
document before `loadUrl`; a committed navigation replaces the document and wipes the stamp,
so `awaitNavigationSettled()` can wait for "fresh document AND readyState complete" — the
returned state now always describes the page at the current URL. Action tools (click/submit/
back/forward/click_and_read) use `awaitActionSettled()`, which additionally accepts "stamped
doc still complete after a 700 ms grace" so non-navigating actions don't hang for the full
timeout.

### A.2 Blank white screenshots right after navigation
`readyState === "complete"` fires when HTML+resources are loaded, **before** the renderer
produces its first frame; `WebView.draw(canvas)` at that instant captures the white backing.
(BrowserController's streamer already worked around this with a blind 600 ms sleep.)
Fix: `awaitFirstPaint()` built on `WebView.postVisualStateCallback` — the platform's actual
"DOM state is ready to draw" signal — bounded at 2.5 s, called by `browser_screenshot`
before capture.

### A.3 Fragment (#anchor) never scrolled
The WebView's native anchor jump happens (if at all) before layout is final, and is skipped
on same-document loads. Fix: `scrollToFragment(url)` runs after the navigation settles —
resolves `getElementById(decodeURIComponent(fragment))`, falls back to legacy
`a[name=...]`, and `scrollIntoView`s it. Best-effort no-op when the anchor doesn't exist.

### A.4 loop_detected false positives during recovery
Two causes in `GenerationHandler`:
1. Browser read tools were not in `READ_ONLY_OBSERVATION_TOOLS`, so identical
   `browser_screenshot` calls repeated across intervening browser *actions* counted as a
   loop (the act-observe reset rule never applied to them).
2. Calls that returned an **error envelope** counted the same as successful calls, so a
   model retrying after transient failures got blocked mid-recovery.
Fix: browser read tools (`screenshot`, `current_url`, `get_text`, `get_dom`, `get_links`,
`wait_for`) added to the freshness-TTL map (5 s) which also makes them observation tools —
any browser action, including a `browser_open` navigation, resets their repeat count. And
`PriorToolCall` gained `wasError` (parsed from the recorded output envelope); errored calls
are excluded from the repeat count. Threshold and trip-cap semantics are otherwise unchanged.

### B. Stale CSS/JS from the WebView cache
Nothing ever set `cacheMode` or cleared the cache, so per-URL asset caching served stale
stylesheets after on-disk changes (query-string tricks don't help since asset URLs are
unchanged). Fix, two layers in `navigateAndSettle`:
- **Default behaviour**: every `browser_open` navigation runs under `LOAD_NO_CACHE` (network
  revalidation for the document and its subresources), restored to `LOAD_DEFAULT` after the
  page settles so in-page runtime fetches keep normal caching.
- **`fresh: true` param on browser_open**: additionally `clearCache(true)` before the load,
  guaranteeing even in-page re-requests can't hit old entries. Cookies/localStorage untouched.

### 7. full_page screenshot was a no-op
The capture drew into a viewport-sized bitmap; nothing enabled whole-document drawing.
Fix: `WebView.enableSlowWholeDocumentDraw()` called before WebView construction in **both**
hosts (BrowserView foreground, HeadlessBrowserSession), and `browser_screenshot` now sizes
the bitmap to `contentHeight * scale` (min: viewport; max: existing 8192 px cap →
`capped_height: true`) and translates the canvas by `+scrollY` so the capture starts at the
document top. Envelope gains `full_page: true` on request; the misleading
`viewport_only: true` field is gone (it only ever appeared when `full_page` was passed, and
was a lie either way).

### D. Sessions "mixed into one" across conversations
`BrowserController` is a single global slot. `bindHeadless` already rejected *concurrent
headless* owners, but: (a) foreground mode had no ownership at all — any conversation's
tools drove the user-visible WebView and read each other's page state; (b) all non-open
tools dispatched via `withController()` with **no caller identity**, so even in headless
mode a second conversation's `browser_get_text` could read the first's page as long as it
didn't need to bind. Fix (strict ownership + session tagging — see tradeoff below):
- `BrowserController.ownerConvId` (`@Volatile`, mutated under the existing `bindLock`).
- `claimOwnership(convId)` — called by `browser_open` in both modes; refuses while a
  *different* conversation's task window is genuinely in flight (same lapse rule as
  `bindHeadless`, so a dead conversation can't lock the browser forever).
- `withController(callerConvId)` — every tool now passes its caller's conversation id; a
  non-owner gets `notOwnerEnvelope()` (`error: browser_busy` with recovery text telling it
  to claim its own session via browser_open) **before** the WebView is touched.
  `isOwnedBy` never allows silent takeover — a lapsed window frees the *claim*, not reads.
- `browser_done` → `releaseOwnership(callerConvId)` (owner-only; a non-owner calling
  browser_done can't yank the session). All unbind paths (`unbindForeground`,
  `unbindHeadless`, `clearModeIfHeadless`, `stopCurrentTask`) also clear ownership.
- Legacy callers with a null conversation id bypass the check (documented in code); those
  are interactive foreground paths where the user is watching.

---

## Per-file changes

| File | Change |
|---|---|
| `browser/BrowserWebViewConfig.kt` | E: `loadWithOverviewMode=false`, `setInitialScale(density*100)`. |
| `browser/BrowserController.kt` | D: `ownerConvId` + `claimOwnership`/`releaseOwnership`/`isOwnedBy` + `notOwnerEnvelope`, ownership cleared on all unbind paths; `withController(callerConvId)` gate. A: new suspend helpers `markPreNavigation`, `awaitNavigationSettled`, `awaitActionSettled`, `awaitFirstPaint` (postVisualStateCallback), `scrollToFragment`. |
| `browser/BrowserView.kt` | 7: `WebView.enableSlowWholeDocumentDraw()` before foreground WebView creation. |
| `browser/HeadlessBrowserSession.kt` | 7: same call before headless WebView creation. |
| `browser/BrowserToolDefaults.kt` | C.6: `SCROLL_TO`/`SCROLL_BY` constants, added to `WRITE_TOOLS` + `ALL_TOOLS` (catalogue now 20 tools). |
| `data/ai/tools/local/BrowserTools.kt` | A/B: `navigateAndSettle()` shared open body (stamp → optional clearCache → LOAD_NO_CACHE load → settle → LOAD_DEFAULT → fragment scroll); `fresh` param on browser_open; action tools use stamp + `awaitActionSettled`; screenshot uses `awaitFirstPaint` + real full-page capture. C.6: `browserScrollToTool`, `browserScrollByTool` + dispatch entries. D: every tool factory takes `invocationContext` and passes `callerConversationId` into `withController`; browser_open claims ownership, browser_done releases it. |
| `data/ai/GenerationHandler.kt` | A.4: browser read tools added to `FRESHNESS_TTL_MS_BY_TOOL` (5 s TTL, observation-reset rule); `PriorToolCall.wasError` (default false, source-compatible); LoopGuard excludes errored calls; call-site parses recorded output for an `"error"` key. |
| `data/ai/tools/ToolApprovalDefaults.kt` | C.6: `browser_scroll_to`, `browser_scroll_by` approval-gated like `browser_scroll`. |
| `app/src/test/.../browser/BrowserToolsTest.kt` | Catalogue counts 18→20, WRITE_TOOLS 8→10. |
| `app/src/test/.../browser/BrowserReliabilityOverhaulTest.kt` | **New**: ownership claim/release/isolation rules, loop-guard error-exclusion + browser observation reset, new-tool catalogue + args validation + `browser_not_open` short-circuits. |
| `.github/workflows/compile-check.yml` | Registered `BrowserToolsTest` (was unregistered) and `BrowserReliabilityOverhaulTest` following the existing `--tests` pattern. |

## Contract stability

All 10 mission tools (plus the other 8 pre-existing ones) keep their names, required params,
and success-envelope shapes. Additive-only changes: `browser_open` gains optional `fresh`;
`browser_screenshot` full_page envelope replaces the always-wrong `viewport_only: true` with
`full_page: true`/`capped_height: true` (that key only appeared on `full_page=true` requests,
which were documented as a no-op — no agent could have depended on it meaningfully);
`browser_busy` error is now also returned by non-open tools for non-owner conversations
(new error path, existing error code). New tools `browser_scroll_to` / `browser_scroll_by`
are registered in all three places (BrowserToolDefaults catalogue → LocalTools iterates it
automatically; createBrowserTool dispatch; ToolApprovalDefaults) and default OFF like every
write tool. `browser_eval` already existed as `browser_eval_js` (HARDLINE-gated) — kept
as-is rather than adding a duplicate name.

## Design decisions & tradeoffs

- **E — initial scale vs overview mode**: pinning scale to density gives correct CSS width
  for viewport-meta pages (the overwhelming case for agent verification work) at the cost of
  desktop-only pages overflowing horizontally instead of being zoomed out to fit. Chose
  correctness of layout width; agents can scroll/extract text on overflowing pages, and
  `browser_scroll_by` now handles x-axis scroll.
- **A.1 — JS stamp vs WebViewClient callbacks**: a `WebViewClient`-based "navigation
  generation counter" would also work but requires threading per-navigation state through
  both hosts' clients (BrowserView's client is also the user's UI client). The stamp is
  self-contained in the tool layer, works identically for foreground and headless, and
  degrades gracefully (stamp write fails → settle wait times out → tool proceeds, same as
  the old behaviour). Cost: one extra `evaluateJavascript` round trip per navigation.
- **A.4 — error-exclusion granularity**: only the *first* Text part of the recorded output
  is parsed for an `"error"` key, matching how every browser/local tool writes envelopes.
  A tool that legitimately returns `{"error": ...}` as a successful semantic result would be
  under-counted by the guard — acceptable: the guard is a cost safety net, and the trip-cap
  (6/turn) still bounds worst-case spend.
- **B — always LOAD_NO_CACHE on open**: guarantees the "verify after editing a file on
  disk" flow with zero agent effort, at the cost of slower repeat opens of unchanged remote
  pages (revalidation round trips; 304s keep it cheap). `fresh` stays for the hard-clear
  case. Restoring LOAD_DEFAULT after settle keeps SPA runtime fetches cached.
- **D — strict ownership over per-conversation instances**: headless already has
  per-conversation WebViews (the pool); the singleton is the *controller slot* and the
  single foreground Activity. Per-conversation foreground Activities are not meaningful (one
  screen), and multiplexing N live headless sessions through the one slot would need the
  whole streamer/mode architecture reworked. Ownership + tagging is the lightest approach
  that is actually correct: page state can never cross conversations because non-owners are
  rejected before the WebView is touched. Tradeoff: conversations serialize on the browser
  (by design — one WebView), and null-context legacy callers bypass the check.
- **7 — whole-document draw**: `enableSlowWholeDocumentDraw()` disables a WebView drawing
  optimization process-wide and must run before any WebView exists; it slightly increases
  drawing cost for very long pages but is the only non-stitching way to capture full pages
  with `draw(canvas)`. Stitch-by-scrolling was rejected: mutates viewport state mid-tool,
  races animations/lazy-loading, much more code. `contentHeight` is deprecated but has no
  non-deprecated replacement usable off the compositor; suppressed with a comment.

## Not verifiable without a device

- The actual rendered CSS width after the E fix (`window.innerWidth` ≈ widthPx/density) and
  whether any major sites regress under `loadWithOverviewMode=false`.
- `postVisualStateCallback` behaviour on the **headless** (never-attached) WebView — it is
  documented for attached views; if it never fires there, the 2.5 s timeout bounds the cost
  and capture proceeds (no worse than before). Flagged for runtime testing.
- Full-page capture correctness on hardware layer (foreground uses LAYER_TYPE_HARDWARE;
  `draw()` on a software canvas falls back to software rendering, but visual output needs
  eyes) and memory pressure of 8192-px bitmaps on low-RAM devices.
- The nav-stamp on sites with aggressive `window` property policing, and back/forward
  restores served from the back/forward cache (stamp may survive a BFCache restore → the
  700 ms grace path in `awaitActionSettled` covers it).
- Whether LOAD_NO_CACHE noticeably slows typical agent flows on real networks.

## Suggested manual runtime tests

1. **E**: open `https://example.com`, run `browser_eval_js` with
   `JSON.stringify({iw: window.innerWidth, dpr: devicePixelRatio})` — expect
   `iw ≈ screenWidthPx / dpr` (e.g. ~393 on a 1080 px / 2.75 density phone), text not tiny.
2. **A.1**: `browser_open(site A)` → `browser_open(site B)` back-to-back; the second
   envelope's `title` must belong to B. Then `browser_wait_for` a B-only selector followed
   immediately by `browser_get_dom` of the same selector — must be found.
3. **A.2**: cold `browser_open` of a heavy page then immediate `browser_screenshot` —
   no white frame.
4. **A.3**: `browser_open("https://en.wikipedia.org/wiki/Android_(operating_system)#History")`
   → screenshot shows the History section.
5. **A.4**: force a broken state (open an invalid host), then repeat
   `browser_screenshot` twice and `browser_open` retries — no `loop_detected` before real
   spinning (4+ identical successful calls with no intervening action).
6. **B**: serve a page via a local dev server, screenshot; edit the CSS file on disk;
   `browser_open` same URL again — new style must show. Repeat with `fresh: true`.
7. **7**: `browser_screenshot(full_page: true)` on a long article — returned PNG height >
   viewport, content from below the fold visible, `capped_height` on very long pages.
8. **D**: start a browser task from a Telegram conversation, then from the app chat call
   `browser_get_text` — expect `browser_busy` (not the Telegram page's text). After the
   first conversation's `browser_done`, `browser_open` from the second must succeed.
9. **C.6**: `browser_scroll_to("#footer")` scrolls the footer into view;
   `browser_scroll_by(y: -400)` scrolls up 400 px; both stream screenshots in headless mode.
