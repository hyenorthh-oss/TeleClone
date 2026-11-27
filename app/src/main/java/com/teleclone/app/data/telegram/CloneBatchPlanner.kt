package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import kotlin.random.Random

enum class CloneMessageType {
    TEXT,
    PHOTO,
    VIDEO,
    AUDIO,
    DOCUMENT,
    VOICE_NOTE,
    ANIMATION,
    UNSUPPORTED,
}

enum class AlbumCompatibilityGroup {
    VISUAL_MEDIA,
    DOCUMENT,
    AUDIO,
}

data class CloneableMessage<T>(
    val id: Long,
    val mediaAlbumId: Long,
    val canBeSaved: Boolean,
    val type: CloneMessageType,
    val payload: T,
    val originThreadId: Long = 0L,
    val targetThreadId: Long = 0L,
    val canBeForwarded: Boolean = true,
)

data class SendUnit<T>(
    val items: List<CloneableMessage<T>>,
    val requiresIsolatedCall: Boolean = false,
) {
    val size: Int get() = items.size
    val lastMessageId: Long get() = items.last().id
    val targetThreadId: Long get() = items.first().targetThreadId
}

data class TdLibCallBatch<T>(
    val items: List<CloneableMessage<T>>,
) {
    val size: Int get() = items.size
    val lastMessageId: Long get() = items.last().id
    val targetThreadId: Long get() = items.first().targetThreadId
}

data class CloneBlock<T>(
    val units: List<SendUnit<T>>,
    val tdLibCalls: List<TdLibCallBatch<T>>,
) {
    val messageCount: Int get() = units.sumOf { it.size }
    val lastMessageId: Long get() = units.last().lastMessageId
    val targetThreadId: Long get() = units.first().targetThreadId
}

object CloneBatchPlanner {

    const val MAX_ALBUM_ITEMS = 10
    const val MAX_TDLIB_FORWARD_IDS = 100

    private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".mov", ".avi")

    fun isVideoDocument(mimeType: String?, fileName: String?): Boolean {
        val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
        if (normalizedMime.startsWith("video/")) return true
        val normalizedName = fileName?.trim()?.lowercase().orEmpty()
        return VIDEO_EXTENSIONS.any { normalizedName.endsWith(it) }
    }

    fun albumGroupOf(type: CloneMessageType): AlbumCompatibilityGroup? = when (type) {
        CloneMessageType.PHOTO,
        CloneMessageType.VIDEO -> AlbumCompatibilityGroup.VISUAL_MEDIA
        CloneMessageType.DOCUMENT -> AlbumCompatibilityGroup.DOCUMENT
        CloneMessageType.AUDIO -> AlbumCompatibilityGroup.AUDIO
        CloneMessageType.TEXT,
        CloneMessageType.VOICE_NOTE,
        CloneMessageType.ANIMATION,
        CloneMessageType.UNSUPPORTED -> null
    }

    fun isAllowedBySettings(type: CloneMessageType, settings: CloneSettings): Boolean = when (type) {
        CloneMessageType.TEXT -> settings.includeText
        CloneMessageType.PHOTO -> settings.includePhotos
        CloneMessageType.VIDEO,
        CloneMessageType.ANIMATION -> settings.includeVideos
        CloneMessageType.AUDIO,
        CloneMessageType.VOICE_NOTE -> settings.includeAudio
        CloneMessageType.DOCUMENT -> settings.includeDocuments
        CloneMessageType.UNSUPPORTED -> false
    }

    /**
     * Converts chronologically ordered messages into SendUnits:
     * - Skips non-forwardable (`!canBeSaved`) or unsupported/filtered messages without blocking.
     * - Groups consecutive messages sharing `mediaAlbumId != 0L` within the same `targetThreadId`.
     * - Separates incompatible media types (`Document`/`Audio` separated from `Photo`/`Video`).
     * - Splits albums larger than 10 items into sub-albums of at most 10 items.
     */
    fun <T> buildSendUnits(
        messages: List<CloneableMessage<T>>,
        settings: CloneSettings,
    ): List<SendUnit<T>> {
        if (messages.isEmpty()) return emptyList()

        val forwardable = messages
            .asSequence()
            .filter { it.canBeSaved && isAllowedBySettings(it.type, settings) }
            .sortedBy { it.id }
            .toList()

        if (forwardable.isEmpty()) return emptyList()

        val units = mutableListOf<SendUnit<T>>()
        var index = 0

        while (index < forwardable.size) {
            val current = forwardable[index]
            if (current.mediaAlbumId != 0L) {
                val albumId = current.mediaAlbumId
                val threadId = current.targetThreadId
                val rawRun = mutableListOf<CloneableMessage<T>>()
                while (index < forwardable.size &&
                    forwardable[index].mediaAlbumId == albumId &&
                    forwardable[index].targetThreadId == threadId
                ) {
                    rawRun.add(forwardable[index])
                    index++
                }
                units.addAll(splitAlbumRunIntoCompatibleUnits(rawRun))
            } else {
                units.add(SendUnit(items = listOf(current), requiresIsolatedCall = false))
                index++
            }
        }

        return units
    }

    private fun <T> splitAlbumRunIntoCompatibleUnits(
        rawRun: List<CloneableMessage<T>>,
    ): List<SendUnit<T>> {
        val groupedByCompatibility = linkedMapOf<AlbumCompatibilityGroup, MutableList<CloneableMessage<T>>>()
        val standaloneItems = mutableListOf<CloneableMessage<T>>()

        for (item in rawRun) {
            val group = albumGroupOf(item.type)
            if (group == null) {
                standaloneItems.add(item)
            } else {
                groupedByCompatibility.getOrPut(group) { mutableListOf() }.add(item)
            }
        }

        val candidateLists = mutableListOf<List<CloneableMessage<T>>>()
        for ((_, list) in groupedByCompatibility) {
            list.chunked(MAX_ALBUM_ITEMS).forEach { chunk ->
                candidateLists.add(chunk)
            }
        }
        standaloneItems.forEach { standalone ->
            candidateLists.add(listOf(standalone))
        }

        val needsIsolation = candidateLists.size > 1 || standaloneItems.isNotEmpty()
        return candidateLists
            .sortedBy { it.first().id }
            .map { chunk ->
                SendUnit(
                    items = chunk,
                    requiresIsolatedCall = needsIsolation,
                )
            }
    }

    /**
     * Groups SendUnits into execution blocks of up to [blockSize] messages.
     * - Never mixes messages from different `targetThreadId` topics in the same block.
     * - When [preserveAlbumIntegrity] is true, never splits an album across block boundaries.
     */
    fun <T> buildBlocks(
        units: List<SendUnit<T>>,
        blockSize: Int,
        preserveAlbumIntegrity: Boolean = true,
    ): List<CloneBlock<T>> {
        if (units.isEmpty()) return emptyList()
        val safeBlockSize = blockSize.coerceAtLeast(1)

        val blocks = mutableListOf<CloneBlock<T>>()
        val currentUnits = mutableListOf<SendUnit<T>>()
        var currentCount = 0
        var currentTopicId: Long? = null

        fun flushBlock() {
            if (currentUnits.isNotEmpty()) {
                blocks.add(
                    CloneBlock(
                        units = currentUnits.toList(),
                        tdLibCalls = sliceUnitsIntoTdLibCalls(currentUnits),
                    )
                )
                currentUnits.clear()
                currentCount = 0
                currentTopicId = null
            }
        }

        for (unit in units) {
            val topicChanged = currentTopicId != null && currentTopicId != unit.targetThreadId
            val wouldExceed = currentCount + unit.size > safeBlockSize

            if (currentUnits.isNotEmpty() &&
                (topicChanged || currentCount >= safeBlockSize || (preserveAlbumIntegrity && wouldExceed))
            ) {
                flushBlock()
            }

            if (!preserveAlbumIntegrity && unit.size > safeBlockSize && currentUnits.isEmpty()) {
                unit.items.chunked(safeBlockSize).forEach { piece ->
                    val pieceUnit = SendUnit(piece, requiresIsolatedCall = unit.requiresIsolatedCall)
                    blocks.add(
                        CloneBlock(
                            units = listOf(pieceUnit),
                            tdLibCalls = sliceUnitsIntoTdLibCalls(listOf(pieceUnit)),
                        )
                    )
                }
            } else {
                currentUnits.add(unit)
                currentCount += unit.size
                currentTopicId = unit.targetThreadId
            }
        }

        flushBlock()
        return blocks
    }

    /**
     * Slices a block's SendUnits into sequential TDLib `ForwardMessages` calls of at most [maxIdsPerCall] (100) IDs.
     * - Never mixes different `targetThreadId` topics in the same call.
     * - Never splits an album across a call boundary, and isolates split/mixed album sub-units.
     */
    fun <T> sliceUnitsIntoTdLibCalls(
        units: List<SendUnit<T>>,
        maxIdsPerCall: Int = MAX_TDLIB_FORWARD_IDS,
    ): List<TdLibCallBatch<T>> {
        if (units.isEmpty()) return emptyList()
        val safeMax = maxIdsPerCall.coerceIn(1, MAX_TDLIB_FORWARD_IDS)

        val calls = mutableListOf<TdLibCallBatch<T>>()
        val buffer = mutableListOf<CloneableMessage<T>>()
        var bufferTopicId: Long? = null

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                calls.add(TdLibCallBatch(buffer.toList()))
                buffer.clear()
                bufferTopicId = null
            }
        }

        for (unit in units) {
            if (unit.requiresIsolatedCall) {
                flushBuffer()
                unit.items.chunked(safeMax).forEach { part ->
                    calls.add(TdLibCallBatch(part))
                }
            } else {
                val topicChanged = bufferTopicId != null && bufferTopicId != unit.targetThreadId
                if (buffer.isNotEmpty() && (topicChanged || buffer.size + unit.size > safeMax)) {
                    flushBuffer()
                }
                if (unit.size > safeMax) {
                    unit.items.chunked(safeMax).forEach { part ->
                        calls.add(TdLibCallBatch(part))
                    }
                } else {
                    buffer.addAll(unit.items)
                    bufferTopicId = unit.targetThreadId
                }
            }
        }

        flushBuffer()
        return calls
    }

    fun computeJitterDelayMs(
        minDelayMs: Long,
        maxDelayMs: Long,
        random: Random = Random.Default,
    ): Long {
        val safeMin = minDelayMs.coerceAtLeast(0L)
        val safeMax = maxDelayMs.coerceAtLeast(safeMin)
        return if (safeMin == safeMax) {
            safeMin
        } else {
            random.nextLong(safeMin, safeMax + 1L)
        }
    }
}
