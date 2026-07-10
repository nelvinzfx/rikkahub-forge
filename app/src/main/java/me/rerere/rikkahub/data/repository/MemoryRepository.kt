package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.db.fts.MemorySearchHit
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

    suspend fun getCoreMemories(assistantId: String, tokenBudget: Int = DEFAULT_CORE_TOKEN_BUDGET): List<AssistantMemory> {
        var remainingChars = tokenBudget.coerceAtLeast(0) * 4
        if (remainingChars == 0) return emptyList()
        return buildList {
            memoryDAO.getCoreMemoriesOfAssistant(assistantId).forEach { row ->
                val cost = row.content.length + row.title.length + row.tags.length + 48
                if (cost <= remainingChars) {
                    add(toModel(row))
                    remainingChars -= cost
                }
            }
        }
    }

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
    private fun encodeTags(tags: List<String>) = tags.map(String::trim).filter(String::isNotBlank).distinct().joinToString(",")
    private fun decodeTags(tags: String) = tags.split(',').map(String::trim).filter(String::isNotBlank)
}
