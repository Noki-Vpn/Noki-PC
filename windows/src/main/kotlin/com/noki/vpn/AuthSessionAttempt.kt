package com.noki.vpn

internal data class AuthSessionAttempt(
    val accessToken: String,
    val epoch: Long,
)
