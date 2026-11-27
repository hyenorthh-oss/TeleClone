package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalCoroutinesApi::class)
class HybridPreScannerTest {

    @Test
    fun `hybrid scan counts MessageVideo plus video-like MessageDocument and survives 2 empty GetChatHistory cache misses`() = runTest {
        val docMp4 = TdApi.Message().apply {
            id = 10L
            canBeSaved = true
            content = TdApi.MessageDocument(
                TdApi.Document().apply {
                    fileName = "aula01.mp4"
                    mimeType = "video/mp4"
                },
                TdApi.FormattedText("video mp4", emptyArray()),
            )
        }
        val docMkv = TdApi.Message().apply {
            id = 20L
            canBeSaved = true
            content = TdApi.MessageDocument(
                TdApi.Document().apply {
                    fileName = "aula02.MKV"
                    mimeType = "application/octet-stream"
                },
                TdApi.FormattedText("video mkv", emptyArray()),
            )
        }
        val normalVideo = TdApi.Message().apply {
            id = 30L
            canBeSaved = true
            content = TdApi.MessageVideo()
        }
        val pdfDoc = TdApi.Message().apply {
            id = 40L
            canBeSaved = true
            content = TdApi.MessageDocument(
                TdApi.Document().apply {
                    fileName = "apostila.pdf"
                    mimeType = "application/pdf"
                },
                TdApi.FormattedText("pdf", emptyArray()),
            )
        }
        val photo = TdApi.Message().apply {
            id = 50L
            canBeSaved = true
            content = TdApi.MessagePhoto()
        }

        val allTdMessages = listOf(docMp4, docMkv, normalVideo, pdfDoc, photo)
        val converted = allTdMessages.map { TdLibManager.toCloneableMessage(it) }

        var historyCalls = 0
        val gateway = object : HybridScanGateway<TdApi.Message> {
            override suspend fun isForumSupergroup(chatId: Long): Boolean = false
            override suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor> = emptyList()
            override suspend fun createForumTopic(chatId: Long, topic: ForumTopicDescriptor): ForumTopicDescriptor = topic

            override suspend fun fetchHistoryPage(
                chatId: Long,
                fromMessageId: Long,
                limit: Int,
            ): List<CloneableMessage<TdApi.Message>> {
                historyCalls++
                // First 2 responses are empty (cache miss!), 3rd succeeds with subset
                return when (historyCalls) {
                    1, 2 -> emptyList()
                    3 -> converted.filter { it.id in listOf(30L, 40L, 50L) }
                    else -> emptyList()
                }
            }

            override suspend fun searchChatMessages(
                chatId: Long,
                filter: SearchFilterKind,
                fromMessageId: Long,
                limit: Int,
            ): SearchPageResult<TdApi.Message> {
                // Server search delivers the video documents missing from local cache
                val items = when (filter) {
                    SearchFilterKind.DOCUMENT -> converted.filter { it.id in listOf(10L, 20L, 40L) }
                    SearchFilterKind.VIDEO -> converted.filter { it.id == 30L }
                    SearchFilterKind.PHOTO -> converted.filter { it.id == 50L }
                    SearchFilterKind.AUDIO -> emptyList()
                }
                return SearchPageResult(items, nextFromMessageId = 0L)
            }
        }

        // Disable documents while keeping videos+photos enabled: .mp4 & .mkv documents MUST still be included as VIDEO!
        val settings = CloneSettings(
            includeText = true,
            includePhotos = true,
            includeVideos = true,
            includeAudio = true,
            includeDocuments = false,
        )

        val result = HybridPreScanner.scan(
            taskId = 1L,
            sourceChatId = 100L,
            targetChatId = 200L,
            settings = settings,
            gateway = gateway,
            delayFn = { ms -> delay(ms) },
        )

        assertEquals(3, result.videoCount) // docMp4 + docMkv + normalVideo
        assertEquals(1, result.photoCount)
        assertEquals(0, result.otherCount)
        assertEquals(1, result.filteredSkippedCount) // pdfDoc filtered out
        assertEquals(4, result.totalEligible)
        assertEquals(listOf(10L, 20L, 30L, 50L), result.queuedItems.map { it.messageId })
        assertTrue(result.queuedItems.take(3).all { it.mediaType == CloneMessageType.VIDEO.name })
    }

    @Test
    fun `forum topic mapper creates missing topics in target and falls back to 0 when target is not a forum`() = runTest {
        val sourceTopics = listOf(
            ForumTopicDescriptor(1L, "Geral", isGeneral = true),
            ForumTopicDescriptor(101L, "Módulo 1", iconColor = 0x6FB9F0),
            ForumTopicDescriptor(102L, "Módulo 2", iconColor = 0xFFD67E),
        )
        val createdTopics = mutableListOf<String>()

        val forumGateway = object : HybridScanGateway<Long> {
            override suspend fun isForumSupergroup(chatId: Long): Boolean = chatId == 100L || chatId == 200L
            override suspend fun listForumTopics(chatId: Long): List<ForumTopicDescriptor> =
                if (chatId == 100L) sourceTopics else listOf(ForumTopicDescriptor(1L, "General", isGeneral = true))

            override suspend fun createForumTopic(chatId: Long, topic: ForumTopicDescriptor): ForumTopicDescriptor {
                createdTopics.add(topic.name)
                return topic.copy(threadId = 500L + createdTopics.size)
            }

            override suspend fun fetchHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): List<CloneableMessage<Long>> = emptyList()
            override suspend fun searchChatMessages(chatId: Long, filter: SearchFilterKind, fromMessageId: Long, limit: Int): SearchPageResult<Long> =
                SearchPageResult(emptyList(), 0L)
        }

        val forumResult = HybridPreScanner.mapForumTopics(100L, 200L, forumGateway)
        assertTrue(forumResult.sourceIsForum)
        assertTrue(forumResult.targetIsForum)
        assertEquals(3, forumResult.topicCount)
        assertEquals(listOf("Módulo 1", "Módulo 2"), createdTopics)
        assertEquals(1L, forumResult.threadMap[1L])
        assertEquals(501L, forumResult.threadMap[101L])
        assertEquals(502L, forumResult.threadMap[102L])

        // Fallback when target (300L) is a regular channel/group (isForum == false)
        val fallbackResult = HybridPreScanner.mapForumTopics(100L, 300L, forumGateway)
        assertTrue(fallbackResult.sourceIsForum)
        assertFalse(fallbackResult.targetIsForum)
        assertEquals(mapOf(1L to 0L, 101L to 0L, 102L to 0L), fallbackResult.threadMap)
    }
}
