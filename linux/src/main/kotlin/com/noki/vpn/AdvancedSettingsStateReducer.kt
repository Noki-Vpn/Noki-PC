package com.noki.vpn
object AdvancedSettingsStateReducer {
    fun setAllLogging(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(
                connectionLogsEnabled = enabled,
                errorLogsEnabled = enabled,
            ),
        )
    }
    fun setAnonymousLogs(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(anonymousLogsEnabled = enabled),
        )
    }
}
