package com.mobilekinetic.agent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import com.mobilekinetic.agent.claude.ClaudeProcessManager
import com.mobilekinetic.agent.device.api.DeviceApiServer
import com.mobilekinetic.agent.device.api.ReceivedShareData
import com.mobilekinetic.agent.ui.MobileKineticApp
import com.mobilekinetic.agent.ui.TextProcessingActivity
import com.mobilekinetic.agent.ui.TerminalScreen
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.ui.screens.FirstRunWizardScreen
import com.mobilekinetic.agent.ui.screens.SetupScreen
import com.mobilekinetic.agent.ui.theme.MobileKineticTheme
import com.mobilekinetic.agent.ui.viewmodel.ChatViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var claudeProcessManager: ClaudeProcessManager
    @Inject lateinit var settingsRepository: SettingsRepository

    // Permission launcher for RECORD_AUDIO (required by AudioVisualizerBridge)
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted")
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied - audio visualization will use fallback PCM analysis")
        }
    }

    // Batch permission launcher for all dangerous permissions
    private val multiplePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (isGranted) {
                Log.i(TAG, "$permission granted")
            } else {
                Log.w(TAG, "$permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle prefilled message from PROCESS_TEXT activity
        handlePrefilledMessage(intent)

        // Handle incoming share intents (ACTION_SEND / ACTION_SEND_MULTIPLE)
        processIncomingShare(intent)

        // Request RECORD_AUDIO permission for AudioVisualizerBridge
        requestRecordAudioPermissionIfNeeded()
        requestAllPermissionsIfNeeded()

        setContent {
            MobileKineticTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        var bootstrapState by remember { mutableStateOf<BootstrapState>(BootstrapState.Checking) }
        var bootstrapInstalled by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val installed = BootstrapInstaller.isInstalled(this@MainActivity)
            bootstrapInstalled = installed
            bootstrapState = if (installed) {
                Log.i(TAG, "Bootstrap already installed")
                withContext(Dispatchers.IO) {
                    BootstrapInstaller.ensurePermissions(this@MainActivity)
                }
                startTerminalService()
                requestStoragePermissionIfNeeded()
                BootstrapState.Ready
            } else {
                Log.i(TAG, "Bootstrap not installed, showing setup")
                BootstrapState.NeedsSetup
            }
        }

        when (val state = bootstrapState) {
            is BootstrapState.Checking -> {
                LoadingScreen("Checking bootstrap...")
            }
            is BootstrapState.NeedsSetup -> {
                SetupScreen(
                    isBootstrapInstalled = bootstrapInstalled,
                    onSetupComplete = {
                        BootstrapInstaller.ensurePermissions(this@MainActivity)
                        startTerminalService()
                        requestStoragePermissionIfNeeded()
                        bootstrapState = BootstrapState.Ready
                    },
                    onBootstrapInstall = { onProgress ->
                        withContext(Dispatchers.IO) {
                            val result = BootstrapInstaller.install(this@MainActivity) { progress ->
                                onProgress(progress)
                            }
                            when (result) {
                                is BootstrapInstaller.InstallResult.Success -> {
                                    Log.i(TAG, "Bootstrap installation complete")
                                    BootstrapInstaller.ensurePermissions(this@MainActivity)
                                    bootstrapInstalled = true
                                    Result.success("Bootstrap installed successfully")
                                }
                                is BootstrapInstaller.InstallResult.AlreadyInstalled -> {
                                    Log.i(TAG, "Bootstrap already installed")
                                    BootstrapInstaller.ensurePermissions(this@MainActivity)
                                    bootstrapInstalled = true
                                    Result.success("Bootstrap already installed")
                                }
                                is BootstrapInstaller.InstallResult.Error -> {
                                    Log.e(TAG, "Bootstrap failed: ${result.message}", result.exception)
                                    Result.failure(Exception(result.message, result.exception))
                                }
                            }
                        }
                    }
                )
            }
            is BootstrapState.Ready -> {
                var setupComplete by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    setupComplete = settingsRepository.setupComplete.first()
                }

                when (setupComplete) {
                    null -> LoadingScreen("Loading preferences...")
                    false -> FirstRunWizardScreen(
                        onSetupComplete = { setupComplete = true }
                    )
                    true -> MobileKineticApp(
                        terminalContent = { TerminalScreen() },
                        onStopClaude = { claudeProcessManager.interrupt() }
                    )
                }
            }
            is BootstrapState.Error -> {
                ErrorScreen(state.message)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePrefilledMessage(intent)
        processIncomingShare(intent)
    }

    /**
     * Check if the incoming intent carries a prefilled message from TextProcessingActivity
     * (PROCESS_TEXT action). If so, queue it for the ChatViewModel to consume.
     */
    private fun handlePrefilledMessage(intent: Intent?) {
        val prefilledMessage = intent?.getStringExtra(TextProcessingActivity.EXTRA_PREFILLED_MESSAGE)
        if (!prefilledMessage.isNullOrBlank()) {
            Log.i(TAG, "Received prefilled message from PROCESS_TEXT (${prefilledMessage.length} chars)")
            ChatViewModel.queuePendingMessage(prefilledMessage)
            // Clear the extra so it's not re-processed on config change
            intent?.removeExtra(TextProcessingActivity.EXTRA_PREFILLED_MESSAGE)
        }
    }

    /**
     * Process incoming share intents (ACTION_SEND / ACTION_SEND_MULTIPLE).
     * Stores parsed share data for the /share/received API endpoint.
     */
    private fun processIncomingShare(intent: Intent?) {
        if (intent == null) return
        val reader = ShareCompat.IntentReader.from(this)
        if (!reader.isShareIntent) return

        val streamUris = mutableListOf<String>()
        val streamCount = reader.streamCount
        for (i in 0 until streamCount) {
            reader.getStream(i)?.toString()?.let { streamUris.add(it) }
        }

        val data = ReceivedShareData(
            isSingleShare = reader.isSingleShare,
            isMultipleShare = reader.isMultipleShare,
            type = reader.type,
            text = reader.text?.toString(),
            htmlText = reader.htmlText,
            subject = reader.subject,
            streamUri = if (streamCount > 0) streamUris.firstOrNull() else null,
            streamCount = streamCount,
            streamUris = streamUris,
            callingPackage = reader.callingPackage,
            callingActivity = reader.callingActivity?.flattenToString(),
            emailTo = reader.getEmailTo()?.toList() ?: emptyList(),
            emailCc = reader.getEmailCc()?.toList() ?: emptyList(),
            emailBcc = reader.getEmailBcc()?.toList() ?: emptyList(),
            receivedAt = java.time.Instant.now().toString()
        )

        DeviceApiServer.lastReceivedShare = data
        Log.i(TAG, "Stored incoming share: type=${data.type}, hasText=${data.text != null}")
    }

    private fun startTerminalService() {
        MobileKineticService.startService(this)
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun requestRecordAudioPermissionIfNeeded() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "RECORD_AUDIO permission already granted")
            }
            else -> {
                Log.i(TAG, "Requesting RECORD_AUDIO permission for audio visualization")
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun requestAllPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        // Location
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Bluetooth (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        // Call log
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }

        // Contacts
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        // Calendar
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
            permissionsToRequest.add(Manifest.permission.WRITE_CALENDAR)
        }

        // Media images (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting ${permissionsToRequest.size} permissions: ${permissionsToRequest.joinToString()}")
            multiplePermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.i(TAG, "All permissions already granted")
        }
    }
}

private sealed class BootstrapState {
    data object Checking : BootstrapState()
    data object NeedsSetup : BootstrapState()
    data object Ready : BootstrapState()
    data class Error(val message: String) : BootstrapState()
}

@Composable
private fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.Green
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color.Green,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Bootstrap Installation Failed",
                color = Color.Red,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}
