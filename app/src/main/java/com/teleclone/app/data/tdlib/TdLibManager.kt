package com.teleclone.app.data.tdlib

import android.util.Log
import com.teleclone.app.TeleCloneApp
import com.teleclone.app.domain.model.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

object TdLibManager {

    private const val TAG = "TdLibManager"

    // TODO: Replace with your own Telegram API credentials from https://my.telegram.org
    private const val API_ID = 94575
    private const val API_HASH = "a3406de8d171bb422bb6ddf3bbd800e2"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.WaitingForPhoneNumber)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun initialize() {
        Client.execute(TdApi.SetLogVerbosityLevel(2))
        client = Client.create(::handleUpdate, null, null)
        scope.launch { processUpdates() }
        Log.d(TAG, "TDLib client initialized")
    }

    private val updateChannel = Channel<TdApi.Object>(Channel.UNLIMITED)

    private fun handleUpdate(update: TdApi.Object) {
        scope.launch { updateChannel.send(update) }
    }

    private suspend fun processUpdates() {
        for (update in updateChannel) {
            when (update) {
                is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
                else -> Log.v(TAG, "Update: ${update.javaClass.simpleName}")
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = File(
                        TeleCloneApp.instance.filesDir, "tdlib"
                    ).absolutePath
                    useMessageDatabase = true
                    useSecretChats = true
                    apiId = API_ID
                    apiHash = API_HASH
                    systemLanguageCode = "en"
                    deviceModel = "Android"
                    applicationVersion = "1.0.0"
                }
                send(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = AuthState.WaitingForPhoneNumber
            is TdApi.AuthorizationStateWaitCode ->
                _authState.value = AuthState.WaitingForCode
            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = AuthState.WaitingForPassword
            is TdApi.AuthorizationStateReady ->
                _authState.value = AuthState.Ready
            is TdApi.AuthorizationStateLoggingOut ->
                _authState.value = AuthState.LoggingOut
            is TdApi.AuthorizationStateClosed ->
                _authState.value = AuthState.Closed
            else -> Log.d(TAG, "Unhandled auth state: ${state.javaClass.simpleName}")
        }
    }

    fun send(query: TdApi.Function<*>, callback: ((TdApi.Object) -> Unit)? = null) {
        client?.send(query) { result ->
            callback?.invoke(result)
        }
    }

    suspend fun sendAsync(query: TdApi.Function<*>): TdApi.Object {
        val deferred = CompletableDeferred<TdApi.Object>()
        client?.send(query) { result ->
            deferred.complete(result)
        }
        return deferred.await()
    }

    fun sendPhoneNumber(phone: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    fun sendCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    fun sendPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun logout() {
        send(TdApi.LogOut())
    }

    fun destroy() {
        client?.close()
        client = null
    }
}
