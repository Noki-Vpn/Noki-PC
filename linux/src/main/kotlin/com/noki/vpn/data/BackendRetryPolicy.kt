package com.noki.vpn.data

import java.io.IOException

object BackendRetryPolicy {
    private const val MAX_DELAY_MS = 30_000L
    private const val BASE_DELAY_MS = 900L

    fun isTransient(error: Throwable): Boolean {
        return when (error) {
            is IOException -> true
            is BackendException -> error.statusCode == 408 ||
                error.statusCode == 429 ||
                error.statusCode in 500..599
            else -> false
        }
    }

    fun delayMillis(
        error: Throwable,
        attempt: Int,
        jitter: (Long) -> Long,
    ): Long {
        val serverDelay = (error as? BackendException)?.retryAfterMillis
        if (serverDelay != null) return serverDelay.coerceIn(0L, MAX_DELAY_MS)
        val multiplier = 1L shl attempt.coerceIn(0, 2)
        return jitter(BASE_DELAY_MS * multiplier).coerceIn(0L, MAX_DELAY_MS)
    }

    fun parseRetryAfterMillis(rawValue: String?): Long? {
        val seconds = rawValue?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull()
    }
}
