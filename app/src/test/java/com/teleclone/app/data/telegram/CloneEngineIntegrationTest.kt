package com.teleclone.app.data.telegram

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
    ) = CloneableMessage(
        id = id,
        mediaAlbumId = albumId,
        canBeSaved = canBeSaved,
        type = type,
        payload = id,
    )

    @Test
    fun `paginated history fetch continues across page boundary to keep album intact`() = runTest {
        // Page 1 (limit 4) ends mid-album (ids 3, 4 with albumId=888)
        // Page 2 finishes album (ids 5, 6 with albumId=888) + next msg 7
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
                // Simulate infinite hang on TDLib callback
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
    fun `400 CHAT_FORWARDS_RESTRICTED logs and skips without stalling queue`() = runTest {
        val warnings = mutableListOf<String>()
        val outcome = CloneReliabilityExecutor.executeWithPolicy<Int>(
            onRestrictedSkip = { err -> warnings.add(err.message) },
            delayFn = { ms -> delay(ms) },
        ) {
            throw TdLibException(400, "CHAT_FORWARDS_RESTRICTED")
        }

        assertTrue(outcome is ReliabilityOutcome.RestrictedSkipped)
        assertEquals(1, warnings.size)
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
    fun `batch runner commits progress immediately, preserves DB count across pause-resume, with zero duplicates or skips`() = runTest {
        // 350 messages total including a 12-item album, a mixed album, and 2 system messages
        val sourceMessages = mutableListOf<CloneableMessage<Long>>()
        var id = 1L
        // 1..195 text
        repeat(195) { sourceMessages.add(item(id++, CloneMessageType.TEXT)) }
        // 196..207 = 12-photo album (crosses 200 mark, tests >10 rule)
        repeat(12) { sourceMessages.add(item(id++, CloneMessageType.PHOTO, albumId = 5001L)) }
        // 208 = system message (not forwardable)
        sourceMessages.add(item(id++, CloneMessageType.UNSUPPORTED, canBeSaved = false))
        // 209..211 = mixed album (photo + doc + audio)
        sourceMessages.add(item(id++, CloneMessageType.PHOTO, albumId = 5002L))
        sourceMessages.add(item(id++, CloneMessageType.DOCUMENT, albumId = 5002L))
        sourceMessages.add(item(id++, CloneMessageType.AUDIO, albumId = 5002L))
        // remaining text up to 350 total IDs
        while (id <= 350L) {
            sourceMessages.add(item(id++, CloneMessageType.TEXT))
        }
        val expectedForwardableIds = sourceMessages.filter { it.canBeSaved && it.type != CloneMessageType.UNSUPPORTED }.map { it.id }

        val sentIds = mutableListOf<Long>()
        val dbTasks = mutableMapOf<Long, CloneTaskEntity>()
        val observedStatusMessages = mutableListOf<String>()

        var task = CloneTaskEntity(
            id = 99L,
            sourceChatId = 1001L,
            sourceTitle = "Origem",
            targetChatId = 2002L,
            targetTitle = "Destino",
            lastClonedMessageId = 0L,
            totalMessagesEstimate = expectedForwardableIds.size,
            clonedCount = 0,
            status = CloneTaskStatus.RUNNING,
            updatedAt = 0L,
            isBatchMode = true,
            batchBlockSize = 150,
            batchJitterMinMs = 5_000L,
            batchJitterMaxMs = 8_000L,
            preserveAlbumIntegrity = true,
        )
        dbTasks[task.id] = task

        val store = object : TaskProgressStore {
            override suspend fun updateProgress(taskId: Long, lastClonedMessageId: Long, clonedCount: Int, updatedAt: Long) {
                val cur = dbTasks[taskId]!!
                dbTasks[taskId] = cur.copy(
                    lastClonedMessageId = lastClonedMessageId,
                    clonedCount = clonedCount,
                    updatedAt = updatedAt,
                )
            }

            override suspend fun updateStatus(taskId: Long, status: String, updatedAt: Long) {
                val cur = dbTasks[taskId]!!
                dbTasks[taskId] = cur.copy(status = status, updatedAt = updatedAt)
            }
        }

        val transport = object : CloneTransport<Long> {
            override suspend fun fetchHistoryBatch(
                chatId: Long,
                fromMessageId: Long,
                desiredForwardableCount: Int,
            ): PaginatedHistoryResult<Long> {
                return HistoryPagePaginator.fetchBatch(
                    fromMessageId = fromMessageId,
                    desiredForwardableCount = desiredForwardableCount,
                    pageLimit = 50,
                    isChatProtected = { false },
                    fetchPage = { cursorId, limit ->
                        val start = if (cursorId <= 1L && fromMessageId == 0L) 0 else sourceMessages.indexOfFirst { it.id > cursorId }.let {
                            if (it == -1) sourceMessages.size else it
                        }
                        sourceMessages.drop(start).take(limit)
                    },
                    fetchOldestFallback = { emptyList() },
                )
            }

            override suspend fun forwardCopyClean(
                sourceChatId: Long,
                targetChatId: Long,
                messages: List<CloneableMessage<Long>>,
                settings: CloneSettings,
                onStatusUpdate: suspend (String) -> Unit,
            ): Int {
                messages.forEach { sentIds.add(it.id) }
                return messages.size
            }
        }

        var callsCount = 0
        var pausedJob: kotlinx.coroutines.Job? = null

        // Trigger pause cleanly when call #4 begins (Block 1 = 100+50, Block 2 starts with 45 -> 195 persisted!)
        val pausingTransport = object : CloneTransport<Long> by transport {
            override suspend fun forwardCopyClean(
                sourceChatId: Long,
                targetChatId: Long,
                messages: List<CloneableMessage<Long>>,
                settings: CloneSettings,
                onStatusUpdate: suspend (String) -> Unit,
            ): Int {
                callsCount++
                if (callsCount == 4) {
                    pausedJob?.cancel(CancellationException("User paused"))
                    suspendCancellableCoroutine<Unit> { }
                }
                return transport.forwardCopyClean(sourceChatId, targetChatId, messages, settings, onStatusUpdate)
            }
        }

        val runner1 = CloneSessionRunner(
            transport = pausingTransport,
            store = store,
            settingsProvider = { CloneSettings() },
            plannerContext = EmptyCoroutineContext,
            random = Random(7),
            delayFn = { ms -> delay(ms) },
        )

        pausedJob = launch {
            try {
                runner1.run(task)
            } catch (_: CancellationException) {
                store.updateStatus(task.id, CloneTaskStatus.PAUSED, 100L)
            }
        }
        advanceUntilIdle()

        val savedPaused = dbTasks[task.id]!!
        assertEquals(CloneTaskStatus.PAUSED, savedPaused.status)
        assertEquals(195, savedPaused.clonedCount)
        assertEquals(savedPaused.clonedCount, sentIds.size)

        // Resume from saved DB state
        val runner2 = CloneSessionRunner(
            transport = transport,
            store = store,
            settingsProvider = { CloneSettings() },
            plannerContext = EmptyCoroutineContext,
            random = Random(7),
            delayFn = { ms -> delay(ms) },
            onProgress = { _, statusText, _, _, _ -> observedStatusMessages.add(statusText) },
        )

        val finalTask = runner2.run(savedPaused.copy(status = CloneTaskStatus.RUNNING))
        advanceUntilIdle()

        assertEquals(CloneTaskStatus.COMPLETED, finalTask.status)
        assertEquals(expectedForwardableIds.size, finalTask.clonedCount)
        assertEquals(expectedForwardableIds, sentIds)
        assertEquals(expectedForwardableIds.size, sentIds.distinct().size)
        assertTrue(observedStatusMessages.any { it.contains("Pausa jitter:") })
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
