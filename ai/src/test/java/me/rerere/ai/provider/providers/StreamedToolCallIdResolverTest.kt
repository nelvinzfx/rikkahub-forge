package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamedToolCallIdResolverTest {
    @Test
    fun `interleaved indexes resolve to their original tool call ids`() {
        val resolver = StreamedToolCallIdResolver()

        assertEquals("call-0", resolver.resolve(index = 0, wireId = "call-0"))
        assertEquals("call-1", resolver.resolve(index = 1, wireId = "call-1"))
        assertEquals("call-0", resolver.resolve(index = 0, wireId = null))
        assertEquals("call-1", resolver.resolve(index = 1, wireId = null))
        assertEquals("call-0", resolver.resolve(index = 0, wireId = ""))
    }

    @Test
    fun `removing a completed content block prevents stale id reuse`() {
        val resolver = StreamedToolCallIdResolver()
        resolver.resolve(index = 1, wireId = "call-1")

        resolver.remove(1)

        assertEquals("", resolver.resolve(index = 1, wireId = null))
    }
}
