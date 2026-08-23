package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the Moonshot-via-LLMGateway 400s on kimi-k3:
 *  - "Conflict in schema definitions for key 'anyOf'" (allOf of sibling anyOfs)
 *  - "type should be defined in anyOf items instead of the parent schema"
 *  - "conflicting keywords (required) are defined on the parent schema and inside anyOf"
 *
 * Moonshot's "moonshot flavored json schema" dialect rejects every combinator shape we
 * tried; a plain object schema with no anyOf/allOf/oneOf is the only form verified
 * HTTP 200 against the live provider. The match/write alias constraint is enforced by
 * the engine (parseTermuxEditRequest), not the schema. These tests pin that the schema
 * stays combinator-free so nobody reintroduces a provider-hostile shape.
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
    fun editSpecSchemaUsesNoCombinatorsAnywhere() {
        val counts = mutableMapOf("allOf" to 0, "anyOf" to 0, "oneOf" to 0, "not" to 0)
        walkKeys(editSpecSchema()) { key ->
            counts.computeIfPresent(key) { _, v -> v + 1 }
        }
        assertEquals(
            "schema must stay a plain object; combinators 400 on strict provider dialects",
            mapOf("allOf" to 0, "anyOf" to 0, "oneOf" to 0, "not" to 0),
            counts,
        )
    }

    @Test
    fun editSpecSchemaIsPlainClosedObject() {
        val schema = editSpecSchema()
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }

    @Test
    fun editSpecSchemaKeepsAliasPropertiesAndModeRequired() {
        val schema = editSpecSchema()

        val props = schema["properties"]!!.jsonObject.keys
        assertTrue(props.containsAll(listOf("mode", "match_text", "matchText", "write_text", "writeText", "occurrence")))

        // occurrence stays a combinator-free union type array (like expected_sha256's
        // ["string","null"]), which strict provider dialects accept.
        val occurrenceType = schema["properties"]!!.jsonObject["occurrence"]!!.jsonObject["type"]!!.jsonArray
        assertEquals(listOf("string", "integer"), occurrenceType.map { it.jsonPrimitive.content })

        // only mode is schema-required; the match/write constraint lives in the engine
        assertEquals(
            listOf("mode"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
