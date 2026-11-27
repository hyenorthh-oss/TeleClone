package com.teleclone.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

object CloneTaskStatus {
    const val SCANNING = "SCANNING"
    const val RUNNING = "RUNNING"
    const val VERIFYING = "VERIFYING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val ERROR = "ERROR"
    const val ARCHIVED = "ARCHIVED"

    fun isActiveOrRunning(status: String?): Boolean =
        status == SCANNING || status == RUNNING || status == VERIFYING
}

@Immutable
@Entity(tableName = "clone_tasks")
data class CloneTaskEntity(
    @PrimaryKey val id: Long,
    val sourceChatId: Long,
    val sourceTitle: String,
    val targetChatId: Long,
    val targetTitle: String,
    val lastClonedMessageId: Long,
    val totalMessagesEstimate: Int,
    val clonedCount: Int,
    val status: String,
    val updatedAt: Long,
    val isBatchMode: Boolean = false,
    val batchBlockSize: Int = 1,
    val batchJitterMinMs: Long = 2500L,
    val batchJitterMaxMs: Long = 2500L,
    val preserveAlbumIntegrity: Boolean = true,
    val totalFound: Int = 0,
    val videoCount: Int = 0,
    val photoCount: Int = 0,
    val otherCount: Int = 0,
    val topicCount: Int = 0,
    val filteredSkippedCount: Int = 0,
    val failedPermanentCount: Int = 0,
    val lastFailureReason: String = "",
    val targetIsForum: Boolean = false,
)
