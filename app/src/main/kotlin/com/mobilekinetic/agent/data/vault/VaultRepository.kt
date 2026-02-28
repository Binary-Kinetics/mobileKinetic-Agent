package com.mobilekinetic.agent.data.vault

import android.util.Base64
import com.mobilekinetic.agent.data.db.dao.CredentialDao
import com.mobilekinetic.agent.data.db.entity.CredentialEntity
import com.mobilekinetic.agent.security.EncryptedData
import com.mobilekinetic.agent.security.VaultKeyManager
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates between biometric-authenticated [Cipher] instances and the
 * [CredentialDao] persistence layer.
 *
 * Callers obtain an authenticated [Cipher] from [com.mobilekinetic.agent.security.BiometricAuthManager]
 * and pass it to the save / update / decrypt methods here. This class never
 * touches the biometric prompt itself -- it only consumes the already-unlocked
 * cipher for encryption and decryption via [VaultKeyManager].
 *
 * The encrypted password and its initialisation vector are packed into a single
 * Base64-encoded string (separated by [IV_SEPARATOR]) and stored in
 * [CredentialEntity.encryptedValue].
 */
@Singleton
class VaultRepository @Inject constructor(
    private val credentialDao: CredentialDao,
    private val vaultKeyManager: VaultKeyManager
) {

    companion object {
        /**
         * Delimiter used to separate the Base64-encoded ciphertext from the
         * Base64-encoded IV inside [CredentialEntity.encryptedValue].
         */
        private const val IV_SEPARATOR = ":"
    }

    // -- Read operations (pass-through to DAO) --------------------------------

    /**
     * Observes all stored credentials, ordered by most recently updated first.
     */
    fun getAllCredentials(): Flow<List<CredentialEntity>> =
        credentialDao.getAllCredentials()

    /**
     * Returns a single credential by its [id], or null if not found.
     */
    suspend fun getCredentialById(id: String): CredentialEntity? =
        credentialDao.getCredential(id)

    /**
     * Observes credentials filtered by [category].
     */
    fun getCredentialsByCategory(category: String): Flow<List<CredentialEntity>> =
        credentialDao.getCredentialsByCategory(category)

    // -- Write operations (encrypt then persist) -------------------------------

    /**
     * Encrypts [password] with the biometric-authenticated [cipher] and persists
     * a new credential.
     *
     * @param cipher      An authenticated encrypt-mode [Cipher] obtained from
     *                    BiometricAuthManager.authenticateForEncrypt.
     * @param serviceName Display name for the credential (maps to CredentialEntity.name).
     * @param username    Username / login identifier (stored in CredentialEntity.category).
     * @param password    Plaintext password to encrypt and store.
     * @return The generated credential ID.
     */
    suspend fun saveCredential(
        cipher: Cipher,
        serviceName: String,
        username: String,
        password: String
    ): String {
        val encryptedData = vaultKeyManager.encrypt(cipher, password.toByteArray())
        val encodedValue = packEncryptedData(encryptedData)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val entity = CredentialEntity(
            id = id,
            name = serviceName,
            category = username,
            encryptedValue = encodedValue,
            notes = "",
            createdAt = now,
            updatedAt = now
        )
        credentialDao.insertCredential(entity)
        return id
    }

    /**
     * Re-encrypts [password] with the biometric-authenticated [cipher] and
     * updates an existing credential identified by [id].
     *
     * @param cipher      An authenticated encrypt-mode [Cipher].
     * @param id          The ID of the credential to update.
     * @param serviceName Updated display name.
     * @param username    Updated username.
     * @param password    New plaintext password to encrypt.
     */
    suspend fun updateCredential(
        cipher: Cipher,
        id: String,
        serviceName: String,
        username: String,
        password: String
    ) {
        val encryptedData = vaultKeyManager.encrypt(cipher, password.toByteArray())
        val encodedValue = packEncryptedData(encryptedData)

        val existing = credentialDao.getCredential(id)
        val now = System.currentTimeMillis()

        val entity = CredentialEntity(
            id = id,
            name = serviceName,
            category = username,
            encryptedValue = encodedValue,
            notes = existing?.notes.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        credentialDao.updateCredential(entity)
    }

    // -- Decrypt operation -----------------------------------------------------

    /**
     * Decrypts the password stored in [entity] using the biometric-authenticated
     * [cipher].
     *
     * @param cipher An authenticated decrypt-mode [Cipher] obtained from
     *               BiometricAuthManager.authenticateForDecrypt (initialised
     *               with the same IV that was used during encryption).
     * @param entity The credential whose CredentialEntity.encryptedValue will
     *               be decrypted.
     * @return The plaintext password.
     */
    fun decryptPassword(cipher: Cipher, entity: CredentialEntity): String {
        val encryptedData = unpackEncryptedData(entity.encryptedValue)
        val decryptedBytes = vaultKeyManager.decrypt(cipher, encryptedData)
        return String(decryptedBytes)
    }

    // -- Delete operation ------------------------------------------------------

    /**
     * Deletes the credential with the given [id].
     */
    suspend fun deleteCredential(id: String) {
        val entity = credentialDao.getCredential(id) ?: return
        credentialDao.deleteCredential(entity)
    }

    // -- Helpers: pack / unpack EncryptedData into a single String -------------

    /**
     * Extracts the IV from a stored CredentialEntity.encryptedValue so it can
     * be passed to BiometricAuthManager.authenticateForDecrypt before calling
     * [decryptPassword].
     */
    fun getIvFromCredential(entity: CredentialEntity): ByteArray =
        unpackEncryptedData(entity.encryptedValue).iv

    /**
     * Encodes [EncryptedData] (ciphertext + IV) into a single Base64 string
     * using [IV_SEPARATOR] as the delimiter.
     */
    private fun packEncryptedData(data: EncryptedData): String {
        val ciphertextB64 = Base64.encodeToString(data.ciphertext, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(data.iv, Base64.NO_WRAP)
        return "$ciphertextB64$IV_SEPARATOR$ivB64"
    }

    /**
     * Decodes a packed string back into [EncryptedData].
     */
    private fun unpackEncryptedData(packed: String): EncryptedData {
        val parts = packed.split(IV_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid encrypted value format" }
        val ciphertext = Base64.decode(parts[0], Base64.NO_WRAP)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        return EncryptedData(ciphertext = ciphertext, iv = iv)
    }
}
