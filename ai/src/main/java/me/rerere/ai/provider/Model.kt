package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID as JavaUUID
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Optional capability/pricing metadata, populated from OpenRouter's /models endpoint.
    val contextLength: Int? = null,
    val supportedParameters: List<String> = emptyList(),
    val pricePromptPerToken: Double? = null,
    val priceCompletionPerToken: Double? = null,
    // null = auto-detect via computeAIIconByName
    // "asset:openai.svg" = built-in asset icon
    // "file:///..." or "https://..." = custom file/URL
    val customIcon: String? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}

/**
 * Generate a deterministic Uuid for a Model based on its providerId + modelId.
 *
 * Fixes the issue where re-selecting a model after de-selecting generates a
 * new random Uuid (via [Model]'s default `id = Uuid.random()`), breaking all
 * conversations that referenced the old Uuid via `chatModelId`.
 *
 * Using UUID.nameUUIDFromBytes (v3 / MD5-based) ensures the same
 * providerId + modelId pair always produces the same Uuid, so deselect /
 * reselect cycles preserve model identity across existing conversations.
 */
fun deterministicModelId(providerId: Uuid, modelId: String): Uuid {
    val name = "$providerId::$modelId".toByteArray(Charsets.UTF_8)
    return Uuid.parse(JavaUUID.nameUUIDFromBytes(name).toString())
}


