package com.noki.vpn.data

object EndpointStartupPreflightPolicy {
    private const val MIN_DEFAULT_MAX_ATTEMPTS = 8

    data class Result(
        val selected: BackendEndpointCandidate?,
        val failedTcpCodes: List<String>,
        val hasReachablePrecheck: Boolean,
    ) {
        val shouldPenalizeFailedTcp: Boolean
            get() = hasReachablePrecheck && failedTcpCodes.isNotEmpty()
    }

    fun selectWithTcpPrecheck(
        candidates: List<BackendEndpointCandidate>,
        health: Map<String, EndpointHealth>,
        networkKind: EndpointRankingPolicy.NetworkKind,
        nowMillis: Long,
        rotationIndex: (String) -> Int,
        canReach: (BackendEndpointCandidate) -> Boolean,
        maxAttempts: Int? = null,
        excludeNokiNv: Boolean = false,
    ): Result {
        val failedCodes = mutableListOf<String>()
        val excludedCodes = mutableSetOf<String>()
        val rotationBases = mutableMapOf<String, Int>()
        val stableRotationIndex = { rotationKey: String ->
            rotationBases.getOrPut(rotationKey) { rotationIndex(rotationKey) }
        }
        val effectiveMaxAttempts = (maxAttempts ?: defaultMaxAttempts(candidates)).coerceAtLeast(0)
        var attempts = 0
        while (attempts < effectiveMaxAttempts) {
            val selection = EndpointRankingPolicy.select(
                candidates = candidates,
                health = health,
                networkKind = networkKind,
                nowMillis = nowMillis,
                rotationIndex = stableRotationIndex,
                excludedCodes = excludedCodes,
                excludeNokiNv = excludeNokiNv,
            ) ?: break
            attempts += 1
            if (!EndpointTransportPolicy.requiresTcpPrecheck(selection.candidate)) {
                return Result(
                    selected = selection.candidate,
                    failedTcpCodes = failedCodes,
                    hasReachablePrecheck = false,
                )
            }
            if (canReach(selection.candidate)) {
                return Result(
                    selected = selection.candidate,
                    failedTcpCodes = failedCodes,
                    hasReachablePrecheck = true,
                )
            }
            failedCodes += selection.candidate.code
            excludedCodes += selection.candidate.code
        }

        return Result(
            selected = null,
            failedTcpCodes = failedCodes,
            hasReachablePrecheck = false,
        )
    }

    private fun defaultMaxAttempts(candidates: List<BackendEndpointCandidate>): Int {
        val usableCandidateCount = candidates.count { candidate ->
            !candidate.canaryOnly && EndpointSecurityPolicy.isAllowedCandidate(candidate) && EndpointTransportPolicy.isDirectCandidate(candidate)
        }
        return maxOf(MIN_DEFAULT_MAX_ATTEMPTS, usableCandidateCount)
    }
}
