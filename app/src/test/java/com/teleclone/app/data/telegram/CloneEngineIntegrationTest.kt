package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneMessageEntity
import com.teleclone.app.data.model.CloneMessageStatus
import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CloneEngineIntegrationTest {

    private fun item(
        id: Long,
        type: CloneMessageType = CloneMessageType.TEXT,
        albumId: Long = 0L,
        canBeSaved: Boolean = true,
        originThreadId: Long = 0L,
        targetThreadId: Long = originThreadId,
        canBeForwarded: Boolean = true,
    ) = CloneableMessage(
        id = id,
        mediaAlbumId = albumId,
        canBeSaved = canBeSaved,
        type = type,
        payload = id,
        originThreadId = originThreadId,
        targetThreadId = targetThreadId,
        canBeForwarded = canBeForwarded,
    )

    private class InMemoryTaskQueueStore(initialTask: CloneTaskEntity) : TaskQueueStore {
        var task: CloneTaskEntity = initialTask
        val messages = linkedMapOf<Long, CloneMessageEntity>()

        override suspend fun saveScannedMessages(items: List<CloneMessageEntity>) {
            items.forEach { messages.putIfAbsent(it.messageId, it) }
        }

        override suspend fun getPendingMessages(taskId: Long): List<CloneMessageEntity> =
            messages.values.filter { it.taskId == taskId && it.status == CloneMessageStatus.PENDING }.sortedBy { it.messageId }

        override suspend fun countByStatus(taskId: Long, status: String): Int =
            messages.values.count { it.taskId == taskId && it.status == status }

        override suspend fun markSent(taskId: Long, messageIds: List<Long>) {
            messageIds.forEach { id ->
                messages[id]?.let { messages[id] = it.copy(status = CloneMessageStatus.SENT, errorMessage = "") }
            }
        }

        override suspend fun markFailedPermanent(taskId: Long, messageIds: List<Long>, reason: String) {
            messageIds.forEach { id ->
                messages[id]?.let {
                    messages[id] = it.copy(
                        status = CloneMessageStatus.FAILED_PERMANENT,
                        attemptCount = it.attemptCount + 1,
                        errorMessage = reason,
                    )
                }
            }
        }

        override suspend fun incrementAttempt(taskId: Long, messageIds: List<Long>, reason: String) {
            messageIds.forEach { id ->
                messages[id]?.let {
                    messages[id] = it.copy(
                        attemptCount = it.attemptCount + 1,
                        errorMessage = reason,
                    )
                }
            }
        }

        override suspend fun updateTask(task: CloneTaskEntity) {
            this.task = task
        }

        override suspend fun updateDetailedProgress(
            taskId: Long,
            lastClonedMessageId: Long,
            clonedCount: Int,
            failedCount: Int,
            lastFailureReason: String,
            updatedAt: Long,
        ) {
            task = task.copy(
                lastClonedMessageId = lastClonedMessageId,
                clonedCount = clonedCount,
                failedPermanentCount = failedCount,
                lastFailureReason = lastFailureReason,
                updatedAt = updatedAt,
            )
        }

        override suspend fun updateStatus(taskId: Long, status: String, updatedAt: Long) {
            task = task.copy(status = status, updatedAt = updatedAt)
        }
    }

    @Test
    fun `paginated history fetch continues across page boundary to keep album intact`() = runTest {
        val allMessages = listOf(
            item(1L, CloneMessageType.TEXT),
            item(2L, CloneMessageType.TEXT),
            item(3L, CloneMessageType.PHOTO, albumId = 888L),
            item(4L, CloneMessageType.PHOTO, albumId = 888L),
            item(5L, CloneMessageType.VIDEO, albumId = 888L),
            item(6L, CloneMessageType.PHOTO, albumId = 888L),
            item(7L, CloneMessageType.TEXT),
        )

        val result = HistoryPagePaginator.fetchBatch(
            fromMessageId = 0L,
            desiredForwardableCount = 4,
            pageLimit = 4,
            isChatProtected = { false },
            fetchPage = { cursorId, limit ->
                val startIdx = if (cursorId <= 1L) 0 else allMessages.indexOfFirst { it.id > cursorId }.let {
                    if (it == -1) allMessages.size else it
                }
                allMessages.drop(startIdx).take(limit)
            },
            fetchOldestFallback = { emptyList() },
        )

        assertEquals(7, result.messages.size)
        val units = CloneBatchPlanner.buildSendUnits(result.messages, CloneSettings())
        assertEquals(4, units.size)
        assertEquals(listOf(3L, 4L, 5L, 6L), units[2].items.map { it.id })
    }

    @Test
    fun `25s TDLib timeout triggers backoff retries 2s 5s 10s without hanging forever`() = runTest {
        var attempts = 0
        val recordedBackoffs = mutableListOf<Long>()

        val outcome = CloneReliabilityExecutor.executeWithPolicy(
            timeoutMs = 25_000L,
            backoffMs = listOf(2_000L, 5_000L, 10_000L),
            onRetryAttempt = { _, waitMs, _ -> recordedBackoffs.add(waitMs) },
            delayFn = { ms -> delay(ms) },
        ) {
            attempts++
            if (attempts <= 3) {
                suspendCancellableCoroutine<Int> { /* never resumes */ }
            } else {
                42
            }
        }

        assertEquals(ReliabilityOutcome.Success(42), outcome)
        assertEquals(4, attempts)
        assertEquals(listOf(2_000L, 5_000L, 10_000L), recordedBackoffs)
    }

    @Test
    fun `permanent 25s TDLib timeouts fail after 3 retries instead of hanging`() = runTest {
        var attempts = 0
        try {
            CloneReliabilityExecutor.executeWithPolicy<Int>(
                timeoutMs = 25_000L,
                backoffMs = listOf(2_000L, 5_000L, 10_000L),
                delayFn = { ms -> delay(ms) },
            ) {
                attempts++
                suspendCancellableCoroutine { /* never resumes */ }
            }
            fail("Expected TdLibTimeoutException")
        } catch (e: TdLibTimeoutException) {
            assertEquals(4, attempts)
        }
    }

    @Test
    fun `429 FloodWait auto-recovers after X plus 1 seconds and emits countdown`() = runTest {
        var attempts = 0
        val ticks = mutableListOf<Int>()

        val outcome = CloneReliabilityExecutor.executeWithPolicy(
            onFloodWaitTick = { remaining -> ticks.add(remaining) },
            delayFn = { ms -> delay(ms) },
        ) {
            attempts++
            if (attempts == 1) {
                throw TdLibException(429, "Too Many Requests: retry after 4")
            }
            "ok"
        }

        assertEquals(ReliabilityOutcome.Success("ok"), outcome)
        assertEquals(listOf(5, 4, 3, 2, 1), ticks)
        assertEquals(2, attempts)
    }

    @Test
    fun `confirmation barrier handles partial failure 97 ok plus 3 failed by resending only missing 3 items before advancing`() = runTest {
        val sourceMessages = (1L..110L).map { item(it) }
        val sentHistory = mutableListOf<List<Long>>()
        var firstBatchDispatched = false

        val task = CloneTaskEntity(
            id = 50L,
            sourceChatId = 100L,
            sourceTitle = "Origem",
            targetChatId = 200L,
            targetTitle = "Destino",
            lastClonedMessageId = 0L,
            totalMessagesEstimate = 0,
            clonedCount = 0,
            status = CloneTaskStatus.SCANNING,
            updatedAt = 0L,
            isBatchMode = true,
            batchBlockSize = 100,
            batchJitterMinMs = 2_000L,
            batchJitterMaxMs = 2_000L,
            preserveAlbumIntegrity = true,
        )
        val store = InMemoryTaskQueueStore(task)

        val gateway = object : CloneExecutionGateway<Long> {
            override suspend fun isForumSupergroup(chatId: Long): Boolean = false
            override suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor> = emptyList()
            override suspend fun createForumTopic(chatId: Long, topic: ForumTopicDescriptor): ForumTopicDescriptor = topic
            override suspend fun fetchHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): List<CloneableMessage<Long>> =
                if (fromMessageId == 0L) sourceMessages else emptyList()

            override suspend fun searchChatMessages(chatId: Long, filter: SearchFilterKind, fromMessageId: Long, limit: Int) =
                SearchPageResult(emptyList<CloneableMessage<Long>>(), 0L)

            override suspend fun isChatProtected(chatId: Long): Boolean = false
            override suspend fun getMessage(chatId: Long, messageId: Long): CloneableMessage<Long>? =
                sourceMessages.firstOrNull { it.id == messageId }

            override suspend fun canForwardMessage(chatId: Long, message: CloneableMessage<Long>): Boolean = true

            override suspend fun forwardMessages(
                sourceChatId: Long,
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                messages: List<CloneableMessage<Long>>,
            ): List<DispatchedItemHandle> {
                sentHistory.add(messages.map { it.id })
                if (!firstBatchDispatched && messages.size == 100) {
                    firstBatchDispatched = true
                    return messages.mapIndexed { idx, msg ->
                        if (idx >= 97) {
                            // 3 items stay pending/transiently fail in target
                            DispatchedItemHandle(msg.id, 10_000L + msg.id, DispatchedSendingState.Pending)
                        } else {
                            DispatchedItemHandle(msg.id, 10_000L + msg.id, DispatchedSendingState.Confirmed)
                        }
                    }
                }
                return messages.map { DispatchedItemHandle(it.id, 20_000L + it.id, DispatchedSendingState.Confirmed) }
            }

            override suspend fun applyPostCopyTextCleanup(targetChatId: Long, handles: List<DispatchedItemHandle>, settings: CloneSettings) = Unit

            override suspend fun checkDispatchedMessageState(targetChatId: Long, targetMessageId: Long): DispatchedSendingState {
                // Simulate those 3 items failing with 429 retry after 2s during drain
                return DispatchedSendingState.Failed(429, "FLOOD_WAIT_2", retryAfterSeconds = 2)
            }

            override suspend fun reuploadProtectedItems(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                items: List<CloneableMessage<Long>>,
                settings: CloneSettings,
            ): List<DispatchedItemHandle> = emptyList()
        }

        val runner = CloneSessionRunner(
            gateway = gateway,
            store = store,
            settingsProvider = { CloneSettings() },
            plannerContext = EmptyCoroutineContext,
            delayFn = { ms -> delay(ms) },
        )

        val completed = runner.run(task)
        assertEquals(CloneTaskStatus.COMPLETED, completed.status)
        assertEquals(110, completed.clonedCount)
        assertEquals(0, completed.failedPermanentCount)
        // Call 1: 1..100 (97 ok + 3 failed), Call 2: ONLY 98..100 BEFORE advancing, Call 3: 101..110!
        assertEquals(3, sentHistory.size)
        assertEquals((1L..100L).toList(), sentHistory[0])
        assertEquals(listOf(98L, 99L, 100L), sentHistory[1])
        assertEquals((101L..110L).toList(), sentHistory[2])
    }

    @Test
    fun `4-phase runner executes recovery pass, protected content fallback, pause-resume, and stays COMPLETED`() = runTest {
        // 300 messages across 2 forum topics + a 12-item album + a protected message + a silent missing item requiring Phase C recovery
        val sourceMessages = mutableListOf<CloneableMessage<Long>>()
        var id = 1L
        repeat(195) { sourceMessages.add(item(id++, CloneMessageType.TEXT, originThreadId = 10L)) }
        repeat(12) { sourceMessages.add(item(id++, CloneMessageType.PHOTO, albumId = 5001L, originThreadId = 10L)) }
        sourceMessages.add(item(id++, CloneMessageType.UNSUPPORTED, canBeSaved = false, originThreadId = 10L))
        // Protected item #209 (canBeForwarded = false) -> must route to ProtectedContentReuploader
        sourceMessages.add(item(id++, CloneMessageType.VIDEO, originThreadId = 20L, canBeForwarded = false))
        while (id <= 300L) {
            sourceMessages.add(item(id++, CloneMessageType.TEXT, originThreadId = 20L))
        }

        val expectedIds = sourceMessages.filter { it.type != CloneMessageType.UNSUPPORTED }.map { it.id }
        val sentIds = mutableListOf<Long>()
        val reuploadedIds = mutableListOf<Long>()
        val statusPhases = mutableListOf<String>()

        val initialTask = CloneTaskEntity(
            id = 777L,
            sourceChatId = 1001L,
            sourceTitle = "Fórum Origem",
            targetChatId = 2002L,
            targetTitle = "Fórum Destino",
            lastClonedMessageId = 0L,
            totalMessagesEstimate = 0,
            clonedCount = 0,
            status = CloneTaskStatus.SCANNING,
            updatedAt = 0L,
            isBatchMode = true,
            batchBlockSize = 150,
            batchJitterMinMs = 5_000L,
            batchJitterMaxMs = 8_000L,
            preserveAlbumIntegrity = true,
        )
        val store = InMemoryTaskQueueStore(initialTask)
        var callsCount = 0
        var pausedJob: kotlinx.coroutines.Job? = null
        var fail250Remaining = 4 // exhausts Phase B (1 + 3 retries) so Phase C Repescagem recovers #250

        fun makeGateway(pauseAtCall: Int?): CloneExecutionGateway<Long> = object : CloneExecutionGateway<Long> {
            override suspend fun isForumSupergroup(chatId: Long): Boolean = true
            override suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor> =
                if (chatId == 1001L) {
                    listOf(ForumTopicDescriptor(10L, "Tópico A"), ForumTopicDescriptor(20L, "Tópico B"))
                } else {
                    emptyList()
                }

            override suspend fun createForumTopic(chatId: Long, topic: ForumTopicDescriptor): ForumTopicDescriptor =
                topic.copy(threadId = topic.threadId * 10L) // 10->100, 20->200

            override suspend fun fetchHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): List<CloneableMessage<Long>> =
                if (fromMessageId == 0L) sourceMessages else emptyList()

            override suspend fun searchChatMessages(chatId: Long, filter: SearchFilterKind, fromMessageId: Long, limit: Int) =
                SearchPageResult(emptyList<CloneableMessage<Long>>(), 0L)

            override suspend fun isChatProtected(chatId: Long): Boolean = false
            override suspend fun getMessage(chatId: Long, messageId: Long): CloneableMessage<Long>? =
                sourceMessages.firstOrNull { it.id == messageId }

            override suspend fun canForwardMessage(chatId: Long, message: CloneableMessage<Long>): Boolean =
                message.canBeForwarded

            override suspend fun forwardMessages(
                sourceChatId: Long,
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                messages: List<CloneableMessage<Long>>,
            ): List<DispatchedItemHandle> {
                callsCount++
                if (pauseAtCall != null && callsCount == pauseAtCall) {
                    pausedJob?.cancel(CancellationException("User paused"))
                    suspendCancellableCoroutine<Unit> { }
                }
                // Assert topic routing never mixes topics
                assertTrue(targetIsForum)
                assertTrue(targetThreadId == 100L || targetThreadId == 200L)
                assertTrue(messages.all { it.targetThreadId == targetThreadId })

                // Fail item 250 in Phase B (initial + 3 retries) so Phase C (Repescagem) recovers it!
                if (fail250Remaining > 0 && messages.any { it.id == 250L }) {
                    fail250Remaining--
                    return messages.map { msg ->
                        if (msg.id == 250L) {
                            DispatchedItemHandle(msg.id, 0L, DispatchedSendingState.Failed(500, "Silent temporary error"))
                        } else {
                            sentIds.add(msg.id)
                            DispatchedItemHandle(msg.id, 30_000L + msg.id, DispatchedSendingState.Confirmed)
                        }
                    }
                }

                messages.forEach { sentIds.add(it.id) }
                return messages.map { DispatchedItemHandle(it.id, 30_000L + it.id, DispatchedSendingState.Confirmed) }
            }

            override suspend fun applyPostCopyTextCleanup(targetChatId: Long, handles: List<DispatchedItemHandle>, settings: CloneSettings) = Unit
            override suspend fun checkDispatchedMessageState(targetChatId: Long, targetMessageId: Long) = DispatchedSendingState.Confirmed

            override suspend fun reuploadProtectedItems(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                items: List<CloneableMessage<Long>>,
                settings: CloneSettings,
            ): List<DispatchedItemHandle> {
                assertEquals(200L, targetThreadId)
                items.forEach {
                    reuploadedIds.add(it.id)
                    sentIds.add(it.id)
                }
                return items.map { DispatchedItemHandle(it.id, 40_000L + it.id, DispatchedSendingState.Confirmed) }
            }
        }

        val runner1 = CloneSessionRunner(
            gateway = makeGateway(pauseAtCall = 3),
            store = store,
            settingsProvider = { CloneSettings() },
            plannerContext = EmptyCoroutineContext,
            backoffMs = listOf(100L, 100L, 100L),
            random = Random(1),
            delayFn = { ms -> delay(ms) },
            onProgress = { t, txt, _, _, _ -> statusPhases.add("${t.status}:$txt") },
        )

        pausedJob = launch {
            try {
                runner1.run(initialTask)
            } catch (_: CancellationException) {
                store.updateStatus(initialTask.id, CloneTaskStatus.PAUSED, 100L)
            }
        }
        advanceUntilIdle()

        assertEquals(CloneTaskStatus.PAUSED, store.task.status)
        assertEquals(150, store.task.clonedCount) // Block 1 (150 msgs) persisted before pause at Block 2
        assertEquals(2, store.task.topicCount)

        // Resume from saved DB state (no re-scan, finishes Phase B + Phase C recovery + Phase D persistent COMPLETED)
        val runner2 = CloneSessionRunner(
            gateway = makeGateway(pauseAtCall = null),
            store = store,
            settingsProvider = { CloneSettings() },
            plannerContext = EmptyCoroutineContext,
            backoffMs = listOf(100L, 100L, 100L),
            random = Random(1),
            delayFn = { ms -> delay(ms) },
            onProgress = { t, txt, _, _, _ -> statusPhases.add("${t.status}:$txt") },
        )

        val finalTask = runner2.run(store.task.copy(status = CloneTaskStatus.RUNNING))
        advanceUntilIdle()

        assertEquals(CloneTaskStatus.COMPLETED, finalTask.status)
        assertEquals(CloneTaskStatus.COMPLETED, store.task.status) // Remains persisted until "Concluído"
        assertEquals(expectedIds.size, finalTask.clonedCount)
        assertEquals(0, finalTask.failedPermanentCount)
        assertEquals(listOf(209L), reuploadedIds)
        assertEquals(expectedIds.sorted(), sentIds.sorted())
        assertEquals(expectedIds.size, sentIds.distinct().size)
        assertTrue(statusPhases.any { it.startsWith("${CloneTaskStatus.SCANNING}:") })
        assertTrue(statusPhases.any { it.startsWith("${CloneTaskStatus.VERIFYING}:") })
        assertTrue(statusPhases.any { it.contains("Pausa jitter:") })
        assertTrue(statusPhases.last().startsWith("${CloneTaskStatus.COMPLETED}:"))
    }

    @Test
    fun `TdLibException extracts flood wait seconds and cleanupText strips mentions and links`() {
        assertEquals(18, TdLibException(429, "FLOOD_WAIT_18").extractFloodWaitSeconds())
        assertEquals(45, TdLibException(429, "Too Many Requests: retry after 45").extractFloodWaitSeconds())
        assertEquals(30, TdLibException(429, "Rate limit exceeded").extractFloodWaitSeconds())

        val cleaned = TdLibManager.cleanupText(
            text = "Confira @canal_teste em https://t.me/canal_teste hoje!",
            stripMentions = true,
            signature = "@MeuCanal",
        )
        assertEquals("Confira em hoje!\n\n@MeuCanal", cleaned)
    }
}
