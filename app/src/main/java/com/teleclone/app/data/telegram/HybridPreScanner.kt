package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneMessageEntity
import com.teleclone.app.data.model.CloneMessageStatus
import com.teleclone.app.data.model.CloneSettings
import kotlinx.coroutines.delay

enum class SearchFilterKind {
    VIDEO,
    PHOTO,
    DOCUMENT,
    AUDIO,
}

data class ForumTopicDescriptor(
    val threadId: Long,
    val name: String,
    val iconColor: Int = 0,
    val iconCustomEmojiId: Long = 0L,
    val isGeneral: Boolean = false,
)

data class TopicMappingResult(
    val sourceIsForum: Boolean,
    val targetIsForum: Boolean,
    val topicCount: Int,
    val threadMap: Map<Long, Long>,
)

data class SearchPageResult<T>(
    val messages: List<CloneableMessage<T>>,
    val nextFromMessageId: Long,
)

data class PreScanResult<T>(
    val totalEligible: Int,
    val videoCount: Int,
    val photoCount: Int,
    val otherCount: Int,
    val topicCount: Int,
    val filteredSkippedCount: Int,
    val targetIsForum: Boolean,
    val queuedItems: List<CloneMessageEntity>,
    val messagesById: Map<Long, CloneableMessage<T>>,
)

interface HybridScanGateway<T> {
    suspend fun isForumSupergroup(chatId: Long): Boolean
    suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor>
    suspend fun createForumTopic(chatId: Long, topic: ForumTopicDescriptor): ForumTopicDescriptor
    suspend fun fetchHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): List<CloneableMessage<T>>
    suspend fun searchChatMessages(
        chatId: Long,
        filter: SearchFilterKind,
        fromMessageId: Long,
        limit: Int,
    ): SearchPageResult<T>
}

object HybridPreScanner {

    const val HISTORY_CACHE_MISS_RETRIES = 3
    const val HISTORY_CACHE_MISS_DELAY_MS = 1_500L

    private suspend fun <R> runWithReliability(
        timeoutMs: Long,
        delayFn: suspend (Long) -> Unit,
        onFloodWaitTick: suspend (Int) -> Unit,
        block: suspend () -> R,
    ): R {
        return when (
            val outcome = CloneReliabilityExecutor.executeWithPolicy(
                timeoutMs = timeoutMs,
                skipOnForwardsRestricted = false,
                onFloodWaitTick = onFloodWaitTick,
                delayFn = delayFn,
                block = block,
            )
        ) {
            is ReliabilityOutcome.Success -> outcome.value
            is ReliabilityOutcome.RestrictedSkipped -> throw IllegalStateException(outcome.reason)
        }
    }

    suspend fun <T> mapForumTopics(
        sourceChatId: Long,
        targetChatId: Long,
        gateway: HybridScanGateway<T>,
        timeoutMs: Long = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        onFloodWaitTick: suspend (Int) -> Unit = {},
    ): TopicMappingResult {
        val sourceIsForum = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
            gateway.isForumSupergroup(sourceChatId)
        }
        val targetIsForum = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
            gateway.isForumSupergroup(targetChatId)
        }

        if (!sourceIsForum) {
            return TopicMappingResult(
                sourceIsForum = false,
                targetIsForum = targetIsForum,
                topicCount = 0,
                threadMap = emptyMap(),
            )
        }

        val sourceTopics = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
            gateway.listForumTopics(sourceChatId)
        }
        if (!targetIsForum) {
            val fallbackMap = sourceTopics.associate { it.threadId to 0L }
            return TopicMappingResult(
                sourceIsForum = true,
                targetIsForum = false,
                topicCount = sourceTopics.size,
                threadMap = fallbackMap,
            )
        }

        val targetTopics = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
            gateway.listForumTopics(targetChatId)
        }
        val targetGeneral = targetTopics.firstOrNull { it.isGeneral }
        val targetByName = targetTopics
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()

        val mapping = LinkedHashMap<Long, Long>()
        for (srcTopic in sourceTopics) {
            val normalizedName = srcTopic.name.trim().lowercase()
            val matchedTarget = when {
                srcTopic.isGeneral && targetGeneral != null -> targetGeneral
                targetByName.containsKey(normalizedName) -> targetByName[normalizedName]
                else -> null
            }

            if (matchedTarget != null) {
                mapping[srcTopic.threadId] = matchedTarget.threadId
            } else {
                val created = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
                    gateway.createForumTopic(targetChatId, srcTopic)
                }
                mapping[srcTopic.threadId] = created.threadId
                targetByName[normalizedName] = created
            }
        }

        return TopicMappingResult(
            sourceIsForum = true,
            targetIsForum = true,
            topicCount = sourceTopics.size,
            threadMap = mapping,
        )
    }

    suspend fun <T> scan(
        taskId: Long,
        sourceChatId: Long,
        targetChatId: Long,
        settings: CloneSettings,
        gateway: HybridScanGateway<T>,
        timeoutMs: Long = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        onFloodWaitTick: suspend (Int) -> Unit = {},
        onScanProgress: suspend (scannedSoFar: Int) -> Unit = {},
    ): PreScanResult<T> {
        val topicMapping = mapForumTopics(
            sourceChatId = sourceChatId,
            targetChatId = targetChatId,
            gateway = gateway,
            timeoutMs = timeoutMs,
            delayFn = delayFn,
            onFloodWaitTick = onFloodWaitTick,
        )
        val merged = LinkedHashMap<Long, CloneableMessage<T>>()

        // 1. Walk GetChatHistory strictly from newest -> oldest, retrying empty cache misses up to 3x (1.5s)
        var cursorId = 0L
        var safetyPages = 0
        while (safetyPages < 500) {
            safetyPages++
            var page: List<CloneableMessage<T>> = emptyList()
            var emptyAttempt = 0
            while (emptyAttempt <= HISTORY_CACHE_MISS_RETRIES) {
                page = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
                    gateway.fetchHistoryPage(sourceChatId, cursorId, 100)
                }
                if (page.isNotEmpty() || emptyAttempt == HISTORY_CACHE_MISS_RETRIES) {
                    break
                }
                emptyAttempt++
                delayFn(HISTORY_CACHE_MISS_DELAY_MS)
            }

            if (page.isEmpty()) break

            var addedNew = false
            for (msg in page) {
                if (!merged.containsKey(msg.id)) {
                    addedNew = true
                }
                merged[msg.id] = msg
            }

            val oldestId = page.minOf { it.id }
            if (oldestId == cursorId && !addedNew) break
            if (cursorId > 0L && oldestId >= cursorId) break
            cursorId = oldestId
            onScanProgress(merged.size)
        }

        // 2. Complement with server-backed SearchChatMessages across media filters
        for (filter in SearchFilterKind.entries) {
            var searchCursor = 0L
            var searchPages = 0
            while (searchPages < 500) {
                searchPages++
                val res = runWithReliability(timeoutMs, delayFn, onFloodWaitTick) {
                    gateway.searchChatMessages(sourceChatId, filter, searchCursor, 100)
                }
                if (res.messages.isEmpty()) break

                for (msg in res.messages) {
                    merged[msg.id] = msg
                }
                onScanProgress(merged.size)

                val nextId = res.nextFromMessageId
                if (nextId == 0L || nextId == searchCursor) break
                searchCursor = nextId
            }
        }

        // 3. Deduplicate, sort chronologically (oldest -> newest), classify & apply topic mapping
        val ordered = merged.values.sortedBy { it.id }
        var videoCount = 0
        var photoCount = 0
        var otherCount = 0
        var filteredSkippedCount = 0
        val queuedItems = mutableListOf<CloneMessageEntity>()
        val remappedMessages = LinkedHashMap<Long, CloneableMessage<T>>(ordered.size)

        for (rawMsg in ordered) {
            if (rawMsg.type == CloneMessageType.UNSUPPORTED) {
                continue
            }

            if (!CloneBatchPlanner.isAllowedBySettings(rawMsg.type, settings)) {
                filteredSkippedCount++
                continue
            }

            when (rawMsg.type) {
                CloneMessageType.VIDEO -> videoCount++
                CloneMessageType.PHOTO -> photoCount++
                else -> otherCount++
            }

            val resolvedTargetThread = if (topicMapping.targetIsForum) {
                topicMapping.threadMap[rawMsg.originThreadId] ?: 0L
            } else {
                0L
            }

            val updatedMsg = rawMsg.copy(targetThreadId = resolvedTargetThread)
            remappedMessages[updatedMsg.id] = updatedMsg

            queuedItems.add(
                CloneMessageEntity(
                    taskId = taskId,
                    messageId = updatedMsg.id,
                    albumId = updatedMsg.mediaAlbumId,
                    originThreadId = updatedMsg.originThreadId,
                    targetThreadId = resolvedTargetThread,
                    mediaType = updatedMsg.type.name,
                    status = CloneMessageStatus.PENDING,
                )
            )
        }

        return PreScanResult(
            totalEligible = queuedItems.size,
            videoCount = videoCount,
            photoCount = photoCount,
            otherCount = otherCount,
            topicCount = topicMapping.topicCount,
            filteredSkippedCount = filteredSkippedCount,
            targetIsForum = topicMapping.targetIsForum,
            queuedItems = queuedItems,
            messagesById = remappedMessages,
        )
    }
}
