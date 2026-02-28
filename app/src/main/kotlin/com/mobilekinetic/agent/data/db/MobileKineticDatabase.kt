package com.mobilekinetic.agent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import java.io.File
import com.mobilekinetic.agent.data.db.dao.ConversationDao
import com.mobilekinetic.agent.data.db.dao.CredentialDao
import com.mobilekinetic.agent.data.db.dao.RagDao
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.db.dao.BlacklistDao
import com.mobilekinetic.agent.data.db.dao.MemoryFactDao
import com.mobilekinetic.agent.data.db.dao.SessionSummaryDao
import com.mobilekinetic.agent.data.db.dao.ProviderDao
import com.mobilekinetic.agent.data.db.dao.VaultDao
import com.mobilekinetic.agent.data.db.entity.BlacklistRuleEntity
import com.mobilekinetic.agent.data.db.entity.ConversationEntity
import com.mobilekinetic.agent.data.db.entity.CredentialEntity
import com.mobilekinetic.agent.data.db.entity.MessageEntity
import com.mobilekinetic.agent.data.db.entity.RagDocumentEntity
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import com.mobilekinetic.agent.data.db.entity.ToolUsageEntity
import com.mobilekinetic.agent.data.db.entity.MemoryFactEntity
import com.mobilekinetic.agent.data.db.entity.ProviderConfigEntity
import com.mobilekinetic.agent.data.db.entity.ProviderSettingsEntity
import com.mobilekinetic.agent.data.db.entity.SessionSummaryEntity
import com.mobilekinetic.agent.data.db.entity.VaultEntryEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CredentialEntity::class,
        RagDocumentEntity::class,
        ToolEntity::class,
        ToolUsageEntity::class,
        VaultEntryEntity::class,
        BlacklistRuleEntity::class,
        SessionSummaryEntity::class,
        MemoryFactEntity::class,
        ProviderConfigEntity::class,
        ProviderSettingsEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MobileKineticDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun credentialDao(): CredentialDao
    abstract fun ragDao(): RagDao
    abstract fun toolDao(): ToolDao
    abstract fun vaultDao(): VaultDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun providerDao(): ProviderDao

    companion object {
        @Volatile
        private var INSTANCE: MobileKineticDatabase? = null
        private const val DATABASE_NAME = "mobilekinetic_db"

        init {
            System.loadLibrary("sqlcipher")
        }

        fun getInstance(context: Context, passphrase: ByteArray): MobileKineticDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphrase).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): MobileKineticDatabase {
            // Migrate existing DB from databases/ to the safe persistent location under files/
            val oldDb = context.getDatabasePath(DATABASE_NAME)
            val newDir = File(context.filesDir, "home/Memory/Room").apply { mkdirs() }
            val newDb = File(newDir, DATABASE_NAME)
            if (oldDb.exists() && !newDb.exists()) {
                oldDb.copyTo(newDb)
                File("${oldDb.path}-wal").let { if (it.exists()) it.copyTo(File("${newDb.path}-wal")) }
                File("${oldDb.path}-shm").let { if (it.exists()) it.copyTo(File("${newDb.path}-shm")) }
            }

            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                MobileKineticDatabase::class.java,
                newDb.absolutePath
            )
                .openHelperFactory(factory)
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}
