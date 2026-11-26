package com.teleclone.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teleclone.app.data.local.AppDatabase
import com.teleclone.app.data.local.SettingsDataStore
import com.teleclone.app.data.model.AuthState
import com.teleclone.app.data.model.CloneProgressState
import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.TelegramChatItem
import com.teleclone.app.data.model.UserProfile
import com.teleclone.app.data.telegram.TdLibManager
import com.teleclone.app.service.CloneForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao = database.cloneTaskDao()
    private val settingsDataStore = SettingsDataStore(application)

    val authState: StateFlow<AuthState> = TdLibManager.authState

    val cloneSettings: StateFlow<CloneSettings> = settingsDataStore.cloneSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, CloneSettings())

    val activeDbTask: StateFlow<CloneTaskEntity?> = dao.observeActiveTask()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val totalClonedMessages: StateFlow<Int> = dao.observeTotalClonedMessages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val serviceProgress: StateFlow<CloneProgressState> = CloneForegroundService.progressState

    private val _chats = MutableStateFlow<List<TelegramChatItem>>(emptyList())
    val chats: StateFlow<List<TelegramChatItem>> = _chats.asStateFlow()

    private val _isLoadingChats = MutableStateFlow(false)
    val isLoadingChats: StateFlow<Boolean> = _isLoadingChats.asStateFlow()

    private val _chatsError = MutableStateFlow<String?>(null)
    val chatsError: StateFlow<String?> = _chatsError.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _cacheSizeMb = MutableStateFlow(0.0)
    val cacheSizeMb: StateFlow<Double> = _cacheSizeMb.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _lastKnownAuthStep = MutableStateFlow<AuthState>(AuthState.WaitApiCredentials)
    val lastKnownAuthStep: StateFlow<AuthState> = _lastKnownAuthStep.asStateFlow()

    init {
        viewModelScope.launch {
            authState.collect { state ->
                when (state) {
                    is AuthState.WaitApiCredentials,
                    is AuthState.WaitPhoneNumber,
                    is AuthState.WaitCode,
                    is AuthState.WaitPassword -> {
                        _lastKnownAuthStep.value = state
                        _authError.value = null
                    }
                    is AuthState.Error -> {
                        _authError.value = state.msg
                    }
                    is AuthState.Ready -> {
                        _authError.value = null
                        refreshUserProfile()
                        refreshChats()
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
        refreshCacheSize()
    }

    fun submitApiCredentials(apiIdText: String, apiHash: String) {
        val parsedId = apiIdText.trim().toIntOrNull()
        if (parsedId == null || parsedId <= 0) {
            _authError.value = "Informe um API_ID numérico válido"
            return
        }
        if (apiHash.trim().length < 10) {
            _authError.value = "Informe um API_HASH válido"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            val res = TdLibManager.submitApiCredentials(parsedId, apiHash)
            res.onFailure { _authError.value = it.message ?: "Erro ao validar API_ID / API_HASH" }
        }
    }

    fun submitPhoneNumber(phone: String) {
        if (phone.isBlank()) {
            _authError.value = "Informe o número de telefone com código do país"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            val res = TdLibManager.submitPhoneNumber(phone)
            res.onFailure { _authError.value = it.message ?: "Erro ao enviar telefone" }
        }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) {
            _authError.value = "Informe o código recebido"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            val res = TdLibManager.submitCode(code)
            res.onFailure { _authError.value = it.message ?: "Código inválido" }
        }
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) {
            _authError.value = "Informe a senha de verificação em duas etapas"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            val res = TdLibManager.submitPassword(password)
            res.onFailure { _authError.value = it.message ?: "Senha incorreta" }
        }
    }

    fun backToApiCredentials() {
        _authError.value = null
        TdLibManager.resetToApiCredentials()
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun refreshChats() {
        if (_isLoadingChats.value) return
        viewModelScope.launch {
            _isLoadingChats.value = true
            _chatsError.value = null
            try {
                val loaded = TdLibManager.loadUserChats()
                _chats.value = loaded
            } catch (e: Exception) {
                _chatsError.value = e.message ?: "Erro ao carregar canais"
            } finally {
                _isLoadingChats.value = false
            }
        }
    }

    fun refreshUserProfile() {
        viewModelScope.launch {
            _userProfile.value = TdLibManager.getCurrentUserProfile()
        }
    }

    fun logout() {
        viewModelScope.launch {
            TdLibManager.logout()
            _chats.value = emptyList()
            _userProfile.value = null
        }
    }

    fun startCloning(context: Context, source: TelegramChatItem, target: TelegramChatItem) {
        CloneForegroundService.startCloning(
            context = context,
            sourceChatId = source.id,
            sourceTitle = source.title,
            targetChatId = target.id,
            targetTitle = target.title,
        )
    }

    fun pauseCloning(context: Context) {
        CloneForegroundService.pauseCloning(context)
    }

    fun resumeCloning(context: Context, taskId: Long) {
        CloneForegroundService.resumeCloning(context, taskId)
    }

    fun cancelCloning(context: Context) {
        viewModelScope.launch {
            val task = serviceProgress.value.activeTask ?: activeDbTask.value
            if (task != null) {
                dao.deleteById(task.id)
            }
            CloneForegroundService.cancelCloning(context)
        }
    }

    fun updateDelaySeconds(value: Float) {
        viewModelScope.launch { settingsDataStore.updateDelaySeconds(value) }
    }

    fun updateIncludeText(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateIncludeText(value) }
    }

    fun updateIncludePhotos(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateIncludePhotos(value) }
    }

    fun updateIncludeVideos(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateIncludeVideos(value) }
    }

    fun updateIncludeAudio(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateIncludeAudio(value) }
    }

    fun updateIncludeDocuments(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateIncludeDocuments(value) }
    }

    fun updateStripMentionsAndLinks(value: Boolean) {
        viewModelScope.launch { settingsDataStore.updateStripMentionsAndLinks(value) }
    }

    fun updateCustomSignature(value: String) {
        viewModelScope.launch { settingsDataStore.updateCustomSignature(value) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                calculateDirSize(getApplication<Application>().cacheDir)
            }
            _cacheSizeMb.value = bytes / (1024.0 * 1024.0)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deleteDirContents(getApplication<Application>().cacheDir)
            }
            refreshCacheSize()
        }
    }

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return total
    }

    private fun deleteDirContents(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirContents(file)
            }
            file.delete()
        }
    }
}
