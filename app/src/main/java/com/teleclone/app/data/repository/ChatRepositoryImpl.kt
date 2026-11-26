package com.teleclone.app.data.repository

import android.content.Context
import com.teleclone.app.data.local.db.TeleCloneDatabase
import com.teleclone.app.data.local.db.entity.ChatEntity
import com.teleclone.app.data.local.db.entity.MessageEntity
import com.teleclone.app.data.tdlib.TdLibManager
import com.teleclone.app.domain.model.Chat
import com.teleclone.app.domain.model.Message
import com.teleclone.app.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi

class ChatRepositoryImpl(context: Context) : ChatRepository {

    private val db = TeleCloneDatabase.getInstance(context)
    private val chatDao = db.chatDao()
    private val messageDao = db.messageDao()

    override fun getChats(): Flow<List<Chat>> {
        return chatDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMessages(chatId: Long): Flow<List<Message>> {
        return messageDao.observeByChatId(chatId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText().apply {
                this.text = text
            }
        }
        TdLibManager.sendAsync(TdApi.SendMessage(chatId, 0, null, null, null, content))
    }

    override suspend fun loadChats() {
        val result = TdLibManager.sendAsync(
            TdApi.GetChats(TdApi.ChatListMain(), 100)
        )
        if (result is TdApi.Chats) {
            result.chatIds.forEach { chatId ->
                val chatResult = TdLibManager.sendAsync(TdApi.GetChat(chatId))
                if (chatResult is TdApi.Chat) {
                    val lastMsg = (chatResult.lastMessage?.content as? TdApi.MessageText)
                        ?.text?.text ?: ""
                    val entity = ChatEntity(
                        id = chatResult.id,
                        title = chatResult.title,
                        lastMessage = lastMsg,
                        lastMessageTime = chatResult.lastMessage?.date?.toLong() ?: 0L,
                        unreadCount = chatResult.unreadCount,
                        avatarPath = chatResult.photo?.small?.local?.path,
                        isPinned = false,
                    )
                    chatDao.upsert(entity)
                }
            }
        }
    }

    override suspend fun loadMessages(chatId: Long, fromMessageId: Long, limit: Int) {
        val result = TdLibManager.sendAsync(
            TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)
        )
        if (result is TdApi.Messages) {
            val entities = result.messages?.map { msg ->
                val text = (msg.content as? TdApi.MessageText)?.text?.text ?: "[media]"
                val senderName = when (val sender = msg.senderId) {
                    is TdApi.MessageSenderUser -> {
                        val user = TdLibManager.sendAsync(TdApi.GetUser(sender.userId))
                        if (user is TdApi.User) "${user.firstName} ${user.lastName}".trim()
                        else "User"
                    }
                    is TdApi.MessageSenderChat -> {
                        val chat = TdLibManager.sendAsync(TdApi.GetChat(sender.chatId))
                        if (chat is TdApi.Chat) chat.title else "Chat"
                    }
                    else -> "Unknown"
                }
                MessageEntity(
                    id = msg.id,
                    chatId = msg.chatId,
                    senderName = senderName,
                    text = text,
                    timestamp = msg.date.toLong(),
                    isOutgoing = msg.isOutgoing,
                )
            } ?: emptyList()
            messageDao.upsertAll(entities)
        }
    }
}
