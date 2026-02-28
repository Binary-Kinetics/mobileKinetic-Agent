package com.mobilekinetic.agent.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.mobilekinetic.agent.data.db.MobileKineticDatabase
import com.mobilekinetic.agent.data.db.DatabaseKeyManager
import com.mobilekinetic.agent.data.db.dao.ConversationDao
import com.mobilekinetic.agent.data.db.dao.CredentialDao
import com.mobilekinetic.agent.data.db.dao.RagDao
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.db.dao.BlacklistDao
import com.mobilekinetic.agent.data.db.dao.MemoryFactDao
import com.mobilekinetic.agent.data.db.dao.SessionSummaryDao
import com.mobilekinetic.agent.data.db.dao.VaultDao
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.preferences.createSettingsDataStore
import com.mobilekinetic.agent.data.settings.BackupSettingsRepository
import com.mobilekinetic.agent.data.settings.InjectionSettingsRepository
import com.mobilekinetic.agent.data.gemma.GemmaModelDownloader
import com.mobilekinetic.agent.data.gemma.GemmaModelManager
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import com.mobilekinetic.agent.data.rag.DualEmbeddingProvider
import com.mobilekinetic.agent.data.rag.EmbeddingModel
import com.mobilekinetic.agent.data.rag.GemmaEmbeddingProvider
import com.mobilekinetic.agent.data.memory.SessionMemoryRepository
import com.mobilekinetic.agent.data.rag.RagHttpServer
import com.mobilekinetic.agent.data.rag.RagRepository
import com.mobilekinetic.agent.data.rag.ToolMemory
import com.mobilekinetic.agent.data.chat.ConversationRepository
import com.mobilekinetic.agent.data.vault.CredentialVault
import com.mobilekinetic.agent.data.vault.VaultHttpServer
import com.mobilekinetic.agent.device.api.DeviceApiServer
import com.mobilekinetic.agent.privacy.PrivacyGate
import com.mobilekinetic.agent.privacy.PrivacyGateRuleSync
import com.mobilekinetic.agent.security.CredentialGatekeeper
import com.mobilekinetic.agent.security.CredentialVaultKeyManager
import com.mobilekinetic.agent.security.VaultSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return createSettingsDataStore(context)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository {
        return SettingsRepository(dataStore)
    }

    @Provides
    @Singleton
    fun provideDatabaseKeyManager(@ApplicationContext context: Context): DatabaseKeyManager {
        return DatabaseKeyManager(context)
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager
    ): MobileKineticDatabase {
        return MobileKineticDatabase.getInstance(context, keyManager.getPassphrase())
    }

    @Provides
    @Singleton
    fun provideConversationDao(db: MobileKineticDatabase): ConversationDao {
        return db.conversationDao()
    }

    @Provides
    @Singleton
    fun provideConversationRepository(conversationDao: ConversationDao): ConversationRepository {
        return ConversationRepository(conversationDao)
    }

    @Provides
    @Singleton
    fun provideCredentialDao(db: MobileKineticDatabase): CredentialDao {
        return db.credentialDao()
    }

    @Provides
    @Singleton
    fun provideRagDao(db: MobileKineticDatabase): RagDao {
        return db.ragDao()
    }

    @Provides
    @Singleton
    fun provideToolDao(db: MobileKineticDatabase): ToolDao {
        return db.toolDao()
    }

    @Provides
    @Singleton
    fun provideEmbeddingModel(@ApplicationContext context: Context): EmbeddingModel {
        return EmbeddingModel(context)
    }

    @Provides
    @Singleton
    fun provideRagRepository(ragDao: RagDao, dualEmbeddingProvider: DualEmbeddingProvider): RagRepository {
        return RagRepository(ragDao, dualEmbeddingProvider)
    }

    @Provides
    @Singleton
    fun provideToolMemory(toolDao: ToolDao, ragRepository: RagRepository): ToolMemory {
        return ToolMemory(toolDao, ragRepository)
    }

    @Provides
    @Singleton
    fun provideRagHttpServer(ragRepository: RagRepository, sessionMemoryRepository: SessionMemoryRepository): RagHttpServer {
        return RagHttpServer(ragRepository, sessionMemoryRepository)
    }

    @Provides
    @Singleton
    fun provideDeviceApiServer(
        @ApplicationContext context: Context,
        privacyGate: PrivacyGate,
        gemmaTextGenerator: GemmaTextGenerator,
        gemmaModelManager: GemmaModelManager
    ): DeviceApiServer {
        return DeviceApiServer(context, privacyGate, gemmaTextGenerator, gemmaModelManager)
    }

    @Provides
    @Singleton
    fun provideVaultDao(db: MobileKineticDatabase): VaultDao = db.vaultDao()

    @Provides
    @Singleton
    fun provideCredentialVault(vaultDao: VaultDao, keyManager: CredentialVaultKeyManager): CredentialVault =
        CredentialVault(vaultDao, keyManager)

    @Provides
    @Singleton
    fun provideVaultHttpServer(vault: CredentialVault, gatekeeper: CredentialGatekeeper, session: VaultSession): VaultHttpServer =
        VaultHttpServer(vault, gatekeeper, session)

    @Provides
    @Singleton
    fun provideBlacklistDao(db: MobileKineticDatabase): BlacklistDao = db.blacklistDao()

    @Provides
    @Singleton
    fun provideSessionSummaryDao(db: MobileKineticDatabase): SessionSummaryDao = db.sessionSummaryDao()

    @Provides
    @Singleton
    fun provideMemoryFactDao(db: MobileKineticDatabase): MemoryFactDao = db.memoryFactDao()

    @Provides
    @Singleton
    fun providePrivacyGateRuleSync(blacklistDao: BlacklistDao, privacyGate: PrivacyGate): PrivacyGateRuleSync =
        PrivacyGateRuleSync(blacklistDao, privacyGate)

    @Provides
    @Singleton
    fun provideInjectionSettingsRepository(dataStore: DataStore<Preferences>): InjectionSettingsRepository {
        return InjectionSettingsRepository(dataStore)
    }

    @Provides
    @Singleton
    fun provideBackupSettingsRepository(dataStore: DataStore<Preferences>): BackupSettingsRepository {
        return BackupSettingsRepository(dataStore)
    }
}
