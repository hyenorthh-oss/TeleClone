package com.teleclone.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class BatchModeConfig(
    val isBatchMode: Boolean = false,
    val blockSizeText: String = "100",
    val jitterMinText: String = "5",
    val jitterMaxText: String = "8",
    val autoPauseFloodWait: Boolean = false,
    val variableJitterEnabled: Boolean = false,
    val preserveAlbumIntegrity: Boolean = false,
    val riskAccepted: Boolean = false,
)

@Immutable
data class BatchValidationResult(
    val blockSize: Int? = null,
    val jitterMinSeconds: Double? = null,
    val jitterMaxSeconds: Double? = null,
    val isBlockSizeValid: Boolean = false,
    val isJitterMinValid: Boolean = false,
    val isJitterMaxValid: Boolean = false,
    val allProtectionsEnabled: Boolean = false,
    val blockSizeError: String? = null,
    val jitterError: String? = null,
) {
    val isValid: Boolean
        get() = isBlockSizeValid && isJitterMinValid && isJitterMaxValid && allProtectionsEnabled
}

object BatchModeValidator {

    fun validate(config: BatchModeConfig): BatchValidationResult {
        val parsedBlockSize = config.blockSizeText.trim().toIntOrNull()
        val isBlockSizeValid = parsedBlockSize != null && parsedBlockSize >= 1
        val blockSizeError = if (!isBlockSizeValid) {
            "Informe um valor inteiro >= 1"
        } else {
            null
        }

        val parsedMin = config.jitterMinText.trim().replace(',', '.').toDoubleOrNull()
        val parsedMax = config.jitterMaxText.trim().replace(',', '.').toDoubleOrNull()

        val isMinValid = parsedMin != null && parsedMin.isFinite() && parsedMin >= 0.0
        val isMaxValid = parsedMax != null && parsedMax.isFinite() && parsedMax >= 0.0 &&
            (parsedMin == null || parsedMax >= parsedMin)

        val jitterError = when {
            !isMinValid -> "Mínimo deve ser >= 0"
            parsedMax == null || !parsedMax.isFinite() || parsedMax < 0.0 -> "Máximo deve ser >= 0"
            parsedMin != null && parsedMax < parsedMin -> "Máximo deve ser >= Mínimo"
            else -> null
        }

        val allProtections = config.autoPauseFloodWait &&
            config.variableJitterEnabled &&
            config.preserveAlbumIntegrity &&
            config.riskAccepted

        return BatchValidationResult(
            blockSize = parsedBlockSize,
            jitterMinSeconds = parsedMin,
            jitterMaxSeconds = parsedMax,
            isBlockSizeValid = isBlockSizeValid,
            isJitterMinValid = isMinValid,
            isJitterMaxValid = isMaxValid,
            allProtectionsEnabled = allProtections,
            blockSizeError = blockSizeError,
            jitterError = jitterError,
        )
    }

    fun canStartCloning(
        hasValidSourceAndTarget: Boolean,
        isTaskRunning: Boolean,
        config: BatchModeConfig,
        validation: BatchValidationResult = validate(config),
    ): Boolean {
        if (!hasValidSourceAndTarget || isTaskRunning) return false
        return if (!config.isBatchMode) true else validation.isValid
    }
}
