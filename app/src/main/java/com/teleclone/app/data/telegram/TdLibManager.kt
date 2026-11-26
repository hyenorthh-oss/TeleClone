package com.teleclone.app.data.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.teleclone.app.data.local.SettingsDataStore
import com.teleclone.app.data.model.AuthState
import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.data.model.TelegramChatItem
import com.teleclone.app.data.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibException(
    val code: Int,
    override val message: String,
) : Exception("TDLib error $code: $message") {

    fun extractFloodWaitSeconds(): Int? {
        val floodRegex = Regex("""FLOOD_WAIT_(\d+)""", RegexOption.IGNORE_CASE)
        floodRegex.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val retryAfterRegex = Regex("""retry after (\d+)""", RegexOption.IGNORE_CASE)
        retryAfterRegex.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        if (code == 429) return 30
        return null
    }
}

object TdLibManager {

    private const val TAG = "TdLibManager"

    private lateinit var appContext: Context
    private lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val fileDownloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<TdApi.File>>()
    private val sendMessageWaiters = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()

    @Volatile
    private var waitingTdlibParameters = false

    fun initialize(context: Context) {
        if (::appContext.isInitialized && client != null) return
        appContext = context.applicationContext
        settingsDataStore = SettingsDataStore(appContext)
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set TDLib log verbosity", e)
        }
        createClient()
    }

    private fun createClient() {
        client = Client.create(::handleUpdate, null, null)
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationState(update.authorizationState)
            is TdApi.UpdateFile -> {
                val file = update.file
                if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
                    fileDownloadWaiters.remove(file.id)?.complete(file)
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                sendMessageWaiters.remove(update.oldMessageId)?.complete(update.message)
            }
            is TdApi.UpdateMessageSendFailed -> {
                val err = TdLibException(update.error.code, update.error.message)
                sendMessageWaiters.remove(update.oldMessageId)?.completeExceptionally(err)
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                waitingTdlibParameters = true
                scope.launch {
                    val (apiId, apiHash) = settingsDataStore.getApiCredentials()
                    if (apiId > 0 && apiHash.isNotBlank()) {
                        sendTdlibParameters(apiId, apiHash)
                    } else {
                        _authState.value = AuthState.WaitApiCredentials
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                waitingTdlibParameters = false
                _authState.value = AuthState.WaitPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _authState.value = AuthState.WaitCode
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitPassword
            }
            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = AuthState.Loading
            }
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.Loading
                createClient()
            }
            else -> Log.d(TAG, "Unhandled authorization state: ${state.javaClass.simpleName}")
        }
    }

    private suspend fun sendTdlibParameters(apiId: Int, apiHash: String) {
        val dbDir = File(appContext.filesDir, "tdlib_db").apply { mkdirs() }
        val filesDir = File(appContext.filesDir, "tdlib_files").apply { mkdirs() }
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dbDir.absolutePath
            filesDirectory = filesDir.absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = Locale.getDefault().language.ifBlank { "pt" }
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: "14"
            applicationVersion = "1.0.0"
        }
        try {
            sendQuery(params)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Credenciais da API inválidas")
        }
    }

    suspend fun submitApiCredentials(apiId: Int, apiHash: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            settingsDataStore.saveApiCredentials(apiId, apiHash.trim())
            if (waitingTdlibParameters) {
                sendTdlibParameters(apiId, apiHash.trim())
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Erro ao configurar credenciais")
            Result.failure(e)
        }
    }

    fun resetToApiCredentials() {
        _authState.value = AuthState.WaitApiCredentials
    }

    suspend fun submitPhoneNumber(phone: String): Result<Unit> {
        val normalized = phone.trim()
        return try {
            _authState.value = AuthState.Loading
            settingsDataStore.savePhoneNumber(normalized)
            sendQuery(TdApi.SetAuthenticationPhoneNumber(normalized, null))
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Número de telefone inválido")
            Result.failure(e)
        }
    }

    suspend fun submitCode(code: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            sendQuery(TdApi.CheckAuthenticationCode(code.trim()))
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Código de verificação inválido")
            Result.failure(e)
        }
    }

    suspend fun submitPassword(password: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            sendQuery(TdApi.CheckAuthenticationPassword(password))
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Senha 2FA incorreta")
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            settingsDataStore.clearSessionCredentials()
            sendQuery(TdApi.LogOut())
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Falha ao encerrar sessão")
            Result.failure(e)
        }
    }

    suspend fun <T : TdApi.Object> sendQuery(query: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val currentClient = client
            if (currentClient == null) {
                cont.resumeWithException(IllegalStateException("Cliente TDLib não inicializado"))
                return@suspendCancellableCoroutine
            }
            currentClient.send(query) { result ->
                if (!cont.isActive) return@send
                when (result) {
                    is TdApi.Error -> cont.resumeWithException(TdLibException(result.code, result.message))
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }
                }
            }
        }

    suspend fun getCurrentUserProfile(): UserProfile? {
        return try {
            val user = sendQuery(TdApi.GetMe())
            val avatarPath = user.profilePhoto?.small?.let { smallFile ->
                ensureLocalFile(smallFile)?.absolutePath
            }
            val username = user.usernames?.editableUsername?.takeIf { it.isNotBlank() }
                ?: user.usernames?.activeUsernames?.firstOrNull()
            val fullName = listOf(user.firstName, user.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Usuário Telegram" }
            val phone = if (user.phoneNumber.startsWith("+")) user.phoneNumber else "+${user.phoneNumber}"
            UserProfile(
                id = user.id,
                fullName = fullName,
                username = username,
                phoneNumber = phone,
                avatarLocalPath = avatarPath,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current user profile", e)
            null
        }
    }

    suspend fun loadUserChats(): List<TelegramChatItem> = coroutineScope {
        try {
            sendQuery(TdApi.LoadChats(TdApi.ChatListMain(), 100))
        } catch (_: TdLibException) {
            // 404 means all chats in the list are already loaded
        }

        val chats = sendQuery(TdApi.GetChats(TdApi.ChatListMain(), 100))
        val chatIds = chats.chatIds.toList()

        chatIds.chunked(10).flatMap { chunk ->
            chunk.map { chatId ->
                async(Dispatchers.IO) {
                    runCatching { buildChatItem(chatId) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun buildChatItem(chatId: Long): TelegramChatItem {
        val chat = sendQuery(TdApi.GetChat(chatId))
        var memberCount = 0
        var isChannel = false
        var canSend = false

        when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                isChannel = type.isChannel
                val sg = runCatching { sendQuery(TdApi.GetSupergroup(type.supergroupId)) }.getOrNull()
                if (sg != null) {
                    memberCount = sg.memberCount
                    canSend = when (val status = sg.status) {
                        is TdApi.ChatMemberStatusCreator -> true
                        is TdApi.ChatMemberStatusAdministrator -> {
                            if (sg.isChannel) status.rights.canPostMessages else true
                        }
                        is TdApi.ChatMemberStatusMember -> {
                            !sg.isChannel && chat.permissions.canSendBasicMessages
                        }
                        is TdApi.ChatMemberStatusRestricted -> {
                            !sg.isChannel && status.isMember && status.permissions.canSendBasicMessages
                        }
                        else -> false
                    }
                }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val bg = runCatching { sendQuery(TdApi.GetBasicGroup(type.basicGroupId)) }.getOrNull()
                if (bg != null) {
                    memberCount = bg.memberCount
                    canSend = when (val status = bg.status) {
                        is TdApi.ChatMemberStatusCreator,
                        is TdApi.ChatMemberStatusAdministrator -> true
                        is TdApi.ChatMemberStatusMember -> chat.permissions.canSendBasicMessages
                        is TdApi.ChatMemberStatusRestricted -> status.isMember && status.permissions.canSendBasicMessages
                        else -> false
                    }
                }
            }
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> {
                memberCount = 2
                canSend = true
            }
        }

        val avatarPath = chat.photo?.small?.let { photoFile ->
            ensureLocalFile(photoFile)?.absolutePath
        }

        return TelegramChatItem(
            id = chat.id,
            title = chat.title.ifBlank { "Chat sem título" },
            memberCount = memberCount,
            avatarLocalPath = avatarPath,
            canSendMessages = canSend,
            isChannel = isChannel,
        )
    }

    private suspend fun ensureLocalFile(file: TdApi.File): File? {
        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
            val localFile = File(file.local.path)
            if (localFile.exists() && localFile.length() > 0L) return localFile
        }
        return runCatching { downloadMediaFile(file.id, copyToCache = false) }.getOrNull()
    }

    suspend fun getChatMessageCountEstimate(chatId: Long): Int {
        return runCatching {
            sendQuery(
                TdApi.GetChatMessageCount(
                    chatId,
                    null,
                    TdApi.SearchMessagesFilterEmpty(),
                    false,
                )
            ).count
        }.getOrDefault(0)
    }

    /**
     * Fetches a batch of messages in chronological order (oldest -> newest) starting strictly after [fromMessageId].
     * Respects platform content protection permissions by skipping messages where copying/saving is disallowed.
     */
    suspend fun fetchHistoryBatch(
        chatId: Long,
        fromMessageId: Long,
        limit: Int = 50,
    ): List<TdApi.Message> {
        val chat = sendQuery(TdApi.GetChat(chatId))
        if (chat.hasProtectedContent) {
            return emptyList()
        }

        val safeLimit = limit.coerceIn(1, 99)
        var cursorId = if (fromMessageId > 0L) fromMessageId else 1L

        while (true) {
            val result = sendQuery(
                TdApi.GetChatHistory(
                    chatId,
                    cursorId,
                    -safeLimit,
                    safeLimit,
                    false,
                )
            )
            var rawMessages = result.messages?.toList().orEmpty()

            // Fallback when starting from 0L if querying from ID 1 with negative offset returns empty
            if (rawMessages.isEmpty() && cursorId == 1L) {
                rawMessages = fetchOldestMessagesFallback(chatId, safeLimit)
            }

            if (rawMessages.isEmpty()) {
                return emptyList()
            }

            val ordered = rawMessages
                .filter { it.id > fromMessageId && it.id > cursorId.coerceAtLeast(0L) || (fromMessageId == 0L && cursorId == 1L) }
                .sortedBy { it.id }

            if (ordered.isEmpty()) {
                return emptyList()
            }

            val allowed = ordered.filter { msg ->
                msg.canBeSaved && isSupportedContent(msg.content)
            }

            if (allowed.isNotEmpty()) {
                return allowed
            }

            // Entire batch was restricted or unsupported service messages; advance cursor and continue
            val maxIdInBatch = ordered.last().id
            if (maxIdInBatch <= cursorId) {
                return emptyList()
            }
            cursorId = maxIdInBatch
        }
    }

    private suspend fun fetchOldestMessagesFallback(chatId: Long, limit: Int): List<TdApi.Message> {
        var walkFrom = 0L
        var oldestBatch: List<TdApi.Message> = emptyList()
        while (true) {
            val res = sendQuery(TdApi.GetChatHistory(chatId, walkFrom, 0, limit, false))
            val list = res.messages?.toList().orEmpty()
            if (list.isEmpty()) break
            oldestBatch = list
            val minId = list.minOf { it.id }
            if (minId == walkFrom) break
            walkFrom = minId
        }
        return oldestBatch
    }

    private fun isSupportedContent(content: TdApi.MessageContent): Boolean {
        return when (content) {
            is TdApi.MessageText,
            is TdApi.MessagePhoto,
            is TdApi.MessageVideo,
            is TdApi.MessageAudio,
            is TdApi.MessageDocument,
            is TdApi.MessageVoiceNote,
            is TdApi.MessageAnimation -> true
            else -> false
        }
    }

    /**
     * Downloads a TDLib file waiting for completion via UpdateFile and copies it into cacheDir.
     */
    suspend fun downloadMediaFile(fileId: Int, copyToCache: Boolean = true): File {
        val existing = sendQuery(TdApi.GetFile(fileId))
        val completedFile = if (existing.local.isDownloadingCompleted &&
            existing.local.path.isNotBlank() &&
            File(existing.local.path).exists()
        ) {
            existing
        } else {
            val waiter = CompletableDeferred<TdApi.File>()
            fileDownloadWaiters[fileId] = waiter
            try {
                val triggered = sendQuery(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
                if (triggered.local.isDownloadingCompleted &&
                    triggered.local.path.isNotBlank() &&
                    File(triggered.local.path).exists()
                ) {
                    fileDownloadWaiters.remove(fileId)
                    triggered
                } else {
                    withTimeout(300_000L) {
                        waiter.await()
                    }
                }
            } finally {
                fileDownloadWaiters.remove(fileId)
            }
        }

        val tdFile = File(completedFile.local.path)
        if (!copyToCache) return tdFile

        val safeName = tdFile.name.ifBlank { "media_$fileId" }
        val cachedCopy = File(appContext.cacheDir, "tc_${fileId}_$safeName")
        tdFile.copyTo(cachedCopy, overwrite = true)
        return cachedCopy
    }

    fun cleanupText(text: String, stripMentions: Boolean, signature: String): String {
        var cleaned = text
        if (stripMentions) {
            cleaned = cleaned
                .replace(Regex("""@[A-Za-z0-9_]{3,}"""), "")
                .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""t\.me/\S+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""www\.\S+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""[ \t]{2,}"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }
        val sig = signature.trim()
        if (sig.isNotEmpty()) {
            cleaned = if (cleaned.isBlank()) sig else "$cleaned\n\n$sig"
        }
        return cleaned.trim()
    }

    suspend fun sendMessageClean(
        targetChatId: Long,
        message: TdApi.Message,
        settings: CloneSettings,
        onStatusUpdate: (String) -> Unit,
    ): Boolean {
        var tempFile: File? = null
        try {
            val inputContent = buildInputMessageContent(message, settings, onStatusUpdate) { file ->
                tempFile = file
            } ?: return false

            onStatusUpdate("Enviando mensagem...")
            val sendReq = TdApi.SendMessage().apply {
                chatId = targetChatId
                inputMessageContent = inputContent
            }
            val pendingMsg = sendQuery(sendReq)
            awaitMessageSent(pendingMsg)
            return true
        } finally {
            tempFile?.delete()
        }
    }

    suspend fun sendAlbumClean(
        targetChatId: Long,
        messages: List<TdApi.Message>,
        settings: CloneSettings,
        onStatusUpdate: (String) -> Unit,
    ): Boolean {
        if (messages.size <= 1) {
            val single = messages.firstOrNull() ?: return false
            return sendMessageClean(targetChatId, single, settings, onStatusUpdate)
        }

        val tempFiles = mutableListOf<File>()
        try {
            val contents = mutableListOf<TdApi.InputMessageContent>()
            for (msg in messages) {
                val content = buildInputMessageContent(msg, settings, onStatusUpdate) { file ->
                    tempFiles.add(file)
                }
                if (content != null) {
                    contents.add(content)
                }
            }
            if (contents.isEmpty()) return false

            if (contents.size == 1) {
                onStatusUpdate("Enviando mídia...")
                val pendingMsg = sendQuery(
                    TdApi.SendMessage().apply {
                        chatId = targetChatId
                        inputMessageContent = contents.first()
                    }
                )
                awaitMessageSent(pendingMsg)
                return true
            }

            onStatusUpdate("Enviando álbum (${contents.size} itens)...")
            val albumReq = TdApi.SendMessageAlbum().apply {
                chatId = targetChatId
                inputMessageContents = contents.toTypedArray()
            }
            val pendingMessages = sendQuery(albumReq)
            pendingMessages.messages?.forEach { pending ->
                awaitMessageSent(pending)
            }
            return true
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun awaitMessageSent(message: TdApi.Message) {
        if (message.sendingState == null) return
        val deferred = CompletableDeferred<TdApi.Message>()
        sendMessageWaiters[message.id] = deferred
        try {
            withTimeout(300_000L) {
                deferred.await()
            }
        } finally {
            sendMessageWaiters.remove(message.id)
        }
    }

    private suspend fun buildInputMessageContent(
        message: TdApi.Message,
        settings: CloneSettings,
        onStatusUpdate: (String) -> Unit,
        onTempFileCreated: (File) -> Unit,
    ): TdApi.InputMessageContent? {
        return when (val content = message.content) {
            is TdApi.MessageText -> {
                if (!settings.includeText) return null
                val finalText = cleanupText(
                    content.text.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                if (finalText.isBlank()) return null
                TdApi.InputMessageText().apply {
                    text = TdApi.FormattedText(finalText, emptyArray())
                    clearDraft = true
                }
            }
            is TdApi.MessagePhoto -> {
                if (!settings.includePhotos) return null
                val bestSize = content.photo.sizes.maxByOrNull { it.width * it.height } ?: return null
                onStatusUpdate("Baixando foto...")
                val file = downloadMediaFile(bestSize.photo.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessagePhoto().apply {
                    photo = TdApi.InputFileLocal(file.absolutePath)
                    width = bestSize.width
                    height = bestSize.height
                    caption = TdApi.FormattedText(captionText, emptyArray())
                    hasSpoiler = content.hasSpoiler
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                }
            }
            is TdApi.MessageVideo -> {
                if (!settings.includeVideos) return null
                onStatusUpdate("Baixando vídeo...")
                val file = downloadMediaFile(content.video.video.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessageVideo().apply {
                    video = TdApi.InputFileLocal(file.absolutePath)
                    duration = content.video.duration
                    width = content.video.width
                    height = content.video.height
                    supportsStreaming = content.video.supportsStreaming
                    caption = TdApi.FormattedText(captionText, emptyArray())
                    hasSpoiler = content.hasSpoiler
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                }
            }
            is TdApi.MessageAnimation -> {
                if (!settings.includeVideos) return null
                onStatusUpdate("Baixando animação...")
                val file = downloadMediaFile(content.animation.animation.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessageAnimation().apply {
                    animation = TdApi.InputFileLocal(file.absolutePath)
                    duration = content.animation.duration
                    width = content.animation.width
                    height = content.animation.height
                    caption = TdApi.FormattedText(captionText, emptyArray())
                    hasSpoiler = content.hasSpoiler
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                }
            }
            is TdApi.MessageAudio -> {
                if (!settings.includeAudio) return null
                onStatusUpdate("Baixando áudio...")
                val file = downloadMediaFile(content.audio.audio.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessageAudio().apply {
                    audio = TdApi.InputFileLocal(file.absolutePath)
                    duration = content.audio.duration
                    title = content.audio.title
                    performer = content.audio.performer
                    caption = TdApi.FormattedText(captionText, emptyArray())
                }
            }
            is TdApi.MessageVoiceNote -> {
                if (!settings.includeAudio) return null
                onStatusUpdate("Baixando nota de voz...")
                val file = downloadMediaFile(content.voiceNote.voice.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessageVoiceNote().apply {
                    voiceNote = TdApi.InputFileLocal(file.absolutePath)
                    duration = content.voiceNote.duration
                    waveform = content.voiceNote.waveform
                    caption = TdApi.FormattedText(captionText, emptyArray())
                }
            }
            is TdApi.MessageDocument -> {
                if (!settings.includeDocuments) return null
                onStatusUpdate("Baixando documento...")
                val file = downloadMediaFile(content.document.document.id, copyToCache = true)
                onTempFileCreated(file)
                val captionText = cleanupText(
                    content.caption.text,
                    settings.stripMentionsAndLinks,
                    settings.customSignature,
                )
                TdApi.InputMessageDocument().apply {
                    document = TdApi.InputFileLocal(file.absolutePath)
                    caption = TdApi.FormattedText(captionText, emptyArray())
                }
            }
            else -> null
        }
    }
}
