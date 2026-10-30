package com.noki.vpn.data

import com.noki.vpn.BuildConfig

object AppDiagnosticLogPolicy {
    fun shouldStoreAppLog(
        settings: AdvancedSettings,
        diagnosticBuild: Boolean = BuildConfig.DIAGNOSTIC_LOGGING,
    ): Boolean = diagnosticBuild || settings.connectionLogsEnabled || settings.errorLogsEnabled

    fun shouldUploadAutomatically(settings: AdvancedSettings): Boolean {
        return (settings.connectionLogsEnabled || settings.errorLogsEnabled) &&
            settings.anonymousLogsEnabled
    }
}
