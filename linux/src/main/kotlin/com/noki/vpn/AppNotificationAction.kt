package com.noki.vpn

internal enum class AppNotificationAction(val wireValue: String) {
    OpenSecurityUpdate("open_security_update");

    companion object {
        fun parse(value: String?): AppNotificationAction? =
            entries.firstOrNull { it.wireValue == value?.trim() }
    }
}

