package com.teleclone.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index

object CloneMessageStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
}

@Immutable
@Entity(
    tableName = "clone_messages",
    primaryKeys = ["taskId", "messageId"],
    indices = [Index(value = ["taskId", "status"])],
)
data class CloneMessageEntity(
    val taskId: Long,
    val messageId: Long,
    val albumId: Long,
    val originThreadId: Long,
    val targetThreadId: Long,
    val mediaType: String,
    val status: String = CloneMessageStatus.PENDING,
    val targetMessageId: Long = 0L,
    val attemptCount: Int = 0,
    val errorMessage: String = "",
)
