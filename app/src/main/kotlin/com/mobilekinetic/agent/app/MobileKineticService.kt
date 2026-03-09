package com.mobilekinetic.agent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mobilekinetic.agent.claude.ClaudeCodeManager
import com.mobilekinetic.agent.claude.ContextSynthesizer
import com.mobilekinetic.agent.claude.HeartbeatScheduler
import com.mobilekinetic.agent.claude.AgendaManager
import com.mobilekinetic.agent.terminal.emulator.TerminalSession
import com.mobilekinetic.agent.terminal.emulator.TerminalSessionClient
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.TimeUnit
import android.content.ComponentCallbacks2
import com.mobilekinetic.agent.data.memory.BackupWorker
import com.mobilekinetic.agent.data.memory.DecayWorker
import com.mobilekinetic.agent.data.memory.HomeBackupWorker
import com.mobilekinetic.agent.data.memory.SessionMemoryRepository
import com.mobilekinetic.agent.data.gemma.GemmaModelManager
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import com.mobilekinetic.agent.data.rag.GemmaEmbeddingProvider
import com.mobilekinetic.agent.data.rag.OnnxEmbeddingProvider
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.rag.RagHttpServer
import com.mobilekinetic.agent.data.rag.RagSeeder
import com.mobilekinetic.agent.data.rag.ToolCatalogSeeder
import com.mobilekinetic.agent.data.vault.VaultHttpServer
import com.mobilekinetic.agent.device.api.DeviceApiServer
import com.mobilekinetic.agent.privacy.PrivacyGateRuleSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MobileKineticService : Service(), TerminalSessionClient {

    @Inject lateinit var ragHttpServer: RagHttpServer
    @Inject lateinit var deviceApiServer: DeviceApiServer
    @Inject lateinit var vaultHttpServer: VaultHttpServer
    @Inject lateinit var claudeCodeManager: ClaudeCodeManager
    @Inject lateinit var contextSynthesizer: ContextSynthesizer
    @Inject lateinit var heartbeatScheduler: HeartbeatScheduler
    @Inject lateinit var agendaManager: AgendaManager
    @Inject lateinit var privacyGateRuleSync: PrivacyGateRuleSync
    @Inject lateinit var gemmaModelManager: GemmaModelManager
    @Inject lateinit var gemmaTextGenerator: GemmaTextGenerator
    @Inject lateinit var gemmaEmbeddingProvider: GemmaEmbeddingProvider
    @Inject lateinit var onnxEmbeddingProvider: OnnxEmbeddingProvider
    @Inject lateinit var sessionMemoryRepository: SessionMemoryRepository
    @Inject lateinit var toolDao: ToolDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "MobileKineticService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "mobilekinetic_terminal"
        private const val CHANNEL_NAME = "Terminal Session"

        const val ACTION_STOP = "com.mobilekinetic.agent.ACTION_STOP"

        fun startService(context: Context) {
            val intent = Intent(context, MobileKineticService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: MobileKineticService get() = this@MobileKineticService
    }

    private val binder = LocalBinder()

    val sessions = mutableListOf<TerminalSession>()

    // Track PIDs of spawned processes for cleanup on session close
    private val sessionPids = mutableMapOf<TerminalSession, MutableSet<Int>>()

    var onSessionUpdate: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        try { File("/tmp").setWritable(true, false) } catch (_: Exception) {}
        val appTmp = File(filesDir, "tmp").also { it.mkdirs() }
        android.system.Os.setenv("TMPDIR", appTmp.absolutePath, true)
        Log.i(TAG, "Service created")
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Only include MICROPHONE type if RECORD_AUDIO is granted at runtime,
            // otherwise Android 14+ throws SecurityException on startForeground()
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasMicPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                Log.w(TAG, "RECORD_AUDIO not granted, starting FGS without microphone type")
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // Initialize RAG system, then start Claude process
        serviceScope.launch {
            // Initialize ONNX embedding model before RAG server starts
            try {
                onnxEmbeddingProvider.initialize()
                Log.i(TAG, "ONNX embedding provider initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ONNX embedding provider", e)
            }

            try {
                ragHttpServer.startServer()
                Log.i(TAG, "RAG system initialized on port ${RagHttpServer.DEFAULT_PORT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize RAG system", e)
            }

            try {
                deviceApiServer.startServer()
                Log.i(TAG, "Device API server started on port ${DeviceApiServer.DEFAULT_PORT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Device API server", e)
            }

            try {
                vaultHttpServer.start()
                Log.i(TAG, "Vault HTTP server started on port 5565")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vault HTTP server", e)
            }

            try {
                privacyGateRuleSync.start(serviceScope)
                Log.i(TAG, "Privacy gate rule sync started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start privacy gate rule sync", e)
            }

            // Gemma initialization — text gen only; ONNX is primary for embeddings
            // Gemma embedding skipped: needs Google experimental quantization group access
            try {
                // gemmaEmbeddingProvider.initialize()  // disabled — using ONNX MiniLM (384-dim)
                gemmaTextGenerator.initialize()
                Log.i(TAG, "Gemma text generator initialized (embedding: ONNX)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Gemma text generator", e)
            }

            // Schedule daily memory decay (pinned to 3-6 AM window)
            try {
                val now = java.util.Calendar.getInstance()
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 3)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    if (before(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                val initialDelayMs = target.timeInMillis - now.timeInMillis

                val decayRequest = PeriodicWorkRequestBuilder<DecayWorker>(
                    1, TimeUnit.DAYS,
                    3, TimeUnit.HOURS  // flex window: can run between 3-6 AM
                )
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .build()

                WorkManager.getInstance(this@MobileKineticService)
                    .enqueueUniquePeriodicWork(
                        DecayWorker.WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        decayRequest
                    )
                Log.i(TAG, "Memory decay worker scheduled for 3-6 AM window (initial delay: ${initialDelayMs / 3600000}h)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule memory decay worker", e)
            }

            // Phase 11: Schedule periodic DB backup every 3 hours
            try {
                val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(3, TimeUnit.HOURS).build()
                WorkManager.getInstance(this@MobileKineticService)
                    .enqueueUniquePeriodicWork(
                        BackupWorker.WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        backupRequest
                    )
                Log.i(TAG, "Memory backup worker scheduled (3h interval)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule memory backup worker", e)
            }

            // Schedule rolling home directory backup every 6 hours
            try {
                val homeBackupRequest = PeriodicWorkRequestBuilder<HomeBackupWorker>(6, TimeUnit.HOURS).build()
                WorkManager.getInstance(this@MobileKineticService)
                    .enqueueUniquePeriodicWork(
                        HomeBackupWorker.WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        homeBackupRequest
                    )
                Log.i(TAG, "Home backup worker scheduled (6h interval)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule home backup worker", e)
            }

            // Extract/update scripts from assets and merge MCP config
            try {
                ScriptManager.init(this@MobileKineticService)
                Log.i(TAG, "ScriptManager: scripts deployed and MCP config merged")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deploy scripts", e)
            }

            // Seed tool catalog from bundled JSON assets
            try {
                ToolCatalogSeeder(applicationContext, toolDao).seedIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Tool catalog seeding failed (non-fatal)", e)
            }

            // Synthesize dynamic CLAUDE.md context before starting Claude
            try {
                val claudeMd = contextSynthesizer.synthesize()
                claudeCodeManager.setClaudeMdContent(claudeMd)
                Log.i(TAG, "Context synthesized and CLAUDE.md content set (${claudeMd.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to synthesize context (Claude will start without dynamic context)", e)
            }

            // Start Claude Code manager after RAG + context are available
            try {
                claudeCodeManager.start()
                Log.i(TAG, "Claude Code manager started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Claude Code manager", e)
            }

            // Start heartbeat scheduler for periodic autonomous check-ins
            try {
                heartbeatScheduler.start(HeartbeatScheduler.HeartbeatConfig())
                Log.i(TAG, "Heartbeat scheduler started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start heartbeat scheduler", e)
            }

            // Send first-launch greeting if this is the first run
            try {
                val prefs = getSharedPreferences("mka_service", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("first_launch_greeting_sent", false)) {
                    // Brief delay to let Claude Code fully initialize
                    delay(3000)
                    claudeCodeManager.sendMessage(
                        "Hello! I just started for the first time. Please introduce yourself briefly, " +
                        "confirm all systems are operational, and let me know you're ready."
                    )
                    prefs.edit().putBoolean("first_launch_greeting_sent", true).apply()
                    Log.i(TAG, "First-launch greeting sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send first-launch greeting", e)
            }

            // Seed RAG with bundled entries (runs after all servers + Gemma are up)
            try {
                RagSeeder(applicationContext).checkAndSeed()
            } catch (e: Exception) {
                Log.e(TAG, "RAG seeding failed (non-fatal)", e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "TRIM_MEMORY_RUNNING_CRITICAL — nuking Gemma VRAM and ONNX")
            gemmaTextGenerator.release()
            gemmaEmbeddingProvider.release()
            gemmaModelManager.releaseAll()
            onnxEmbeddingProvider.release()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying, killing " + sessions.size + " sessions")

        // Kill all tracked PIDs before destroying sessions
        for ((session, pids) in sessionPids) {
            if (pids.isNotEmpty()) {
                Log.i(TAG, "Cleaning up ${pids.size} tracked PIDs for session ${session.mHandle}")
                for (pid in pids) {
                    try {
                        android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                        Log.d(TAG, "Sent SIGKILL to PID $pid")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to kill PID $pid: ${e.message}")
                    }
                }
            }
        }
        sessionPids.clear()

        for (session in sessions) {
            session.finishIfRunning()
        }
        sessions.clear()
        gemmaTextGenerator.release()
        gemmaEmbeddingProvider.release()
        Log.i(TAG, "Gemma providers released")
        // Shutdown heartbeat scheduler
        heartbeatScheduler.stop()
        Log.i(TAG, "Heartbeat scheduler stopped")
        // Shutdown Claude Code manager
        claudeCodeManager.stop()
        Log.i(TAG, "Claude Code manager stopped")
        privacyGateRuleSync.stop()
        Log.i(TAG, "Privacy gate rule sync stopped")
        vaultHttpServer.stop()
        Log.i(TAG, "Vault HTTP server stopped")
        // Shutdown RAG system
        ragHttpServer.stopServer()
        deviceApiServer.stopServer()
        Log.i(TAG, "Device API server stopped")
        Log.i(TAG, "RAG system shut down")
        // Release ONNX embedding model after RAG server stops
        onnxEmbeddingProvider.release()
        Log.i(TAG, "ONNX embedding provider released")
        super.onDestroy()
    }

    fun createSession(): TerminalSession {
        val env = BootstrapInstaller.getEnvironment(this)
        val prefix = BootstrapInstaller.getPrefix(this)
        val home = BootstrapInstaller.getHome(this)

        val shellPath = File(applicationInfo.nativeLibraryDir, "libbash.so").absolutePath
        val cwd = home.absolutePath

        val envArray = env.entries.map { (k, v) -> "$k=$v" }.toTypedArray()
        val args = arrayOf("-bash")

        val session = TerminalSession(
            shellPath,
            cwd,
            args,
            envArray,
            null,
            this
        )

        sessions.add(session)
        sessionPids[session] = mutableSetOf()
        Log.i(TAG, "Session created. Total sessions: " + sessions.size)

        return session
    }

    fun getOrCreateSession(): TerminalSession {
        return sessions.firstOrNull() ?: createSession()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Terminal session running"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MobileKineticService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("mK:a Terminal")
            .setContentText("Session running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(pendingLaunch)
            .addAction(
                Notification.Action.Builder(
                    null, "Exit", pendingStop
                ).build()
            )
            .build()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        onSessionUpdate?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        Log.d(TAG, "Title changed: " + changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.i(TAG, "Session finished: " + finishedSession.mHandle + " exitStatus=" + finishedSession.exitStatus)

        // Kill any tracked child processes to prevent orphans
        val pids = sessionPids.remove(finishedSession)
        if (pids != null && pids.isNotEmpty()) {
            Log.i(TAG, "Cleaning up ${pids.size} tracked PIDs for finished session")
            for (pid in pids) {
                try {
                    android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                    Log.d(TAG, "Sent SIGKILL to PID $pid")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill PID $pid: ${e.message}")
                }
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        // Handled by TerminalView
    }

    override fun onBell(session: TerminalSession) {
        // No-op for Phase 1
    }

    override fun onColorsChanged(session: TerminalSession) {
        onSessionUpdate?.invoke()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Cursor blink state change
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.i(TAG, "Shell PID: $pid for session ${session.mHandle}")
        // Track this PID for cleanup when the session closes
        sessionPids[session]?.add(pid)
    }

    override fun getTerminalCursorStyle(): Int? {
        return null
    }

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "Stack trace", e)
    }
}
