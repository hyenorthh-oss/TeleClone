package com.teleclone.app.presentation.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teleclone.app.data.repository.AuthRepositoryImpl
import com.teleclone.app.domain.model.AuthState
import com.teleclone.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {

    private val repository: AuthRepository = AuthRepositoryImpl()

    val authState: StateFlow<AuthState> = repository.authState
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.WaitingForPhoneNumber)

    fun sendPhoneNumber(phone: String) {
        viewModelScope.launch { repository.sendPhoneNumber(phone) }
    }

    fun sendCode(code: String) {
        viewModelScope.launch { repository.sendCode(code) }
    }

    fun sendPassword(password: String) {
        viewModelScope.launch { repository.sendPassword(password) }
    }
}
