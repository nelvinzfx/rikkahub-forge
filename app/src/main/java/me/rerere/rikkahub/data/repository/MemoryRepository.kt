package me.rerere.rikkahub.data.repository

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.db.fts.MemorySearchHit
import me.rerere.rikkahub.data.export.AssistantMemoryEntry
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val fts: MemoryFtsManager,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val MODE_CORE = "core"
        const val MODE_BANK = "bank"
        const val DEFAULT_CORE_TOKEN_BUDGET = 3000

        /** Maximum number of tags stored per memory record. */
        const val MAX_TAGS = 5

        /**
         * Maximum length of a single tag (in characters) after trimming.
         * Tags are short labels, not sentences; anything longer is discarded.
         * Namespaced tags such as `device:lenovo-ideapad-300` (27 chars)
         * and `project:conversation-recall` (27 chars) fit comfortably.
         */
        const val MAX_TAG_LENGTH = 50

        /**
         * Pure budgeting function: greedily selects rows whose per-entry cost
         * (content + title + tags lengths + 48-char overhead) fits within the
         * token budget (converted to characters at 4 chars/token). Oversized
         * entries are skipped but later smaller entries may still be included.
         * Input ordering is preserved in the output.
         */
        internal fun budgetCoreMemories(
            rows: List<MemoryEntity>,
            tokenBudget: Int,
        ): List<MemoryEntity> {
            var remainingChars = tokenBudget.coerceAtLeast(0) * 4
            if (remainingChars == 0) return emptyList()
            return buildList {
                rows.forEach { row ->
                    val cost = row.content.length + row.title.length + row.tags.length + 48
                    if (cost <= remainingChars) {
                        add(row)
                        remainingChars -= cost
                    }
                }
            }
        }
    }

    private val indexMutex = Mutex()
    @Volatile private var indexReady = false

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { rows -> rows.map(::toModel) }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map(::toModel)

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        getMemoriesOfAssistant(GLOBAL_MEMORY_ID)

    suspend fun getCoreMemories(assistantId: String, tokenBudget: Int = DEFAULT_CORE_TOKEN_BUDGET): List<AssistantMemory> =
        budgetCoreMemories(memoryDAO.getCoreMemoriesOfAssistant(assistantId), tokenBudget).map(::toModel)

    suspend fun getMemory(assistantId: String, id: Int): AssistantMemory? {
        val row = memoryDAO.getMemoryById(id)?.takeIf { it.assistantId == assistantId } ?: return null
        memoryDAO.markAccessed(id, System.currentTimeMillis())
        return toModel(row)
    }

    suspend fun search(assistantId: String, query: String, limit: Int = 10): List<MemorySearchHit> {
        require(query.isNotBlank()) { "query must not be empty" }
        ensureIndex()
        return fts.search(assistantId, query, limit)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        fts.deleteScope(assistantId)
    }

    suspend fun updateMemory(memory: AssistantMemory): AssistantMemory {
        val old = memoryDAO.getMemoryById(memory.id) ?: error("Memory record #${memory.id} not found")
        val now = System.currentTimeMillis()
        val row = old.copy(
            content = memory.content.trim(), title = memory.title.trim(),
            mode = normalizeMode(memory.mode), tags = encodeTags(memory.tags),
            importance = memory.importance.coerceIn(0, 100), updatedAt = now,
            sourceConversationId = memory.sourceConversationId, archived = memory.archived,
        )
        memoryDAO.updateMemory(row)
        fts.index(row)
        return toModel(row)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        return updateMemory(toModel(old).copy(content = content))
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        title: String = "",
        mode: String = MODE_CORE,
        tags: List<String> = emptyList(),
        importance: Int = 0,
        sourceConversationId: String? = null,
    ): AssistantMemory {
        val now = System.currentTimeMillis()
        val base = MemoryEntity(
            assistantId = assistantId, content = content.trim(), title = title.trim(),
            mode = normalizeMode(mode), tags = encodeTags(tags), importance = importance.coerceIn(0, 100),
            createdAt = now, updatedAt = now, sourceConversationId = sourceConversationId,
        )
        val row = base.copy(id = memoryDAO.insertMemory(base).toInt())
        fts.index(row)
        return toModel(row)
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        fts.delete(id)
    }

    /**
     * Full-fidelity rows for export: unlike [AssistantMemory] (UI model) these carry
     * lastAccessedAt/accessCount too, so migration between devices is lossless.
     */
    suspend fun getExportEntries(assistantId: String): List<AssistantMemoryEntry> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { row ->
            AssistantMemoryEntry(
                title = row.title,
                content = row.content,
                mode = normalizeMode(row.mode),
                tags = decodeTags(row.tags),
                importance = row.importance,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                lastAccessedAt = row.lastAccessedAt,
                accessCount = row.accessCount,
                sourceConversationId = row.sourceConversationId,
                archived = row.archived,
            )
        }

    /**
     * Insert an imported memory attributed to [assistantId], preserving ALL persisted
     * metadata (mode, tags, importance, timestamps, access stats, archived flag,
     * source conversation). Goes through the same insert + FTS index path as
     * [addMemory] so the search index stays consistent.
     */
    suspend fun insertImportedMemory(assistantId: String, entry: AssistantMemoryEntry): AssistantMemory {
        val base = MemoryEntity(
            assistantId = assistantId, content = entry.content.trim(), title = entry.title.trim(),
            mode = normalizeMode(entry.mode), tags = encodeTags(entry.tags),
            importance = entry.importance.coerceIn(0, 100), createdAt = entry.createdAt,
            updatedAt = entry.updatedAt, lastAccessedAt = entry.lastAccessedAt,
            accessCount = entry.accessCount.coerceAtLeast(0),
            sourceConversationId = entry.sourceConversationId, archived = entry.archived,
        )
        val row = base.copy(id = memoryDAO.insertMemory(base).toInt())
        fts.index(row)
        return toModel(row)
    }

    private suspend fun ensureIndex() {
        if (indexReady) return
        indexMutex.withLock {
            if (!indexReady) {
                fts.rebuild(memoryDAO.getAllMemories())
                indexReady = true
            }
        }
    }

    private fun toModel(row: MemoryEntity) = AssistantMemory(
        id = row.id, content = row.content, title = row.title, mode = normalizeMode(row.mode),
        tags = decodeTags(row.tags), importance = row.importance, createdAt = row.createdAt,
        updatedAt = row.updatedAt, sourceConversationId = row.sourceConversationId, archived = row.archived,
    )

    private fun normalizeMode(mode: String) = if (mode.equals(MODE_BANK, true)) MODE_BANK else MODE_CORE
    private fun encodeTags(tags: List<String>) = normalizeTags(tags).joinToString(",")
    private fun decodeTags(tags: String) = tags.split(',').map(String::trim).filter(String::isNotBlank)
}

/**
 * Normalise a raw tag list into a canonical form suitable for storage.
 *
 * Rules (applied in order):
 *  1. Trim surrounding whitespace from each tag.
 *  2. Lower-case using [Locale.ROOT] (stable across device locales).
 *  3. Discard blank tags (empty after trim).
 *  4. Discard tags longer than [MemoryRepository.MAX_TAG_LENGTH] characters
 *     — tags are short labels, not sentences.
 *  5. De-duplicate case-insensitively, preserving first-seen order.
 *  6. Cap the result to [MemoryRepository.MAX_TAGS] entries.
 *
 * Namespaced tags such as `device:lenovo-ideapad-300` and
 * `project:conversation-recall` survive every step; the colon is
 * treated as an ordinary character.
 *
 * Pure function — no Room, no Android dependency; safe to unit-test on JVM.
 */
internal fun normalizeTags(tags: List<String>): List<String> {
    val seen = HashSet<String>(tags.size)
    return buildList {
        for (raw in tags) {
            val tag = raw.trim().lowercase(Locale.ROOT)
            if (tag.isBlank()) continue
            if (tag.length > MemoryRepository.MAX_TAG_LENGTH) continue
            if (seen.add(tag)) {
                add(tag)
                if (size >= MemoryRepository.MAX_TAGS) break
            }
        }
    }
}
