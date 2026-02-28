package com.mobilekinetic.agent.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseKeyManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "mobilekinetic_db_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_NAME = "mobilekinetic_db_prefs"
        private const val PREFS_KEY_PASSPHRASE = "encrypted_passphrase"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PASSPHRASE_LENGTH = 32
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    fun getPassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREFS_KEY_PASSPHRASE, null)

        return if (stored != null) {
            decryptPassphrase(stored)
        } else {
            val passphrase = generateRandomPassphrase()
            val encrypted = encryptPassphrase(passphrase)
            prefs.edit().putString(PREFS_KEY_PASSPHRASE, encrypted).apply()
            passphrase
        }
    }

    private fun generateRandomPassphrase(): ByteArray {
        val bytes = ByteArray(PASSPHRASE_LENGTH)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun getOrCreateKey(): SecretKey {
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null)
        if (entry is KeyStore.SecretKeyEntry) {
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encryptPassphrase(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(passphrase)
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptPassphrase(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return cipher.doFinal(encrypted)
    }
}
