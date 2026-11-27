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
)

data class SendUnit<T>(
    val items: List<CloneableMessage<T>>,
    val requiresIsolatedCall: Boolean = false,
) {
    val size: Int get() = items.size
    val lastMessageId: Long get() = items.last().id
}

data class TdLibCallBatch<T>(
    val items: List<CloneableMessage<T>>,
) {
    val size: Int get() = items.size
    val lastMessageId: Long get() = items.last().id
}

data class CloneBlock<T>(
    val units: List<SendUnit<T>>,
    val tdLibCalls: List<TdLibCallBatch<T>>,
) {
    val messageCount: Int get() = units.sumOf { it.size }
    val lastMessageId: Long get() = units.last().lastMessageId
}

object CloneBatchPlanner {

    const val MAX_ALBUM_ITEMS = 10
    const val MAX_TDLIB_FORWARD_IDS = 100

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
     * - Groups consecutive messages sharing `mediaAlbumId != 0L`.
     * - Separates incompatible media types (e.g. Photo/Video cannot mix with Document/Audio).
     * - Splits albums larger than 10 items into sub-albums of at most 10 items.
     * - Marks split/mixed sub-albums as `requiresIsolatedCall = true` so TDLib forwards each sub-album separately.
     */
    fun <T> buildSendUnits(
        messages: List<CloneableMessage<T>>,
        settings: CloneSettings,
    ): List<SendUnit<T>> {
        if (messages.isEmpty()) return emptyList()

        // Filter out non-forwardable/system/disabled messages first so a system message in the
        // middle of an album never splits that album into separate sends.
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
                val rawRun = mutableListOf<CloneableMessage<T>>()
                while (index < forwardable.size && forwardable[index].mediaAlbumId == albumId) {
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
     * When [preserveAlbumIntegrity] is true, an album (`SendUnit`) is never split across block boundaries.
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

        for (unit in units) {
            val wouldExceed = currentCount + unit.size > safeBlockSize
            if (currentUnits.isNotEmpty() && (currentCount >= safeBlockSize || (preserveAlbumIntegrity && wouldExceed))) {
                blocks.add(
                    CloneBlock(
                        units = currentUnits.toList(),
                        tdLibCalls = sliceUnitsIntoTdLibCalls(currentUnits),
                    )
                )
                currentUnits.clear()
                currentCount = 0
            }

            if (!preserveAlbumIntegrity && unit.size > safeBlockSize && currentUnits.isEmpty()) {
                // Only if integrity preservation is explicitly disabled, split unit items into blockSize pieces
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
            }
        }

        if (currentUnits.isNotEmpty()) {
            blocks.add(
                CloneBlock(
                    units = currentUnits.toList(),
                    tdLibCalls = sliceUnitsIntoTdLibCalls(currentUnits),
                )
            )
        }

        return blocks
    }

    /**
     * Slices a block's SendUnits into sequential TDLib `ForwardMessages` calls of at most [maxIdsPerCall] (100) IDs.
     * Never splits an album (`SendUnit`) across a call boundary, and isolates split/mixed album sub-units.
     */
    fun <T> sliceUnitsIntoTdLibCalls(
        units: List<SendUnit<T>>,
        maxIdsPerCall: Int = MAX_TDLIB_FORWARD_IDS,
    ): List<TdLibCallBatch<T>> {
        if (units.isEmpty()) return emptyList()
        val safeMax = maxIdsPerCall.coerceIn(1, MAX_TDLIB_FORWARD_IDS)

        val calls = mutableListOf<TdLibCallBatch<T>>()
        val buffer = mutableListOf<CloneableMessage<T>>()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                calls.add(TdLibCallBatch(buffer.toList()))
                buffer.clear()
            }
        }

        for (unit in units) {
            if (unit.requiresIsolatedCall) {
                flushBuffer()
                unit.items.chunked(safeMax).forEach { part ->
                    calls.add(TdLibCallBatch(part))
                }
            } else {
                if (buffer.isNotEmpty() && buffer.size + unit.size > safeMax) {
                    flushBuffer()
                }
                if (unit.size > safeMax) {
                    unit.items.chunked(safeMax).forEach { part ->
                        calls.add(TdLibCallBatch(part))
                    }
                } else {
                    buffer.addAll(unit.items)
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
        val safeMin = minDelayMs.coerceAtLeast(1L)
        val safeMax = maxDelayMs.coerceAtLeast(safeMin)
        return if (safeMin == safeMax) {
            safeMin
        } else {
            random.nextLong(safeMin, safeMax + 1L)
        }
    }
}
