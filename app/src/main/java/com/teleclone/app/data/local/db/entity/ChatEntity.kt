package com.teleclone.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.teleclone.app.domain.model.Chat

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val avatarPath: String? = null,
    val isPinned: Boolean = false,
) {
    fun toDomain(isOnline: Boolean = false) = Chat(
        id = id,
        title = title,
        lastMessage = lastMessage,
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        avatarPath = avatarPath,
        isOnline = isOnline,
        isPinned = isPinned,
    )
}
