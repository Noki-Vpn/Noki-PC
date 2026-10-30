package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class AccountSecurityUiWorkflow(
    private val scope: CoroutineScope,
    private val currentState: () -> AppUiState,
    private val publishState: (AppUiState) -> Unit,
    private val isInvitedDevice: () -> Boolean,
    private val currentAuthAttempt: () -> AuthSessionAttempt?,
    private val sendCurrentEmailCode: suspend (AuthSessionAttempt, String) -> Int,
    private val verifyCurrentEmailCode: suspend (AuthSessionAttempt, String, String) -> Unit,
    private val sendEmailCode: suspend (AuthSessionAttempt, String, String?, String?) -> Int,
    private val changeEmail: suspend (AuthSessionAttempt, String, String, String?, String?) -> BackendUser,
    private val changePassword: suspend (AuthSessionAttempt, String) -> BackendUser,
    private val changeUsername: suspend (AuthSessionAttempt, String) -> BackendUser,
    private val applyUser: (BackendUser, Boolean) -> Unit,
) {
    private val workflow = AccountSecurityWorkflowCoordinator()
    private var operationJob: Job? = null
    private var emailCooldownJob: Job? = null

    fun invalidate() {
        workflow.invalidate()
        val previousOperation = operationJob
        operationJob = null
        previousOperation?.cancel()
        emailCooldownJob?.cancel()
        emailCooldownJob = null
    }

    fun updateEmail(value: String) {
        val action = currentState().accountSecurityState.action as? AccountSecurityActionState.Email ?: return
        if (action.isLoading || action.codeSent) return
        setAction(action.copy(email = value.take(320), error = null))
    }

    fun updateEmailCode(value: String) {
        val action = currentState().accountSecurityState.action as? AccountSecurityActionState.Email ?: return
        if (action.isLoading) return
        setAction(action.copy(verificationCode = value.take(12), error = null))
    }

    fun requestEmailCode() {
        val state = currentState()
        val action = state.accountSecurityState.action as? AccountSecurityActionState.Email ?: return
        if (operationJob != null || action.isLoading || action.cooldownSeconds > 0 || isInvitedDevice()) return
        val language = state.personalizationSettings.language
        val email = action.email.trim()
        val validationError = if (action.verifyingCurrentEmail && !email.equals(state.userProfile.email, ignoreCase = true)) {
            tr(language, "Введите текущую почту аккаунта", "Enter your current account email")
        } else AccountSecurityStateReducer.validateEmail(email, language)
        if (validationError != null) {
            setAction(action.copy(error = validationError))
            return
        }
        val attempt = authenticatedAttempt(action, language) ?: return
        val owner = workflow.begin()
        emailCooldownJob?.cancel()
        setAction(action.copy(email = email, isLoading = true, error = null))
        launchOperation(
            owner = owner,
            original = action,
            language = language,
            request = {
                if (action.verifyingCurrentEmail) sendCurrentEmailCode(attempt, email)
                else sendEmailCode(attempt, email, action.currentEmail, action.currentVerificationCode)
            },
            onSuccess = { cooldown ->
                val latest = currentState().accountSecurityState.action as? AccountSecurityActionState.Email
                    ?: return@launchOperation
                setAction(
                    latest.copy(
                        codeSent = true,
                        cooldownSeconds = cooldown,
                        isLoading = false,
                        error = null,
                    ),
                )
                startEmailCooldown(owner, cooldown)
            },
        )
    }

    fun submitEmail() {
        val state = currentState()
        val action = state.accountSecurityState.action as? AccountSecurityActionState.Email ?: return
        if (operationJob != null || action.isLoading || !action.codeSent || isInvitedDevice()) return
        val language = state.personalizationSettings.language
        val email = action.email.trim()
        val validationError = if (action.verifyingCurrentEmail && !email.equals(state.userProfile.email, ignoreCase = true)) {
            tr(language, "Введите текущую почту аккаунта", "Enter your current account email")
        } else AccountSecurityStateReducer.validateEmail(email, language)
        if (validationError != null) {
            setAction(action.copy(error = validationError))
            return
        }
        val code = action.verificationCode.trim()
        if (code.length !in 4..12) {
            setAction(action.copy(error = tr(language, "Введите код из письма", "Enter the code from the email")))
            return
        }
        val attempt = authenticatedAttempt(action, language) ?: return
        val owner = workflow.begin()
        emailCooldownJob?.cancel()
        setAction(action.copy(email = email, isLoading = true, error = null))
        launchOperation(
            owner = owner,
            original = action,
            language = language,
            request = {
                if (action.verifyingCurrentEmail) {
                    verifyCurrentEmailCode(attempt, email, code)
                    null
                } else changeEmail(attempt, email, code, action.currentEmail, action.currentVerificationCode)
            },
            onSuccess = { user ->
                if (user != null) applyUser(user, true)
                else setAction(AccountSecurityActionState.Email(currentEmail = email, currentVerificationCode = code))
            },
        )
    }

    fun updateNewPassword(value: String) {
        updatePassword { it.copy(newPassword = value.take(128), error = null) }
    }

    fun updatePasswordConfirmation(value: String) {
        updatePassword { it.copy(confirmation = value.take(128), error = null) }
    }

    fun submitPassword() {
        val state = currentState()
        val action = state.accountSecurityState.action as? AccountSecurityActionState.Password ?: return
        if (operationJob != null || action.isLoading || isInvitedDevice()) return
        val language = state.personalizationSettings.language
        val validationError = AccountSecurityStateReducer.validatePassword(
            newPassword = action.newPassword,
            confirmation = action.confirmation,
            language = language,
        )
        if (validationError != null) {
            setAction(action.copy(error = validationError))
            return
        }
        val attempt = authenticatedAttempt(action, language) ?: return
        val owner = workflow.begin()
        setAction(action.copy(isLoading = true, error = null))
        launchOperation(
            owner = owner,
            original = action,
            language = language,
            request = { changePassword(attempt, action.newPassword) },
            onSuccess = { user -> applyUser(user, true) },
        )
    }

    fun updateUsername(value: String) {
        val action = currentState().accountSecurityState.action as? AccountSecurityActionState.Username ?: return
        if (action.isLoading) return
        setAction(
            action.copy(
                username = RegistrationStateReducer.sanitizeUsernameInput(value),
                error = null,
            ),
        )
    }

    fun submitUsername() {
        val state = currentState()
        val action = state.accountSecurityState.action as? AccountSecurityActionState.Username ?: return
        if (operationJob != null || action.isLoading || isInvitedDevice()) return
        val language = state.personalizationSettings.language
        val username = action.username.trim()
        val validationError = AccountSecurityStateReducer.validateUsername(username, language)
        if (validationError != null) {
            setAction(action.copy(error = validationError))
            return
        }
        val attempt = authenticatedAttempt(action, language) ?: return
        val owner = workflow.begin()
        setAction(action.copy(username = username, isLoading = true, error = null))
        launchOperation(
            owner = owner,
            original = action,
            language = language,
            request = { changeUsername(attempt, username) },
            onSuccess = { user -> applyUser(user, false) },
        )
    }

    private fun authenticatedAttempt(
        original: AccountSecurityActionState,
        language: AppLanguage,
    ): AuthSessionAttempt? = currentAuthAttempt() ?: run {
        setError(original, language, BackendException("auth_required", 401))
        null
    }

    private fun <T> launchOperation(
        owner: Long,
        original: AccountSecurityActionState,
        language: AppLanguage,
        request: suspend () -> T,
        onSuccess: (T) -> Unit,
    ) {
        check(operationJob == null)
        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = request()
                if (operationJob !== launchedJob || !isActive || !workflow.accepts(owner)) return@launch
                onSuccess(result)
            } catch (error: CancellationException) {
                if (operationJob === launchedJob && workflow.accepts(owner)) {
                    finishLoading(original)
                }
                throw error
            } catch (error: Throwable) {
                if (operationJob === launchedJob && isActive && workflow.accepts(owner)) {
                    setError(original, language, error)
                }
            } finally {
                if (operationJob === launchedJob) operationJob = null
            }
        }
        operationJob = launchedJob
        launchedJob.start()
    }

    private fun finishLoading(original: AccountSecurityActionState) {
        val action = when (val latest = currentState().accountSecurityState.action ?: original) {
            is AccountSecurityActionState.Email -> latest.copy(isLoading = false)
            is AccountSecurityActionState.Password -> latest.copy(isLoading = false)
            is AccountSecurityActionState.Username -> latest.copy(isLoading = false)
        }
        setAction(action)
    }

    private fun updatePassword(
        transform: (AccountSecurityActionState.Password) -> AccountSecurityActionState.Password,
    ) {
        val action = currentState().accountSecurityState.action as? AccountSecurityActionState.Password ?: return
        if (action.isLoading) return
        setAction(transform(action))
    }

    private fun setAction(action: AccountSecurityActionState) {
        val state = currentState()
        publishState(
            state.copy(accountSecurityState = state.accountSecurityState.copy(action = action)),
        )
    }

    private fun setError(
        original: AccountSecurityActionState,
        language: AppLanguage,
        error: Throwable,
    ) {
        val message = AppErrorMapper.readableAccountSecurityError(language, error)
        val action = when (val latest = currentState().accountSecurityState.action ?: original) {
            is AccountSecurityActionState.Email -> latest.copy(isLoading = false, error = message)
            is AccountSecurityActionState.Password -> latest.copy(isLoading = false, error = message)
            is AccountSecurityActionState.Username -> latest.copy(isLoading = false, error = message)
        }
        setAction(action)
    }

    private fun startEmailCooldown(owner: Long, initialSeconds: Int) {
        emailCooldownJob?.cancel()
        emailCooldownJob = scope.launch {
            var remaining = initialSeconds.coerceAtLeast(0)
            while (isActive && remaining > 0 && workflow.accepts(owner)) {
                delay(1_000)
                remaining -= 1
                val latest = currentState().accountSecurityState.action as? AccountSecurityActionState.Email
                    ?: break
                if (!latest.codeSent || !workflow.accepts(owner)) break
                setAction(latest.copy(cooldownSeconds = remaining))
            }
        }
    }

    private fun tr(language: AppLanguage, russian: String, english: String): String =
        if (language == AppLanguage.RU) russian else english
}
