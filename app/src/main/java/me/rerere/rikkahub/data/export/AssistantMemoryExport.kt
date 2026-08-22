package me.rerere.rikkahub.data.export

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Lossless per-assistant memory dump used to migrate an assistant's memories
 * (core + bank) between devices.
 *
 * Unlike [AssistantMemory] (the UI model), each entry carries EVERY persisted
 * column of `MemoryEntity`, including `lastAccessedAt` and `accessCount`.
 *
 * The device-local auto-increment id is deliberately OMITTED: it is a per-device
 * row identity only and would collide / mislead on the importing device. New ids
 * are assigned by the target database on insert.
 */
@Serializable
data class AssistantMemoryEntry(
    val title: String = "",
    val content: String = "",
    /** One of [MEMORY_SCOPE_CORE] / [MEMORY_SCOPE_BANK]; unknown values normalize to core on import. */
    val mode: String = MEMORY_SCOPE_CORE,
    val tags: List<String> = emptyList(),
    val importance: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val accessCount: Int = 0,
    val sourceConversationId: String? = null,
    val archived: Boolean = false,
)

/** Which slice of the memory store an export covers. */
const val MEMORY_SCOPE_CORE = "core"
const val MEMORY_SCOPE_BANK = "bank"
const val MEMORY_SCOPE_ALL = "all"

@Serializable
data class AssistantMemoryExport(
    val format: String = FORMAT,
    val formatVersion: Int = VERSION,
    val exportedAt: String,
    val sourceAssistantId: String,
    val sourceAssistantName: String,
    val scope: String,
    val memories: List<AssistantMemoryEntry>,
) {
    companion object {
        const val FORMAT = "rikkahub-assistant-memory"
        const val VERSION = 1
    }
}

/**
 * Pure mode normalization mirroring MemoryRepository's rule: anything that is not
 * (case-insensitively) "bank" is treated as core.
 */
fun normalizeMemoryEntryMode(mode: String?): String =
    if (mode.equals(MEMORY_SCOPE_BANK, true)) MEMORY_SCOPE_BANK else MEMORY_SCOPE_CORE

/** Filter entries down to the requested export scope ("all" keeps everything). */
fun filterMemoriesByScope(entries: List<AssistantMemoryEntry>, scope: String): List<AssistantMemoryEntry> =
    when (scope) {
        MEMORY_SCOPE_CORE -> entries.filter { normalizeMemoryEntryMode(it.mode) == MEMORY_SCOPE_CORE }
        MEMORY_SCOPE_BANK -> entries.filter { normalizeMemoryEntryMode(it.mode) == MEMORY_SCOPE_BANK }
        else -> entries
    }

/**
 * Pure format assembly. The caller (VM/UI) resolves which rows belong to the
 * currently viewed assistant; this stays free of Android dependencies.
 */
fun buildAssistantMemoryExport(
    memories: List<AssistantMemoryEntry>,
    sourceAssistantId: String,
    sourceAssistantName: String,
    scope: String,
    exportedAt: LocalDateTime = LocalDateTime.now(),
): AssistantMemoryExport {
    val effectiveScope = when (scope) {
        MEMORY_SCOPE_CORE -> MEMORY_SCOPE_CORE
        MEMORY_SCOPE_BANK -> MEMORY_SCOPE_BANK
        else -> MEMORY_SCOPE_ALL
    }
    return AssistantMemoryExport(
        exportedAt = exportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        sourceAssistantId = sourceAssistantId,
        sourceAssistantName = sourceAssistantName,
        scope = effectiveScope,
        memories = filterMemoriesByScope(memories, effectiveScope),
    )
}

/** Pretty-printed JSON document for the export file. */
fun serializeAssistantMemoryExport(export: AssistantMemoryExport): String =
    JsonInstantPretty.encodeToString(AssistantMemoryExport.serializer(), export)

private val MEMORY_EXPORT_FILE_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * rikkahub-assistant-memory-<sanitized-name-or-shortId>-<yyyyMMdd-HHmmss>.json
 * Same sanitization approach as [rawExportFileName]: non-letter/digit runs collapse
 * to '-', trim edge dashes, cap at 40 chars, fall back to the first 8 chars of the
 * assistant id when nothing usable remains.
 */
fun assistantMemoryExportFileName(
    assistantName: String,
    assistantId: String,
    now: LocalDateTime = LocalDateTime.now(),
): String {
    val sanitized = assistantName.trim()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(40)
    val stem = sanitized.ifEmpty { assistantId.take(8) }
    return "rikkahub-assistant-memory-$stem-${now.format(MEMORY_EXPORT_FILE_TIMESTAMP)}.json"
}

/** Result of parsing a memory export document. */
sealed interface AssistantMemoryImportParse {
    /** Successfully parsed and validated document. */
    data class Success(
        val sourceAssistantId: String,
        val sourceAssistantName: String,
        val scope: String,
        val memories: List<AssistantMemoryEntry>,
    ) : AssistantMemoryImportParse

    /** Rejected document; [reason] is one of the FAILURE_* constants below. */
    data class Failure(val reason: String) : AssistantMemoryImportParse
}

/** Stable (non-localized) failure reasons emitted by [parseAssistantMemoryImport]. */
const val MEMORY_IMPORT_FAILURE_INVALID_JSON = "invalid_json"
const val MEMORY_IMPORT_FAILURE_UNKNOWN_FORMAT = "unknown_format"
const val MEMORY_IMPORT_FAILURE_UNSUPPORTED_VERSION = "unsupported_version"
const val MEMORY_IMPORT_FAILURE_EMPTY = "empty"

/**
 * Parse + validate a memory export document.
 *
 * - Rejects malformed JSON ([MEMORY_IMPORT_FAILURE_INVALID_JSON]).
 * - Rejects documents whose top-level `format` is not [AssistantMemoryExport.FORMAT]
 *   ([MEMORY_IMPORT_FAILURE_UNKNOWN_FORMAT]) — e.g. a conversation export or a random
 *   JSON file must never be imported as memories.
 * - Rejects known-format documents with a future/incompatible `formatVersion`
 *   ([MEMORY_IMPORT_FAILURE_UNSUPPORTED_VERSION]).
 * - Rejects documents carrying zero memories ([MEMORY_IMPORT_FAILURE_EMPTY]).
 * - Unknown/missing optional fields fall back to defaults (mode -> core) thanks to
 *   the serializable defaults; unknown keys are ignored.
 */
fun parseAssistantMemoryImport(text: String): AssistantMemoryImportParse {
    val root = try {
        JsonInstantPretty.parseToJsonElement(text).let {
            it as? kotlinx.serialization.json.JsonObject
        } ?: return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_INVALID_JSON)
    } catch (_: Exception) {
        return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_INVALID_JSON)
    }
    val declaredFormat = (root["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (declaredFormat != AssistantMemoryExport.FORMAT) {
        return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_UNKNOWN_FORMAT)
    }
    val declaredVersion = (root["formatVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    if (declaredVersion != AssistantMemoryExport.VERSION) {
        return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_UNSUPPORTED_VERSION)
    }
    val export = try {
        JsonInstantPretty.decodeFromJsonElement(AssistantMemoryExport.serializer(), root)
    } catch (_: Exception) {
        return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_INVALID_JSON)
    }
    if (export.memories.isEmpty()) {
        return AssistantMemoryImportParse.Failure(MEMORY_IMPORT_FAILURE_EMPTY)
    }
    return AssistantMemoryImportParse.Success(
        sourceAssistantId = export.sourceAssistantId,
        sourceAssistantName = export.sourceAssistantName,
        scope = export.scope,
        // Normalize modes defensively at the boundary so downstream code sees canonical values.
        memories = export.memories.map { it.copy(mode = normalizeMemoryEntryMode(it.mode)) },
    )
}

/** Outcome plan after duplicate filtering. */
data class MemoryImportPlan(
    val toImport: List<AssistantMemoryEntry>,
    val skippedAsDuplicate: Int,
)

/**
 * Split incoming entries into (to import, duplicates).
 *
 * A memory is a DUPLICATE when its normalized mode + trimmed title + trimmed content
 * exactly match an existing memory of the target assistant; such rows are skipped so
 * re-importing the same file twice does not double the memory store. All other fields
 * (timestamps, importance, tags, ...) are ignored for comparison.
 *
 * Pure function over the UI model [AssistantMemory] — unit-testable on JVM.
 */
fun planMemoryImport(
    incoming: List<AssistantMemoryEntry>,
    existing: List<AssistantMemory>,
): MemoryImportPlan {
    val existingKeys = existing.mapTo(HashSet()) { memory ->
        Triple(
            normalizeMemoryEntryMode(memory.mode),
            memory.title.trim(),
            memory.content.trim(),
        )
    }
    val toImport = ArrayList<AssistantMemoryEntry>(incoming.size)
    var skipped = 0
    for (entry in incoming) {
        val key = Triple(
            normalizeMemoryEntryMode(entry.mode),
            entry.title.trim(),
            entry.content.trim(),
        )
        if (key in existingKeys) {
            skipped++
        } else {
            toImport.add(entry)
            // Also count newly-planned entries as present: a single file containing
            // internal duplicates must not import the same memory twice.
            existingKeys.add(key)
        }
    }
    return MemoryImportPlan(toImport = toImport, skippedAsDuplicate = skipped)
}

/** Result handed back to the UI after a full import run. */
sealed interface AssistantMemoryImportResult {
    data class Imported(val importedCount: Int, val skippedCount: Int) : AssistantMemoryImportResult
    data class Failed(val reason: String) : AssistantMemoryImportResult
}
