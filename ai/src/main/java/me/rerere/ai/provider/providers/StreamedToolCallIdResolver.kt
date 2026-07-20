package me.rerere.ai.provider.providers

/** Maps a provider's streamed tool/content-block index to the call id announced at its start. */
internal class StreamedToolCallIdResolver {
    private val idsByIndex = mutableMapOf<Int, String>()

    fun resolve(index: Int?, wireId: String?): String {
        if (index == null) return wireId.orEmpty()
        if (!wireId.isNullOrEmpty()) {
            idsByIndex[index] = wireId
            return wireId
        }
        return idsByIndex[index].orEmpty()
    }

    fun remove(index: Int?) {
        if (index != null) idsByIndex.remove(index)
    }
}
