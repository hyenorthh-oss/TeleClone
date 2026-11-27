package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedContentReuploaderTest {

    @Test
    fun `protected media reuploader downloads, sends via InputFileLocal with topicId, and guarantees DeleteFile in finally`() = runTest {
        val deletedFileIds = mutableListOf<Int>()
        var sentTopic: TdApi.MessageTopic? = null
        var sentContent: TdApi.InputMessageContent? = null

        val transport = object : ProtectedReuploadTransport {
            override suspend fun downloadFile(fileId: Int, timeoutMs: Long): DownloadedLocalMedia {
                return DownloadedLocalMedia(fileId = fileId, localPath = "/cache/video_$fileId.mp4")
            }

            override suspend fun deleteFile(fileId: Int) {
                deletedFileIds.add(fileId)
            }

            override suspend fun sendSingleMessage(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                content: TdApi.InputMessageContent,
            ): TdApi.Message {
                sentTopic = TdLibManager.toTargetTopic(targetIsForum, targetThreadId)
                sentContent = content
                return TdApi.Message().apply {
                    id = 9001L
                    sendingState = null
                }
            }

            override suspend fun sendAlbumMessages(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                contents: List<TdApi.InputMessageContent>,
            ): List<TdApi.Message?> = emptyList()
        }

        val videoMsg = TdApi.Message().apply {
            id = 77L
            content = TdApi.MessageVideo().apply {
                video = TdApi.Video().apply {
                    duration = 12
                    width = 1280
                    height = 720
                    supportsStreaming = true
                    video = TdApi.File().apply { id = 444 }
                }
                caption = TdApi.FormattedText("Aula @canal_antigo", emptyArray())
            }
        }

        val item = CloneableMessage(
            id = 77L,
            mediaAlbumId = 0L,
            canBeSaved = true,
            type = CloneMessageType.VIDEO,
            payload = videoMsg,
            targetThreadId = 321L,
        )

        val handles = ProtectedContentReuploader.reuploadItems(
            targetChatId = 200L,
            targetThreadId = 321L,
            targetIsForum = true,
            items = listOf(item),
            settings = CloneSettings(stripMentionsAndLinks = true, customSignature = "@NovoCanal"),
            transport = transport,
        )

        assertEquals(1, handles.size)
        assertEquals(DispatchedSendingState.Confirmed, handles[0].sendingState)
        assertEquals(listOf(444), deletedFileIds)
        assertTrue(sentTopic is TdApi.MessageTopicForum)
        assertEquals(321, (sentTopic as TdApi.MessageTopicForum).forumTopicId)
        assertTrue(sentContent is TdApi.InputMessageVideo)
        val inputVideo = sentContent as TdApi.InputMessageVideo
        assertTrue(inputVideo.video is TdApi.InputFileLocal)
        assertEquals("/cache/video_444.mp4", (inputVideo.video as TdApi.InputFileLocal).path)
        assertEquals("Aula\n\n@NovoCanal", inputVideo.caption.text)
    }

    @Test
    fun `DeleteFile is still invoked in finally when SendMessageAlbum throws an exception`() = runTest {
        val deletedFileIds = mutableListOf<Int>()

        val failingTransport = object : ProtectedReuploadTransport {
            override suspend fun downloadFile(fileId: Int, timeoutMs: Long): DownloadedLocalMedia {
                return DownloadedLocalMedia(fileId = fileId, localPath = "/cache/photo_$fileId.jpg")
            }

            override suspend fun deleteFile(fileId: Int) {
                deletedFileIds.add(fileId)
            }

            override suspend fun sendSingleMessage(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                content: TdApi.InputMessageContent,
            ): TdApi.Message = throw IllegalStateException("Unexpected single send")

            override suspend fun sendAlbumMessages(
                targetChatId: Long,
                targetThreadId: Long,
                targetIsForum: Boolean,
                contents: List<TdApi.InputMessageContent>,
            ): List<TdApi.Message?> {
                throw TdLibException(500, "Upload failed")
            }
        }

        val p1 = TdApi.Message().apply {
            id = 1L
            mediaAlbumId = 55L
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply {
                    sizes = arrayOf(TdApi.PhotoSize().apply {
                        width = 800
                        height = 600
                        photo = TdApi.File().apply { id = 101 }
                    })
                }
                caption = TdApi.FormattedText("", emptyArray())
            }
        }
        val p2 = TdApi.Message().apply {
            id = 2L
            mediaAlbumId = 55L
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply {
                    sizes = arrayOf(TdApi.PhotoSize().apply {
                        width = 800
                        height = 600
                        photo = TdApi.File().apply { id = 102 }
                    })
                }
                caption = TdApi.FormattedText("", emptyArray())
            }
        }

        try {
            ProtectedContentReuploader.reuploadItems(
                targetChatId = 200L,
                targetThreadId = 0L,
                targetIsForum = false,
                items = listOf(
                    CloneableMessage(1L, 55L, true, CloneMessageType.PHOTO, p1),
                    CloneableMessage(2L, 55L, true, CloneMessageType.PHOTO, p2),
                ),
                settings = CloneSettings(),
                transport = failingTransport,
            )
            fail("Expected exception")
        } catch (e: TdLibException) {
            assertEquals(500, e.code)
        }

        assertEquals(listOf(101, 102), deletedFileIds)
    }
}
