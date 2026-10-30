package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointRankingPolicyTest {
    @Test
    fun releaseAutoRespectsNokiNvCooldownAndHealth() {
        val nv = hysteriaCandidate("nv").copy(proxyType = "nokinv", transport = "nokinv", path = "/tunnel")
        val candidates = listOf(nv, nv.copy(code = "nv-other", nodeId = "other"), hysteriaCandidate("hy2"), tcpCandidate("xhttp"))
        val health = mapOf("nv" to EndpointHealth(score = 0, cooldownUntilMillis = Long.MAX_VALUE),
            "nv-other" to EndpointHealth(score = 0, cooldownUntilMillis = Long.MAX_VALUE),
            "hy2" to EndpointHealth(score = 100))
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            fun select(exclude: Boolean = false) = EndpointRankingPolicy.select(candidates, health, network, 1_000, { 0 }, excludeNokiNv = exclude)!!.candidate
            assertEquals("hy2", select().code)
            assertEquals("hy2", select(true).code)
            assertEquals("hy2", select().code)
            assertEquals("hy2", EndpointRankingPolicy.select(candidates,
                health.mapValues { it.value.copy(cooldownUntilMillis = 0) }, network, 1_000, { 0 })!!.candidate.code)
        }
    }

    @Test
    fun manualTransportIdentityIsGlobalAndUnavailableFamilyNeverBecomesAuto() {
        val first = tcpCandidate("first")
        val foreign = first.copy(code = "foreign", locationCode = "fr", entryPort = 8443, priority = 2)
        val manual = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointCode = first.code, manualEndpointGroupKey = "lv|reality|xhttp|stream-up|100")
        assertEquals(EndpointGroupPolicy.groupKey(first), EndpointGroupPolicy.groupKey(foreign))
        assertEquals(listOf(foreign), EndpointGroupPolicy.manualCandidates(listOf(foreign, hysteriaCandidate("hy2")), manual))
        assertEquals(emptyList<BackendEndpointCandidate>(), EndpointGroupPolicy.manualCandidates(listOf(hysteriaCandidate("hy2")), manual))
        assertEquals(listOf("tls|nokinv|quic", "reality|xhttp|stream-up", "reality|tcp|", "tls|hysteria|", "tls|naive|"),
            EndpointGroupPolicy.protocolOptions().map(EndpointGroupPolicy::groupKey))
    }
    @Test
    fun directHealthDoesNotAdoptRetiredRouteScopedRows() {
        val direct = EndpointHealth(score = 90)
        val route = EndpointHealth(score = 10)
        val scoped = EndpointHealthScope.forNetwork(mapOf("wifi|tcp" to direct,
            "wifi|mh|entry|exit|tcp" to route), EndpointRankingPolicy.NetworkKind.WIFI)
        assertEquals(mapOf("tcp" to direct), scoped)
    }

    @Test
    fun handoverKeepsCurrentOnlyWhenItRemainsInTheBestTier() {
        val tcp = tcpCandidate("tcp")
        val hy2 = hysteriaCandidate("hy2")
        fun select(network: EndpointRankingPolicy.NetworkKind, health: Map<String, EndpointHealth> = emptyMap(),
            preferred: String = "tcp") = EndpointRankingPolicy.select(
            listOf(tcp, tcp.copy(code = "equal"), hy2), health, network, 1_000L, { 1 },
            preferredCode = preferred,
        )?.candidate?.code
        assertEquals("tcp", select(EndpointRankingPolicy.NetworkKind.WIFI))
        assertEquals("tcp", select(EndpointRankingPolicy.NetworkKind.CELLULAR))
        assertEquals("tcp", select(EndpointRankingPolicy.NetworkKind.CELLULAR,
            mapOf("tcp" to EndpointHealth(score = 100, lastUpdatedAtMillis = 1_000L))))
        assertEquals("equal", select(EndpointRankingPolicy.NetworkKind.WIFI,
            mapOf("tcp" to EndpointHealth(cooldownUntilMillis = 2_000L))))
        assertEquals("tcp", select(EndpointRankingPolicy.NetworkKind.WIFI, preferred = "hy2"))
    }

    @Test
    fun countryAndAutoUseWeightedRandomRatherThanAlwaysPickingTheHeaviestNode() {
        val light = VpnServer("a", "A", "LV", "lv1", "a", 443, true,
            metricsAt = "1970-01-01T00:00:00Z", weight = 1, latencyMs = 20)
        val heavy = light.copy(id = "b", name = "B", weight = 9, latencyMs = 25)
        for (mode in listOf(ServerSelectionMode.COUNTRY, ServerSelectionMode.AUTO)) {
            val profile = UserProfile(serverSelectionMode = mode, selectedCountryCode = "LV")
            val nodes = listOf(light, heavy.copy(countryCode = if (mode == ServerSelectionMode.AUTO) "FR" else "LV"),
                heavy.copy(id = "offline", isOnline = false), heavy.copy(id = "zero", weight = 0))
            val winners = (0 until 100).map { index ->
                EndpointRankingPolicy.rankServers(nodes, profile, nowMillis = 0,
                    random = { (index + 0.5) / 100.0 }).first().id
            }.groupingBy { it }.eachCount()
            assertEquals(mode.name, mapOf("a" to 10, "b" to 90), winners)
        }
    }

    @Test
    fun countryScopeExcludesFasterForeignNodesAndLatencyPoolExcludesSlowHeavyNodes() {
        val fast = VpnServer("a", "A", "LV", "lv1", "a", 443, true,
            metricsAt = "1970-01-01T00:00:00Z", weight = 1, latencyMs = 20)
        val slow = fast.copy(id = "slow", weight = 10000, latencyMs = 100)
        val foreign = fast.copy(id = "fr", countryCode = "FR", weight = 10000, latencyMs = 1)
        val country = UserProfile(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "LV")
        assertEquals(listOf("a", "slow"), EndpointRankingPolicy.rankServers(
            listOf(slow, foreign, fast), country, nowMillis = 0, random = { 0.999 }).map { it.id })
    }

    @Test
    fun nodeSelectionKeepsManualScopeAndWeightsNodesOnlyOnce() {
        val nodes = listOf(
            VpnServer("a", "A", "DE", "de1", "a", 443, true, metricsAt = "1970-01-01T00:00:00Z", weight = 1),
            VpnServer("b", "B", "DE", "de2", "b", 443, true, metricsAt = "1970-01-01T00:00:00Z", weight = 9),
            VpnServer("c", "C", "FR", "fr", "c", null, true),
        )
        val country = UserProfile(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "DE")
        assertEquals(listOf("b", "a"), EndpointRankingPolicy.rankServers(nodes + nodes[1], country, nowMillis = 0, random = { 0.5 }).map { it.id })
        val manual = country.copy(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "c")
        assertEquals(listOf("c"), EndpointRankingPolicy.rankServers(nodes, manual, nowMillis = 0).map { it.id })
    }

    @Test
    fun nodeSelectionKeepsUnknownPingAsFallbackAndIgnoresStaleLoad() {
        val nodes = listOf(
            VpnServer("a", "A", "DE", "de", "a", 443, true, latencyMs = 20, weight = 1),
            VpnServer("b", "B", "DE", "de", "b", null, true, weight = 100),
            VpnServer("offline", "Offline", "DE", "de", "o", 443, false),
        )
        assertEquals(listOf("a", "b"), EndpointRankingPolicy.rankServers(nodes, UserProfile(serverSelectionMode = ServerSelectionMode.AUTO), nowMillis = 0).map { it.id })
    }
    @Test
    fun autoRankingRetainsHysteriaAsLastFallbackOnEveryNetwork() {
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val ranked = EndpointRankingPolicy.rankCandidates(
                candidates = listOf(hysteriaCandidate("hy2"), tcpCandidate("tcp")),
                health = emptyMap(),
                networkKind = network,
                nowMillis = 0L,
            )
            assertEquals(network.name, listOf("tcp", "hy2"), ranked.map { it.code })
        }
    }

    @Test
    fun staleScoreMovesTowardBaselineBeforeSelection() {
        val dayMillis = 24 * 60 * 60 * 1_000L
        val nowMillis = dayMillis * 2
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(tcpCandidate("stale"), tcpCandidate("fresh")),
            health = mapOf(
                "stale" to EndpointHealth(score = 60, lastUpdatedAtMillis = nowMillis - dayMillis),
                "fresh" to EndpointHealth(score = 65, lastUpdatedAtMillis = nowMillis),
            ),
            networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
            nowMillis = nowMillis,
            rotationIndex = { 0 },
        )

        assertEquals("stale", selected?.candidate?.code)
    }

    @Test
    fun newResultAppliesToRecoveredStaleScore() {
        val dayMillis = 24 * 60 * 60 * 1_000L
        val updated = EndpointRankingPolicy.updateAfterResult(
            previous = EndpointHealth(
                score = 0,
                lastUpdatedAtMillis = dayMillis,
            ),
            success = true,
            nowMillis = dayMillis * 10,
        )

        assertEquals(78, updated.score)
    }

    @Test
    fun warmupKeepsBestAndExploresLeastRecentlyMeasuredEndpoint() {
        val ranked = listOf(
            tcpCandidate("best"),
            tcpCandidate("recent"),
            tcpCandidate("unmeasured"),
            tcpCandidate("old"),
        )

        val selected = EndpointRankingPolicy.selectWarmupCandidates(
            rankedCandidates = ranked,
            health = mapOf(
                "best" to EndpointHealth(lastUpdatedAtMillis = 400L),
                "recent" to EndpointHealth(lastUpdatedAtMillis = 300L),
                "old" to EndpointHealth(lastUpdatedAtMillis = 100L),
            ),
            maxCandidates = 2,
        )

        assertEquals(listOf("best", "unmeasured"), selected.map { it.code })
    }

    @Test
    fun measuredHealthOverridesWifiClassOrder() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(
                tcpCandidate("tcp"),
                tcpCandidate("xhttp").copy(
                    transport = "xhttp",
                    transportMode = "stream-up",
                    flow = null,
                ),
            ),
            health = mapOf(
                "tcp" to EndpointHealth(score = 5, lastUpdatedAtMillis = 1_000L),
                "xhttp" to EndpointHealth(score = 100, lastUpdatedAtMillis = 1_000L),
            ),
            networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
            nowMillis = 1_000L,
            rotationIndex = { 0 },
        )

        assertEquals("xhttp", selected?.candidate?.code)
    }

    @Test
    fun measuredHysteriaCanBeatRealityOnCellular() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(tcpCandidate("tcp"), hysteriaCandidate("hy2")),
            health = mapOf(
                "tcp" to EndpointHealth(score = 5, lastUpdatedAtMillis = 1_000L),
                "hy2" to EndpointHealth(score = 100, lastUpdatedAtMillis = 1_000L),
            ),
            networkKind = EndpointRankingPolicy.NetworkKind.CELLULAR,
            nowMillis = 1_000L,
            rotationIndex = { 0 },
        )

        assertEquals("hy2", selected?.candidate?.code)
    }

    @Test
    fun freshMeasurementsOutrankStaticClassAndPriorityInBothSelectionPaths() {
        val now = 10_000L
        val candidates = listOf(tcpCandidate("tcp").copy(priority = 1), hysteriaCandidate("hy2").copy(priority = 999))
        val health = mapOf(
            "tcp" to EndpointHealth(score = 90, latencyEwmaMs = 2_000, latencyUpdatedAtMillis = now),
            "hy2" to EndpointHealth(score = 90, latencyEwmaMs = 100, latencyUpdatedAtMillis = now),
        )
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val ranked = EndpointRankingPolicy.rankCandidates(candidates, health, network, now)
            val selected = EndpointRankingPolicy.select(candidates, health, network, now, { 0 })
            assertEquals(listOf("hy2", "tcp"), ranked.map { it.code })
            assertEquals(ranked.first(), selected?.candidate)
        }
    }

    @Test
    fun expiredEvidenceReturnsToStaticBootstrapAndCannotBypassEligibility() {
        val now = 10 * 24 * 60 * 60 * 1_000L
        val tcp = tcpCandidate("tcp")
        val hy2 = hysteriaCandidate("hy2")
        val oldHealth = mapOf("hy2" to EndpointHealth(score = 100, lastUpdatedAtMillis = 1,
            latencyEwmaMs = 1, latencyUpdatedAtMillis = 1))
        assertEquals("tcp", EndpointRankingPolicy.select(
            listOf(hy2, tcp), oldHealth, EndpointRankingPolicy.NetworkKind.WIFI, now, { 0 },
        )?.candidate?.code)
        val candidates = listOf(tcp, hy2, hy2.copy(code = "canary", canaryOnly = true),
            hy2.copy(code = "blank", entryHost = ""), hy2.copy(code = "excluded"))
        val health = candidates.associate { it.code to EndpointHealth(score = 100, lastUpdatedAtMillis = now) } +
            ("hy2" to EndpointHealth(score = 100, cooldownUntilMillis = now + 1))
        assertEquals(listOf("tcp"), EndpointRankingPolicy.rankCandidates(
            candidates, health, EndpointRankingPolicy.NetworkKind.WIFI, now, excludedCodes = setOf("excluded"),
        ).map { it.code })
    }

    @Test
    fun successfulEndpointAlwaysClearsExistingCooldown() {
        val updated = EndpointRankingPolicy.updateAfterResult(
            previous = EndpointHealth(score = 10, cooldownUntilMillis = 99_000L),
            success = true,
            nowMillis = 10_000L,
        )

        assertEquals(0L, updated.cooldownUntilMillis)
    }

    @Test
    fun bestCoolingEndpointIsUsedAsLastResort() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(tcpCandidate("weak"), tcpCandidate("best")),
            health = mapOf(
                "weak" to EndpointHealth(score = 20, cooldownUntilMillis = 99_000L),
                "best" to EndpointHealth(score = 60, cooldownUntilMillis = 99_000L),
            ),
            networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
            nowMillis = 10_000L,
            rotationIndex = { 0 },
        )

        assertEquals("best", selected?.candidate?.code)
    }

    @Test
    fun successfulSampleUpdatesQuarterWeightEwma() {
        val updated = EndpointRankingPolicy.updateAfterResult(
            previous = EndpointHealth(latencyEwmaMs = 400L),
            success = true,
            nowMillis = 10_000L,
            latencyMs = 800L,
        )

        assertEquals(500L, updated.latencyEwmaMs)
        assertEquals(10_000L, updated.latencyUpdatedAtMillis)
    }

    @Test
    fun newSampleDoesNotReviveExpiredOrFutureLatencyHistory() {
        val now = 24 * 60 * 60 * 1_000L
        for (oldTimestamp in listOf(1L, now + 1L)) {
            val recovered = EndpointRankingPolicy.updateAfterResult(
                previous = EndpointHealth(score = 100, latencyEwmaMs = 4_000L,
                    latencyUpdatedAtMillis = oldTimestamp),
                success = true,
                nowMillis = now,
                latencyMs = 100L,
            )
            assertEquals(100L, recovered.latencyEwmaMs)
            assertEquals(now, recovered.latencyUpdatedAtMillis)
            assertEquals("recovered", EndpointRankingPolicy.select(
                candidates = listOf(tcpCandidate("other"), tcpCandidate("recovered")),
                health = mapOf("recovered" to recovered,
                    "other" to EndpointHealth(score = 100, latencyEwmaMs = 200L,
                        latencyUpdatedAtMillis = now)),
                networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
                nowMillis = now,
                rotationIndex = { 0 },
            )?.candidate?.code)
        }
    }

    @Test
    fun freshLowerLatencyBreaksEqualScoreTie() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(tcpCandidate("slow"), tcpCandidate("fast")),
            health = mapOf(
                "slow" to EndpointHealth(score = 80, latencyEwmaMs = 2_000L, latencyUpdatedAtMillis = 1_000L),
                "fast" to EndpointHealth(score = 80, latencyEwmaMs = 100L, latencyUpdatedAtMillis = 1_000L),
            ),
            networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
            nowMillis = 2_000L,
            rotationIndex = { 0 },
        )

        assertEquals("fast", selected?.candidate?.code)
    }

    @Test
    fun higherBackendWeightBreaksEqualHealthAndLatencyTie() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(tcpCandidate("light", weight = 10), tcpCandidate("heavy", weight = 100)),
            health = emptyMap(),
            networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
            nowMillis = 2_000L,
            rotationIndex = { 0 },
        )

        assertEquals("heavy", selected?.candidate?.code)
    }

    @Test
    fun manualHysteriaIsEligibleOnOtherTransport() {
        val selected = EndpointRankingPolicy.select(
            candidates = listOf(hysteriaCandidate("hy2")),
            health = emptyMap(),
            networkKind = EndpointRankingPolicy.NetworkKind.OTHER,
            nowMillis = 0L,
            rotationIndex = { 0 },
        )

        assertEquals("hy2", selected?.candidate?.code)
    }
}
