package com.teleclone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchModeValidatorTest {

    private fun validAllProtectionsConfig() = BatchModeConfig(
        isBatchMode = true,
        blockSizeText = "250",
        jitterMinText = "5",
        jitterMaxText = "8",
        autoPauseFloodWait = true,
        variableJitterEnabled = true,
        preserveAlbumIntegrity = true,
        riskAccepted = true,
    )

    @Test
    fun `standard mode allows start when source and target are valid regardless of batch fields`() {
        val config = BatchModeConfig(
            isBatchMode = false,
            blockSizeText = "0",
            jitterMinText = "-1",
            jitterMaxText = "-5",
            autoPauseFloodWait = false,
            variableJitterEnabled = false,
            preserveAlbumIntegrity = false,
            riskAccepted = false,
        )

        assertTrue(
            BatchModeValidator.canStartCloning(
                hasValidSourceAndTarget = true,
                isTaskRunning = false,
                config = config,
            )
        )
    }

    @Test
    fun `toggling to batch mode locks start button until all 4 protections are enabled`() {
        val base = validAllProtectionsConfig()
        assertTrue(BatchModeValidator.canStartCloning(true, false, base))

        val missingFloodWait = base.copy(autoPauseFloodWait = false)
        val missingJitter = base.copy(variableJitterEnabled = false)
        val missingAlbumIntegrity = base.copy(preserveAlbumIntegrity = false)
        val missingRisk = base.copy(riskAccepted = false)

        assertFalse(BatchModeValidator.canStartCloning(true, false, missingFloodWait))
        assertFalse(BatchModeValidator.canStartCloning(true, false, missingJitter))
        assertFalse(BatchModeValidator.canStartCloning(true, false, missingAlbumIntegrity))
        assertFalse(BatchModeValidator.canStartCloning(true, false, missingRisk))
    }

    @Test
    fun `invalid block sizes zero negative empty and non numeric are rejected`() {
        val base = validAllProtectionsConfig()

        listOf("", "0", "-5", "abc").forEach { invalid ->
            val cfg = base.copy(blockSizeText = invalid)
            val validation = BatchModeValidator.validate(cfg)
            assertFalse("Expected invalid blockSize for '$invalid'", validation.isBlockSizeValid)
            assertFalse(BatchModeValidator.canStartCloning(true, false, cfg, validation))
        }
    }

    @Test
    fun `invalid jitter bounds zero negative and max less than min are rejected`() {
        val base = validAllProtectionsConfig()

        val zeroMin = base.copy(jitterMinText = "0", jitterMaxText = "8")
        assertFalse(BatchModeValidator.validate(zeroMin).isValid)

        val negativeMin = base.copy(jitterMinText = "-2", jitterMaxText = "8")
        assertFalse(BatchModeValidator.validate(negativeMin).isValid)

        val maxLessThanMin = base.copy(jitterMinText = "10", jitterMaxText = "5")
        val validation = BatchModeValidator.validate(maxLessThanMin)
        assertFalse(validation.isJitterMaxValid)
        assertFalse(BatchModeValidator.canStartCloning(true, false, maxLessThanMin, validation))
    }

    @Test
    fun `valid high block sizes and equal or decimal jitter are accepted`() {
        val base = validAllProtectionsConfig()

        listOf("1", "50", "100", "250", "500", "1000", "2500").forEach { size ->
            val cfg = base.copy(blockSizeText = size, jitterMinText = "5.5", jitterMaxText = "8.5")
            val validation = BatchModeValidator.validate(cfg)
            assertTrue("Expected valid config for blockSize=$size", validation.isValid)
            assertEquals(size.toInt(), validation.blockSize)
            assertNull(validation.blockSizeError)
            assertNull(validation.jitterError)
            assertTrue(BatchModeValidator.canStartCloning(true, false, cfg, validation))
        }

        val equalJitter = base.copy(jitterMinText = "6", jitterMaxText = "6")
        assertTrue(BatchModeValidator.validate(equalJitter).isValid)
    }

    @Test
    fun `start button stays disabled when task is already running or source target invalid`() {
        val valid = validAllProtectionsConfig()
        assertFalse(BatchModeValidator.canStartCloning(hasValidSourceAndTarget = false, isTaskRunning = false, config = valid))
        assertFalse(BatchModeValidator.canStartCloning(hasValidSourceAndTarget = true, isTaskRunning = true, config = valid))
    }
}
