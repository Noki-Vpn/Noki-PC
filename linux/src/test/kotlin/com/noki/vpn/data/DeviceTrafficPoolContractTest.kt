package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTrafficPoolContractTest {
    @Test
    fun `server pool is independent of owner role and old responses remain primary`() {
        val device = JSONObject(NATIVE_DEVICE).toBackendDevice()
        assertEquals("invited", device.accessRole)
        assertEquals("shared", device.trafficPool)
        assertEquals("primary", JSONObject(NATIVE_DEVICE).apply { remove("traffic_pool") }
            .toBackendDevice().trafficPool)
        assertEquals("shared", JSONObject(INCY_DEVICE).toBackendIncyDevice().trafficPool)
        assertEquals("primary", JSONObject(INCY_DEVICE).apply { remove("traffic_pool") }
            .toBackendIncyDevice().trafficPool)
    }

    @Test
    fun `subscription retains server aggregate quota and calendar dates without local defaults`() {
        val subscription = JSONObject("""{
            "status":"active","primary_device_limit":3,
            "shared_traffic_used_gb":12.75,"shared_traffic_limit_gb":73.5,
            "shared_period_start":"2026-10-01","shared_period_end":"2026-11-01",
            "shared_free_only":true,"free_locations_only":false
        }""").toBackendSubscription()
        assertEquals(3, subscription.primaryDeviceLimit)
        assertEquals(12.75, checkNotNull(subscription.sharedTrafficUsedGb), 0.0)
        assertEquals(73.5, checkNotNull(subscription.sharedTrafficLimitGb), 0.0)
        assertEquals("2026-10-01", subscription.sharedPeriodStart)
        assertEquals("2026-11-01", subscription.sharedPeriodEnd)
        assertTrue(subscription.sharedFreeOnly)
        assertFalse(subscription.freeLocationsOnly)

        val legacy = JSONObject("""{"status":"active"}""").toBackendSubscription()
        assertNull(legacy.primaryDeviceLimit)
        assertNull(legacy.sharedTrafficUsedGb)
        assertNull(legacy.sharedTrafficLimitGb)
        assertNull(legacy.sharedPeriodStart)
        assertNull(legacy.sharedPeriodEnd)
        assertFalse(legacy.sharedFreeOnly)
        assertFalse(legacy.freeLocationsOnly)
    }

    @Test
    fun `native move sends current owner proof and decodes the returned device list`() = runBlocking {
        val api = api { request ->
            assertEquals("PATCH", request.method)
            assertEquals("/v1/devices/target/traffic-pool", request.url.encodedPath)
            assertEquals("Bearer access", request.header("Authorization"))
            assertEquals("current", request.header("X-Device-Id"))
            assertEquals("proof", request.header("X-Device-Key"))
            assertEquals("shared", request.payload().getString("traffic_pool"))
            200 to "[$NATIVE_DEVICE]"
        }
        val devices = api.setDeviceTrafficPool("access", "target", "shared", "current", "proof")
        assertEquals(1, devices.size)
        assertEquals("shared", devices.single().trafficPool)
        assertEquals("invited", devices.single().accessRole)
    }

    @Test
    fun `INCY move uses owner authorization and decodes a single device`() = runBlocking {
        val api = api { request ->
            assertEquals("PATCH", request.method)
            assertEquals("/v1/incy/devices/target/traffic-pool", request.url.encodedPath)
            assertEquals("Bearer access", request.header("Authorization"))
            assertEquals("primary", request.payload().getString("traffic_pool"))
            200 to INCY_DEVICE.replace("shared", "primary")
        }
        assertEquals("primary", api.setIncyDeviceTrafficPool("access", "target", "primary").trafficPool)
    }

    @Test
    fun `pool moves preserve server full slot errors and reject invalid pools before network`() {
        val api = api { 409 to """{"detail":"Primary device limit reached"}""" }
        val native = assertThrows(BackendException::class.java) {
            runBlocking { api.setDeviceTrafficPool("access", "target", "primary", "current", "proof") }
        }
        assertEquals(409, native.statusCode)
        val incy = assertThrows(BackendException::class.java) {
            runBlocking { api.setIncyDeviceTrafficPool("access", "target", "primary") }
        }
        assertEquals(409, incy.statusCode)
        val noNetwork = api { error("Invalid traffic pool must not reach network") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { noNetwork.setDeviceTrafficPool("access", "target", "owner", "current", "proof") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { noNetwork.setIncyDeviceTrafficPool("access", "target", "owner") }
        }
    }

    @Test
    fun `device creation lets the server choose pool without a traffic pool payload`() = runBlocking {
        val api = api { request ->
            assertEquals("POST", request.method)
            assertFalse(request.payload().has("traffic_pool"))
            when (request.url.encodedPath) {
                "/v1/devices/invites" -> 200 to """{"invite_code":"INVITE","expires_at":"2026-10-19T12:00:00Z"}"""
                "/v1/incy/devices" -> {
                    assertEquals("My device", request.payload().getString("name"))
                    200 to JSONObject(INCY_DEVICE).put("import_link", "incy://crypt1/test").toString()
                }
                else -> error("Unexpected creation path")
            }
        }
        assertEquals("INVITE", api.createDeviceInvite("access", "current", "proof").inviteCode)
        assertEquals("shared", api.createIncyDevice("access", "My device").device.trafficPool)
    }

    private fun Request.payload(): JSONObject = JSONObject(Buffer().also { checkNotNull(body).writeTo(it) }.readUtf8())

    private fun api(reply: (Request) -> Pair<Int, String>): BackendApiClient = BackendApiClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(), "https://api.example",
    )

    private companion object {
        const val NATIVE_DEVICE = """{"id":"target","device_key":"key","device_name":"PC","platform":"windows","access_role":"invited","traffic_pool":"shared"}"""
        const val INCY_DEVICE = """{"id":"target","name":"INCY","traffic_pool":"shared"}"""
    }
}
