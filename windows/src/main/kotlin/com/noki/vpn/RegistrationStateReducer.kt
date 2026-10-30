package com.noki.vpn

import com.noki.vpn.data.AppLanguage

object RegistrationStateReducer {
    sealed interface CodeRequestTransition {
        data object Noop : CodeRequestTransition
        data class UpdateState(val state: AppUiState) : CodeRequestTransition
        data class Send(
            val state: AppUiState,
            val form: RegistrationFormState,
        ) : CodeRequestTransition
    }

    sealed interface SubmitTransition {
        data class UpdateState(val state: AppUiState) : SubmitTransition
        data class Register(
            val state: AppUiState,
            val form: RegistrationFormState,
        ) : SubmitTransition
    }

    fun usernameChanged(
        current: AppUiState,
        value: String,
        language: AppLanguage,
    ): AppUiState {
        val filtered = sanitizeUsernameInput(value)
        val error = if (filtered != value) {
            tr(
                language,
                "Используйте латиницу, цифры, _, - или .",
                "Use Latin letters, digits, _, - or .",
            )
        } else {
            null
        }
        return current.copy(
            registrationForm = current.registrationForm.copy(
                username = filtered,
                isLoading = false,
                error = error,
            ),
        )
    }

    fun sanitizeUsernameInput(value: String): String =
        value.filter(::isSafeUsernameChar).take(64)

    fun emailChanged(
        current: AppUiState,
        value: String,
    ): AppUiState {
        return current.copy(
            registrationForm = current.registrationForm.copy(
                email = value,
                verificationCode = "",
                codeSent = false,
                isLoading = false,
                isCodeSending = false,
                codeCooldownSeconds = 0,
                error = null,
            ),
        )
    }

    fun verificationCodeChanged(
        current: AppUiState,
        value: String,
    ): AppUiState {
        val code = value.filter { it in '0'..'9' }.take(12)
        return current.copy(
            registrationForm = current.registrationForm.copy(
                verificationCode = code,
                isLoading = false,
                error = null,
            ),
        )
    }

    fun passwordChanged(
        current: AppUiState,
        value: String,
    ): AppUiState {
        return current.copy(registrationForm = current.registrationForm.copy(password = value, isLoading = false, error = null))
    }

    fun passwordRepeatChanged(
        current: AppUiState,
        value: String,
    ): AppUiState {
        return current.copy(registrationForm = current.registrationForm.copy(passwordRepeat = value, isLoading = false, error = null))
    }

    fun requestCode(
        current: AppUiState,
        language: AppLanguage,
        isValidEmail: (String) -> Boolean,
    ): CodeRequestTransition {
        val form = current.registrationForm
        if (!isValidEmail(form.email)) {
            return CodeRequestTransition.UpdateState(
                current.copy(
                    registrationForm = form.copy(
                        error = tr(language, "Введите корректный e-mail", "Enter a valid email"),
                    ),
                ),
            )
        }
        if (current.authStep == AuthStep.REGISTRATION_EMAIL && form.codeSent) {
            return CodeRequestTransition.UpdateState(
                current.copy(
                    authStep = AuthStep.REGISTRATION_CODE,
                    registrationForm = form.copy(error = null),
                    inlineMessage = null,
                ),
            )
        }
        if (form.isCodeSending) {
            return CodeRequestTransition.UpdateState(
                current.copy(
                    registrationForm = form.copy(
                        error = tr(language, "Код уже отправляется", "The code is already being sent"),
                    ),
                ),
            )
        }
        if (form.codeCooldownSeconds > 0) {
            return CodeRequestTransition.UpdateState(
                current.copy(
                    registrationForm = form.copy(
                        error = tr(
                            language,
                            "Код уже отправлен. Повторите через ${form.codeCooldownSeconds} с",
                            "The code has already been sent. Try again in ${form.codeCooldownSeconds}s",
                        ),
                    ),
                ),
            )
        }
        return CodeRequestTransition.Send(
            state = current.copy(
                registrationForm = form.copy(isCodeSending = true, error = null),
                inlineMessage = null,
            ),
            form = form,
        )
    }

    fun codeRequestSent(
        current: AppUiState,
        cooldownSeconds: Int,
        language: AppLanguage,
    ): AppUiState {
        return current.copy(
            registrationForm = current.registrationForm.copy(
                verificationCode = "",
                isCodeSending = false,
                codeSent = true,
                codeCooldownSeconds = cooldownSeconds.coerceAtLeast(0),
                isLoading = false,
                error = null,
            ),
            inlineMessage = tr(language, "Код отправлен на почту", "The code was sent to your email"),
        )
    }

    fun codeRequestFailed(
        current: AppUiState,
        error: String,
    ): AppUiState {
        return current.copy(
            registrationForm = current.registrationForm.copy(
                isCodeSending = false,
                error = error,
            ),
        )
    }

    fun submit(
        current: AppUiState,
        language: AppLanguage,
        isValidEmail: (String) -> Boolean,
    ): SubmitTransition {
        val form = current.registrationForm
        val error = validationError(form, language, isValidEmail)
        if (error != null) {
            return SubmitTransition.UpdateState(
                current.copy(registrationForm = form.copy(error = error)),
            )
        }
        return SubmitTransition.Register(
            state = current.copy(
                registrationForm = form.copy(isLoading = true, error = null),
                inlineMessage = null,
            ),
            form = form,
        )
    }

    fun registrationFailed(
        current: AppUiState,
        error: String,
    ): AppUiState {
        return current.copy(
            registrationForm = current.registrationForm.copy(
                isLoading = false,
                error = error,
            ),
        )
    }

    fun usernameValidationError(
        username: String,
        language: AppLanguage,
    ): String? {
        return when {
            username.isBlank() -> tr(language, "Укажите username", "Choose a username")
            username.length < 3 -> tr(language, "Username должен быть не короче 3 символов", "Username must be at least 3 characters")
            !username.all(::isSafeUsernameChar) -> tr(
                language,
                "Используйте латиницу, цифры, _, - или .",
                "Use Latin letters, digits, _, - or .",
            )
            !username.first().isAsciiLetterOrDigit() || !username.last().isAsciiLetterOrDigit() -> tr(
                language,
                "Username должен начинаться и заканчиваться буквой или цифрой",
                "Username must start and end with a letter or digit",
            )
            else -> null
        }
    }

    fun verificationCodeValidationError(
        verificationCode: String,
        language: AppLanguage,
    ): String? {
        val code = verificationCode.trim()
        return when {
            code.isBlank() -> tr(language, "Введите код из письма", "Enter the email code")
            code.length < 4 -> tr(
                language,
                "Код должен быть не короче 4 символов",
                "Code must be at least 4 characters",
            )
            code.length > 12 -> tr(
                language,
                "Код должен быть не длиннее 12 символов",
                "Code must be at most 12 characters",
            )
            else -> null
        }
    }

    fun validationError(
        form: RegistrationFormState,
        language: AppLanguage,
        isValidEmail: (String) -> Boolean,
    ): String? {
        val usernameError = usernameValidationError(form.username, language)
        val verificationCodeError = verificationCodeValidationError(form.verificationCode, language)
        return when {
            usernameError != null -> usernameError
            form.email.isBlank() -> tr(language, "Введите e-mail", "Enter your email")
            !isValidEmail(form.email) -> tr(language, "Введите корректный e-mail", "Enter a valid email")
            verificationCodeError != null -> verificationCodeError
            form.password.length < 8 -> tr(language, "Пароль должен быть не короче 8 символов", "Password must be at least 8 characters")
            form.password != form.passwordRepeat -> tr(language, "Пароли не совпадают", "Passwords do not match")
            else -> null
        }
    }

    private fun isSafeUsernameChar(char: Char): Boolean {
        return char.isAsciiLetterOrDigit() || char == '_' || char == '-' || char == '.'
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
