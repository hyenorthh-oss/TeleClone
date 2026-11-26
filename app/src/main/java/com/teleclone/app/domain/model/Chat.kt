package com.teleclone.app.domain.model

data class Chat(
    val id: Long,
    val title: String,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val avatarPath: String? = null,
    val isOnline: Boolean = false,
    val isPinned: Boolean = false,
)
