package com.eggbusinessmanager.app.feature.auth.biometric

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Shows the system biometric prompt bound to a Keystore [Cipher] (research R8: BIOMETRIC_STRONG with a
 * CryptoObject). The password is always the fallback, offered as the prompt's negative button.
 */
class BiometricPromptLauncher(private val activity: FragmentActivity?) {

    /** True when a strong biometric (fingerprint or face) is enrolled and usable. */
    fun isAvailable(): Boolean = activity != null &&
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** The authenticated cipher, or null if the owner cancelled or biometrics failed. */
    suspend fun authenticate(cipher: Cipher, title: String, subtitle: String, negativeText: String): Cipher? {
        val host = activity ?: return null
        return suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }
                // onAuthenticationFailed: a non-matching finger; the system prompt lets the owner retry.
            }
            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeText)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

@Composable
fun rememberBiometricPromptLauncher(): BiometricPromptLauncher {
    val activity = LocalActivity.current as? FragmentActivity
    return remember(activity) { BiometricPromptLauncher(activity) }
}
