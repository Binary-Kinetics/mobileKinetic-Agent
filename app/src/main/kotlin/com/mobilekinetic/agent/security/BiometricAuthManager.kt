package com.mobilekinetic.agent.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [BiometricPrompt] with [BiometricPrompt.CryptoObject] support so that
 * callers can perform biometric-gated encryption and decryption through the
 * [VaultKeyManager].
 *
 * Usage flow:
 * 1. Call [canAuthenticate] to verify biometric hardware is available.
 * 2. Call [authenticateForEncrypt] or [authenticateForDecrypt] with callbacks.
 * 3. On success the callback receives an authenticated [Cipher] that can be
 *    passed to [VaultKeyManager.encrypt] or [VaultKeyManager.decrypt].
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    private val vaultKeyManager: VaultKeyManager
) {

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateForEncrypt(
        activity: FragmentActivity,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit
    ) {
        val cipher: Cipher
        try {
            cipher = vaultKeyManager.getEncryptCipher()
        } catch (e: Exception) {
            onError("Failed to initialise encrypt cipher: " + (e.message ?: ""))
            return
        }

        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        showPrompt(activity, cryptoObject, onSuccess, onError)
    }

    fun authenticateForDecrypt(
        activity: FragmentActivity,
        iv: ByteArray,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit
    ) {
        val cipher: Cipher
        try {
            cipher = vaultKeyManager.getDecryptCipher(iv)
        } catch (e: Exception) {
            onError("Failed to initialise decrypt cipher: " + (e.message ?: ""))
            return
        }

        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        showPrompt(activity, cryptoObject, onSuccess, onError)
    }

    private fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle("Vault Authentication")
            .setSubtitle("Authenticate to access secure vault")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    }

    private fun showPrompt(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) {
                    onSuccess(authenticatedCipher)
                } else {
                    onError("Authentication succeeded but cipher was unavailable.")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Called on each failed attempt; the prompt stays visible so we
                // do not forward this to onError. The system will eventually
                // call onAuthenticationError if too many attempts fail.
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(buildPromptInfo(), cryptoObject)
    }
}
