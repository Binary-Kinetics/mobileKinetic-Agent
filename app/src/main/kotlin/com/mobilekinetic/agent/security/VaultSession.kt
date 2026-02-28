package com.mobilekinetic.agent.security

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultSession @Inject constructor() {

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L  // 5 minutes
    }

    private data class Session(val expiresAt: Long)

    private val activeSession = AtomicReference<Session?>(null)

    /** Called after successful biometric authentication. */
    fun unlock(ttlMs: Long = DEFAULT_TTL_MS) {
        activeSession.set(Session(expiresAt = System.currentTimeMillis() + ttlMs))
    }

    /** Check if the vault is currently unlocked. */
    val isUnlocked: Boolean
        get() {
            val session = activeSession.get() ?: return false
            if (System.currentTimeMillis() > session.expiresAt) {
                activeSession.set(null)
                return false
            }
            return true
        }

    /** Remaining time in milliseconds, or 0 if locked. */
    val remainingMs: Long
        get() {
            val session = activeSession.get() ?: return 0L
            val remaining = session.expiresAt - System.currentTimeMillis()
            if (remaining <= 0) {
                activeSession.set(null)
                return 0L
            }
            return remaining
        }

    /** Explicitly lock the vault. */
    fun lock() {
        activeSession.set(null)
    }
}
