package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneMessageEntity
import com.teleclone.app.data.model.CloneMessageStatus
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

sealed class DispatchedSendingState {
    data object Confirmed : DispatchedSendingState()
    data object Pending : DispatchedSendingState()
    data class Failed(
        val errorCode: Int,
        val errorMessage: String,
        val retryAfterSeconds: Int? = null,
    ) : DispatchedSendingState()
}

data class DispatchedItemHandle(
    val sourceMessageId: Long,
    val targetMessageId: Long,
    val sendingState: DispatchedSendingState,
    val isRestrictedOrNull: Boolean = false,
)

data class BarrierDrainResult(
    val confirmedSourceIds: List<Long>,
    val restrictedSourceIds: List<Long>,
    val retryableSourceIds: List<Long>,
    val permanentFailed: Map<Long, String>,
    val floodWaitSeconds: Int?,
)

interface CloneExecutionGateway<T> : HybridScanGateway<T> {
    suspend fun isChatProtected(chatId: Long): Boolean
    suspend fun getMessage(chatId: Long, messageId: Long): CloneableMessage<T>?
    suspend fun canForwardMessage(chatId: Long, message: CloneableMessage<T>): Boolean
    suspend fun forwardMessages(
        sourceChatId: Long,
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        messages: List<CloneableMessage<T>>,
    ): List<DispatchedItemHandle>

    suspend fun applyPostCopyTextCleanup(
        targetChatId: Long,
        handles: List<DispatchedItemHandle>,
        settings: CloneSettings,
    )

    suspend fun checkDispatchedMessageState(
        targetChatId: Long,
        targetMessageId: Long,
    ): DispatchedSendingState

    suspend fun reuploadProtectedItems(
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        items: List<CloneableMessage<T>>,
        settings: CloneSettings,
    ): List<DispatchedItemHandle>
}

interface TaskQueueStore {
    suspend fun saveScannedMessages(items: List<CloneMessageEntity>)
    suspend fun getPendingMessages(taskId: Long): List<CloneMessageEntity>
    suspend fun countByStatus(taskId: Long, status: String): Int
    suspend fun markSent(taskId: Long, messageIds: List<Long>)
    suspend fun markFailedPermanent(taskId: Long, messageIds: List<Long>, reason: String)
    suspend fun incrementAttempt(taskId: Long, messageIds: List<Long>, reason: String)
    suspend fun updateTask(task: CloneTaskEntity)
    suspend fun updateDetailedProgress(
        taskId: Long,
        lastClonedMessageId: Long,
        clonedCount: Int,
        failedCount: Int,
        lastFailureReason: String,
        updatedAt: Long,
    )
    suspend fun updateStatus(taskId: Long, status: String, updatedAt: Long)
}

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

object ConfirmationBarrier {

    const val DEFAULT_POLL_INTERVAL_MS = 400L
    const val DEFAULT_DRAIN_TIMEOUT_MS = 30_000L

    suspend fun <T> drainUntilSettled(
        targetChatId: Long,
        initialHandles: List<DispatchedItemHandle>,
        gateway: CloneExecutionGateway<T>,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
        delayFn: (suspend (Long) -> Unit)? = null,
        elapsedMsProvider: (() -> Long)? = null,
    ): BarrierDrainResult {
        val currentStates = initialHandles.associateBy { it.sourceMessageId }.toMutableMap()
        var simulatedElapsed = 0L
        val startMs = elapsedMsProvider?.invoke() ?: 0L

        fun elapsed(): Long = if (elapsedMsProvider != null) {
            elapsedMsProvider.invoke() - startMs
        } else {
            simulatedElapsed
        }

        while (currentCoroutineContext().isActive && elapsed() < drainTimeoutMs) {
            val pendingHandles = currentStates.values.filter {
                !it.isRestrictedOrNull && it.sendingState is DispatchedSendingState.Pending
            }
            if (pendingHandles.isEmpty()) break

            if (delayFn != null) delayFn(pollIntervalMs) else delay(pollIntervalMs)
            simulatedElapsed += pollIntervalMs

            for (pending in pendingHandles) {
                if (pending.targetMessageId <= 0L) continue
                val updated = runCatching {
                    gateway.checkDispatchedMessageState(targetChatId, pending.targetMessageId)
                }.getOrElse { err ->
                    if (err is TdLibException && CloneReliabilityExecutor.isForwardsRestrictedError(err)) {
                        DispatchedSendingState.Failed(err.code, err.message)
                    } else {
                        pending.sendingState
                    }
                }
                currentStates[pending.sourceMessageId] = pending.copy(sendingState = updated)
            }
        }

        val confirmed = mutableListOf<Long>()
        val restricted = mutableListOf<Long>()
        val retryable = mutableListOf<Long>()
        val permanentFailed = linkedMapOf<Long, String>()
        var maxFloodWait: Int? = null

        for (handle in initialHandles) {
            val latest = currentStates[handle.sourceMessageId] ?: handle
            if (latest.isRestrictedOrNull) {
                restricted.add(latest.sourceMessageId)
                continue
            }

            when (val state = latest.sendingState) {
                is DispatchedSendingState.Confirmed -> confirmed.add(latest.sourceMessageId)
                is DispatchedSendingState.Pending -> retryable.add(latest.sourceMessageId)
                is DispatchedSendingState.Failed -> {
                    val tdErr = TdLibException(state.errorCode, state.errorMessage)
                    val waitSec = state.retryAfterSeconds?.takeIf { it > 0 }
                        ?: tdErr.extractFloodWaitSeconds()
                    when {
                        CloneReliabilityExecutor.isForwardsRestrictedError(tdErr) -> {
                            restricted.add(latest.sourceMessageId)
                        }
                        waitSec != null -> {
                            maxFloodWait = maxOf(maxFloodWait ?: 0, waitSec)
                            retryable.add(latest.sourceMessageId)
                        }
                        state.errorCode == 400 || state.errorCode == 403 -> {
                            permanentFailed[latest.sourceMessageId] = state.errorMessage
                        }
                        else -> {
                            retryable.add(latest.sourceMessageId)
                        }
                    }
                }
            }
        }

        return BarrierDrainResult(
            confirmedSourceIds = confirmed,
            restrictedSourceIds = restricted,
            retryableSourceIds = retryable,
            permanentFailed = permanentFailed,
            floodWaitSeconds = maxFloodWait,
        )
    }
}

class CloneSessionRunner<T>(
    private val gateway: CloneExecutionGateway<T>,
    private val store: TaskQueueStore,
    private val settingsProvider: suspend () -> CloneSettings,
    private val plannerContext: CoroutineContext = Dispatchers.Default,
    private val timeoutMs: Long = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS,
    private val backoffMs: List<Long> = CloneReliabilityExecutor.RETRY_BACKOFF_MS,
    private val pollIntervalMs: Long = ConfirmationBarrier.DEFAULT_POLL_INTERVAL_MS,
    private val drainTimeoutMs: Long = ConfirmationBarrier.DEFAULT_DRAIN_TIMEOUT_MS,
    private val random: Random = Random.Default,
    private val delayFn: (suspend (Long) -> Unit)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onTaskUpdated: (CloneTaskEntity) -> Unit = {},
    private val onProgress: (suspend (
        task: CloneTaskEntity,
        statusText: String,
        floodWaitSeconds: Int?,
        isPaused: Boolean,
        forceImmediate: Boolean,
    ) -> Unit)? = null,
    private val onLogWarning: (String) -> Unit = {},
) {

    private suspend fun pause(ms: Long) {
        if (delayFn != null) delayFn.invoke(ms) else delay(ms)
    }

    private suspend fun emitProgress(
        task: CloneTaskEntity,
        statusText: String,
        floodWaitSeconds: Int? = null,
        isPaused: Boolean = false,
        forceImmediate: Boolean = false,
    ) {
        onProgress?.invoke(task, statusText, floodWaitSeconds, isPaused, forceImmediate)
    }

    suspend fun run(initialTask: CloneTaskEntity): CloneTaskEntity {
        var currentTask = initialTask
        onTaskUpdated(currentTask)

        // ==================== FASE A: PRÉ-SCAN HÍBRIDO ====================
        val cachedMessages = LinkedHashMap<Long, CloneableMessage<T>>()
        if (currentTask.totalFound == 0 && store.getPendingMessages(currentTask.id).isEmpty()) {
            currentTask = currentTask.copy(
                status = CloneTaskStatus.SCANNING,
                updatedAt = nowMs(),
            )
            onTaskUpdated(currentTask)
            store.updateTask(currentTask)
            emitProgress(
                currentTask,
                "Contabilizando arquivos e tópicos do canal de origem...",
                null,
                false,
                true,
            )

            val settings = settingsProvider()
            val scanResult = HybridPreScanner.scan(
                taskId = currentTask.id,
                sourceChatId = currentTask.sourceChatId,
                targetChatId = currentTask.targetChatId,
                settings = settings,
                gateway = gateway,
                timeoutMs = timeoutMs,
                delayFn = delayFn,
                onFloodWaitTick = { remaining ->
                    emitProgress(
                        currentTask,
                        "Aguardando limite do Telegram (${remaining}s)...",
                        remaining,
                        false,
                        true,
                    )
                },
                onScanProgress = { scanned ->
                    emitProgress(
                        currentTask,
                        "Contabilizando arquivos e tópicos ($scanned encontrados)...",
                        null,
                        false,
                        false,
                    )
                },
            )

            cachedMessages.putAll(scanResult.messagesById)
            store.saveScannedMessages(scanResult.queuedItems)

            currentTask = currentTask.copy(
                status = CloneTaskStatus.RUNNING,
                totalFound = scanResult.totalEligible,
                totalMessagesEstimate = scanResult.totalEligible,
                videoCount = scanResult.videoCount,
                photoCount = scanResult.photoCount,
                otherCount = scanResult.otherCount,
                topicCount = scanResult.topicCount,
                filteredSkippedCount = scanResult.filteredSkippedCount,
                targetIsForum = scanResult.targetIsForum,
                clonedCount = 0,
                failedPermanentCount = 0,
                updatedAt = nowMs(),
            )
            onTaskUpdated(currentTask)
            store.updateTask(currentTask)

            val summaryText = buildString {
                append("Scan: ${scanResult.totalEligible} itens (")
                append("${scanResult.videoCount} vídeos, ")
                append("${scanResult.photoCount} fotos, ")
                append("${scanResult.otherCount} outros")
                if (scanResult.topicCount > 0) {
                    append(", ${scanResult.topicCount} tópicos")
                }
                append(")")
            }
            emitProgress(currentTask, summaryText, null, false, true)
        } else {
            val sentAlready = store.countByStatus(currentTask.id, CloneMessageStatus.SENT)
            val failedAlready = store.countByStatus(currentTask.id, CloneMessageStatus.FAILED_PERMANENT)
            currentTask = currentTask.copy(
                status = CloneTaskStatus.RUNNING,
                clonedCount = sentAlready,
                failedPermanentCount = failedAlready,
                updatedAt = nowMs(),
            )
            onTaskUpdated(currentTask)
            store.updateTask(currentTask)
        }

        // ==================== FASE B: ENVIO COM BARREIRA ====================
        val sourceChatProtected = runCatching {
            val outcome = CloneReliabilityExecutor.executeWithPolicy(
                timeoutMs = timeoutMs,
                skipOnForwardsRestricted = false,
                onFloodWaitTick = { remaining ->
                    emitProgress(
                        currentTask,
                        "Aguardando limite do Telegram (${remaining}s)...",
                        remaining,
                        false,
                        true,
                    )
                },
                delayFn = delayFn,
            ) { gateway.isChatProtected(currentTask.sourceChatId) }
            (outcome as? ReliabilityOutcome.Success)?.value == true
        }.getOrDefault(false)

        val pendingPhaseB = store.getPendingMessages(currentTask.id)
        if (pendingPhaseB.isNotEmpty()) {
            currentTask = executeQueuePass(
                startTask = currentTask,
                queueEntities = pendingPhaseB,
                cachedMessages = cachedMessages,
                sourceChatProtected = sourceChatProtected,
                isRecoveryPass = false,
            )
        }

        // ==================== FASE C: REPESCAGEM AUTOMÁTICA ====================
        currentTask = currentTask.copy(
            status = CloneTaskStatus.VERIFYING,
            updatedAt = nowMs(),
        )
        onTaskUpdated(currentTask)
        store.updateStatus(currentTask.id, CloneTaskStatus.VERIFYING, currentTask.updatedAt)
        emitProgress(
            currentTask,
            "Verificando integridade e repescando faltantes...",
            null,
            false,
            true,
        )

        for (round in 1..2) {
            if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
            val missing = store.getPendingMessages(currentTask.id)
            if (missing.isEmpty()) break

            emitProgress(
                currentTask,
                "Repescagem automática (rodada $round/2 • ${missing.size} faltantes)...",
                null,
                false,
                true,
            )
            currentTask = executeQueuePass(
                startTask = currentTask,
                queueEntities = missing,
                cachedMessages = cachedMessages,
                sourceChatProtected = sourceChatProtected,
                isRecoveryPass = true,
            )
        }

        val remainingUnconfirmed = store.getPendingMessages(currentTask.id)
        if (remainingUnconfirmed.isNotEmpty()) {
            val reason = remainingUnconfirmed.firstOrNull { it.errorMessage.isNotBlank() }?.errorMessage
                ?: "Falha persistente após repescagem"
            store.markFailedPermanent(
                taskId = currentTask.id,
                messageIds = remainingUnconfirmed.map { it.messageId },
                reason = reason,
            )
            currentTask = currentTask.copy(
                lastFailureReason = currentTask.lastFailureReason.ifBlank { reason },
            )
        }

        // ==================== FASE D: RESUMO PERSISTENTE ====================
        val finalSent = store.countByStatus(currentTask.id, CloneMessageStatus.SENT)
        val finalFailed = store.countByStatus(currentTask.id, CloneMessageStatus.FAILED_PERMANENT)
        val finalTotal = maxOf(currentTask.totalFound, finalSent + finalFailed)

        val completed = currentTask.copy(
            status = CloneTaskStatus.COMPLETED,
            clonedCount = finalSent,
            failedPermanentCount = finalFailed,
            totalFound = finalTotal,
            totalMessagesEstimate = finalTotal,
            updatedAt = nowMs(),
        )
        onTaskUpdated(completed)
        store.updateTask(completed)
        emitProgress(completed, "Clonagem Concluída!", null, false, true)
        return completed
    }

    private suspend fun executeQueuePass(
        startTask: CloneTaskEntity,
        queueEntities: List<CloneMessageEntity>,
        cachedMessages: MutableMap<Long, CloneableMessage<T>>,
        sourceChatProtected: Boolean,
        isRecoveryPass: Boolean,
    ): CloneTaskEntity {
        var currentTask = startTask
        val settings = settingsProvider()

        val resolvedMessages = mutableListOf<CloneableMessage<T>>()
        val missingOnOrigin = mutableListOf<Long>()

        for (entity in queueEntities) {
            if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
            val cached = cachedMessages[entity.messageId]
            val msg = cached ?: run {
                val fetched = runCatching {
                    when (
                        val outcome = CloneReliabilityExecutor.executeWithPolicy(
                            timeoutMs = timeoutMs,
                            backoffMs = backoffMs,
                            skipOnForwardsRestricted = false,
                            onFloodWaitTick = { remaining ->
                                emitProgress(
                                    currentTask,
                                    "Aguardando limite do Telegram (${remaining}s)...",
                                    remaining,
                                    false,
                                    true,
                                )
                            },
                            delayFn = delayFn,
                        ) {
                            gateway.getMessage(currentTask.sourceChatId, entity.messageId)
                        }
                    ) {
                        is ReliabilityOutcome.Success -> outcome.value
                        is ReliabilityOutcome.RestrictedSkipped -> null
                    }
                }.getOrNull()
                if (fetched != null) {
                    val mapped = fetched.copy(targetThreadId = entity.targetThreadId)
                    cachedMessages[entity.messageId] = mapped
                    mapped
                } else {
                    null
                }
            }

            if (msg == null) {
                missingOnOrigin.add(entity.messageId)
            } else {
                resolvedMessages.add(msg.copy(targetThreadId = entity.targetThreadId))
            }
        }

        if (missingOnOrigin.isNotEmpty()) {
            store.markFailedPermanent(
                taskId = currentTask.id,
                messageIds = missingOnOrigin,
                reason = "Mensagem indisponível na origem",
            )
            currentTask = syncCountsFromStore(currentTask, missingOnOrigin.last(), "Mensagem indisponível na origem")
        }

        if (resolvedMessages.isEmpty()) return currentTask

        val units = withContext(plannerContext) {
            CloneBatchPlanner.buildSendUnits(resolvedMessages, settings)
        }
        if (units.isEmpty()) return currentTask

        if (currentTask.isBatchMode && !isRecoveryPass) {
            val blocks = withContext(plannerContext) {
                CloneBatchPlanner.buildBlocks(
                    units = units,
                    blockSize = currentTask.batchBlockSize,
                    preserveAlbumIntegrity = currentTask.preserveAlbumIntegrity,
                )
            }

            for ((blockIdx, block) in blocks.withIndex()) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")

                for (call in block.tdLibCalls) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                    currentTask = dispatchAndConfirmItems(
                        startTask = currentTask,
                        items = call.items,
                        sourceChatProtected = sourceChatProtected,
                        statusPrefix = "Modo Lote (${block.messageCount} msgs)",
                    )
                }

                if (blockIdx < blocks.lastIndex && currentCoroutineContext().isActive) {
                    val jitterMs = CloneBatchPlanner.computeJitterDelayMs(
                        minDelayMs = currentTask.batchJitterMinMs,
                        maxDelayMs = currentTask.batchJitterMaxMs,
                        random = random,
                    )
                    val formatted = String.format(Locale.US, "%.1fs", jitterMs / 1000.0)
                    emitProgress(
                        currentTask,
                        "Modo Lote (${block.messageCount} msgs) • Pausa jitter: $formatted...",
                        null,
                        false,
                        true,
                    )
                    if (jitterMs > 0L) pause(jitterMs)
                }
            }
        } else {
            val calls = if (isRecoveryPass) {
                withContext(plannerContext) { CloneBatchPlanner.sliceUnitsIntoTdLibCalls(units) }
            } else {
                units.map { TdLibCallBatch(it.items) }
            }

            for ((idx, call) in calls.withIndex()) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                val latestSettings = settingsProvider()
                val prefix = if (isRecoveryPass) "Repescagem" else "Clonando"
                currentTask = dispatchAndConfirmItems(
                    startTask = currentTask,
                    items = call.items,
                    sourceChatProtected = sourceChatProtected,
                    statusPrefix = prefix,
                )

                if (!isRecoveryPass && idx < calls.lastIndex) {
                    val cadenceMs = (latestSettings.delaySeconds * 1000f).toLong().coerceAtLeast(500L)
                    emitProgress(
                        currentTask,
                        "Aguardando cadência (${String.format(Locale.US, "%.1fs", latestSettings.delaySeconds)})...",
                        null,
                        false,
                        false,
                    )
                    pause(cadenceMs)
                }
            }
        }

        return currentTask
    }

    private suspend fun dispatchAndConfirmItems(
        startTask: CloneTaskEntity,
        items: List<CloneableMessage<T>>,
        sourceChatProtected: Boolean,
        statusPrefix: String,
    ): CloneTaskEntity {
        var currentTask = startTask
        val itemMap = items.associateBy { it.id }
        var remainingIds = items.map { it.id }
        var transientRetryIndex = 0
        val forceProtectedIds = mutableSetOf<Long>()

        if (sourceChatProtected) {
            forceProtectedIds.addAll(remainingIds)
        } else {
            for (msg in items) {
                val canFwd = runCatching {
                    gateway.canForwardMessage(currentTask.sourceChatId, msg)
                }.getOrDefault(msg.canBeForwarded)
                if (!canFwd) {
                    forceProtectedIds.add(msg.id)
                }
            }
        }

        while (remainingIds.isNotEmpty() && currentCoroutineContext().isActive) {
            val latestSettings = settingsProvider()
            val remainingItems = remainingIds.mapNotNull { itemMap[it] }
            if (remainingItems.isEmpty()) break

            val targetThreadId = remainingItems.first().targetThreadId
            val useProtectedReupload = remainingItems.any { forceProtectedIds.contains(it.id) }

            emitProgress(
                currentTask,
                when {
                    useProtectedReupload -> "$statusPrefix • Reenvio protegido (${remainingItems.size} itens)..."
                    remainingItems.size > 10 -> "$statusPrefix • Enviando bloco (${remainingItems.size} msgs)..."
                    remainingItems.size > 1 -> "$statusPrefix • Enviando álbum (${remainingItems.size} itens)..."
                    else -> "$statusPrefix • Enviando mensagem..."
                },
                null,
                false,
                false,
            )

            val handles: List<DispatchedItemHandle> = try {
                val dispatchOutcome = CloneReliabilityExecutor.executeWithPolicy(
                    timeoutMs = timeoutMs,
                    backoffMs = backoffMs,
                    skipOnForwardsRestricted = !useProtectedReupload,
                    onFloodWaitTick = { remaining ->
                        emitProgress(
                            currentTask,
                            "Aguardando limite do Telegram (${remaining}s)...",
                            remaining,
                            false,
                            true,
                        )
                    },
                    onRetryAttempt = { attempt, delayMs, _ ->
                        emitProgress(
                            currentTask,
                            "Reconectando ($attempt/${backoffMs.size}) em ${delayMs / 1000}s...",
                            null,
                            false,
                            true,
                        )
                    },
                    onRestrictedSkip = { err ->
                        onLogWarning("Direct forward restricted, routing to ProtectedContentReuploader: ${err.message}")
                    },
                    delayFn = delayFn,
                ) {
                    if (useProtectedReupload) {
                        reuploadSegmented(
                            targetChatId = currentTask.targetChatId,
                            targetThreadId = targetThreadId,
                            targetIsForum = currentTask.targetIsForum,
                            items = remainingItems,
                            settings = latestSettings,
                        )
                    } else {
                        gateway.forwardMessages(
                            sourceChatId = currentTask.sourceChatId,
                            targetChatId = currentTask.targetChatId,
                            targetThreadId = targetThreadId,
                            targetIsForum = currentTask.targetIsForum,
                            messages = remainingItems,
                        )
                    }
                }

                when (dispatchOutcome) {
                    is ReliabilityOutcome.RestrictedSkipped -> {
                        forceProtectedIds.addAll(remainingIds)
                        continue
                    }
                    is ReliabilityOutcome.Success -> dispatchOutcome.value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (transientRetryIndex < backoffMs.size && CloneReliabilityExecutor.isTransientError(e)) {
                    val waitMs = backoffMs[transientRetryIndex++]
                    store.incrementAttempt(currentTask.id, remainingIds, e.message ?: "Erro temporário")
                    emitProgress(
                        currentTask,
                        "Reenviando faltantes ($transientRetryIndex/${backoffMs.size}) em ${waitMs / 1000}s...",
                        null,
                        false,
                        true,
                    )
                    pause(waitMs)
                    continue
                }
                val reason = e.message ?: "Erro ao enviar lote"
                store.incrementAttempt(currentTask.id, remainingIds, reason)
                currentTask = currentTask.copy(lastFailureReason = currentTask.lastFailureReason.ifBlank { reason })
                onTaskUpdated(currentTask)
                break
            }

            if (!useProtectedReupload && (latestSettings.stripMentionsAndLinks || latestSettings.customSignature.isNotBlank())) {
                runCatching {
                    gateway.applyPostCopyTextCleanup(currentTask.targetChatId, handles, latestSettings)
                }.onFailure { err ->
                    if (err is TdLibException && err.extractFloodWaitSeconds() != null) {
                        onLogWarning("FloodWait during post-copy text edit: ${err.message}")
                    }
                }
            }

            val drain = ConfirmationBarrier.drainUntilSettled(
                targetChatId = currentTask.targetChatId,
                initialHandles = handles,
                gateway = gateway,
                pollIntervalMs = pollIntervalMs,
                drainTimeoutMs = drainTimeoutMs,
                delayFn = delayFn,
            )

            if (drain.confirmedSourceIds.isNotEmpty()) {
                store.markSent(currentTask.id, drain.confirmedSourceIds)
                currentTask = syncCountsFromStore(
                    currentTask,
                    drain.confirmedSourceIds.max(),
                    currentTask.lastFailureReason,
                )
                emitProgress(
                    currentTask,
                    "$statusPrefix • Confirmadas: ${currentTask.clonedCount}/${currentTask.totalFound}",
                    null,
                    false,
                    false,
                )
            }

            if (drain.permanentFailed.isNotEmpty()) {
                val groupedByReason = drain.permanentFailed.entries.groupBy({ it.value }, { it.key })
                for ((reason, ids) in groupedByReason) {
                    store.markFailedPermanent(currentTask.id, ids, reason)
                }
                val firstReason = drain.permanentFailed.values.firstOrNull().orEmpty()
                currentTask = syncCountsFromStore(
                    currentTask,
                    drain.permanentFailed.keys.max(),
                    firstReason,
                )
            }

            val nextMissing = mutableListOf<Long>()
            if (drain.restrictedSourceIds.isNotEmpty()) {
                val newlyProtected = drain.restrictedSourceIds.filter { !forceProtectedIds.contains(it) }
                if (newlyProtected.isNotEmpty()) {
                    forceProtectedIds.addAll(newlyProtected)
                    nextMissing.addAll(newlyProtected)
                } else {
                    store.markFailedPermanent(
                        currentTask.id,
                        drain.restrictedSourceIds,
                        "Conteúdo protegido bloqueado no reenvio",
                    )
                    currentTask = syncCountsFromStore(
                        currentTask,
                        drain.restrictedSourceIds.max(),
                        "Conteúdo protegido bloqueado no reenvio",
                    )
                }
            }

            if (drain.retryableSourceIds.isNotEmpty()) {
                nextMissing.addAll(drain.retryableSourceIds)
            }

            if (nextMissing.isEmpty()) {
                break
            }

            remainingIds = nextMissing.sorted()

            // If we only moved restricted items into forceProtectedIds without failing, retry immediately via reuploader
            if (drain.retryableSourceIds.isEmpty() && drain.floodWaitSeconds == null) {
                continue
            }

            val floodWait = drain.floodWaitSeconds
            if (floodWait != null) {
                val totalWait = (floodWait + 1).coerceAtLeast(1)
                for (rem in totalWait downTo 1) {
                    emitProgress(
                        currentTask,
                        "Aguardando limite do Telegram (${rem}s)...",
                        rem,
                        false,
                        true,
                    )
                    pause(1_000L)
                }
                continue
            }

            if (transientRetryIndex < backoffMs.size) {
                val waitMs = backoffMs[transientRetryIndex++]
                store.incrementAttempt(currentTask.id, remainingIds, "Falha parcial no lote; aguardando reenvio")
                emitProgress(
                    currentTask,
                    "Reenviando ${remainingIds.size} faltantes ($transientRetryIndex/${backoffMs.size}) em ${waitMs / 1000}s...",
                    null,
                    false,
                    true,
                )
                pause(waitMs)
            } else {
                store.incrementAttempt(currentTask.id, remainingIds, "Tentativas do lote esgotadas")
                currentTask = currentTask.copy(
                    lastFailureReason = currentTask.lastFailureReason.ifBlank { "Falha temporária no envio" },
                )
                onTaskUpdated(currentTask)
                break
            }
        }

        return currentTask
    }

    private suspend fun reuploadSegmented(
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        items: List<CloneableMessage<T>>,
        settings: CloneSettings,
    ): List<DispatchedItemHandle> {
        val units = withContext(plannerContext) {
            CloneBatchPlanner.buildSendUnits(items, settings)
        }
        val handles = mutableListOf<DispatchedItemHandle>()
        for (unit in units) {
            handles.addAll(
                gateway.reuploadProtectedItems(
                    targetChatId = targetChatId,
                    targetThreadId = targetThreadId,
                    targetIsForum = targetIsForum,
                    items = unit.items,
                    settings = settings,
                )
            )
        }
        return handles
    }

    private suspend fun syncCountsFromStore(
        task: CloneTaskEntity,
        latestMessageId: Long,
        failureReason: String,
    ): CloneTaskEntity {
        val sent = store.countByStatus(task.id, CloneMessageStatus.SENT)
        val failed = store.countByStatus(task.id, CloneMessageStatus.FAILED_PERMANENT)
        val now = nowMs()
        val updated = task.copy(
            clonedCount = sent,
            failedPermanentCount = failed,
            lastClonedMessageId = maxOf(task.lastClonedMessageId, latestMessageId),
            lastFailureReason = task.lastFailureReason.ifBlank { failureReason },
            updatedAt = now,
        )
        onTaskUpdated(updated)
        store.updateDetailedProgress(
            taskId = updated.id,
            lastClonedMessageId = updated.lastClonedMessageId,
            clonedCount = sent,
            failedCount = failed,
            lastFailureReason = updated.lastFailureReason,
            updatedAt = now,
        )
        return updated
    }
}
