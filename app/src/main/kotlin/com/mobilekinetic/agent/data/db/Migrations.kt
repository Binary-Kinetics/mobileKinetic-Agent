package com.mobilekinetic.agent.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database migrations for mK:a.
 *
 * CRITICAL: Every future schema change MUST add a new migration here.
 * Never use fallbackToDestructiveMigration -- it wipes all user data
 * including RAG memories, session summaries, vault entries, and facts.
 *
 * The database is SQLCipher-encrypted. Migrations run the same SQL
 * as plaintext Room -- SQLCipher handles the encryption transparently.
 *
 * Current version: 8
 * Tables: conversations, messages, credentials, rag_documents, tools,
 *         tool_usage, vault_entries, blacklist_rules, session_summaries,
 *         memory_facts, provider_configs, provider_settings
 */
object Migrations {

    /**
     * Migration v7 -> v8: Multi-provider support.
     *
     * Creates provider_configs and provider_settings tables,
     * adds provider_id column to conversations, and inserts
     * default provider settings row.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create provider_configs table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS provider_configs (
                    `key` TEXT NOT NULL PRIMARY KEY,
                    providerId TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())

            // Create provider_settings table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS provider_settings (
                    id INTEGER NOT NULL PRIMARY KEY,
                    activeProviderId TEXT NOT NULL DEFAULT 'claude_cli',
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())

            // Add provider_id column to conversations
            db.execSQL("ALTER TABLE conversations ADD COLUMN provider_id TEXT NOT NULL DEFAULT 'claude_cli'")

            // Insert default provider settings (singleton row)
            db.execSQL("INSERT OR IGNORE INTO provider_settings (id, activeProviderId, updatedAt) VALUES (1, 'claude_cli', ${System.currentTimeMillis()})")
        }
    }

    /** All migrations in order. Pass to Room.databaseBuilder.addMigrations(). */
    val ALL = arrayOf(
        MIGRATION_7_8
    )
}
