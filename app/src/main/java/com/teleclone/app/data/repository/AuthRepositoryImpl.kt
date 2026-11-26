package com.teleclone.app.data.repository

import com.teleclone.app.data.tdlib.TdLibManager
import com.teleclone.app.domain.model.AuthState
import com.teleclone.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow

class AuthRepositoryImpl : AuthRepository {

    override val authState: Flow<AuthState> = TdLibManager.authState

    override suspend fun sendPhoneNumber(phoneNumber: String) {
        TdLibManager.sendPhoneNumber(phoneNumber)
    }

    override suspend fun sendCode(code: String) {
        TdLibManager.sendCode(code)
    }

    override suspend fun sendPassword(password: String) {
        TdLibManager.sendPassword(password)
    }

    override suspend fun logout() {
        TdLibManager.logout()
    }
}
