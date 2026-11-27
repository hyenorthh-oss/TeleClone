package com.teleclone.app.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class TdLibTimeoutException(message: String) : Exception(message)

sealed class ReliabilityOutcome<out T> {
    data class Success<T>(val value: T) : ReliabilityOutcome<T>()
    data class RestrictedSkipped(val reason: String) : ReliabilityOutcome<Nothing>()
}

object CloneReliabilityExecutor {

    const val DEFAULT_TIMEOUT_MS = 25_000L
    val RETRY_BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)

    fun isForwardsRestrictedError(error: TdLibException): Boolean {
        if (error.code != 400) return false
        val msg = error.message.uppercase()
        return msg.contains("CHAT_FORWARDS_RESTRICTED") ||
            msg.contains("PROTECTED_CONTENT") ||
            msg.contains("FORWARD_NOT_ALLOWED")
    }

    fun isTransientError(throwable: Throwable): Boolean {
        if (throwable is TdLibTimeoutException) return true
        if (throwable is TdLibException) {
            if (throwable.extractFloodWaitSeconds() != null) return false
            if (throwable.code == 400 || throwable.code == 401 || throwable.code == 403 || throwable.code == 404) {
                return false
            }
            if (throwable.code >= 500) return true
            val msg = throwable.message.uppercase()
            return msg.contains("TIMEOUT") ||
                msg.contains("TIMED OUT") ||
                msg.contains("NETWORK") ||
                msg.contains("CONNECTION") ||
                msg.contains("RESET") ||
                msg.contains("UNAVAILABLE") ||
                msg.contains("EOF")
        }
        val msg = throwable.message?.uppercase().orEmpty()
        return msg.contains("TIMEOUT") ||
            msg.contains("NETWORK") ||
            msg.contains("CONNECTION")
    }

    suspend fun <T> executeWithPolicy(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        backoffMs: List<Long> = RETRY_BACKOFF_MS,
        autoHandleFloodWait: Boolean = true,
        skipOnForwardsRestricted: Boolean = true,
        onFloodWaitTick: suspend (remainingSeconds: Int) -> Unit = {},
        onRetryAttempt: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
        onRestrictedSkip: (TdLibException) -> Unit = {},
        delayFn: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): ReliabilityOutcome<T> {
        var transientRetryIndex = 0

        while (true) {
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    block()
                } ?: throw TdLibTimeoutException("Timeout de ${timeoutMs / 1000}s na chamada TDLib")

                return ReliabilityOutcome.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TdLibException) {
                if (skipOnForwardsRestricted && isForwardsRestrictedError(e)) {
                    onRestrictedSkip(e)
                    return ReliabilityOutcome.RestrictedSkipped(e.message)
                }

                val floodWaitSeconds = e.extractFloodWaitSeconds()
                if (floodWaitSeconds != null && autoHandleFloodWait) {
                    val totalWait = (floodWaitSeconds + 1).coerceAtLeast(1)
                    for (remaining in totalWait downTo 1) {
                        onFloodWaitTick(remaining)
                        delayFn(1_000L)
                    }
                    continue
                }

                if (isTransientError(e) && transientRetryIndex < backoffMs.size) {
                    val waitMs = backoffMs[transientRetryIndex]
                    transientRetryIndex++
                    onRetryAttempt(transientRetryIndex, waitMs, e)
                    delayFn(waitMs)
                    continue
                }

                throw e
            } catch (e: Throwable) {
                if (isTransientError(e) && transientRetryIndex < backoffMs.size) {
                    val waitMs = backoffMs[transientRetryIndex]
                    transientRetryIndex++
                    onRetryAttempt(transientRetryIndex, waitMs, e)
                    delayFn(waitMs)
                    continue
                }
                throw e
            }
        }
    }
}
