package com.noki.vpn.data

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import com.noki.vpn.vpn.measureRuRelayRtt
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuRelayCatalogTest {
    @Test
    fun relayRttQueueSharesTheSameBudgetAndCancelsItsProbe() = runBlocking {
        val entries = (1..7).map { index -> RuRelayCatalogEntry("relay-$index", "RU", "RU", true, null,
            probeHost = "8.8.8.8", probePort = 20000, pairReady = true) }
        var active = 0
        val started = System.nanoTime()
        val samples = measureRuRelayRtt(RuRelayCatalog("exit", entries)) { relay ->
            active++
            try {
                kotlinx.coroutines.delay(if (relay.id == "relay-7") 1_000L else 2_100L)
                42
            } finally { active-- }
        }
        assertEquals(6, samples.size)
        assertEquals(0, active)
        org.junit.Assert.assertTrue((System.nanoTime() - started) / 1_000_000L < 3_500)
    }
    private val exitId = "11111111-1111-4111-8111-111111111111"
    private val readyId = "22222222-2222-4222-8222-222222222222"
    private val offlineId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun discoversRealForeignParticipantsWithoutTransportEndpoints() = runBlocking {
        var observed: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"route_version":1,"entry_node_id":null,"exit_node_id":null,"relays":[],
                    "foreign_nodes":[{"id":"$exitId","name":"NL overlay","country_code":"NL"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example").ruRelayCatalog("access", readyId, "proof")
        assertEquals(listOf(RuForeignNode(exitId, "NL overlay", "NL")), result.foreignNodes)
        assertEquals(null, observed!!.url.queryParameter("exit_node_id"))
        assertEquals(readyId, observed!!.header("X-Device-Id"))
        assertEquals("proof", observed!!.header("X-Device-Key"))
        assertEquals(null, result.exitNodeId)
    }

    @Test
    fun discoveryRejectsSelectedRouteAndDuplicateOrRussianParticipants() {
        fun discovery() = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":null,
            "relays":[],"foreign_nodes":[{"id":"$exitId","name":"NL overlay","country_code":"NL"}]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(discovery().put("exit_node_id", exitId), null)
        }
        val russian = discovery().apply { getJSONArray("foreign_nodes").getJSONObject(0).put("country_code", "RU") }
        assertThrows(IllegalArgumentException::class.java) { RuRelayCatalogJsonParser.parse(russian, null) }
        val duplicate = discovery().apply { getJSONArray("foreign_nodes").put(getJSONArray("foreign_nodes").getJSONObject(0)) }
        assertThrows(IllegalArgumentException::class.java) { RuRelayCatalogJsonParser.parse(duplicate, null) }
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(discovery().apply { remove("foreign_nodes") }, null)
        }
    }

    @Test
    fun absentOrUnreachableTcpTargetsPermitWeightedAdmission() = runBlocking {
        val noTargets = RuRelayCatalog(exitId, listOf(RuRelayCatalogEntry(readyId, "RU", "RU", true, null)))
        assertEquals(emptyList<RuRelayRttSample>(), measureRuRelayRtt(noTargets) { error("no target must be dialed") })
        val target = noTargets.copy(relays = listOf(noTargets.relays.single().copy(probeHost = "8.8.8.8", probePort = 20000, pairReady = true)))
        assertEquals(emptyList<RuRelayRttSample>(), measureRuRelayRtt(target) { null })
    }

    @Test
    fun freshConnectSamplesOnlyReachablePairTcpProbes() = runBlocking {
        val ru3Id = "55555555-5555-4555-8555-555555555555"
        val noProbeId = "44444444-4444-4444-8444-444444444444"
        val catalog = RuRelayCatalog(exitId, listOf(
            RuRelayCatalogEntry(readyId, "RU2", "RU", true, null, "192.0.2.2", 20045, pairReady = true),
            RuRelayCatalogEntry(ru3Id, "RU3", "RU", true, null, "192.0.2.3", 20050, pairReady = true),
            RuRelayCatalogEntry(offlineId, "RU4", "RU", false, "offline", "192.0.2.4", 20055),
            RuRelayCatalogEntry(noProbeId, "RU5", "RU", true, null, "192.0.2.5"),
        ))
        val first = measureRuRelayRtt(catalog) { relay ->
            if (relay.id == readyId) null else 42
        }
        assertEquals(listOf(RuRelayRttSample(ru3Id, 42)), first)
        val second = measureRuRelayRtt(catalog) { relay ->
            if (relay.id == ru3Id) null else 28
        }
        assertEquals(listOf(RuRelayRttSample(readyId, 28)), second)
    }

    @Test
    fun acceptsOnlyReadyValidRelayProbePorts() {
        for ((port, expected) in listOf(30001 to 30001, 0 to null, 65536 to null)) {
            val json = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
                {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"pair_ready":true,
                 "probe_host":"8.8.8.8","probe_port":$port}
            ]}""")
            assertEquals(expected, RuRelayCatalogJsonParser.parse(json, exitId).relays.single().probePort)
            json.getJSONArray("relays").getJSONObject(0).put("probe_host", JSONObject.NULL)
            assertEquals(null, RuRelayCatalogJsonParser.parse(json, exitId).relays.single().probePort)
        }
    }

    @Test
    fun parsesAvailableAndUnavailableRuRelaysWithoutInventingAvailability() {
        val catalog = RuRelayCatalogJsonParser.parse(JSONObject("""{
            "route_version":1,"entry_node_id":null,
            "exit_node_id":"$exitId",
            "relays":[
                {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"pair_ready":true,"unavailable_reason":null,"probe_host":"8.8.8.8"},
                {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false,"unavailable_reason":"offline","probe_host":"1.1.1.1"}
            ]
        }"""), exitId)
        assertEquals(listOf(true, false), catalog.relays.map { it.available })
        assertEquals("offline", catalog.relays[1].unavailableReason)
        assertEquals("8.8.8.8", catalog.relays[0].probeHost)
        assertEquals(null, catalog.relays[1].probeHost)
    }

    @Test
    fun rejectsWrongExitAndMissingUnavailableReason() {
        val json = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(json, offlineId)
        }
        val missingReason = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
            {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false}
        ]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(missingReason, exitId)
        }
        val overlapping = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
            {"id":"$exitId","name":"Kazan","country_code":"RU","available":true}
        ]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(overlapping, exitId)
        }
    }

    @Test
    fun apiScopesCatalogToAuthenticatedDeviceAndExactExit() = runBlocking {
        var observed: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example")
            .ruRelayCatalog("access", readyId, "proof", exitId)
        assertEquals(exitId, result.exitNodeId)
        assertEquals("/v1/vpn/ru-relays", observed!!.url.encodedPath)
        assertEquals(exitId, observed!!.url.queryParameter("exit_node_id"))
        assertEquals("1", observed!!.url.queryParameter("route_version"))
        assertEquals(null, observed!!.url.queryParameter("entry_node_id"))
        assertEquals(readyId, observed!!.url.queryParameter("device_id"))
        assertEquals("Bearer access", observed!!.header("Authorization"))
        assertEquals(readyId, observed!!.header("X-Device-Id"))
        assertEquals("proof", observed!!.header("X-Device-Key"))
    }

    @Test
    fun apiScopesExplicitForeignEntryAndRejectsAResponseForTheShorterRoute() = runBlocking {
        val entryId = "44444444-4444-4444-8444-444444444444"
        var responseEntry: Any = entryId
        val observed = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed += chain.request()
            val payload = JSONObject().put("route_version", 1).put("entry_node_id", responseEntry)
                .put("exit_node_id", exitId).put("relays", org.json.JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val result = api.ruRelayCatalog("access", readyId, "proof", exitId, entryId)
        assertEquals(entryId, result.entryNodeId)
        assertEquals(1, result.routeVersion)
        assertEquals(entryId, observed.single().url.queryParameter("entry_node_id"))
        responseEntry = JSONObject.NULL
        val failure = runCatching { api.ruRelayCatalog("access", readyId, "proof", exitId, entryId) }
        org.junit.Assert.assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, observed.size)
        Unit
    }

    @Test
    fun catalogRequiresVersionAndExplicitNullableEntryForTheExactTuple() {
        fun response() = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}""")
        for (field in listOf("route_version", "entry_node_id")) {
            assertThrows(field, IllegalArgumentException::class.java) {
                RuRelayCatalogJsonParser.parse(response().apply { remove(field) }, exitId)
            }
        }
        for ((field, value) in listOf("route_version" to 2, "route_version" to "1", "entry_node_id" to readyId)) {
            assertThrows(field, IllegalArgumentException::class.java) {
                RuRelayCatalogJsonParser.parse(response().put(field, value), exitId)
            }
        }
    }
}
