package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

internal class BackendJsonApi(
    client: OkHttpClient,
    baseUrl: String,
    streamingClient: OkHttpClient = client,
) {
    val baseUrl = baseUrl.trimEnd('/')
    private val transport = BackendTransport(client, this.baseUrl, streamingClient)

    fun apiUrl(path: String): String = "$baseUrl$API_PREFIX$path"

    suspend fun get(path: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url(apiUrl(path))
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
        )
    }

    suspend fun post(
        path: String,
        payload: JSONObject,
        token: String? = null,
        currentDeviceId: String? = null,
        currentDeviceKey: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl(path))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
        token?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
        execute(request.build())
    }

    suspend fun patch(
        path: String,
        payload: JSONObject,
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): JSONObject = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url(apiUrl(path))
                .header("Authorization", "Bearer $token")
                .currentDeviceHeaders(currentDeviceId, currentDeviceKey)
                .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    suspend fun execute(request: Request): JSONObject = transport.jsonObject(request)

    suspend fun executeStreaming(request: Request): JSONObject = transport.streamingJsonObject(request)

    suspend fun executeArray(request: Request): JSONArray = transport.jsonArray(request)

    suspend fun stream(request: Request, sink: (ResponseBody) -> Unit) = transport.stream(request, sink)

    companion object {
        private const val API_PREFIX = "/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun Request.Builder.currentDeviceHeaders(
    currentDeviceId: String?,
    currentDeviceKey: String?,
    deviceNonce: String? = null,
    deviceSignature: String? = null,
): Request.Builder = apply {
    header("X-Noki-Version-Code", BuildConfig.VERSION_CODE.toString())
    currentDeviceId?.takeIf { it.isNotBlank() }?.let { header("X-Device-Id", it) }
    currentDeviceKey?.takeIf { it.isNotBlank() }?.let { header("X-Device-Key", it) }
    deviceNonce?.let { header("X-Device-Nonce", it) }
    deviceSignature?.let { header("X-Device-Signature", it) }
}
