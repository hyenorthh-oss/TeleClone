package com.teleclone.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

object CloneTaskStatus {
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val ERROR = "ERROR"
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
)
