package com.noki.vpn.vpn

enum class VpnPreparationStrategy(val forceRefreshSession: Boolean, val allowCachedFallback: Boolean) {
    CachedFirst(false, true),
    CachedFirstWithoutFallback(false, false),
    FreshOnly(true, false),
    FreshWithCachedFallback(true, true),
}
