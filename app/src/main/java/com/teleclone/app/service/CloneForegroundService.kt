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
import androidx.core.app.NotificationCompat
import com.teleclone.app.MainActivity
import com.teleclone.app.data.local.AppDatabase
import com.teleclone.app.data.local.SettingsDataStore
import com.teleclone.app.data.model.CloneProgressState
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import com.teleclone.app.data.telegram.TdLibException
import com.teleclone.app.data.telegram.TdLibManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class CloneForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cloneJob: Job? = null

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

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        sourceTitle = sourceTitle,
                        targetTitle = targetTitle,
                        cloned = 0,
                        total = 0,
                        statusText = "Iniciando clonagem...",
                        isPaused = false,
                    )
                )
                startOrResumeCloning(sourceId, sourceTitle, targetId, targetTitle, existingTaskId)
            }
            ACTION_PAUSE -> {
                pauseActiveCloning()
            }
            ACTION_RESUME -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
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
    ) {
        cloneJob?.cancel()
        cloneJob = serviceScope.launch {
            val dao = database.cloneTaskDao()
            val now = System.currentTimeMillis()
            val task: CloneTaskEntity = if (existingTaskId > 0L) {
                dao.getById(existingTaskId)?.copy(
                    status = CloneTaskStatus.RUNNING,
                    updatedAt = now,
                )?.also { dao.update(it) } ?: createNewTask(sourceChatId, sourceTitle, targetChatId, targetTitle)
            } else {
                createNewTask(sourceChatId, sourceTitle, targetChatId, targetTitle)
            }

            runCloningLoop(task)
        }
    }

    private suspend fun createNewTask(
        sourceChatId: Long,
        sourceTitle: String,
        targetChatId: Long,
        targetTitle: String,
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
        )
        database.cloneTaskDao().insert(task)
        return task
    }

    private fun resumeTaskById(taskId: Long) {
        serviceScope.launch {
            val dao = database.cloneTaskDao()
            val task = if (taskId > 0L) dao.getById(taskId) else _progressState.value.activeTask
            if (task != null) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        sourceTitle = task.sourceTitle,
                        targetTitle = task.targetTitle,
                        cloned = task.clonedCount,
                        total = task.totalMessagesEstimate,
                        statusText = "Retomando clonagem...",
                        isPaused = false,
                    )
                )
                val updated = task.copy(status = CloneTaskStatus.RUNNING, updatedAt = System.currentTimeMillis())
                dao.update(updated)
                cloneJob?.cancel()
                cloneJob = serviceScope.launch { runCloningLoop(updated) }
            }
        }
    }

    private fun pauseActiveCloning() {
        cloneJob?.cancel()
        serviceScope.launch {
            val current = _progressState.value.activeTask ?: return@launch
            val pausedTask = current.copy(
                status = CloneTaskStatus.PAUSED,
                updatedAt = System.currentTimeMillis(),
            )
            database.cloneTaskDao().updateStatus(
                pausedTask.id,
                CloneTaskStatus.PAUSED,
                pausedTask.updatedAt,
            )
            updateProgressState(pausedTask, "Clonagem pausada", null, isPaused = true)
        }
    }

    private fun cancelActiveCloning() {
        cloneJob?.cancel()
        serviceScope.launch {
            val current = _progressState.value.activeTask
            if (current != null) {
                database.cloneTaskDao().deleteById(current.id)
            }
            _progressState.value = CloneProgressState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runCloningLoop(initialTask: CloneTaskEntity) {
        val dao = database.cloneTaskDao()
        var currentTask = initialTask

        if (currentTask.totalMessagesEstimate <= 0) {
            val estimate = TdLibManager.getChatMessageCountEstimate(currentTask.sourceChatId)
            if (estimate > 0) {
                currentTask = currentTask.copy(totalMessagesEstimate = estimate)
                dao.updateTotalEstimate(currentTask.id, estimate, System.currentTimeMillis())
            }
        }

        updateProgressState(currentTask, "Carregando histórico...", null, isPaused = false)

        try {
            while (serviceScope.isActive) {
                val batch = executeWithFloodWait(currentTask) {
                    TdLibManager.fetchHistoryBatch(
                        chatId = currentTask.sourceChatId,
                        fromMessageId = currentTask.lastClonedMessageId,
                        limit = 50,
                    )
                }

                if (batch.isEmpty()) {
                    val completed = currentTask.copy(
                        status = CloneTaskStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis(),
                    )
                    dao.updateStatus(completed.id, CloneTaskStatus.COMPLETED, completed.updatedAt)
                    updateProgressState(completed, "Clonagem concluída", null, isPaused = false)
                    delay(2000L)
                    _progressState.value = CloneProgressState()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }

                // Group consecutive messages by mediaAlbumId when mediaAlbumId != 0L
                val groups = groupMessagesIntoSendUnits(batch)

                for (unit in groups) {
                    if (!serviceScope.isActive) return

                    val latestSettings = settingsDataStore.getCloneSettings()
                    val sentCount = executeWithFloodWait(currentTask) {
                        TdLibManager.forwardCopyClean(
                            sourceChatId = currentTask.sourceChatId,
                            targetChatId = currentTask.targetChatId,
                            messages = unit,
                            settings = latestSettings,
                            onStatusUpdate = { detail ->
                                updateProgressState(currentTask, detail, null, isPaused = false)
                            },
                        )
                    }

                    val sent = sentCount > 0
                    val lastIdInUnit = unit.last().id
                    val newCount = currentTask.clonedCount + sentCount
                    val now = System.currentTimeMillis()

                    currentTask = currentTask.copy(
                        lastClonedMessageId = lastIdInUnit,
                        clonedCount = newCount,
                        updatedAt = now,
                    )
                    dao.updateProgress(
                        taskId = currentTask.id,
                        lastClonedMessageId = lastIdInUnit,
                        clonedCount = newCount,
                        updatedAt = now,
                    )

                    updateProgressState(
                        currentTask,
                        if (sent) "Aguardando cadência (${String.format("%.1fs", latestSettings.delaySeconds)})..." else "Item ignorado pelos filtros",
                        null,
                        isPaused = false,
                    )

                    if (sent) {
                        delay((latestSettings.delaySeconds * 1000f).toLong().coerceAtLeast(500L))
                    }
                }
            }
        } catch (_: CancellationException) {
            // Handled by pause or cancel action
        } catch (e: Exception) {
            val errorTask = currentTask.copy(
                status = CloneTaskStatus.ERROR,
                updatedAt = System.currentTimeMillis(),
            )
            dao.updateStatus(errorTask.id, CloneTaskStatus.ERROR, errorTask.updatedAt)
            updateProgressState(
                errorTask,
                "Erro: ${e.message ?: "falha na clonagem"}",
                null,
                isPaused = true,
            )
        }
    }

    private fun groupMessagesIntoSendUnits(messages: List<TdApi.Message>): List<List<TdApi.Message>> {
        val units = mutableListOf<List<TdApi.Message>>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            if (current.mediaAlbumId != 0L) {
                val album = mutableListOf(current)
                var nextIdx = index + 1
                while (nextIdx < messages.size && messages[nextIdx].mediaAlbumId == current.mediaAlbumId) {
                    album.add(messages[nextIdx])
                    nextIdx++
                }
                units.add(album)
                index = nextIdx
            } else {
                units.add(listOf(current))
                index++
            }
        }
        return units
    }

    private suspend fun <T> executeWithFloodWait(
        task: CloneTaskEntity,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return block()
            } catch (e: TdLibException) {
                val waitSeconds = e.extractFloodWaitSeconds()
                if (waitSeconds != null) {
                    val totalWait = waitSeconds + 2
                    for (remaining in totalWait downTo 1) {
                        updateProgressState(
                            task,
                            "Aguardando limite da API (${remaining}s)...",
                            remaining,
                            isPaused = false,
                        )
                        delay(1000L)
                    }
                } else {
                    throw e
                }
            }
        }
    }

    private fun updateProgressState(
        task: CloneTaskEntity,
        statusText: String,
        floodWaitSeconds: Int?,
        isPaused: Boolean,
    ) {
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
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "teleclone_sync_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.teleclone.app.action.START_CLONE"
        const val ACTION_PAUSE = "com.teleclone.app.action.PAUSE_CLONE"
        const val ACTION_RESUME = "com.teleclone.app.action.RESUME_CLONE"
        const val ACTION_CANCEL = "com.teleclone.app.action.CANCEL_CLONE"

        const val EXTRA_SOURCE_CHAT_ID = "extra_source_chat_id"
        const val EXTRA_SOURCE_TITLE = "extra_source_title"
        const val EXTRA_TARGET_CHAT_ID = "extra_target_chat_id"
        const val EXTRA_TARGET_TITLE = "extra_target_title"
        const val EXTRA_TASK_ID = "extra_task_id"

        private val _progressState = MutableStateFlow(CloneProgressState())
        val progressState: StateFlow<CloneProgressState> = _progressState.asStateFlow()

        fun startCloning(
            context: Context,
            sourceChatId: Long,
            sourceTitle: String,
            targetChatId: Long,
            targetTitle: String,
        ) {
            val intent = Intent(context, CloneForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_CHAT_ID, sourceChatId)
                putExtra(EXTRA_SOURCE_TITLE, sourceTitle)
                putExtra(EXTRA_TARGET_CHAT_ID, targetChatId)
                putExtra(EXTRA_TARGET_TITLE, targetTitle)
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
            context.startService(intent)
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
            context.startService(intent)
        }
    }
}
