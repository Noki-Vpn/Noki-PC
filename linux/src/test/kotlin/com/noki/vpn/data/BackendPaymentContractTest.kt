package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackendPaymentContractTest {
    @Test fun `native checkout uses backend method IDs device proof and authoritative status`() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val payloads = mutableListOf<JSONObject>()
        val invoice = """{"public_id":"payment-1","plan_code":"pro_yearly","status":"pending","amount_rub":4800,"payment_url":"https://pay.example/invoice"}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val body = when (request.url.encodedPath) {
                "/v1/payments/config" -> """{"configured":true,"checkout_enabled":true,"methods":[{"id":null,"code":"card","label":"Card","enabled":true},{"id":2,"code":"sbp","label":"SBP","enabled":true},{"id":13,"code":"crypto","label":"Crypto","enabled":true}]}"""
                "/v1/payments/create" -> {
                    payloads.add(JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()))
                    invoice
                }
                "/v1/payments" -> "[${invoice.replace("pending", "paid")}]"
                else -> error("Unexpected route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val config = api.paymentConfig()
        assertEquals(listOf(null, 2, 13), config.methods.map { it.id })
        config.methods.forEach { method ->
            assertEquals("pending", api.createPayment("access", "pro_yearly", method.id, "device", "proof").status)
        }
        assertEquals("paid", api.payments("access", "device", "proof").single().status)
        assertNull(requests.first().header("Authorization"))
        requests.drop(1).forEach {
            assertEquals("Bearer access", it.header("Authorization"))
            assertEquals("device", it.header("X-Device-Id"))
            assertEquals("proof", it.header("X-Device-Key"))
            assertEquals(BuildConfig.VERSION_CODE.toString(), it.header("X-Noki-Version-Code"))
        }
        assertTrue(payloads.first().isNull("payment_method"))
        assertEquals(2, payloads[1].getInt("payment_method"))
        assertEquals(13, payloads[2].getInt("payment_method"))
        payloads.forEach {
            assertEquals("pro_yearly", it.getString("plan_code"))
            assertTrue(it.isNull("promo_code"))
            assertEquals(setOf("plan_code", "payment_method", "promo_code"), it.keys().asSequence().toSet())
        }
    }
}
