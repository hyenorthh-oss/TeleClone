package com.teleclone.app.presentation.screens.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.teleclone.app.data.repository.ChatRepositoryImpl
import com.teleclone.app.domain.model.Message
import com.teleclone.app.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val chatId: Long = savedStateHandle["chatId"] ?: 0L
    private val repository: ChatRepository = ChatRepositoryImpl(application)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMessages(chatId).collect { _messages.value = it }
        }
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            try { repository.loadMessages(chatId) } catch (_: Exception) {}
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try { repository.sendMessage(chatId, text) } catch (_: Exception) {}
        }
    }
}
