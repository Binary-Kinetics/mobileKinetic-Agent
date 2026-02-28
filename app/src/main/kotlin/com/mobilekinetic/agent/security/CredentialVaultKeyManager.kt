package com.mobilekinetic.agent.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialVaultKeyManager @Inject constructor() {

    companion object {
        private const val TAG = "VaultKeyManager"
        private const val KEY_ALIAS = "mobilekinetic_credential_vault_key_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    fun encrypt(plaintext: ByteArray): EncryptedData {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedData(ciphertext = ciphertext, iv = cipher.iv)
    }

    fun decrypt(encrypted: EncryptedData): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, encrypted.iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            cipher.doFinal(encrypted.ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.e(TAG, "Key permanently invalidated — deleting and re-keying", e)
            keyStore.deleteEntry(KEY_ALIAS)
            throw e  // Caller must handle: clear vault, re-store credentials
        }
    }
}
