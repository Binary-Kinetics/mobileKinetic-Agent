package com.mobilekinetic.agent.privacy

import android.util.Log
import com.mobilekinetic.agent.data.db.dao.BlacklistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyGateRuleSync @Inject constructor(
    private val blacklistDao: BlacklistDao,
    private val privacyGate: PrivacyGate
) {
    companion object {
        private const val TAG = "PrivacyGateRuleSync"
    }

    private var syncJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            blacklistDao.getEnabledRules()
                .map { entities ->
                    entities.map { entity ->
                        BlacklistRuleSnapshot(
                            id = entity.id,
                            ruleType = entity.ruleType,
                            value = entity.value,
                            action = entity.action,
                            matchFields = entity.matchFields
                        )
                    }
                }
                .collectLatest { snapshots ->
                    privacyGate.updateRules(snapshots)
                    Log.i(TAG, "Synced ${snapshots.size} enabled rules to PrivacyGate")
                }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }
}
