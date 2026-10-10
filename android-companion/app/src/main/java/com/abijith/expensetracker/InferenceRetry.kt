package com.abijith.expensetracker

import android.os.SystemClock
import kotlin.random.Random

internal const val MAX_INFERENCE_RETRIES = 2
internal const val RETRY_WINDOW_MS = 240_000L
private val RETRYABLE_INFERENCE_ERRORS = setOf("timeout", "network", "service_unavailable")

internal data class RetriedInference<T>(val result: T?, val requests: Int, val elapsedMs: Double)

/** Retries one stage only: category retries never repeat successful classification.
 * Python does a single request. Cancellation/consent/permission are checked before
 * every attempt and during backoff. An already-sent request cannot be recalled.
 * This window bounds scheduling retries, not an in-flight socket operation.
 */
internal fun <T> retryInference(
    canContinue: () -> Boolean,
    call: () -> T,
    errorCode: (T) -> String?,
    maxRetries: Int = MAX_INFERENCE_RETRIES,
    nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    jitterMillis: () -> Long = { Random.nextLong(0, 251) },
): RetriedInference<T> {
    require(maxRetries in 0..MAX_INFERENCE_RETRIES)
    val started = nowMillis()
    var requests = 0
    var latest: T? = null
    fun result(value: T?) = RetriedInference(value, requests, (nowMillis() - started).coerceAtLeast(0).toDouble())
    while (true) {
        if (!canContinue()) return result(null)
        if (requests > 0 && nowMillis() - started >= RETRY_WINDOW_MS) return result(latest)
        val response = call()
        latest = response
        requests++
        if (!canContinue()) return result(null)
        if (errorCode(response) !in RETRYABLE_INFERENCE_ERRORS || requests > maxRetries) return result(latest)
        val wait = (1_000L shl (requests - 1)) + jitterMillis().coerceIn(0, 250)
        if (nowMillis() - started + wait >= RETRY_WINDOW_MS) return result(latest)
        var remaining = wait
        while (remaining > 0) {
            if (!canContinue()) return result(null)
            val part = minOf(remaining, 100L)
            try { sleepMillis(part) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result(null)
            }
            remaining -= part
        }
    }
}
