package com.noki.vpn

import com.noki.vpn.data.AppLanguage

sealed interface AccountSecurityActionState {
    data class Email(
        val email: String = "",
        val verifyingCurrentEmail: Boolean = false,
        val currentEmail: String? = null,
        val currentVerificationCode: String? = null,
        val verificationCode: String = "",
        val codeSent: Boolean = false,
        val cooldownSeconds: Int = 0,
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : AccountSecurityActionState

    data class Password(
        val newPassword: String = "",
        val confirmation: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : AccountSecurityActionState

    data class Username(
        val username: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
    ) : AccountSecurityActionState
}

data class AccountSecurityUiState(
    val action: AccountSecurityActionState? = null,
)

internal object AccountSecurityStateReducer {
    fun validateEmail(email: String, language: AppLanguage): String? {
        val clean = email.trim()
        val valid = clean.length in 3..320 &&
            clean.count { it == '@' } == 1 &&
            clean.substringBefore('@').isNotBlank() &&
            clean.substringAfter('@').contains('.') &&
            !clean.endsWith("@a.noki", ignoreCase = true)
        return if (valid) null else tr(language, "Введите корректный e-mail", "Enter a valid email")
    }

    fun validatePassword(
        newPassword: String,
        confirmation: String,
        language: AppLanguage,
    ): String? = when {
        newPassword.length < 8 ->
            tr(language, "Пароль должен быть не короче 8 символов", "Password must be at least 8 characters")
        newPassword != confirmation ->
            tr(language, "Пароли не совпадают", "Passwords do not match")
        else -> null
    }

    fun validateUsername(username: String, language: AppLanguage): String? =
        RegistrationStateReducer.usernameValidationError(username.trim(), language)

    fun dismiss(current: AccountSecurityUiState): AccountSecurityUiState =
        current.copy(action = null)

    fun email(current: AccountSecurityUiState, email: String, hasRealEmail: Boolean = false): AccountSecurityUiState =
        current.copy(action = AccountSecurityActionState.Email(email = email, verifyingCurrentEmail = hasRealEmail))

    fun password(current: AccountSecurityUiState): AccountSecurityUiState =
        current.copy(action = AccountSecurityActionState.Password())

    fun username(current: AccountSecurityUiState, username: String): AccountSecurityUiState =
        current.copy(action = AccountSecurityActionState.Username(username = username))

    fun back(current: AccountSecurityUiState): AccountSecurityUiState = when (val action = current.action) {
        is AccountSecurityActionState.Email -> {
            if (action.codeSent) {
                current.copy(
                    action = action.copy(
                        verificationCode = "",
                        codeSent = false,
                        cooldownSeconds = 0,
                        isLoading = false,
                        error = null,
                    ),
                )
            } else if (action.currentEmail != null) {
                current.copy(action = AccountSecurityActionState.Email(verifyingCurrentEmail = true))
            } else {
                dismiss(current)
            }
        }

        is AccountSecurityActionState.Password -> dismiss(current)

        is AccountSecurityActionState.Username,
        null -> dismiss(current)
    }

    private fun tr(language: AppLanguage, russian: String, english: String): String =
        if (language == AppLanguage.RU) russian else english
}
