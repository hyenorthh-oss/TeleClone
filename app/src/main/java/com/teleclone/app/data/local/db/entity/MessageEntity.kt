package com.teleclone.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.teleclone.app.domain.model.Message

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    val chatId: Long,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = false,
) {
    fun toDomain() = Message(
        id = id,
        chatId = chatId,
        senderName = senderName,
        text = text,
        timestamp = timestamp,
        isOutgoing = isOutgoing,
        isRead = isRead,
    )
}
