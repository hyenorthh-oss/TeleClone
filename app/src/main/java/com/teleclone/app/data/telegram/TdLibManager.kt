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
import kotlin.math.ceil

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
    private const val QUERY_TIMEOUT_MS = CloneReliabilityExecutor.DEFAULT_TIMEOUT_MS
    private const val MAX_EVENT_CACHE_SIZE = 2000

    private lateinit var appContext: Context
    private lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val fileDownloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<TdApi.File>>()
    private val sendSucceededEvents = ConcurrentHashMap<Long, TdApi.Message>()
    private val sendFailedEvents = ConcurrentHashMap<Long, DispatchedSendingState.Failed>()

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
                if (sendSucceededEvents.size > MAX_EVENT_CACHE_SIZE) {
                    sendSucceededEvents.clear()
                }
                sendSucceededEvents[update.oldMessageId] = update.message
            }
            is TdApi.UpdateMessageSendFailed -> {
                if (sendFailedEvents.size > MAX_EVENT_CACHE_SIZE) {
                    sendFailedEvents.clear()
                }
                val retryAfterSec = update.error?.message?.let { msg ->
                    TdLibException(update.error.code, msg).extractFloodWaitSeconds()
                }
                sendFailedEvents[update.oldMessageId] = DispatchedSendingState.Failed(
                    errorCode = update.error?.code ?: 500,
                    errorMessage = update.error?.message ?: "Falha no envio",
                    retryAfterSeconds = retryAfterSec,
                )
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

    fun extractThreadId(topicId: TdApi.MessageTopic?): Long = when (topicId) {
        is TdApi.MessageTopicForum -> topicId.forumTopicId.toLong()
        is TdApi.MessageTopicThread -> topicId.messageThreadId
        else -> 0L
    }

    fun toTargetTopic(targetIsForum: Boolean, targetThreadId: Long): TdApi.MessageTopic? =
        if (targetIsForum && targetThreadId > 0L) TdApi.MessageTopicForum(targetThreadId.toInt()) else null

    fun toCloneableMessage(msg: TdApi.Message): CloneableMessage<TdApi.Message> {
        val type = when (val content = msg.content) {
            is TdApi.MessageText -> CloneMessageType.TEXT
            is TdApi.MessagePhoto -> CloneMessageType.PHOTO
            is TdApi.MessageVideo -> CloneMessageType.VIDEO
            is TdApi.MessageAudio -> CloneMessageType.AUDIO
            is TdApi.MessageDocument -> {
                if (CloneBatchPlanner.isVideoDocument(content.document.mimeType, content.document.fileName)) {
                    CloneMessageType.VIDEO
                } else {
                    CloneMessageType.DOCUMENT
                }
            }
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
            originThreadId = extractThreadId(msg.topicId),
        )
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

    fun createExecutionGateway(): CloneExecutionGateway<TdApi.Message> {
        val reuploadTransport = object : ProtectedReuploadTransport {
            override suspend fun downloadFile(fileId: Int, timeoutMs: Long): DownloadedLocalMedia {
                val file = sendQueryWithTimeout(
                    TdApi.DownloadFile(fileId, 32, 0L, 0L, true),
                    timeoutMs = timeoutMs,
                )
                val path = file.local.path
                if (!file.local.isDownloadingCompleted || path.isBlank()) {
                    throw TdLibException(500, "Falha ao baixar arquivo $fileId para reenvio")
                }
                return DownloadedLocalMedia(fileId = file.id, localPath = path)
            }

            override suspend fun deleteFile(fileId: Int) {
                runCatching {
                    sendQueryWithTimeout(TdApi.DeleteFile(fileId), timeoutMs = 10_000L)
                }
            }

            override suspend fun sendSingleMessage(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                content: TdApi.InputMessageContent,
            ): TdApi.Message {
                val options = defaultSendOptions()
                return sendQueryWithTimeout(
                    TdApi.SendMessage(
                        targetChatId,
                        toTargetTopic(targetIsForum, targetThreadId),
                        null,
                        options,
                        null,
                        content,
                    )
                )
            }

            override suspend fun sendAlbumMessages(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                contents: List<TdApi.InputMessageContent>,
            ): List<TdApi.Message?> {
                val options = defaultSendOptions()
                val res = sendQueryWithTimeout(
                    TdApi.SendMessageAlbum(
                        targetChatId,
                        toTargetTopic(targetIsForum, targetThreadId),
                        null,
                        options,
                        contents.toTypedArray(),
                    )
                )
                return res.messages?.toList().orEmpty()
            }
        }

        return object : CloneExecutionGateway<TdApi.Message> {

            override suspend fun isForumSupergroup(chatId: Long): Boolean {
                val chat = sendQueryWithTimeout(TdApi.GetChat(chatId))
                val type = chat.type
                if (type is TdApi.ChatTypeSupergroup) {
                    val sg = sendQueryWithTimeout(TdApi.GetSupergroup(type.supergroupId))
                    return sg.isForum
                }
                return false
            }

            override suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor> {
                val topics = mutableListOf<ForumTopicDescriptor>()
                var offsetDate = 0
                var offsetMsgId = 0L
                var offsetTopicId = 0
                var pages = 0

                while (pages < 50) {
                    pages++
                    val res = sendQueryWithTimeout(
                        TdApi.GetForumTopics(chatId, "", offsetDate, offsetMsgId, offsetTopicId, 100)
                    )
                    val list = res.topics?.toList().orEmpty()
                    if (list.isEmpty()) break

                    for (ft in list) {
                        val info = ft.info ?: continue
                        topics.add(
                            ForumTopicDescriptor(
                                threadId = info.forumTopicId.toLong(),
                                name = info.name.orEmpty(),
                                iconColor = info.icon?.color ?: 0,
                                iconCustomEmojiId = info.icon?.customEmojiId ?: 0L,
                                isGeneral = info.isGeneral,
                            )
                        )
                    }

                    if (res.nextOffsetForumTopicId == 0 && res.nextOffsetDate == 0 && res.nextOffsetMessageId == 0L) {
                        break
                    }
                    if (res.nextOffsetForumTopicId == offsetTopicId &&
                        res.nextOffsetDate == offsetDate &&
                        res.nextOffsetMessageId == offsetMsgId
                    ) {
                        break
                    }
                    offsetDate = res.nextOffsetDate
                    offsetMsgId = res.nextOffsetMessageId
                    offsetTopicId = res.nextOffsetForumTopicId
                }

                return topics.distinctBy { it.threadId }
            }

            override suspend fun createForumTopic(
                chatId: Long,
                topic: ForumTopicDescriptor,
            ): ForumTopicDescriptor {
                val icon = TdApi.ForumTopicIcon(
                    topic.iconColor.takeIf { it != 0 } ?: 0x6FB9F0,
                    topic.iconCustomEmojiId,
                )
                val created = sendQueryWithTimeout(
                    TdApi.CreateForumTopic(
                        chatId,
                        topic.name.ifBlank { "Tópico" }.take(128),
                        false,
                        icon,
                    )
                )
                return ForumTopicDescriptor(
                    threadId = created.forumTopicId.toLong(),
                    name = created.name.orEmpty(),
                    iconColor = created.icon?.color ?: 0,
                    iconCustomEmojiId = created.icon?.customEmojiId ?: 0L,
                    isGeneral = created.isGeneral,
                )
            }

            override suspend fun fetchHistoryPage(
                chatId: Long,
                fromMessageId: Long,
                limit: Int,
            ): List<CloneableMessage<TdApi.Message>> {
                val res = sendQueryWithTimeout(
                    TdApi.GetChatHistory(chatId, fromMessageId, 0, limit.coerceIn(1, 100), false)
                )
                return res.messages?.filterNotNull().orEmpty().map { toCloneableMessage(it) }
            }

            override suspend fun searchChatMessages(
                chatId: Long,
                filter: SearchFilterKind,
                fromMessageId: Long,
                limit: Int,
            ): SearchPageResult<TdApi.Message> {
                val tdFilter = when (filter) {
                    SearchFilterKind.VIDEO -> TdApi.SearchMessagesFilterVideo()
                    SearchFilterKind.PHOTO -> TdApi.SearchMessagesFilterPhoto()
                    SearchFilterKind.DOCUMENT -> TdApi.SearchMessagesFilterDocument()
                    SearchFilterKind.AUDIO -> TdApi.SearchMessagesFilterAudio()
                }
                val res = sendQueryWithTimeout(
                    TdApi.SearchChatMessages(
                        chatId,
                        null,
                        "",
                        null,
                        fromMessageId,
                        0,
                        limit.coerceIn(1, 100),
                        tdFilter,
                    )
                )
                val msgs = res.messages?.filterNotNull().orEmpty().map { toCloneableMessage(it) }
                return SearchPageResult(
                    messages = msgs,
                    nextFromMessageId = res.nextFromMessageId,
                )
            }

            override suspend fun isChatProtected(chatId: Long): Boolean {
                val chat = sendQueryWithTimeout(TdApi.GetChat(chatId))
                return chat.hasProtectedContent
            }

            override suspend fun getMessage(chatId: Long, messageId: Long): CloneableMessage<TdApi.Message>? {
                return try {
                    val msg = sendQueryWithTimeout(TdApi.GetMessage(chatId, messageId))
                    toCloneableMessage(msg)
                } catch (e: TdLibException) {
                    if (e.code == 404) null else throw e
                }
            }

            override suspend fun canForwardMessage(chatId: Long, message: CloneableMessage<TdApi.Message>): Boolean {
                return runCatching {
                    val props = sendQueryWithTimeout(TdApi.GetMessageProperties(chatId, message.id))
                    props.canBeForwarded && props.canBeCopied
                }.getOrDefault(message.canBeSaved)
            }

            override suspend fun forwardMessages(
                sourceChatId: Long,
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                messages: List<CloneableMessage<TdApi.Message>>,
            ): List<DispatchedItemHandle> {
                val forwardReq = TdApi.ForwardMessages().apply {
                    chatId = targetChatId
                    topicId = toTargetTopic(targetIsForum, targetThreadId)
                    fromChatId = sourceChatId
                    messageIds = messages.map { it.id }.toLongArray()
                    options = defaultSendOptions()
                    sendCopy = true
                    removeCaption = false
                }
                val forwarded = sendQueryWithTimeout(forwardReq, QUERY_TIMEOUT_MS)
                val destMessages = forwarded.messages

                return messages.mapIndexed { idx, src ->
                    val dest = destMessages?.getOrNull(idx)
                    if (dest == null) {
                        DispatchedItemHandle(
                            sourceMessageId = src.id,
                            targetMessageId = 0L,
                            sendingState = DispatchedSendingState.Failed(400, "CHAT_FORWARDS_RESTRICTED"),
                            isRestrictedOrNull = true,
                        )
                    } else {
                        ProtectedContentReuploader.toDispatchedHandle(src.id, dest)
                    }
                }
            }

            override suspend fun applyPostCopyTextCleanup(
                targetChatId: Long,
                handles: List<DispatchedItemHandle>,
                settings: CloneSettings,
            ) {
                for (handle in handles) {
                    if (handle.targetMessageId <= 0L || handle.sendingState !is DispatchedSendingState.Confirmed) {
                        continue
                    }
                    val copiedMsg = runCatching {
                        sendQueryWithTimeout(TdApi.GetMessage(targetChatId, handle.targetMessageId))
                    }.getOrNull() ?: continue
                    applyTextCleanupIfNeeded(targetChatId, copiedMsg, settings)
                }
            }

            override suspend fun checkDispatchedMessageState(
                targetChatId: Long,
                targetMessageId: Long,
            ): DispatchedSendingState {
                sendSucceededEvents.remove(targetMessageId)?.let {
                    return DispatchedSendingState.Confirmed
                }
                sendFailedEvents.remove(targetMessageId)?.let {
                    return it
                }

                return try {
                    val msg = sendQueryWithTimeout(TdApi.GetMessage(targetChatId, targetMessageId))
                    when (val sending = msg.sendingState) {
                        null -> DispatchedSendingState.Confirmed
                        is TdApi.MessageSendingStatePending -> DispatchedSendingState.Pending
                        is TdApi.MessageSendingStateFailed -> {
                            val retrySec = sending.retryAfter.takeIf { it > 0.0 }?.let { ceil(it).toInt() }
                            DispatchedSendingState.Failed(
                                errorCode = sending.error?.code ?: 500,
                                errorMessage = sending.error?.message ?: "Falha no envio",
                                retryAfterSeconds = retrySec,
                            )
                        }
                        else -> DispatchedSendingState.Pending
                    }
                } catch (e: TdLibException) {
                    if (e.code == 404) {
                        sendSucceededEvents.remove(targetMessageId)?.let {
                            return DispatchedSendingState.Confirmed
                        }
                        sendFailedEvents.remove(targetMessageId)?.let {
                            return it
                        }
                    }
                    throw e
                }
            }

            override suspend fun reuploadProtectedItems(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                items: List<CloneableMessage<TdApi.Message>>,
                settings: CloneSettings,
            ): List<DispatchedItemHandle> {
                return ProtectedContentReuploader.reuploadItems(
                    targetChatId = targetChatId,
                    targetThreadId = targetThreadId,
                    targetIsForum = targetIsForum,
                    items = items,
                    settings = settings,
                    transport = reuploadTransport,
                )
            }
        }
    }

    private fun defaultSendOptions(): TdApi.MessageSendOptions = TdApi.MessageSendOptions().apply {
        disableNotification = false
        fromBackground = false
        protectContent = false
        updateOrderOfInstalledStickerSets = false
        schedulingState = null
        sendingId = 0
        onlyPreview = false
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
