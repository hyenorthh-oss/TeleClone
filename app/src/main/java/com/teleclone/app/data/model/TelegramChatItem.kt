package com.teleclone.app.data.model

data class TelegramChatItem(
    val id: Long,
    val title: String,
    val memberCount: Int,
    val avatarLocalPath: String?,
    val canSendMessages: Boolean,
    val isChannel: Boolean,
)
