package com.noki.vpn.data

fun appRoutingModeLabel(
    mode: AppFilterMode,
    language: AppLanguage,
): String {
    val suffix = when (mode) {
        AppFilterMode.ALL_APPS -> if (language == AppLanguage.RU) "все приложения" else "all apps"
        AppFilterMode.ONLY_SELECTED -> if (language == AppLanguage.RU) "только отмеченные" else "selected only"
        AppFilterMode.ALL_EXCEPT_SELECTED -> if (language == AppLanguage.RU) "кроме отмеченных" else "except selected"
    }
    return if (language == AppLanguage.RU) {
        "Маршрутизация - $suffix"
    } else {
        "Traffic routing - $suffix"
    }
}
