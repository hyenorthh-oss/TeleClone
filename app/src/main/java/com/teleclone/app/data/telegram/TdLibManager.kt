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
import kotlinx.coroutines.withTimeoutOrNull
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

data class HistoryBatchResult(
    val messages: List<TdApi.Message>,
    val maxScannedMessageId: Long,
    val isProtectedChat: Boolean = false,
)

object TdLibManager {

    private const val TAG = "TdLibManager"
    private const val QUERY_TIMEOUT_MS = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS

    private lateinit var appContext: Context
    private lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val fileDownloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<TdApi.File>>()

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
            sendQueryWithTimeout(params)
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
            sendQueryWithTimeout(TdApi.SetAuthenticationPhoneNumber(normalized, null))
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Número de telefone inválido")
            Result.failure(e)
        }
    }

    suspend fun submitCode(code: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            sendQueryWithTimeout(TdApi.CheckAuthenticationCode(code.trim()))
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Código de verificação inválido")
            Result.failure(e)
        }
    }

    suspend fun submitPassword(password: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading
            sendQueryWithTimeout(TdApi.CheckAuthenticationPassword(password))
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
            sendQueryWithTimeout(TdApi.LogOut())
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

    suspend fun <T : TdApi.Object> sendQueryWithTimeout(
        query: TdApi.Function<T>,
        timeoutMs: Long = QUERY_TIMEOUT_MS,
    ): T {
        return withTimeoutOrNull(timeoutMs) {
            sendQuery(query)
        } ?: throw TdLibTimeoutException("Tempo limite (${timeoutMs / 1000}s) excedido na TDLib")
    }

    suspend fun getCurrentUserProfile(): UserProfile? {
        return try {
            val user = sendQueryWithTimeout(TdApi.GetMe())
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
            sendQueryWithTimeout(TdApi.LoadChats(TdApi.ChatListMain(), 100))
        } catch (_: TdLibException) {
            // 404 means all chats in the list are already loaded
        }

        val chats = sendQueryWithTimeout(TdApi.GetChats(TdApi.ChatListMain(), 100))
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
        val chat = sendQueryWithTimeout(TdApi.GetChat(chatId))
        var memberCount = 0
        var isChannel = false
        var canSend = false

        when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                isChannel = type.isChannel
                val sg = runCatching { sendQueryWithTimeout(TdApi.GetSupergroup(type.supergroupId)) }.getOrNull()
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
                val bg = runCatching { sendQueryWithTimeout(TdApi.GetBasicGroup(type.basicGroupId)) }.getOrNull()
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
        return runCatching { downloadAvatarFile(file.id) }.getOrNull()
    }

    suspend fun getChatMessageCountEstimate(chatId: Long): Int {
        return runCatching {
            sendQueryWithTimeout(
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
     * Fetches chronological messages strictly after [fromMessageId], continuing across page
     * boundaries if the last message of a page belongs to an album (`mediaAlbumId != 0L`)
     * or until [desiredForwardableCount] is reached.
     *
     * Respects platform content protection permissions and skips non-forwardable system messages.
     */
    suspend fun fetchHistoryBatch(
        chatId: Long,
        fromMessageId: Long,
        desiredForwardableCount: Int = 50,
    ): HistoryBatchResult {
        val paginated = HistoryPagePaginator.fetchBatch(
            fromMessageId = fromMessageId,
            desiredForwardableCount = desiredForwardableCount,
            isChatProtected = {
                val chat = sendQueryWithTimeout(TdApi.GetChat(chatId))
                if (chat.hasProtectedContent) {
                    Log.w(TAG, "Source chat $chatId has protected content; skipping forward.")
                    true
                } else {
                    false
                }
            },
            fetchPage = { cursorId, limit ->
                val result = sendQueryWithTimeout(
                    TdApi.GetChatHistory(
                        chatId,
                        cursorId,
                        -limit,
                        limit,
                        false,
                    )
                )
                result.messages?.toList().orEmpty().map { toCloneableMessage(it) }
            },
            fetchOldestFallback = { limit ->
                fetchOldestMessagesFallback(chatId, limit).map { toCloneableMessage(it) }
            },
        )
        return HistoryBatchResult(
            messages = paginated.messages.map { it.payload },
            maxScannedMessageId = paginated.maxScannedMessageId,
            isProtectedChat = paginated.isProtectedChat,
        )
    }

    private suspend fun fetchOldestMessagesFallback(chatId: Long, limit: Int): List<TdApi.Message> {
        var walkFrom = 0L
        var oldestBatch: List<TdApi.Message> = emptyList()
        var steps = 0
        while (steps < 200) {
            steps++
            val res = sendQueryWithTimeout(TdApi.GetChatHistory(chatId, walkFrom, 0, limit, false))
            val list = res.messages?.toList().orEmpty()
            if (list.isEmpty()) break
            oldestBatch = list
            val minId = list.minOf { it.id }
            if (minId == walkFrom) break
            walkFrom = minId
        }
        return oldestBatch
    }

    fun toCloneableMessage(msg: TdApi.Message): CloneableMessage<TdApi.Message> {
        val type = when (msg.content) {
            is TdApi.MessageText -> CloneMessageType.TEXT
            is TdApi.MessagePhoto -> CloneMessageType.PHOTO
            is TdApi.MessageVideo -> CloneMessageType.VIDEO
            is TdApi.MessageAudio -> CloneMessageType.AUDIO
            is TdApi.MessageDocument -> CloneMessageType.DOCUMENT
            is TdApi.MessageVoiceNote -> CloneMessageType.VOICE_NOTE
            is TdApi.MessageAnimation -> CloneMessageType.ANIMATION
            else -> CloneMessageType.UNSUPPORTED
        }
        return CloneableMessage(
            id = msg.id,
            mediaAlbumId = msg.mediaAlbumId,
            canBeSaved = msg.canBeSaved,
            type = type,
            payload = msg,
        )
    }

    /**
     * Downloads an avatar file into TDLib's internal directory waiting for completion via UpdateFile.
     */
    private suspend fun downloadAvatarFile(fileId: Int): File {
        val existing = sendQueryWithTimeout(TdApi.GetFile(fileId))
        val completedFile = if (existing.local.isDownloadingCompleted &&
            existing.local.path.isNotBlank() &&
            File(existing.local.path).exists()
        ) {
            existing
        } else {
            val waiter = CompletableDeferred<TdApi.File>()
            fileDownloadWaiters[fileId] = waiter
            try {
                val triggered = sendQueryWithTimeout(TdApi.DownloadFile(fileId, 16, 0L, 0L, false))
                if (triggered.local.isDownloadingCompleted &&
                    triggered.local.path.isNotBlank() &&
                    File(triggered.local.path).exists()
                ) {
                    fileDownloadWaiters.remove(fileId)
                    triggered
                } else {
                    withTimeoutOrNull(25_000L) {
                        waiter.await()
                    } ?: throw TdLibTimeoutException("Timeout ao baixar avatar")
                }
            } finally {
                fileDownloadWaiters.remove(fileId)
            }
        }
        return File(completedFile.local.path)
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

    /**
     * Copies messages cloud-to-cloud directly on Telegram servers without the "Forwarded from" tag
     * using `ForwardMessages(sendCopy = true, removeCaption = false)` with a 25s timeout.
     *
     * Never blocks waiting indefinitely for `UpdateMessageSendSucceeded` on album items:
     * as soon as `TdApi.Messages` returns without error, all items are accounted for immediately.
     */
    suspend fun forwardCopyClean(
        sourceChatId: Long,
        targetChatId: Long,
        messages: List<TdApi.Message>,
        settings: CloneSettings,
        onStatusUpdate: (String) -> Unit,
    ): Int {
        if (messages.isEmpty()) return 0

        onStatusUpdate(
            when {
                messages.size > 10 -> "Enviando bloco (${messages.size} msgs)..."
                messages.size > 1 -> "Enviando álbum (${messages.size} itens)..."
                else -> "Enviando mensagem..."
            }
        )

        val sendOptions = TdApi.MessageSendOptions().apply {
            disableNotification = false
            fromBackground = false
            protectContent = false
            updateOrderOfInstalledStickerSets = false
            schedulingState = null
            sendingId = 0
            onlyPreview = false
        }

        val forwardReq = TdApi.ForwardMessages().apply {
            chatId = targetChatId
            topicId = null
            fromChatId = sourceChatId
            messageIds = messages.map { it.id }.toLongArray()
            options = sendOptions
            sendCopy = true
            removeCaption = false
        }

        val forwarded = sendQueryWithTimeout(forwardReq, QUERY_TIMEOUT_MS)
        val copiedMessages = forwarded.messages
            ?.filterNotNull()
            .orEmpty()

        val shouldModifyText = settings.stripMentionsAndLinks || settings.customSignature.isNotBlank()
        if (shouldModifyText) {
            for (copiedMsg in copiedMessages) {
                if (copiedMsg.sendingState == null && copiedMsg.id > 0L) {
                    applyTextCleanupIfNeeded(targetChatId, copiedMsg, settings)
                }
            }
        }

        return if (copiedMessages.isNotEmpty()) copiedMessages.size else messages.size
    }

    private suspend fun applyTextCleanupIfNeeded(
        targetChatId: Long,
        copiedMsg: TdApi.Message,
        settings: CloneSettings,
    ) {
        try {
            when (val content = copiedMsg.content) {
                is TdApi.MessageText -> {
                    val originalText = content.text.text
                    if (originalText.isNotBlank()) {
                        val cleaned = cleanupText(
                            originalText,
                            settings.stripMentionsAndLinks,
                            settings.customSignature,
                        )
                        if (cleaned.isNotBlank() && cleaned != originalText) {
                            sendQueryWithTimeout(
                                TdApi.EditMessageText().apply {
                                    chatId = targetChatId
                                    messageId = copiedMsg.id
                                    replyMarkup = null
                                    inputMessageContent = TdApi.InputMessageText().apply {
                                        text = TdApi.FormattedText(cleaned, emptyArray())
                                        clearDraft = true
                                    }
                                }
                            )
                        }
                    }
                }
                is TdApi.MessagePhoto -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, content.showCaptionAboveMedia, settings
                )
                is TdApi.MessageVideo -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, content.showCaptionAboveMedia, settings
                )
                is TdApi.MessageAnimation -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, content.showCaptionAboveMedia, settings
                )
                is TdApi.MessageAudio -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, false, settings
                )
                is TdApi.MessageVoiceNote -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, false, settings
                )
                is TdApi.MessageDocument -> editCaptionIfNeeded(
                    targetChatId, copiedMsg.id, content.caption.text, false, settings
                )
                else -> Unit
            }
        } catch (e: TdLibException) {
            if (e.extractFloodWaitSeconds() != null) {
                throw e
            }
            Log.w(TAG, "Skipping post-copy text edit on message ${copiedMsg.id}: ${e.message}")
        } catch (e: TdLibTimeoutException) {
            Log.w(TAG, "Timeout editing post-copy text on message ${copiedMsg.id}: ${e.message}")
        }
    }

    private suspend fun editCaptionIfNeeded(
        targetChatId: Long,
        messageId: Long,
        originalCaption: String,
        showAboveMedia: Boolean,
        settings: CloneSettings,
    ) {
        if (originalCaption.isBlank()) return
        val cleaned = cleanupText(
            originalCaption,
            settings.stripMentionsAndLinks,
            settings.customSignature,
        )
        if (cleaned != originalCaption) {
            sendQueryWithTimeout(
                TdApi.EditMessageCaption().apply {
                    chatId = targetChatId
                    this.messageId = messageId
                    replyMarkup = null
                    caption = TdApi.FormattedText(cleaned, emptyArray())
                    showCaptionAboveMedia = showAboveMedia
                }
            )
        }
    }
}
