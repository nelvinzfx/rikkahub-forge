package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the Kimi-via-LLMGateway 400:
 * "Conflict in schema definitions for key 'anyOf'. Previous: [{'required': ['match_text']},
 * {'required': ['matchText']}], New: [{'required': ['write_text']}, {'required': ['writeText']}]".
 *
 * Strict provider-side schema flatteners (Kimi, Fireworks, LiteLLM) merge sibling allOf
 * branches into one flat object and reject the request when two branches declare the same
 * combinator key with different values. editSpecSchema must therefore stay a single flat
 * anyOf of the four alias combinations, with no allOf and no nested anyOf anywhere.
 */
class TermuxEditToolSchemaTest {

    private fun walkKeys(node: Any?, onKey: (String) -> Unit) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) -> onKey(key); walkKeys(value, onKey) }
            is JsonArray -> node.forEach { walkKeys(it, onKey) }
            else -> {}
        }
    }

    @Test
    fun editSpecSchemaIsSingleFlatAnyOfOfFourAliasCombinations() {
        val schema = editSpecSchema()

        val anyOf = schema["anyOf"]?.jsonArray ?: error("schema must carry a top-level anyOf")
        assertEquals(4, anyOf.size)

        val combos = anyOf.map { entry ->
            entry.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }.toSet()
        assertEquals(
            setOf(
                setOf("match_text", "write_text"),
                setOf("match_text", "writeText"),
                setOf("matchText", "write_text"),
                setOf("matchText", "writeText"),
            ),
            combos,
        )
    }

    @Test
    fun editSpecSchemaHasNoConflictingCombinatorsAnywhere() {
        var allOfCount = 0
        var anyOfCount = 0
        walkKeys(editSpecSchema()) { key ->
            if (key == "allOf") allOfCount++
            if (key == "anyOf") anyOfCount++
        }
        assertEquals("allOf must not appear (provider flatteners conflict on it)", 0, allOfCount)
        assertEquals("exactly one anyOf (the flat top-level one)", 1, anyOfCount)
    }

    @Test
    fun editSpecSchemaKeepsAliasPropertiesAndModeRequired() {
        val schema = editSpecSchema()

        val props = schema["properties"]!!.jsonObject.keys
        assertTrue(props.containsAll(listOf("mode", "match_text", "matchText", "write_text", "writeText")))

        // only mode is unconditionally required; the match/write constraint lives in the anyOf
        assertEquals(
            listOf("mode"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(schema.containsKey("allOf"))
    }
}
