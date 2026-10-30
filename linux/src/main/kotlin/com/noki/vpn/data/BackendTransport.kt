package com.noki.vpn.data

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal class BackendTransport(
    private val controlPlaneClient: OkHttpClient,
    baseUrl: String,
    private val streamingClient: OkHttpClient = controlPlaneClient,
) {
    private val origin = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun jsonObject(request: Request): JSONObject = execute(controlPlaneClient, request) { response ->
        val raw = response.body.readLimitedText()
        if (!response.isSuccessful) throw backendError(response, raw)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    suspend fun jsonArray(request: Request): JSONArray = execute(controlPlaneClient, request) { response ->
        val raw = response.body.readLimitedText()
        if (!response.isSuccessful) throw backendError(response, raw)
        if (raw.isBlank()) JSONArray() else JSONArray(raw)
    }

    suspend fun streamingJsonObject(request: Request): JSONObject = execute(streamingClient, request) { response ->
        val raw = response.body.readLimitedText()
        if (!response.isSuccessful) throw backendError(response, raw)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    suspend fun stream(request: Request, sink: (ResponseBody) -> Unit) {
        execute(streamingClient, request) { response ->
            if (!response.isSuccessful) throw backendError(response, response.body.readLimitedText())
            sink(response.body)
        }
    }

    private suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        decode: (Response) -> T,
    ): T {
        validate(request.url)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use { decode(it) } })
                }
            })
        }
    }

    private fun validate(url: HttpUrl) {
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
            "backend_url_origin_not_allowed"
        }
        val basePath = origin.encodedPath.trimEnd('/')
        require(basePath.isEmpty() || url.encodedPath == basePath || url.encodedPath.startsWith("$basePath/")) {
            "backend_url_path_not_allowed"
        }
    }

    private fun backendError(response: Response, raw: String): BackendException {
        return BackendException(
            message = errorDetail(raw, response.code),
            statusCode = response.code,
            retryAfterMillis = BackendRetryPolicy.parseRetryAfterMillis(response.header("Retry-After")),
        )
    }

    private fun ResponseBody.readLimitedText(): String {
        val declaredLength = contentLength()
        if (declaredLength > MAX_JSON_RESPONSE_BYTES) {
            throw responseTooLarge()
        }
        val initialCapacity = declaredLength
            .takeIf { it in 1..MAX_JSON_RESPONSE_BYTES }
            ?.toInt()
            ?: DEFAULT_BUFFER_SIZE
        val output = ByteArrayOutputStream(initialCapacity)
        byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L
            while (true) {
                val readLimit = minOf(
                    buffer.size.toLong(),
                    MAX_JSON_RESPONSE_BYTES - totalBytes + 1L,
                ).toInt()
                val read = input.read(buffer, 0, readLimit)
                if (read < 0) break
                if (read == 0) continue
                totalBytes += read
                if (totalBytes > MAX_JSON_RESPONSE_BYTES) {
                    throw responseTooLarge()
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun responseTooLarge(): BackendException =
        BackendException("Backend response is too large", 0)

    private fun errorDetail(raw: String, statusCode: Int): String {
        val fallback = "HTTP $statusCode"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return fallback
        return when (val detail = json.opt("detail")) {
            is String -> detail.ifBlank { fallback }
            is JSONArray -> detail.validationMessage().ifBlank { "Validation error" }
            null -> fallback
            else -> detail.toString().ifBlank { fallback }
        }
    }

    private fun JSONArray.validationMessage(): String = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val loc = item.optJSONArray("loc")
            val field = if (loc != null && loc.length() > 0) loc.optString(loc.length() - 1) else ""
            val message = item.optString("msg")
            when {
                field.isNotBlank() && message.isNotBlank() -> add("$field: $message")
                message.isNotBlank() -> add(message)
            }
        }
    }.joinToString("; ")

    private companion object {
        const val MAX_JSON_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}
