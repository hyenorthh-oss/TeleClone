package com.teleclone.app.presentation.screens.chats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teleclone.app.data.repository.ChatRepositoryImpl
import com.teleclone.app.domain.model.Chat
import com.teleclone.app.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository = ChatRepositoryImpl(application)

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getChats().collect { _chats.value = it }
        }
        loadChats()
    }

    fun loadChats() {
        viewModelScope.launch {
            _isLoading.value = true
            try { repository.loadChats() } catch (_: Exception) {}
            _isLoading.value = false
        }
    }
}
