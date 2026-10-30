package com.noki.vpn.data

internal fun tcpCandidate(
    code: String,
    priority: Int = 10,
    weight: Int = 100,
): BackendEndpointCandidate {
    return BackendEndpointCandidate(
        code = code,
        nodeId = "node",
        label = code,
        locationCode = "lv1",
        locationName = "Latvia",
        entryHost = "$code.example.com",
        entryPort = 443,
        serverName = "www.lu.lv",
        proxyType = "vless",
        transport = "xhttp",
        transportMode = "stream-up",
        security = "reality",
        fingerprint = "chrome",
        requestHost = null,
        path = "/noki",
        alpn = null,
        allowInsecure = false,
        enableMux = false,
        randomUserAgent = false,
        publicKey = "public",
        shortId = "short",
        flow = null,
        priority = priority,
        weight = weight,
        canaryOnly = false,
        tags = emptyList(),
    )
}

internal fun hysteriaCandidate(
    code: String,
    priority: Int = 90,
): BackendEndpointCandidate {
    return tcpCandidate(code, priority).copy(
        entryPort = 2443,
        serverName = "$code.example.com",
        proxyType = "hysteria",
        transport = "hysteria",
        transportMode = null,
        security = "tls",
        publicKey = null,
        shortId = null,
        flow = null,
    )
}
