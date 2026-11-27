package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

data class PaginatedHistoryResult<T>(
    val messages: List<CloneableMessage<T>>,
    val maxScannedMessageId: Long,
    val isProtectedChat: Boolean = false,
)

object HistoryPagePaginator {

    suspend fun <T> fetchBatch(
        fromMessageId: Long,
        desiredForwardableCount: Int,
        pageLimit: Int = 50,
        maxSafetyPages: Int = 30,
        isChatProtected: suspend () -> Boolean,
        fetchPage: suspend (cursorId: Long, limit: Int) -> List<CloneableMessage<T>>,
        fetchOldestFallback: suspend (limit: Int) -> List<CloneableMessage<T>>,
        isForwardable: (CloneableMessage<T>) -> Boolean = {
            it.canBeSaved && it.type != CloneMessageType.UNSUPPORTED
        },
    ): PaginatedHistoryResult<T> {
        if (isChatProtected()) {
            return PaginatedHistoryResult(
                messages = emptyList(),
                maxScannedMessageId = fromMessageId,
                isProtectedChat = true,
            )
        }

        val targetCount = desiredForwardableCount.coerceIn(1, 1000)
        val safePageLimit = pageLimit.coerceIn(1, 99)
        val collected = LinkedHashMap<Long, CloneableMessage<T>>()
        var maxScannedId = fromMessageId
        var cursorId = if (fromMessageId > 0L) fromMessageId else 1L
        var isFirstPage = true
        var safetyPages = 0

        while (safetyPages < maxSafetyPages) {
            safetyPages++
            var rawMessages = fetchPage(cursorId, safePageLimit)

            if (rawMessages.isEmpty() && isFirstPage && fromMessageId == 0L) {
                rawMessages = fetchOldestFallback(safePageLimit)
            }
            isFirstPage = false

            if (rawMessages.isEmpty()) break

            val ordered = rawMessages
                .filter { msg ->
                    if (fromMessageId == 0L && cursorId == 1L && maxScannedId == 0L) {
                        true
                    } else {
                        msg.id > maxScannedId
                    }
                }
                .sortedBy { it.id }

            if (ordered.isEmpty()) break

            maxScannedId = maxOf(maxScannedId, ordered.last().id)
            for (msg in ordered) {
                collected[msg.id] = msg
            }

            val tailAlbumId = ordered.last().mediaAlbumId
            val forwardableCount = collected.values.count(isForwardable)
            val needsAlbumTailContinuation = tailAlbumId != 0L
            val needsMoreForBlock = forwardableCount < targetCount

            if (!needsAlbumTailContinuation && !needsMoreForBlock) {
                break
            }

            if (ordered.last().id <= cursorId && cursorId != 1L) {
                break
            }
            cursorId = ordered.last().id
        }

        return PaginatedHistoryResult(
            messages = collected.values.toList().sortedBy { it.id },
            maxScannedMessageId = maxScannedId,
            isProtectedChat = false,
        )
    }
}

interface CloneTransport<T> {
    suspend fun fetchHistoryBatch(
        chatId: Long,
        fromMessageId: Long,
        desiredForwardableCount: Int,
    ): PaginatedHistoryResult<T>

    suspend fun forwardCopyClean(
        sourceChatId: Long,
        targetChatId: Long,
        messages: List<CloneableMessage<T>>,
        settings: CloneSettings,
        onStatusUpdate: suspend (String) -> Unit,
    ): Int
}

interface TaskProgressStore {
    suspend fun updateProgress(
        taskId: Long,
        lastClonedMessageId: Long,
        clonedCount: Int,
        updatedAt: Long,
    )

    suspend fun updateStatus(
        taskId: Long,
        status: String,
        updatedAt: Long,
    )
}

class CloneSessionRunner<T>(
    private val transport: CloneTransport<T>,
    private val store: TaskProgressStore,
    private val settingsProvider: suspend () -> CloneSettings,
    private val plannerContext: CoroutineContext = Dispatchers.Default,
    private val timeoutMs: Long = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS,
    private val backoffMs: List<Long> = CloneReliabilityExecutor.RETRY_BACKOFF_MS,
    private val random: Random = Random.Default,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onTaskUpdated: (CloneTaskEntity) -> Unit = {},
    private val onProgress: suspend (
        task: CloneTaskEntity,
        statusText: String,
        floodWaitSeconds: Int?,
        isPaused: Boolean,
        forceImmediate: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    private val onLogWarning: (String) -> Unit = {},
) {

    suspend fun run(initialTask: CloneTaskEntity): CloneTaskEntity {
        var currentTask = initialTask
        onTaskUpdated(currentTask)

        onProgress(
            currentTask,
            if (currentTask.isBatchMode) {
                "Modo Lote (${currentTask.batchBlockSize} msgs) • Carregando histórico..."
            } else {
                "Carregando histórico..."
            },
            null,
            false,
            true,
        )

        currentTask = if (currentTask.isBatchMode) {
            runBatchLoop(currentTask)
        } else {
            runStandardLoop(currentTask)
        }

        val completed = currentTask.copy(
            status = CloneTaskStatus.COMPLETED,
            updatedAt = nowMs(),
        )
        onTaskUpdated(completed)
        store.updateStatus(completed.id, CloneTaskStatus.COMPLETED, completed.updatedAt)
        onProgress(completed, "Clonagem concluída", null, false, true)
        return completed
    }

    private suspend fun runStandardLoop(startTask: CloneTaskEntity): CloneTaskEntity {
        var currentTask = startTask

        while (currentCoroutineContext().isActive) {
            val historyOutcome = executeReliable(currentTask) {
                transport.fetchHistoryBatch(
                    chatId = currentTask.sourceChatId,
                    fromMessageId = currentTask.lastClonedMessageId,
                    desiredForwardableCount = 50,
                )
            }

            val history = when (historyOutcome) {
                is ReliabilityOutcome.RestrictedSkipped -> return currentTask
                is ReliabilityOutcome.Success -> historyOutcome.value
            }

            if (history.isProtectedChat || history.messages.isEmpty()) {
                return currentTask
            }

            val latestSettings = settingsProvider()
            val units = withContext(plannerContext) {
                CloneBatchPlanner.buildSendUnits(history.messages, latestSettings)
            }

            if (units.isEmpty()) {
                if (history.maxScannedMessageId > currentTask.lastClonedMessageId) {
                    val now = nowMs()
                    currentTask = currentTask.copy(
                        lastClonedMessageId = history.maxScannedMessageId,
                        updatedAt = now,
                    )
                    onTaskUpdated(currentTask)
                    store.updateProgress(
                        taskId = currentTask.id,
                        lastClonedMessageId = currentTask.lastClonedMessageId,
                        clonedCount = currentTask.clonedCount,
                        updatedAt = now,
                    )
                    continue
                }
                return currentTask
            }

            for ((index, unit) in units.withIndex()) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")

                val unitSettings = settingsProvider()
                val sendOutcome = executeReliable(currentTask) {
                    transport.forwardCopyClean(
                        sourceChatId = currentTask.sourceChatId,
                        targetChatId = currentTask.targetChatId,
                        messages = unit.items,
                        settings = unitSettings,
                        onStatusUpdate = { detail ->
                            onProgress(currentTask, detail, null, false, false)
                        },
                    )
                }

                val sentCount = when (sendOutcome) {
                    is ReliabilityOutcome.RestrictedSkipped -> 0
                    is ReliabilityOutcome.Success -> sendOutcome.value
                }

                val isLastUnitInBatch = index == units.lastIndex
                val nextCursorId = if (isLastUnitInBatch && history.maxScannedMessageId > unit.lastMessageId) {
                    history.maxScannedMessageId
                } else {
                    unit.lastMessageId
                }

                val now = nowMs()
                val newCount = currentTask.clonedCount + sentCount
                currentTask = currentTask.copy(
                    lastClonedMessageId = nextCursorId,
                    clonedCount = newCount,
                    updatedAt = now,
                )
                onTaskUpdated(currentTask)
                store.updateProgress(
                    taskId = currentTask.id,
                    lastClonedMessageId = nextCursorId,
                    clonedCount = newCount,
                    updatedAt = now,
                )

                onProgress(
                    currentTask,
                    if (sentCount > 0) {
                        "Aguardando cadência (${String.format(Locale.US, "%.1fs", unitSettings.delaySeconds)})..."
                    } else {
                        "Item restrito ou ignorado"
                    },
                    null,
                    false,
                    false,
                )

                if (sentCount > 0) {
                    delayFn((unitSettings.delaySeconds * 1000f).toLong().coerceAtLeast(500L))
                }
            }
        }

        return currentTask
    }

    private suspend fun runBatchLoop(startTask: CloneTaskEntity): CloneTaskEntity {
        var currentTask = startTask
        val blockSize = currentTask.batchBlockSize.coerceAtLeast(1)
        val pendingUnits = ArrayDeque<SendUnit<T>>()
        var fetchCursorId = currentTask.lastClonedMessageId
        var reachedEndOfHistory = false

        while (currentCoroutineContext().isActive) {
            val latestSettings = settingsProvider()

            while (pendingUnits.sumOf { it.size } < blockSize && !reachedEndOfHistory && currentCoroutineContext().isActive) {
                val remainingNeeded = (blockSize - pendingUnits.sumOf { it.size }).coerceIn(1, 200)
                val historyOutcome = executeReliable(currentTask) {
                    transport.fetchHistoryBatch(
                        chatId = currentTask.sourceChatId,
                        fromMessageId = fetchCursorId,
                        desiredForwardableCount = remainingNeeded,
                    )
                }

                val history = when (historyOutcome) {
                    is ReliabilityOutcome.RestrictedSkipped -> {
                        reachedEndOfHistory = true
                        break
                    }
                    is ReliabilityOutcome.Success -> historyOutcome.value
                }

                if (history.isProtectedChat || history.messages.isEmpty() || history.maxScannedMessageId <= fetchCursorId) {
                    reachedEndOfHistory = true
                    break
                }

                fetchCursorId = history.maxScannedMessageId
                val builtUnits = withContext(plannerContext) {
                    CloneBatchPlanner.buildSendUnits(history.messages, latestSettings)
                }

                if (builtUnits.isEmpty()) {
                    if (pendingUnits.isEmpty()) {
                        val now = nowMs()
                        currentTask = currentTask.copy(
                            lastClonedMessageId = fetchCursorId,
                            updatedAt = now,
                        )
                        onTaskUpdated(currentTask)
                        store.updateProgress(
                            taskId = currentTask.id,
                            lastClonedMessageId = fetchCursorId,
                            clonedCount = currentTask.clonedCount,
                            updatedAt = now,
                        )
                    }
                } else {
                    builtUnits.forEach { pendingUnits.addLast(it) }
                }
            }

            if (pendingUnits.isEmpty()) {
                if (reachedEndOfHistory) return currentTask
                continue
            }

            val nextBlock = withContext(plannerContext) {
                CloneBatchPlanner.buildBlocks(
                    units = pendingUnits.toList(),
                    blockSize = blockSize,
                    preserveAlbumIntegrity = currentTask.preserveAlbumIntegrity,
                ).firstOrNull()
            } ?: return currentTask

            repeat(nextBlock.units.size) {
                if (pendingUnits.isNotEmpty()) pendingUnits.removeFirst()
            }

            var blockSentTotal = 0
            for (tdLibCall in nextBlock.tdLibCalls) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")

                val callSettings = settingsProvider()
                val sendOutcome = executeReliable(currentTask) {
                    transport.forwardCopyClean(
                        sourceChatId = currentTask.sourceChatId,
                        targetChatId = currentTask.targetChatId,
                        messages = tdLibCall.items,
                        settings = callSettings,
                        onStatusUpdate = { detail ->
                            onProgress(
                                currentTask,
                                "Modo Lote (${nextBlock.messageCount} msgs) • $detail",
                                null,
                                false,
                                false,
                            )
                        },
                    )
                }

                val sentCount = when (sendOutcome) {
                    is ReliabilityOutcome.RestrictedSkipped -> 0
                    is ReliabilityOutcome.Success -> sendOutcome.value
                }

                blockSentTotal += sentCount
                val now = nowMs()
                val newCount = currentTask.clonedCount + sentCount
                val nextCursor = if (pendingUnits.isEmpty() && reachedEndOfHistory && fetchCursorId > tdLibCall.lastMessageId) {
                    fetchCursorId
                } else {
                    tdLibCall.lastMessageId
                }

                currentTask = currentTask.copy(
                    lastClonedMessageId = nextCursor,
                    clonedCount = newCount,
                    updatedAt = now,
                )
                onTaskUpdated(currentTask)
                store.updateProgress(
                    taskId = currentTask.id,
                    lastClonedMessageId = nextCursor,
                    clonedCount = newCount,
                    updatedAt = now,
                )

                onProgress(
                    currentTask,
                    "Modo Lote (${nextBlock.messageCount} msgs) • Enviadas: $blockSentTotal/${nextBlock.messageCount}",
                    null,
                    false,
                    false,
                )
            }

            val hasMoreWork = pendingUnits.isNotEmpty() || !reachedEndOfHistory
            if (hasMoreWork && blockSentTotal > 0) {
                val jitterMs = CloneBatchPlanner.computeJitterDelayMs(
                    minDelayMs = currentTask.batchJitterMinMs,
                    maxDelayMs = currentTask.batchJitterMaxMs,
                    random = random,
                )
                val formattedJitter = String.format(Locale.US, "%.1fs", jitterMs / 1000.0)
                onProgress(
                    currentTask,
                    "Modo Lote (${nextBlock.messageCount} msgs) • Pausa jitter: $formattedJitter...",
                    null,
                    false,
                    true,
                )
                delayFn(jitterMs)
            }
        }

        return currentTask
    }

    private suspend fun <R> executeReliable(
        task: CloneTaskEntity,
        block: suspend () -> R,
    ): ReliabilityOutcome<R> {
        return CloneReliabilityExecutor.executeWithPolicy(
            timeoutMs = timeoutMs,
            backoffMs = backoffMs,
            onFloodWaitTick = { remaining ->
                onProgress(
                    task,
                    "Aguardando limite do Telegram (${remaining}s)...",
                    remaining,
                    false,
                    true,
                )
            },
            onRetryAttempt = { attempt, delayMs, _ ->
                onProgress(
                    task,
                    "Reconectando ($attempt/${backoffMs.size}) em ${delayMs / 1000}s...",
                    null,
                    false,
                    true,
                )
            },
            onRestrictedSkip = { err ->
                onLogWarning("Forwards restricted (400): ${err.message}")
            },
            delayFn = delayFn,
            block = block,
        )
    }
}
