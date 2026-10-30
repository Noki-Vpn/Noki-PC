package com.noki.vpn

import androidx.compose.runtime.Immutable
import com.noki.vpn.data.BackendIncyDevice
import com.noki.vpn.data.BackendAppNotification
import com.noki.vpn.data.IncyImportLink
import com.noki.vpn.data.V2raynSubscriptionUrl
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppInfo
import com.noki.vpn.data.BillingCycle
import com.noki.vpn.data.DailyStats
import com.noki.vpn.data.DeviceSession
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.PlanCode
import com.noki.vpn.data.PlanSummary
import com.noki.vpn.data.SecuritySettings
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UsageBar
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.vpn.VpnRuntimeMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.ceil

enum class AppDestination {
    SPLASH,
    LOGIN,
    REGISTRATION,
    PASSWORD_RECOVERY,
    INVITE_DEVICE,
    INVITE_QR_SCANNER,
    HOME,
    ACCOUNT,
    STATS,
    SETTINGS,
    PLANS,
    ADVANCED_SETTINGS,
    MULTIHOP,
    APP_FILTER,
    SITE_RULES_ALWAYS_ON,
    SITE_RULES_BYPASS,
    SECURITY,
    ACCOUNT_CREDENTIAL_CHANGE,
    SUPPORT,
    PERSONALIZATION,
    DEVICES,
}

enum class AuthStep {
    WELCOME,
    EMAIL_LOGIN,
    REGISTRATION_EMAIL,
    REGISTRATION_CODE,
    REGISTRATION_PROFILE,
    REGISTRATION_PASSWORD,
}

enum class PasswordRecoveryPurpose {
    LOGIN,
    ACCOUNT_SECURITY,
}

enum class TelegramAuthPurpose {
    LOGIN,
    LINK,
}

sealed interface TelegramLoginState {
    data object Idle : TelegramLoginState
    data class LaunchingSdk(val purpose: TelegramAuthPurpose) : TelegramLoginState
    data class Exchanging(val purpose: TelegramAuthPurpose) : TelegramLoginState
    data object Authenticated : TelegramLoginState
    data class Error(
        val message: String,
        val purpose: TelegramAuthPurpose = TelegramAuthPurpose.LOGIN,
    ) : TelegramLoginState
}

sealed interface AppDialog {
    data object Logout : AppDialog
    data object LogoutOthers : AppDialog
    data object AccessDenied : AppDialog
    data object FreeTrafficLimitReached : AppDialog
    data object DeviceLimitReached : AppDialog
    data object EmptySelectedApps : AppDialog
    data object VpnConflict : AppDialog
    data object ResetAppFilter : AppDialog
    data class ChangeServer(
        val locationCode: String,
        val mode: ServerSelectionMode = ServerSelectionMode.COUNTRY,
    ) : AppDialog
    data class DeleteAccount(
        val isDeleting: Boolean = false,
        val error: String? = null,
    ) : AppDialog
    data class UnlinkTelegram(
        val isUnlinking: Boolean = false,
        val error: String? = null,
    ) : AppDialog
    data class RemoveDevice(val deviceId: String) : AppDialog
    data class RenameDevice(val deviceId: String) : AppDialog
}

data class IncyDevicesUiState(
    val devices: List<BackendIncyDevice> = emptyList(),
    val nameInput: String = "",
    val selectedDeviceId: String? = null,
    val importLink: IncyImportLink? = null,
    val v2raynSubscriptionUrl: V2raynSubscriptionUrl? = null,
    val isCreateDialogVisible: Boolean = false,
    val isManageDialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@Immutable
data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val error: String? = null,
    val isLoading: Boolean = false,
)

@Immutable
data class RegistrationFormState(
    val username: String = "",
    val email: String = "",
    val verificationCode: String = "",
    val codeSent: Boolean = false,
    val isCodeSending: Boolean = false,
    val codeCooldownSeconds: Int = 0,
    val password: String = "",
    val passwordRepeat: String = "",
    val error: String? = null,
    val isLoading: Boolean = false,
)

@Immutable
data class PasswordRecoveryFormState(
    val email: String = "",
    val verificationCode: String = "",
    val showCodeField: Boolean = false,
    val codeSent: Boolean = false,
    val isCodeSending: Boolean = false,
    val codeCooldownSeconds: Int = 0,
    val password: String = "",
    val passwordRepeat: String = "",
    val passwordStepVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@Immutable
data class InviteDeviceFormState(
    val inviteCode: String = "",
    val generatedInviteCode: String? = null,
    val generatedInviteExpiresAt: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@Immutable
data class AndroidUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String? = null,
    val isForced: Boolean = false,
    val architecture: String,
    val apkUrl: String,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
)

@Immutable
data class AndroidUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val isReadyToInstall: Boolean = false,
    val currentVersionName: String = "",
    val update: AndroidUpdateInfo? = null,
    val error: String? = null,
)

@Immutable
data class SettingsPreparedState(
    val language: AppLanguage = AppLanguage.EN,
    val username: String = "username",
    val email: String = "email not set",
    val avatarUri: String? = null,
    val planTitle: String = "Free",
    val trafficUsageLabel: String = "-- / -- Gb",
    val daysLabel: String = "-- d.",
    val planActionLabel: String = "Go Plus",
    val planActionColorArgb: Long = SettingsPreparedStatePolicy.SettingsAccentStrongArgb,
    val profileGradientColorArgb: Long = SettingsPreparedStatePolicy.SettingsErrorArgb,
    val devicesTitle: String = "Devices",
    val advancedTitle: String = "Advanced settings",
    val securityTitle: String = "Security",
    val supportTitle: String = "Support",
    val personalizationTitle: String = "Personalization",
    val logoutTitle: String = "Logout",
    val isInvitedDevice: Boolean = false,
    val showSecurityUpdateBadge: Boolean = false,
)

object SettingsPreparedStatePolicy {
    const val SettingsErrorArgb: Long = 0xFFFF6B6B
    const val SettingsTextMutedArgb: Long = 0xFF6E8797
    const val SettingsAccentPrimaryArgb: Long = 0xFF7AE7C7
    const val SettingsAccentSecondaryArgb: Long = 0xFF8CC8FF
    const val SettingsAccentStrongArgb: Long = 0xFF42D6A4

    fun withPreparedSettingsState(state: AppUiState): AppUiState {
        val prepared = prepare(state)
        return if (state.settingsPreparedState == prepared) {
            state
        } else {
            state.copy(settingsPreparedState = prepared)
        }
    }

    fun prepare(state: AppUiState): SettingsPreparedState {
        val language = state.personalizationSettings.language
        val currentPlan = currentPlan(state)
        val isInvitedDevice = state.currentDeviceAccessRole.equals("invited", ignoreCase = true)
        val email = state.userProfile.email
        return SettingsPreparedState(
            language = language,
            username = state.userProfile.username.ifBlank { "username" },
            email = when {
                isInvitedDevice -> tr(language, "Приглашённое устройство", "Invited device")
                email.isBlank() -> tr(language, "почта не указана", "email not set")
                else -> email
            },
            avatarUri = state.userProfile.avatarUri.takeUnless { isInvitedDevice },
            planTitle = planTitle(state, currentPlan),
            trafficUsageLabel = backendUsageLabel(state, language, currentPlan),
            daysLabel = daysLabel(state.userProfile.subscriptionExpiresAt, language),
            planActionLabel = planActionLabel(state, language),
            planActionColorArgb = planActionColorArgb(state, currentPlan),
            profileGradientColorArgb = profileGradientColorArgb(state, currentPlan),
            devicesTitle = tr(language, "Устройства", "Devices"),
            advancedTitle = tr(language, "Расширенные настройки", "Advanced settings"),
            securityTitle = tr(language, "Безопасность", "Security"),
            supportTitle = tr(language, "Поддержка", "Support"),
            personalizationTitle = tr(language, "Персонализация", "Personalization"),
            logoutTitle = tr(language, "Выход", "Logout"),
            isInvitedDevice = isInvitedDevice,
            showSecurityUpdateBadge = state.isAndroidUpdateAvailable,
        )
    }

    private fun currentPlan(state: AppUiState): PlanSummary? {
        val rawCode = state.userProfile.selectedPlanCodeRaw.trim().ifBlank {
            state.userProfile.selectedPlanCode.code
        }
        val tier = currentPlanTier(state)
        return state.plans.firstOrNull { it.code.equals(rawCode, ignoreCase = true) }
            ?: state.plans.firstOrNull { it.tier.equals(tier, ignoreCase = true) }
    }

    fun currentPlanTier(state: AppUiState): String {
        val profile = state.userProfile
        val rawCode = profile.selectedPlanCodeRaw.trim().ifBlank { profile.selectedPlanCode.code }
        val tier = profile.selectedPlanTier?.trim()?.takeIf { it.isNotBlank() }
            ?: state.plans.firstOrNull { it.code.equals(rawCode, ignoreCase = true) }
                ?.tier?.trim()?.takeIf { it.isNotBlank() }
            ?: rawCode.lowercase(Locale.ROOT).replace(Regex("[_-](monthly|yearly)$"), "")
        return tier.lowercase(Locale.ROOT)
    }

    private fun planTitle(state: AppUiState, plan: PlanSummary?): String {
        state.userProfile.selectedPlanName?.takeIf { it.isNotBlank() }?.let { return it }
        plan?.title?.takeIf { it.isNotBlank() }?.let { return cleanPlanTitle(it) }
        val rawCode = state.userProfile.selectedPlanCodeRaw.takeIf { it.isNotBlank() }
            ?: state.userProfile.selectedPlanCode.code
        return cleanPlanTitle(rawCode)
    }

    private fun cleanPlanTitle(value: String): String {
        return value
            .replace(Regex("([_-])(monthly|yearly)$", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
    }

    private fun backendUsageLabel(
        state: AppUiState,
        language: AppLanguage,
        plan: PlanSummary?,
    ): String {
        val profile = state.userProfile
        val used = gbValue(profile.trafficUsedGb, language)
        val limit = when {
            profile.trafficLimitGb != null -> gbValue(profile.trafficLimitGb, language)
            !profile.selectedPlanTier.isNullOrBlank() -> "∞"
            plan?.trafficLimitGb == null && plan != null -> "∞"
            profile.selectedPlanCode == PlanCode.PREMIUM -> "∞"
            else -> "--"
        }
        return "$used / $limit Gb"
    }

    private fun gbValue(value: Double?, language: AppLanguage): String {
        if (value == null || !value.isFinite() || value < 0.0) return "--"
        val raw = when {
            value >= 100.0 -> String.format(Locale.US, "%.0f", value)
            value >= 10.0 -> String.format(Locale.US, "%.1f", value)
            else -> String.format(Locale.US, "%.2f", value)
        }
        val compact = trimDecimalZeros(raw)
        return if (language == AppLanguage.RU) compact.replace('.', ',') else compact
    }

    private fun trimDecimalZeros(value: String): String {
        return if (value.contains('.')) value.trimEnd('0').trimEnd('.') else value
    }

    private fun daysLabel(expiresAt: String?, language: AppLanguage): String {
        val days = remainingSubscriptionDays(expiresAt) ?: return tr(language, "-- д.", "-- d.")
        return tr(language, "$days д.", "$days d.")
    }

    private fun remainingSubscriptionDays(expiresAt: String?): Long? {
        if (expiresAt.isNullOrBlank()) return null
        val instant = runCatching { OffsetDateTime.parse(expiresAt).toInstant() }
            .getOrElse { runCatching { Instant.parse(expiresAt) }.getOrNull() }
            ?: return null
        val remainingMs = Duration.between(Instant.now(), instant).toMillis()
        if (remainingMs <= 0L) return 0L
        return ceil(remainingMs / 86_400_000.0).toLong()
    }

    private fun planActionLabel(
        state: AppUiState,
        language: AppLanguage,
    ): String {
        return if (isFreePlan(state)) {
            tr(language, "Перейти на Plus", "Go Plus")
        } else {
            tr(language, "О тарифе", "About plan")
        }
    }

    fun isFreePlan(state: AppUiState): Boolean {
        val profile = state.userProfile
        profile.selectedPlanTier?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it.equals("free", ignoreCase = true)
        }
        val code = profile.selectedPlanCodeRaw.trim().ifBlank { profile.selectedPlanCode.code }
        return code.equals("free", ignoreCase = true) ||
            code.startsWith("free_", ignoreCase = true) || code.startsWith("free-", ignoreCase = true)
    }

    private fun planActionColorArgb(state: AppUiState, plan: PlanSummary?): Long {
        return planColorArgb(state, plan) ?: when (state.userProfile.selectedPlanCode) {
            PlanCode.PREMIUM -> SettingsTextMutedArgb
            PlanCode.PRO -> SettingsAccentSecondaryArgb
            PlanCode.FREE,
            PlanCode.PLUS -> SettingsAccentStrongArgb
        }
    }

    private fun profileGradientColorArgb(state: AppUiState, plan: PlanSummary?): Long {
        return planColorArgb(state, plan) ?: when (state.userProfile.selectedPlanCode) {
            PlanCode.FREE -> SettingsErrorArgb
            PlanCode.PLUS -> SettingsAccentPrimaryArgb
            PlanCode.PRO -> SettingsAccentSecondaryArgb
            PlanCode.PREMIUM -> SettingsTextMutedArgb
        }
    }

    private fun planColorArgb(state: AppUiState, plan: PlanSummary?): Long? {
        val raw = (state.userProfile.selectedPlanBadgeColor ?: plan?.badgeColor)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val hex = raw.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> 0xFF000000L or hex.toLong(16)
                8 -> hex.toLong(16)
                else -> null
            }
        }.getOrNull()
    }

    private fun tr(language: AppLanguage, russian: String, english: String): String {
        return if (language == AppLanguage.RU) russian else english
    }
}

data class AppUiState(
    val paymentCheckout: PaymentCheckoutState = PaymentCheckoutState(),
    val isReady: Boolean = false,
    val screenStack: List<AppDestination> = listOf(AppDestination.SPLASH),
    val profile: VlessProfile = VlessProfile(),
    val filterMode: AppFilterMode = AppFilterMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val installedApps: List<AppInfo> = emptyList(),
    val isLoadingInstalledApps: Boolean = false,
    val userProfile: UserProfile = UserProfile(),
    val personalizationSettings: PersonalizationSettings = PersonalizationSettings(),
    val securitySettings: SecuritySettings = SecuritySettings(),
    val advancedSettings: AdvancedSettings = AdvancedSettings(),
    val endpointOptions: List<VpnEndpointOption> = emptyList(),
    val endpointOptionsCountryCode: String? = null,
    val isAuthenticated: Boolean = false,
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val vpnRuntimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
    val connectedAtMillis: Long? = null,
    val connectionReason: String = "",
    val inlineMessage: String? = null,
    val authStep: AuthStep = AuthStep.WELCOME,
    val telegramLoginState: TelegramLoginState = TelegramLoginState.Idle,
    val accountSecurityState: AccountSecurityUiState = AccountSecurityUiState(),
    val loginForm: LoginFormState = LoginFormState(),
    val registrationForm: RegistrationFormState = RegistrationFormState(),
    val passwordRecoveryForm: PasswordRecoveryFormState = PasswordRecoveryFormState(),
    val passwordRecoveryPurpose: PasswordRecoveryPurpose = PasswordRecoveryPurpose.LOGIN,
    val inviteDeviceForm: InviteDeviceFormState = InviteDeviceFormState(),
    val billingCycle: BillingCycle = BillingCycle.YEARLY,
    val plans: List<PlanSummary> = emptyList(),
    val devices: List<DeviceSession> = emptyList(),
    val incyDevices: IncyDevicesUiState = IncyDevicesUiState(),
    val appNotificationHistory: List<BackendAppNotification> = emptyList(),
    val hasUnreadAppNotifications: Boolean = false,
    val locations: List<ServerLocation> = emptyList(),
    val usageBars: List<UsageBar> = emptyList(),
    val dailyStats: List<DailyStats> = emptyList(),
    val isRefreshingData: Boolean = false,
    val dialog: AppDialog? = null,
    val alwaysOnInput: String = "",
    val bypassInput: String = "",
    val currentDeviceAccessRole: String = "owner",
    val isUploadingLogs: Boolean = false,
    val logUploadMessage: String? = null,
    val isAndroidUpdateAvailable: Boolean = false,
    val androidUpdate: AndroidUpdateUiState = AndroidUpdateUiState(),
    val settingsPreparedState: SettingsPreparedState = SettingsPreparedState(),
    val isUploadingAvatar: Boolean = false,
    val avatarUploadMessage: String? = null,
    val pendingAvatarCropUri: String? = null,
) {
    val currentDestination: AppDestination
        get() = screenStack.lastOrNull() ?: AppDestination.SPLASH
}
