package com.mobilekinetic.agent.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the result of an AES-GCM encryption operation.
 * [ciphertext] is the encrypted payload (includes the GCM authentication tag).
 * [iv] is the initialisation vector that was used and must be stored alongside
 * the ciphertext so decryption can reproduce the same GCM parameters.
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedData) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

/**
 * Manages a biometric-gated AES-256-GCM key stored in the Android Keystore.
 *
 * Unlike [com.mobilekinetic.agent.data.db.DatabaseKeyManager] which uses a freely-accessible
 * Keystore key, this manager sets [KeyGenParameterSpec.Builder.setUserAuthenticationRequired]
 * to true so that the key can only be used after the user has authenticated via
 * [androidx.biometric.BiometricPrompt]. Callers obtain a [Cipher] from this class, wrap
 * it inside a [androidx.biometric.BiometricPrompt.CryptoObject], and only after successful
 * authentication does the Cipher become usable.
 */
@Singleton
class VaultKeyManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_ALIAS = "mobilekinetic_vault_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val KEY_SIZE = 256
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    // ------------------------------------------------------------------
    // Key management
    // ------------------------------------------------------------------

    /**
     * Creates a new AES-256 key in the Android Keystore under [KEYSTORE_ALIAS].
     * The key is configured to:
     *  - require user authentication (biometric) before every use
     *  - only support ENCRYPT and DECRYPT purposes
     *  - use GCM block mode with no padding
     *
     * If a key with the same alias already exists it will be overwritten.
     */
    fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    // ------------------------------------------------------------------
    // Cipher factories
    // ------------------------------------------------------------------

    /**
     * Returns a [Cipher] initialised for encryption.
     *
     * Because the underlying key has userAuthenticationRequired = true, the
     * returned Cipher must be wrapped in a
     * [androidx.biometric.BiometricPrompt.CryptoObject] and authenticated before
     * [encrypt] is called.
     *
     * If no key exists yet, [generateKey] is called automatically.
     *
     * @throws android.security.keystore.UserNotAuthenticatedException if used
     *         without prior biometric authentication.
     */
    fun getEncryptCipher(): Cipher {
        ensureKeyExists()
        val key = getKey()
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * Returns a [Cipher] initialised for decryption using the supplied [iv].
     *
     * The same biometric-authentication requirement as [getEncryptCipher] applies.
     *
     * @param iv The initialisation vector that was produced during encryption
     *           (available from [EncryptedData.iv]).
     */
    fun getDecryptCipher(iv: ByteArray): Cipher {
        ensureKeyExists()
        val key = getKey()
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, spec)
        }
    }

    // ------------------------------------------------------------------
    // Encrypt / Decrypt
    // ------------------------------------------------------------------

    /**
     * Encrypts [plaintext] using the provided (already-authenticated) [cipher].
     *
     * @param cipher A [Cipher] obtained from [getEncryptCipher] after it has
     *               been authenticated via BiometricPrompt.
     * @param plaintext The raw bytes to encrypt.
     * @return An [EncryptedData] bundle containing the ciphertext and the IV.
     */
    fun encrypt(cipher: Cipher, plaintext: ByteArray): EncryptedData {
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedData(
            ciphertext = ciphertext,
            iv = cipher.iv
        )
    }

    /**
     * Decrypts [encryptedData] using the provided (already-authenticated) [cipher].
     *
     * @param cipher A [Cipher] obtained from [getDecryptCipher] after it has
     *               been authenticated via BiometricPrompt.
     * @param encryptedData The [EncryptedData] bundle produced by [encrypt].
     * @return The original plaintext bytes.
     */
    fun decrypt(cipher: Cipher, encryptedData: EncryptedData): ByteArray {
        return cipher.doFinal(encryptedData.ciphertext)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun getKey(): SecretKey {
        return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateKey()
        }
    }
}
