package me.rerere.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class InputSchemaSerializationTest {

    @Test
    fun `object schema serializes definitions alongside nested references`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("files", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("\$ref", "#/\$defs/FileSpec") })
                })
            },
            required = listOf("files"),
            schema = "https://json-schema.org/draft/2020-12/schema",
            defs = buildJsonObject {
                put("FileSpec", buildJsonObject { put("type", "object") })
            },
        )

        val encoded = Json { encodeDefaults = true }.encodeToJsonElement(
            InputSchema.serializer(),
            schema,
        ).jsonObject

        assertEquals("object", encoded["type"]?.jsonPrimitive?.content)
        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            encoded["\$schema"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "#/\$defs/FileSpec",
            encoded["properties"]!!.jsonObject["files"]!!.jsonObject["items"]!!
                .jsonObject["\$ref"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "object",
            encoded["\$defs"]!!.jsonObject["FileSpec"]!!.jsonObject["type"]
                ?.jsonPrimitive?.content,
        )
    }
}
