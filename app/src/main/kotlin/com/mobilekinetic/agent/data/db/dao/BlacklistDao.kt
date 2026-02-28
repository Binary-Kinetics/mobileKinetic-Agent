package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import com.mobilekinetic.agent.data.db.entity.BlacklistRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistDao {
    @Query("SELECT * FROM blacklist_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<BlacklistRuleEntity>>

    @Query("SELECT * FROM blacklist_rules WHERE isEnabled = 1 ORDER BY ruleType ASC")
    fun getEnabledRules(): Flow<List<BlacklistRuleEntity>>

    @Query("SELECT * FROM blacklist_rules WHERE ruleType = :type AND isEnabled = 1")
    fun getRulesByType(type: String): Flow<List<BlacklistRuleEntity>>

    @Query("SELECT * FROM blacklist_rules WHERE id = :id LIMIT 1")
    suspend fun getRule(id: String): BlacklistRuleEntity?

    @Upsert
    suspend fun insertRule(rule: BlacklistRuleEntity)

    @Update
    suspend fun updateRule(rule: BlacklistRuleEntity)

    @Query("DELETE FROM blacklist_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE blacklist_rules SET isEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM blacklist_rules")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM blacklist_rules WHERE isEnabled = 1")
    suspend fun getEnabledCount(): Int
}
