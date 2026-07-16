package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpToolSchemaConversionTest {

    @Test
    fun `sdk tool schema conversion preserves definitions used by nested refs`() {
        val converted = ToolSchema(
            schema = "https://json-schema.org/draft/2020-12/schema",
            properties = buildJsonObject {
                put("edits", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("\$ref", "#/\$defs/EditSpec") })
                })
            },
            required = listOf("edits"),
            defs = buildJsonObject {
                put("EditSpec", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("mode", buildJsonObject { put("type", "string") })
                    })
                })
            },
        ).toSchema() as InputSchema.Obj

        assertEquals("https://json-schema.org/draft/2020-12/schema", converted.schema)
        assertEquals(listOf("edits"), converted.required)
        assertEquals(
            "#/\$defs/EditSpec",
            converted.properties["edits"]!!.jsonObject["items"]!!
                .jsonObject["\$ref"]?.jsonPrimitive?.content,
        )
        assertNotNull(converted.defs?.get("EditSpec"))
    }
}
