package com.teleclone.app.domain.repository

import com.teleclone.app.domain.model.Chat
import com.teleclone.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getChats(): Flow<List<Chat>>
    fun getMessages(chatId: Long): Flow<List<Message>>
    suspend fun sendMessage(chatId: Long, text: String)
    suspend fun loadChats()
    suspend fun loadMessages(chatId: Long, fromMessageId: Long = 0, limit: Int = 50)
}
