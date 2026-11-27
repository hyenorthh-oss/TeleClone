package com.teleclone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teleclone.app.MainActivity
import com.teleclone.app.data.local.AppDatabase
import com.teleclone.app.data.local.SettingsDataStore
import com.teleclone.app.data.model.CloneProgressState
import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import com.teleclone.app.data.telegram.CloneSessionRunner
import com.teleclone.app.data.telegram.CloneTransport
import com.teleclone.app.data.telegram.CloneableMessage
import com.teleclone.app.data.telegram.PaginatedHistoryResult
import com.teleclone.app.data.telegram.TaskProgressStore
import com.teleclone.app.data.telegram.TdLibManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

class CloneForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cloneJob: Job? = null

    @Volatile
    private var activeLoopTask: CloneTaskEntity? = null

    @Volatile
    private var lastProgressDispatchMs: Long = 0L

    private lateinit var database: AppDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(applicationContext)
        settingsDataStore = SettingsDataStore(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceId = intent.getLongExtra(EXTRA_SOURCE_CHAT_ID, 0L)
                val sourceTitle = intent.getStringExtra(EXTRA_SOURCE_TITLE).orEmpty()
                val targetId = intent.getLongExtra(EXTRA_TARGET_CHAT_ID, 0L)
                val targetTitle = intent.getStringExtra(EXTRA_TARGET_TITLE).orEmpty()
                val existingTaskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
                val isBatchMode = intent.getBooleanExtra(EXTRA_IS_BATCH_MODE, false)
                val blockSize = intent.getIntExtra(EXTRA_BATCH_BLOCK_SIZE, 1).coerceAtLeast(1)
                val jitterMinMs = intent.getLongExtra(EXTRA_BATCH_JITTER_MIN_MS, 2500L).coerceAtLeast(1L)
                val jitterMaxMs = intent.getLongExtra(EXTRA_BATCH_JITTER_MAX_MS, jitterMinMs).coerceAtLeast(jitterMinMs)
                val preserveAlbumIntegrity = intent.getBooleanExtra(EXTRA_PRESERVE_ALBUM_INTEGRITY, true)

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        sourceTitle = sourceTitle,
                        targetTitle = targetTitle,
                        cloned = 0,
                        total = 0,
                        statusText = if (isBatchMode) "Iniciando Modo Lote ($blockSize msgs)..." else "Iniciando clonagem...",
                        isPaused = false,
                    )
                )
                startOrResumeCloning(
                    sourceChatId = sourceId,
                    sourceTitle = sourceTitle,
                    targetChatId = targetId,
                    targetTitle = targetTitle,
                    existingTaskId = existingTaskId,
                    isBatchMode = isBatchMode,
                    blockSize = blockSize,
                    jitterMinMs = jitterMinMs,
                    jitterMaxMs = jitterMaxMs,
                    preserveAlbumIntegrity = preserveAlbumIntegrity,
                )
            }
            ACTION_PAUSE -> {
                pauseActiveCloning()
            }
            ACTION_RESUME -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
                val current = activeLoopTask ?: _progressState.value.activeTask
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        sourceTitle = current?.sourceTitle.orEmpty(),
                        targetTitle = current?.targetTitle.orEmpty(),
                        cloned = current?.clonedCount ?: 0,
                        total = current?.totalMessagesEstimate ?: 0,
                        statusText = "Retomando clonagem...",
                        isPaused = false,
                    )
                )
                resumeTaskById(taskId)
            }
            ACTION_CANCEL -> {
                cancelActiveCloning()
            }
        }
        return START_NOT_STICKY
    }

    private fun startOrResumeCloning(
        sourceChatId: Long,
        sourceTitle: String,
        targetChatId: Long,
        targetTitle: String,
        existingTaskId: Long,
        isBatchMode: Boolean,
        blockSize: Int,
        jitterMinMs: Long,
        jitterMaxMs: Long,
        preserveAlbumIntegrity: Boolean,
    ) {
        cloneJob?.cancel()
        cloneJob = serviceScope.launch {
            val dao = database.cloneTaskDao()
            val now = System.currentTimeMillis()
            val task: CloneTaskEntity = if (existingTaskId > 0L) {
                dao.getById(existingTaskId)?.copy(
                    status = CloneTaskStatus.RUNNING,
                    updatedAt = now,
                )?.also { dao.update(it) } ?: createNewTask(
                    sourceChatId = sourceChatId,
                    sourceTitle = sourceTitle,
                    targetChatId = targetChatId,
                    targetTitle = targetTitle,
                    isBatchMode = isBatchMode,
                    blockSize = blockSize,
                    jitterMinMs = jitterMinMs,
                    jitterMaxMs = jitterMaxMs,
                    preserveAlbumIntegrity = preserveAlbumIntegrity,
                )
            } else {
                createNewTask(
                    sourceChatId = sourceChatId,
                    sourceTitle = sourceTitle,
                    targetChatId = targetChatId,
                    targetTitle = targetTitle,
                    isBatchMode = isBatchMode,
                    blockSize = blockSize,
                    jitterMinMs = jitterMinMs,
                    jitterMaxMs = jitterMaxMs,
                    preserveAlbumIntegrity = preserveAlbumIntegrity,
                )
            }

            runCloningLoop(task)
        }
    }

    private suspend fun createNewTask(
        sourceChatId: Long,
        sourceTitle: String,
        targetChatId: Long,
        targetTitle: String,
        isBatchMode: Boolean,
        blockSize: Int,
        jitterMinMs: Long,
        jitterMaxMs: Long,
        preserveAlbumIntegrity: Boolean,
    ): CloneTaskEntity {
        val estimate = TdLibManager.getChatMessageCountEstimate(sourceChatId)
        val task = CloneTaskEntity(
            id = System.currentTimeMillis(),
            sourceChatId = sourceChatId,
            sourceTitle = sourceTitle,
            targetChatId = targetChatId,
            targetTitle = targetTitle,
            lastClonedMessageId = 0L,
            totalMessagesEstimate = estimate,
            clonedCount = 0,
            status = CloneTaskStatus.RUNNING,
            updatedAt = System.currentTimeMillis(),
            isBatchMode = isBatchMode,
            batchBlockSize = blockSize,
            batchJitterMinMs = jitterMinMs,
            batchJitterMaxMs = jitterMaxMs,
            preserveAlbumIntegrity = preserveAlbumIntegrity,
        )
        database.cloneTaskDao().insert(task)
        return task
    }

    private fun resumeTaskById(taskId: Long) {
        cloneJob?.cancel()
        cloneJob = serviceScope.launch {
            val dao = database.cloneTaskDao()
            val task = if (taskId > 0L) dao.getById(taskId) else (activeLoopTask ?: _progressState.value.activeTask)
            if (task != null) {
                val updated = task.copy(
                    status = CloneTaskStatus.RUNNING,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.update(updated)
                runCloningLoop(updated)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseActiveCloning() {
        val jobToCancel = cloneJob
        cloneJob = null
        jobToCancel?.cancel()

        serviceScope.launch {
            withContext(NonCancellable) {
                val current = activeLoopTask ?: _progressState.value.activeTask ?: return@withContext
                val pausedTask = current.copy(
                    status = CloneTaskStatus.PAUSED,
                    updatedAt = System.currentTimeMillis(),
                )
                activeLoopTask = pausedTask
                database.cloneTaskDao().updateStatus(
                    pausedTask.id,
                    CloneTaskStatus.PAUSED,
                    pausedTask.updatedAt,
                )
                updateProgressState(
                    task = pausedTask,
                    statusText = "Clonagem pausada",
                    floodWaitSeconds = null,
                    isPaused = true,
                    forceImmediate = true,
                )
            }
        }
    }

    private fun cancelActiveCloning() {
        val jobToCancel = cloneJob
        cloneJob = null
        jobToCancel?.cancel()

        serviceScope.launch {
            withContext(NonCancellable) {
                val current = activeLoopTask ?: _progressState.value.activeTask
                if (current != null) {
                    database.cloneTaskDao().deleteById(current.id)
                }
                activeLoopTask = null
                _progressState.value = CloneProgressState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runCloningLoop(initialTask: CloneTaskEntity) {
        val dao = database.cloneTaskDao()
        var currentTask = initialTask
        activeLoopTask = currentTask

        if (currentTask.totalMessagesEstimate <= 0) {
            val estimate = TdLibManager.getChatMessageCountEstimate(currentTask.sourceChatId)
            if (estimate > 0) {
                currentTask = currentTask.copy(totalMessagesEstimate = estimate)
                activeLoopTask = currentTask
                dao.updateTotalEstimate(currentTask.id, estimate, System.currentTimeMillis())
            }
        }

        val transport = object : CloneTransport<TdApi.Message> {
            override suspend fun fetchHistoryBatch(
                chatId: Long,
                fromMessageId: Long,
                desiredForwardableCount: Int,
            ): PaginatedHistoryResult<TdApi.Message> {
                val res = TdLibManager.fetchHistoryBatch(
                    chatId = chatId,
                    fromMessageId = fromMessageId,
                    desiredForwardableCount = desiredForwardableCount,
                )
                return PaginatedHistoryResult(
                    messages = res.messages.map { TdLibManager.toCloneableMessage(it) },
                    maxScannedMessageId = res.maxScannedMessageId,
                    isProtectedChat = res.isProtectedChat,
                )
            }

            override suspend fun forwardCopyClean(
                sourceChatId: Long,
                targetChatId: Long,
                messages: List<CloneableMessage<TdApi.Message>>,
                settings: CloneSettings,
                onStatusUpdate: suspend (String) -> Unit,
            ): Int {
                return TdLibManager.forwardCopyClean(
                    sourceChatId = sourceChatId,
                    targetChatId = targetChatId,
                    messages = messages.map { it.payload },
                    settings = settings,
                    onStatusUpdate = { detail ->
                        val latest = activeLoopTask ?: currentTask
                        updateProgressState(latest, detail, null, isPaused = false)
                    },
                )
            }
        }

        val store = object : TaskProgressStore {
            override suspend fun updateProgress(
                taskId: Long,
                lastClonedMessageId: Long,
                clonedCount: Int,
                updatedAt: Long,
            ) {
                dao.updateProgress(taskId, lastClonedMessageId, clonedCount, updatedAt)
            }

            override suspend fun updateStatus(taskId: Long, status: String, updatedAt: Long) {
                dao.updateStatus(taskId, status, updatedAt)
            }
        }

        val runner = CloneSessionRunner(
            transport = transport,
            store = store,
            settingsProvider = { settingsDataStore.getCloneSettings() },
            onTaskUpdated = { updated ->
                currentTask = updated
                activeLoopTask = updated
            },
            onProgress = { task, statusText, floodWaitSeconds, isPaused, forceImmediate ->
                updateProgressState(
                    task = task,
                    statusText = statusText,
                    floodWaitSeconds = floodWaitSeconds,
                    isPaused = isPaused,
                    forceImmediate = forceImmediate,
                )
            },
            onLogWarning = { msg -> Log.w(TAG, msg) },
        )

        try {
            runner.run(currentTask)
            delay(2000L)
            activeLoopTask = null
            _progressState.value = CloneProgressState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cloning loop error", e)
            val errorTask = (activeLoopTask ?: currentTask).copy(
                status = CloneTaskStatus.ERROR,
                updatedAt = System.currentTimeMillis(),
            )
            activeLoopTask = errorTask
            withContext(NonCancellable) {
                dao.updateStatus(errorTask.id, CloneTaskStatus.ERROR, errorTask.updatedAt)
                updateProgressState(
                    task = errorTask,
                    statusText = "Erro: ${e.message ?: "falha na clonagem"}",
                    floodWaitSeconds = null,
                    isPaused = true,
                    forceImmediate = true,
                )
            }
        }
    }

    private fun updateProgressState(
        task: CloneTaskEntity,
        statusText: String,
        floodWaitSeconds: Int?,
        isPaused: Boolean,
        forceImmediate: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate &&
            floodWaitSeconds == null &&
            !isPaused &&
            task.status == CloneTaskStatus.RUNNING &&
            (now - lastProgressDispatchMs) < PROGRESS_THROTTLE_MS
        ) {
            return
        }
        lastProgressDispatchMs = now

        _progressState.value = CloneProgressState(
            activeTask = task,
            statusMessage = statusText,
            floodWaitSeconds = floodWaitSeconds,
        )
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                sourceTitle = task.sourceTitle,
                targetTitle = task.targetTitle,
                cloned = task.clonedCount,
                total = task.totalMessagesEstimate,
                statusText = statusText,
                isPaused = isPaused,
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clonagem TeleClone",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progresso da clonagem de canais em segundo plano"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        sourceTitle: String,
        targetTitle: String,
        cloned: Int,
        total: Int,
        statusText: String,
        isPaused: Boolean,
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleActionIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CloneForegroundService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val progressLabel = if (total > 0) "$cloned / $total" else "$cloned mensagens"
        val title = if (sourceTitle.isNotBlank() && targetTitle.isNotBlank()) {
            "Clonando: $sourceTitle \u2192 $targetTitle"
        } else {
            "TeleClone"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$progressLabel - $statusText")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!isPaused)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Retomar" else "Pausar",
                toggleActionIntent,
            )

        if (total > 0) {
            builder.setProgress(total, cloned.coerceAtMost(total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cloneJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CloneForegroundService"
        private const val CHANNEL_ID = "teleclone_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_THROTTLE_MS = 200L

        const val ACTION_START = "com.teleclone.app.action.START_CLONE"
        const val ACTION_PAUSE = "com.teleclone.app.action.PAUSE_CLONE"
        const val ACTION_RESUME = "com.teleclone.app.action.RESUME_CLONE"
        const val ACTION_CANCEL = "com.teleclone.app.action.CANCEL_CLONE"

        const val EXTRA_SOURCE_CHAT_ID = "extra_source_chat_id"
        const val EXTRA_SOURCE_TITLE = "extra_source_title"
        const val EXTRA_TARGET_CHAT_ID = "extra_target_chat_id"
        const val EXTRA_TARGET_TITLE = "extra_target_title"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_IS_BATCH_MODE = "extra_is_batch_mode"
        const val EXTRA_BATCH_BLOCK_SIZE = "extra_batch_block_size"
        const val EXTRA_BATCH_JITTER_MIN_MS = "extra_batch_jitter_min_ms"
        const val EXTRA_BATCH_JITTER_MAX_MS = "extra_batch_jitter_max_ms"
        const val EXTRA_PRESERVE_ALBUM_INTEGRITY = "extra_preserve_album_integrity"

        private val _progressState = MutableStateFlow(CloneProgressState())
        val progressState: StateFlow<CloneProgressState> = _progressState.asStateFlow()

        fun startCloning(
            context: Context,
            sourceChatId: Long,
            sourceTitle: String,
            targetChatId: Long,
            targetTitle: String,
            isBatchMode: Boolean = false,
            batchBlockSize: Int = 1,
            batchJitterMinMs: Long = 2500L,
            batchJitterMaxMs: Long = 2500L,
            preserveAlbumIntegrity: Boolean = true,
        ) {
            val intent = Intent(context, CloneForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_CHAT_ID, sourceChatId)
                putExtra(EXTRA_SOURCE_TITLE, sourceTitle)
                putExtra(EXTRA_TARGET_CHAT_ID, targetChatId)
                putExtra(EXTRA_TARGET_TITLE, targetTitle)
                putExtra(EXTRA_IS_BATCH_MODE, isBatchMode)
                putExtra(EXTRA_BATCH_BLOCK_SIZE, batchBlockSize)
                putExtra(EXTRA_BATCH_JITTER_MIN_MS, batchJitterMinMs)
                putExtra(EXTRA_BATCH_JITTER_MAX_MS, batchJitterMaxMs)
                putExtra(EXTRA_PRESERVE_ALBUM_INTEGRITY, preserveAlbumIntegrity)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseCloning(context: Context) {
            val intent = Intent(context, CloneForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            runCatching { context.startService(intent) }
        }

        fun resumeCloning(context: Context, taskId: Long) {
            val intent = Intent(context, CloneForegroundService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelCloning(context: Context) {
            val intent = Intent(context, CloneForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.startService(intent) }
        }
    }
}
