package com.teleclone.app.domain.model

data class Message(
    val id: Long,
    val chatId: Long,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = false,
)
