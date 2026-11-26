package com.teleclone.app.data.model

sealed class AuthState {
    data object Loading : AuthState()
    data object WaitApiCredentials : AuthState()
    data object WaitPhoneNumber : AuthState()
    data object WaitCode : AuthState()
    data object WaitPassword : AuthState()
    data object Ready : AuthState()
    data class Error(val msg: String) : AuthState()
}
