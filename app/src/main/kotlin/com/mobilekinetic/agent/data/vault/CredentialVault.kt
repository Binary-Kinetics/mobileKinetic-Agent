package com.mobilekinetic.agent.data.vault

import com.mobilekinetic.agent.data.db.dao.VaultDao
import com.mobilekinetic.agent.data.db.entity.VaultEntryEntity
import com.mobilekinetic.agent.security.CredentialVaultKeyManager
import com.mobilekinetic.agent.security.EncryptedData
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialVault @Inject constructor(
    private val vaultDao: VaultDao,
    private val keyManager: CredentialVaultKeyManager
) {
    suspend fun store(name: String, value: String, description: String? = null) {
        val encrypted = keyManager.encrypt(value.toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        val existing = vaultDao.getByName(name)
        val entity = VaultEntryEntity(
            id = existing?.id ?: 0,
            name = name,
            encryptedValue = encrypted.ciphertext,
            iv = encrypted.iv,
            description = description,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        vaultDao.upsert(entity)
    }

    suspend fun get(name: String): String? {
        val entity = vaultDao.getByName(name) ?: return null
        val decrypted = keyManager.decrypt(EncryptedData(entity.encryptedValue, entity.iv))
        return String(decrypted, Charsets.UTF_8)
    }

    suspend fun delete(name: String): Boolean {
        val exists = vaultDao.getByName(name) != null
        if (exists) vaultDao.deleteByName(name)
        return exists
    }

    suspend fun list(): List<String> = vaultDao.getAllNames().first()

    suspend fun exists(name: String): Boolean = vaultDao.getByName(name) != null

    suspend fun count(): Int = vaultDao.getCount()

    suspend fun listWithMeta(): List<Pair<String, String?>> {
        return vaultDao.getAllSync().map { it.name to it.description }
    }

    suspend fun getEntry(name: String): VaultEntryEntity? {
        return vaultDao.getByName(name)
    }
}
