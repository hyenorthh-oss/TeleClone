package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import org.drinkless.tdlib.TdApi

data class DownloadedLocalMedia(
    val fileId: Int,
    val localPath: String,
)

interface ProtectedReuploadTransport {
    suspend fun downloadFile(fileId: Int, timeoutMs: Long): DownloadedLocalMedia
    suspend fun deleteFile(fileId: Int)
    suspend fun sendSingleMessage(
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        content: TdApi.InputMessageContent,
    ): TdApi.Message

    suspend fun sendAlbumMessages(
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        contents: List<TdApi.InputMessageContent>,
    ): List<TdApi.Message?>
}

object ProtectedContentReuploader {

    const val DOWNLOAD_TIMEOUT_MS = 60_000L

    fun extractFileId(content: TdApi.MessageContent): Int? = when (content) {
        is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id
        is TdApi.MessageVideo -> content.video.video.id
        is TdApi.MessageDocument -> content.document.document.id
        is TdApi.MessageAudio -> content.audio.audio.id
        is TdApi.MessageVoiceNote -> content.voiceNote.voice.id
        is TdApi.MessageAnimation -> content.animation.animation.id
        else -> null
    }

    fun buildTextContent(
        originalText: TdApi.FormattedText,
        settings: CloneSettings,
    ): TdApi.InputMessageText {
        val cleanedText = if (settings.stripMentionsAndLinks || settings.customSignature.isNotBlank()) {
            TdLibManager.cleanupText(
                text = originalText.text,
                stripMentions = settings.stripMentionsAndLinks,
                signature = settings.customSignature,
            )
        } else {
            originalText.text
        }
        val entities = if (cleanedText == originalText.text) {
            originalText.entities ?: emptyArray()
        } else {
            emptyArray()
        }
        return TdApi.InputMessageText().apply {
            text = TdApi.FormattedText(cleanedText, entities)
            clearDraft = true
        }
    }

    fun buildMediaInputContent(
        msg: TdApi.Message,
        localPath: String,
        settings: CloneSettings,
    ): TdApi.InputMessageContent? {
        val localFile = TdApi.InputFileLocal(localPath)

        fun cleanCaption(caption: TdApi.FormattedText?): TdApi.FormattedText {
            val baseText = caption?.text.orEmpty()
            val shouldModify = settings.stripMentionsAndLinks || settings.customSignature.isNotBlank()
            if (!shouldModify) {
                return caption ?: TdApi.FormattedText("", emptyArray())
            }
            val cleaned = TdLibManager.cleanupText(
                text = baseText,
                stripMentions = settings.stripMentionsAndLinks,
                signature = settings.customSignature,
            )
            return if (cleaned == baseText && caption != null) {
                caption
            } else {
                TdApi.FormattedText(cleaned, emptyArray())
            }
        }

        return when (val content = msg.content) {
            is TdApi.MessagePhoto -> {
                val largest = content.photo.sizes.maxByOrNull { it.width * it.height }
                TdApi.InputMessagePhoto().apply {
                    photo = localFile
                    thumbnail = null
                    addedStickerFileIds = IntArray(0)
                    width = largest?.width ?: 0
                    height = largest?.height ?: 0
                    caption = cleanCaption(content.caption)
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                    selfDestructType = null
                    hasSpoiler = content.hasSpoiler
                }
            }
            is TdApi.MessageVideo -> {
                TdApi.InputMessageVideo().apply {
                    video = localFile
                    thumbnail = null
                    cover = null
                    startTimestamp = content.startTimestamp
                    addedStickerFileIds = IntArray(0)
                    duration = content.video.duration
                    width = content.video.width
                    height = content.video.height
                    supportsStreaming = content.video.supportsStreaming
                    caption = cleanCaption(content.caption)
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                    selfDestructType = null
                    hasSpoiler = content.hasSpoiler
                }
            }
            is TdApi.MessageDocument -> {
                if (CloneBatchPlanner.isVideoDocument(content.document.mimeType, content.document.fileName)) {
                    TdApi.InputMessageDocument().apply {
                        document = localFile
                        thumbnail = null
                        disableContentTypeDetection = false
                        caption = cleanCaption(content.caption)
                    }
                } else {
                    TdApi.InputMessageDocument().apply {
                        document = localFile
                        thumbnail = null
                        disableContentTypeDetection = true
                        caption = cleanCaption(content.caption)
                    }
                }
            }
            is TdApi.MessageAudio -> {
                TdApi.InputMessageAudio().apply {
                    audio = localFile
                    albumCoverThumbnail = null
                    duration = content.audio.duration
                    title = content.audio.title.orEmpty()
                    performer = content.audio.performer.orEmpty()
                    caption = cleanCaption(content.caption)
                }
            }
            is TdApi.MessageVoiceNote -> {
                TdApi.InputMessageVoiceNote().apply {
                    voiceNote = localFile
                    duration = content.voiceNote.duration
                    waveform = content.voiceNote.waveform ?: ByteArray(0)
                    caption = cleanCaption(content.caption)
                    selfDestructType = null
                }
            }
            is TdApi.MessageAnimation -> {
                TdApi.InputMessageAnimation().apply {
                    animation = localFile
                    thumbnail = null
                    addedStickerFileIds = IntArray(0)
                    duration = content.animation.duration
                    width = content.animation.width
                    height = content.animation.height
                    caption = cleanCaption(content.caption)
                    showCaptionAboveMedia = content.showCaptionAboveMedia
                    hasSpoiler = content.hasSpoiler
                }
            }
            else -> null
        }
    }

    suspend fun reuploadItems(
        targetChatId: Long,
        targetThreadId: Long,
        targetIsForum: Boolean,
        items: List<CloneableMessage<TdApi.Message>>,
        settings: CloneSettings,
        transport: ProtectedReuploadTransport,
    ): List<DispatchedItemHandle> {
        if (items.isEmpty()) return emptyList()

        // Single text message
        if (items.size == 1 && items[0].payload.content is TdApi.MessageText) {
            val item = items[0]
            val textContent = items[0].payload.content as TdApi.MessageText
            val input = buildTextContent(textContent.text, settings)
            val sent = transport.sendSingleMessage(targetChatId, targetThreadId, targetIsForum, input)
            return listOf(toDispatchedHandle(item.id, sent))
        }

        // Media single or album (2..10): always delete downloaded TDLib cache files in finally!
        val downloadedFileIds = mutableListOf<Int>()
        try {
            val prepared = mutableListOf<Pair<Long, TdApi.InputMessageContent>>()
            for (item in items) {
                val raw = item.payload
                if (raw.content is TdApi.MessageText) {
                    val sentText = transport.sendSingleMessage(
                        targetChatId = targetChatId,
                        targetThreadId = targetThreadId,
                        targetIsForum = targetIsForum,
                        content = buildTextContent((raw.content as TdApi.MessageText).text, settings),
                    )
                    if (items.size == 1) {
                        return listOf(toDispatchedHandle(item.id, sentText))
                    }
                    continue
                }

                val fileId = extractFileId(raw.content)
                    ?: throw IllegalStateException("Mídia sem fileId válido para reenvio protegido")

                val downloaded = transport.downloadFile(fileId, DOWNLOAD_TIMEOUT_MS)
                downloadedFileIds.add(downloaded.fileId)

                val inputContent = buildMediaInputContent(raw, downloaded.localPath, settings)
                    ?: throw IllegalStateException("Tipo de mídia não suportado no reenvio protegido")

                prepared.add(item.id to inputContent)
            }

            if (prepared.isEmpty()) return emptyList()

            return if (prepared.size == 1) {
                val (sourceId, content) = prepared[0]
                val sent = transport.sendSingleMessage(targetChatId, targetThreadId, targetIsForum, content)
                listOf(toDispatchedHandle(sourceId, sent))
            } else {
                val albumResults = transport.sendAlbumMessages(
                    targetChatId = targetChatId,
                    targetThreadId = targetThreadId,
                    targetIsForum = targetIsForum,
                    contents = prepared.map { it.second },
                )
                prepared.mapIndexed { idx, (sourceId, _) ->
                    val sentMsg = albumResults.getOrNull(idx)
                    if (sentMsg == null) {
                        DispatchedItemHandle(
                            sourceMessageId = sourceId,
                            targetMessageId = 0L,
                            sendingState = DispatchedSendingState.Failed(500, "Item nulo em SendMessageAlbum"),
                        )
                    } else {
                        toDispatchedHandle(sourceId, sentMsg)
                    }
                }
            }
        } finally {
            for (fileId in downloadedFileIds) {
                runCatching { transport.deleteFile(fileId) }
            }
        }
    }

    fun toDispatchedHandle(sourceMessageId: Long, msg: TdApi.Message): DispatchedItemHandle {
        val state = when (val sending = msg.sendingState) {
            null -> DispatchedSendingState.Confirmed
            is TdApi.MessageSendingStatePending -> DispatchedSendingState.Pending
            is TdApi.MessageSendingStateFailed -> {
                val retryAfterSec = sending.retryAfter.takeIf { it > 0.0 }?.let { kotlin.math.ceil(it).toInt() }
                DispatchedSendingState.Failed(
                    errorCode = sending.error?.code ?: 500,
                    errorMessage = sending.error?.message ?: "Falha no envio",
                    retryAfterSeconds = retryAfterSec,
                )
            }
            else -> DispatchedSendingState.Pending
        }
        return DispatchedItemHandle(
            sourceMessageId = sourceMessageId,
            targetMessageId = msg.id,
            sendingState = state,
        )
    }
}
