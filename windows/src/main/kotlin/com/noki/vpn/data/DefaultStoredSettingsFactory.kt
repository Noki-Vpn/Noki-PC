package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import java.util.Locale

object DefaultStoredSettingsFactory {
    const val FULL_GLASS_MIN_API_LEVEL = 33

    private val legacyRussianDirectPackages = setOf(
        "ru.rostel",
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.nspk.mirpay",
        "ru.yandex.searchplugin",
        "ru.yandex.yandexmaps",
        "ru.yandex.taxi",
        "ru.beru.android",
        "ru.yandex.music",
        "ru.kinopoisk",
        "ru.dublgis.dgismobile",
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "com.avito.android",
        "com.vkontakte.android",
        "ru.ok.android",
        "ru.mail.mailapp",
        "ru.rutube.app",
        "ru.sbcs.store",
    )

    private val defaultRussianDirectPackages = legacyRussianDirectPackages + setOf(
        "ru.oneme.app", // MAX
        "com.vk.im",
        "com.vk.vkvideo",
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
        "ru.sber.telecom",
        "ru.onlime.my.app",
        "com.logistic.sdek",
    )

    fun updatedDefaultPackages(packages: Set<String>, mode: AppFilterMode): Set<String> =
        if (mode == AppFilterMode.ALL_EXCEPT_SELECTED && packages == legacyRussianDirectPackages) {
            defaultRussianDirectPackages
        } else {
            packages
        }

    fun create(
        locale: Locale = Locale.getDefault(),
        sdkInt: Int = FULL_GLASS_MIN_API_LEVEL,
        isFreshInstall: Boolean = true,
    ): StoredSettings {
        return StoredSettings(
            profile = VlessProfile(),
            filterMode = AppFilterMode.ALL_EXCEPT_SELECTED,
            selectedPackages = defaultRussianDirectPackages,
            userProfile = UserProfile(serverSelectionMode = if (isFreshInstall) ServerSelectionMode.AUTO else ServerSelectionMode.COUNTRY),
            personalizationSettings = PersonalizationSettings(
                language = AppLanguage.fromLocale(locale),
                glassMode = if (isFreshInstall && sdkInt < FULL_GLASS_MIN_API_LEVEL) {
                    GlassMode.SIMPLE
                } else {
                    GlassMode.FULL
                },
            ),
            securitySettings = SecuritySettings(),
            advancedSettings = AdvancedSettings(
                ruRelayEnabled = BuildConfig.RU_RELAY_DEFAULT_ENABLED,
                bypassDomains = listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE),
            ),
            isAuthenticated = false,
            backendAccessToken = null,
            backendRefreshToken = null,
            backendAccessTokenExpiresInSeconds = null,
            backendRefreshExpiresAt = null,
            backendDeviceKey = "",
            backendDeviceId = "",
            backendDeviceAccessRole = "owner",
        )
    }

    fun normalizeAlwaysOnDomains(domains: List<String>): List<String> {
        return DomainRulePolicy.normalizeList(domains)
    }

    fun normalizeBypassDomains(domains: List<String>): List<String> {
        return DomainRulePolicy.normalizeList(domains)
    }
}
