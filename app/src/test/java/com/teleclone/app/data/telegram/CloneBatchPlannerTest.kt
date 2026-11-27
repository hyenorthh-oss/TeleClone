package com.teleclone.app.data.telegram

import com.teleclone.app.data.model.CloneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CloneBatchPlannerTest {

    private val allEnabledSettings = CloneSettings()

    private fun msg(
        id: Long,
        type: CloneMessageType = CloneMessageType.TEXT,
        albumId: Long = 0L,
        canBeSaved: Boolean = true,
        targetThreadId: Long = 0L,
    ) = CloneableMessage(
        id = id,
        mediaAlbumId = albumId,
        canBeSaved = canBeSaved,
        type = type,
        payload = id,
        originThreadId = targetThreadId,
        targetThreadId = targetThreadId,
    )

    @Test
    fun `video document detection recognizes video mimeType and mp4 mkv mov avi extensions`() {
        assertTrue(CloneBatchPlanner.isVideoDocument("video/mp4", "clip.bin"))
        assertTrue(CloneBatchPlanner.isVideoDocument("video/x-matroska", null))
        assertTrue(CloneBatchPlanner.isVideoDocument("application/octet-stream", "Aula01.MKV"))
        assertTrue(CloneBatchPlanner.isVideoDocument(null, "video.mov"))
        assertTrue(CloneBatchPlanner.isVideoDocument(null, "sample.avi"))
        assertTrue(CloneBatchPlanner.isVideoDocument(null, "movie.mp4"))
        assertFalse(CloneBatchPlanner.isVideoDocument("application/pdf", "apostila.pdf"))
    }

    @Test
    fun `block of 250 single messages is sliced internally into 100 100 50 TDLib calls`() {
        val messages = (1L..250L).map { msg(it) }
        val units = CloneBatchPlanner.buildSendUnits(messages, allEnabledSettings)
        val blocks = CloneBatchPlanner.buildBlocks(units, blockSize = 250, preserveAlbumIntegrity = true)

        assertEquals(1, blocks.size)
        assertEquals(250, blocks[0].messageCount)
        assertEquals(listOf(100, 100, 50), blocks[0].tdLibCalls.map { it.size })
    }

    @Test
    fun `block of 500 single messages is sliced internally into five 100-item TDLib calls`() {
        val messages = (1L..500L).map { msg(it) }
        val units = CloneBatchPlanner.buildSendUnits(messages, allEnabledSettings)
        val blocks = CloneBatchPlanner.buildBlocks(units, blockSize = 500, preserveAlbumIntegrity = true)

        assertEquals(1, blocks.size)
        assertEquals(500, blocks[0].messageCount)
        assertEquals(listOf(100, 100, 100, 100, 100), blocks[0].tdLibCalls.map { it.size })
    }

    @Test
    fun `blocks and TDLib calls never mix messages from different targetThreadId topics`() {
        val topic1Msgs = (1L..120L).map { msg(it, targetThreadId = 10L) }
        val topic2Msgs = (121L..250L).map { msg(it, targetThreadId = 20L) }

        val units = CloneBatchPlanner.buildSendUnits(topic1Msgs + topic2Msgs, allEnabledSettings)
        val blocks = CloneBatchPlanner.buildBlocks(units, blockSize = 250, preserveAlbumIntegrity = true)

        assertEquals(2, blocks.size)
        assertEquals(10L, blocks[0].targetThreadId)
        assertEquals(120, blocks[0].messageCount)
        assertEquals(listOf(100, 20), blocks[0].tdLibCalls.map { it.size })

        assertEquals(20L, blocks[1].targetThreadId)
        assertEquals(130, blocks[1].messageCount)
        assertEquals(listOf(100, 30), blocks[1].tdLibCalls.map { it.size })
    }

    @Test
    fun `album crossing 100-id TDLib limit is never split across ForwardMessages calls`() {
        val singles = (1L..98L).map { msg(it) }
        val album = (99L..103L).map { msg(it, type = CloneMessageType.PHOTO, albumId = 777L) }
        val units = CloneBatchPlanner.buildSendUnits(singles + album, allEnabledSettings)
        val blocks = CloneBatchPlanner.buildBlocks(units, blockSize = 250, preserveAlbumIntegrity = true)

        assertEquals(1, blocks.size)
        assertEquals(listOf(98, 5), blocks[0].tdLibCalls.map { it.size })
        assertEquals((99L..103L).toList(), blocks[0].tdLibCalls[1].items.map { it.id })
    }

    @Test
    fun `album crossing block boundary closes block before album when preserving integrity`() {
        val singles = (1L..248L).map { msg(it) }
        val album = (249L..253L).map { msg(it, type = CloneMessageType.PHOTO, albumId = 900L) }
        val trailing = listOf(msg(254L))

        val units = CloneBatchPlanner.buildSendUnits(singles + album + trailing, allEnabledSettings)
        val blocks = CloneBatchPlanner.buildBlocks(units, blockSize = 250, preserveAlbumIntegrity = true)

        assertEquals(2, blocks.size)
        assertEquals(248, blocks[0].messageCount)
        assertEquals(listOf(100, 100, 48), blocks[0].tdLibCalls.map { it.size })
        assertEquals(6, blocks[1].messageCount)
        assertEquals((249L..253L).toList(), blocks[1].units[0].items.map { it.id })
    }

    @Test
    fun `albums over 10 items are split into sub-albums of at most 10 isolated calls`() {
        val hugeAlbum = (1L..23L).map { msg(it, type = CloneMessageType.PHOTO, albumId = 555L) }
        val units = CloneBatchPlanner.buildSendUnits(hugeAlbum, allEnabledSettings)

        assertEquals(listOf(10, 10, 3), units.map { it.size })
        assertTrue(units.all { it.requiresIsolatedCall })

        val calls = CloneBatchPlanner.sliceUnitsIntoTdLibCalls(units)
        assertEquals(listOf(10, 10, 3), calls.map { it.size })
    }

    @Test
    fun `mixed media album separates photo-video from document and audio and skips system messages`() {
        val mixed = listOf(
            msg(1L, CloneMessageType.PHOTO, albumId = 44L),
            msg(2L, CloneMessageType.UNSUPPORTED, albumId = 0L, canBeSaved = false),
            msg(3L, CloneMessageType.VIDEO, albumId = 44L),
            msg(4L, CloneMessageType.DOCUMENT, albumId = 44L),
            msg(5L, CloneMessageType.AUDIO, albumId = 44L),
            msg(6L, CloneMessageType.TEXT, albumId = 0L),
            msg(7L, CloneMessageType.PHOTO, albumId = 0L, canBeSaved = false),
        )

        val units = CloneBatchPlanner.buildSendUnits(mixed, allEnabledSettings)
        assertEquals(4, units.size)
        assertEquals(listOf(1L, 3L), units[0].items.map { it.id })
        assertEquals(listOf(4L), units[1].items.map { it.id })
        assertEquals(listOf(5L), units[2].items.map { it.id })
        assertEquals(listOf(6L), units[3].items.map { it.id })
    }

    @Test
    fun `jitter delay stays strictly within min and max bounds`() {
        val rng = Random(42)
        repeat(500) {
            val delay = CloneBatchPlanner.computeJitterDelayMs(5_000L, 8_000L, rng)
            assertTrue("Delay $delay out of [5000, 8000]", delay in 5_000L..8_000L)
        }
        assertEquals(5_000L, CloneBatchPlanner.computeJitterDelayMs(5_000L, 5_000L, rng))
        assertEquals(0L, CloneBatchPlanner.computeJitterDelayMs(0L, 0L, rng))
    }
}
