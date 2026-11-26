package com.teleclone.app.domain.repository

import com.teleclone.app.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val authState: Flow<AuthState>
    suspend fun sendPhoneNumber(phoneNumber: String)
    suspend fun sendCode(code: String)
    suspend fun sendPassword(password: String)
    suspend fun logout()
}
