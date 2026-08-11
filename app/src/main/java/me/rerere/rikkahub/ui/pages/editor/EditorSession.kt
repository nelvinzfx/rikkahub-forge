package me.rerere.rikkahub.ui.pages.editor

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Serialized form of the open-tab session. Draft TEXT is not stored here: a
 * dirty tab can hold up to 5 MB, so drafts live in per-tab files under
 * filesDir/editor/drafts/ and the manifest only records that one exists.
 * JsonInstant is ignoreUnknownKeys + encodeDefaults, so the format tolerates
 * future fields without a version bump.
 */
@Serializable
data class SessionTab(
    val uri: String,
    val name: String,
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    val hasDraft: Boolean = false,
    val diskLastModified: Long = 0L,
    val diskLength: Long = -1L,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
)

@Serializable
data class EditorSession(
    val version: Int = 1,
    val tabs: List<SessionTab> = emptyList(),
    val activeUri: String? = null,
    val expandedDirs: Set<String> = emptySet(),
)

object EditorSessionCodec {
    fun encode(session: EditorSession): String = JsonInstant.encodeToString(session)

    /** null on any parse error — a corrupt manifest must cold-start, not crash */
    fun decode(raw: String): EditorSession? = runCatching {
        JsonInstant.decodeFromString<EditorSession>(raw)
    }.getOrNull()
}

/** stable, filesystem-safe draft name derived from the document uri */
fun draftFileName(uri: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) } + ".txt"
}
