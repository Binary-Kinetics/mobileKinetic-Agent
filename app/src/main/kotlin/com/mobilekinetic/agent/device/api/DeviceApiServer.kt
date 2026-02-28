package com.mobilekinetic.agent.device.api

import android.Manifest
import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.BroadcastOptions
import android.accounts.Account
import android.app.AutomaticZenRule
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.GnssStatus
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.app.usage.NetworkStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.StatFs
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.graphics.Color
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.ExperimentalPrefetch
import androidx.browser.customtabs.PrefetchOptions
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicReference
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import com.mobilekinetic.agent.receiver.AlarmReceiver
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import android.app.DownloadManager
import android.app.admin.DevicePolicyManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.Person
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.telecom.TelecomManager
import android.util.Base64
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Collections
import java.util.concurrent.Executors
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mobilekinetic.agent.privacy.PrivacyGate
import com.mobilekinetic.agent.receiver.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import com.mobilekinetic.agent.data.gemma.GemmaModelManager
import com.mobilekinetic.agent.data.gemma.ModelState
import androidx.biometric.BiometricManager

/** Stores the last incoming share intent data parsed via ShareCompat.IntentReader */
data class ReceivedShareData(
    val isSingleShare: Boolean = false,
    val isMultipleShare: Boolean = false,
    val type: String? = null,
    val text: String? = null,
    val htmlText: String? = null,
    val subject: String? = null,
    val streamUri: String? = null,
    val streamCount: Int = 0,
    val streamUris: List<String> = emptyList(),
    val callingPackage: String? = null,
    val callingActivity: String? = null,
    val emailTo: List<String> = emptyList(),
    val emailCc: List<String> = emptyList(),
    val emailBcc: List<String> = emptyList(),
    val receivedAt: String = ""
)

@android.annotation.SuppressLint("UnsafeOptInUsageError")
class DeviceApiServer(
    private val context: Context,
    private val privacyGate: PrivacyGate,
    private val gemmaTextGenerator: GemmaTextGenerator,
    private val gemmaModelManager: GemmaModelManager,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DeviceApiServer"
        const val DEFAULT_PORT = 5563
        private const val MIME_JSON = "application/json"
        private const val CHANNEL_ID = "device_api_notifications"

        // LuxTTS PC-based TTS server constants
        private const val LUXTTS_BASE_URL = ""
        private const val LUXTTS_WSS_URL = ""
        private const val LUXTTS_MIN_TEXT_LENGTH = 20

        /** Static reference for incoming share data - set from MainActivity */
        @Volatile
        @JvmStatic
        var lastReceivedShare: ReceivedShareData? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Network callback storage
    private val networkCallbacks = mutableMapOf<String, NetworkCallback>()
    private val callbackEvents = mutableMapOf<String, MutableList<Map<String, Any>>>()
    private val connectivityManager by lazy { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    // NFC tag state storage
    private var lastScannedTag: Tag? = null
    private var lastNdefMessage: NdefMessage? = null

    // Lazy system service initialization
    private val batteryManager by lazy { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    private val wifiManager by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val clipboardManager by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val notificationManager by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val vibrator by lazy { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(context) }
    private val telephonyManager by lazy { context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val bluetoothManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val alarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }

    // Bluetooth Classic state
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothScanReceiver: BroadcastReceiver? = null
    private val bluetoothDiscoveredDevices = mutableListOf<JSONObject>()

    // BLE state
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var gattServices: List<BluetoothGattService> = emptyList()
    private val bleScanResults = mutableMapOf<String, BleScanResult>()
    private var bleScanCallback: ScanCallback? = null
    private val gattCharacteristicReadCache = mutableMapOf<String, ByteArray>()

    // HFP Headset state
    private var headsetProfile: BluetoothHeadset? = null
    private var headsetProfileReady = false

    // Chrome Custom Tabs prefetch support
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: android.content.ComponentName, client: CustomTabsClient) {
            customTabsClient = client
            client.warmup(0L)
            customTabsSession = client.newSession(null)
            Log.d(TAG, "CustomTabs service connected and warmed up")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            customTabsClient = null
            customTabsSession = null
            Log.d(TAG, "CustomTabs service disconnected")
        }
    }
    private var customTabsBound = false

    // Audio recording state
    private var audioMediaRecorder: MediaRecorder? = null
    private var audioRecordingFile: File? = null
    private var audioRecordingStartTime: Long = 0L
    private var audioRecordingMaxDuration: Int = 60
    private var isAudioRecording: Boolean = false
    private val audioRecordingHandler = Handler(Looper.getMainLooper())

    // CameraX provider (lazy, shared across all CameraX handlers)
    private val cameraProvider by lazy {
        ProcessCameraProvider.getInstance(context).get()
    }

    // Camera video recording state (CameraX)
    private var activeRecording: Recording? = null
    private var videoRecordingFile: File? = null
    private var videoRecordingStartTime: Long = 0L
    private var videoMaxDurationSeconds: Int = 30
    private val isVideoRecording: Boolean get() = activeRecording != null

    // Image analysis state
    private var isAnalyzing: Boolean = false

    // Geofencing state
    private val geofencingClient by lazy { LocationServices.getGeofencingClient(context) }
    private val activeGeofences = mutableMapOf<String, JSONObject>()

    // Download Manager
    private val downloadManager by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    // Media playback (for /media/play_file)
    private var mediaPlayer: MediaPlayer? = null

    // Settings screen map (for /settings/open and /settings/list)
    private val settingsScreenMap = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "application" to Settings.ACTION_APPLICATION_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "locale" to Settings.ACTION_LOCALE_SETTINGS,
        "input_method" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "development" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "battery_saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "notification" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "vpn" to Settings.ACTION_VPN_SETTINGS,
        "hotspot" to "com.android.settings.TetherSettings",
        "data_roaming" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "print" to Settings.ACTION_PRINT_SETTINGS,
        "cast" to Settings.ACTION_CAST_SETTINGS,
        "night_display" to Settings.ACTION_NIGHT_DISPLAY_SETTINGS,
        "usage_access" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
        "notification_access" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "battery_optimization" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        "manage_apps" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
        "default_apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        "device_info" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "memory" to Settings.ACTION_MEMORY_CARD_SETTINGS,
        "dream" to Settings.ACTION_DREAM_SETTINGS,
        "all" to Settings.ACTION_SETTINGS,
        "wifi_ip" to Settings.ACTION_WIFI_IP_SETTINGS,
        "wireless" to Settings.ACTION_WIRELESS_SETTINGS,
        "sync" to Settings.ACTION_SYNC_SETTINGS,
        "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "network_operator" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
        "search" to Settings.ACTION_SEARCH_SETTINGS,
        "dictionary" to Settings.ACTION_USER_DICTIONARY_SETTINGS,
        "apn" to Settings.ACTION_APN_SETTINGS,
        "add_account" to Settings.ACTION_ADD_ACCOUNT,
        "nfc_sharing" to Settings.ACTION_NFCSHARING_SETTINGS,
        "nfc_payment" to Settings.ACTION_NFC_PAYMENT_SETTINGS
    )

    init {
        // Initialize TTS
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
        }

        // Create notification channel
        createNotificationChannel()
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                method == Method.GET && uri == "/health" -> handleHealth()
                method == Method.GET && uri == "/device_info" -> handleDeviceInfo()
                method == Method.GET && uri == "/storage/info" -> handleStorageInfo()
                method == Method.GET && uri == "/battery" -> handleBattery()
                method == Method.GET && uri == "/wifi" -> handleWifi()
                method == Method.GET && uri == "/location" -> handleLocation()
                method == Method.GET && uri == "/sensors" -> handleSensors()
                method == Method.POST && uri == "/sensors/read" -> handleSensorsRead(session)
                method == Method.GET && uri == "/sensors/orientation" -> handleSensorsOrientation()
                method == Method.POST && uri == "/sensors/trigger" -> handleSensorsTrigger(session)
                method == Method.POST && uri == "/vibrate/effect" -> handleVibrateEffect(session)
                method == Method.POST && uri == "/vibrate/pattern" -> handleVibratePattern(session)
                method == Method.POST && uri == "/vibrate/cancel" -> handleVibrateCancel()
                method == Method.GET && uri == "/screen/state" -> handleScreenState()
                method == Method.GET && uri == "/bluetooth" -> handleBluetooth()
                method == Method.GET && uri == "/network/capabilities" -> handleNetworkCapabilities()
                method == Method.POST && uri == "/network/request" -> handleNetworkRequest(session)
                method == Method.GET && uri == "/network/data_usage" -> handleDataUsage(session)
                method == Method.POST && uri == "/network/bind_process" -> handleBindProcess(session)
                method == Method.GET && uri == "/network/active" -> handleGetActiveNetwork()
                method == Method.GET && uri == "/network/all" -> handleGetAllNetworks()
                method == Method.POST && uri == "/network/callback/register" -> handleRegisterCallback(session)
                method == Method.POST && uri == "/network/callback/unregister" -> handleUnregisterCallback(session)
                method == Method.GET && uri.startsWith("/network/callback/events") -> handleGetCallbackEvents(session)
                method == Method.GET && uri.startsWith("/network/link_properties") -> handleGetLinkProperties(session)
                method == Method.POST && uri == "/network/report_bad" -> handleReportBadNetwork(session)
                method == Method.GET && uri == "/network/restore_default" -> handleRestoreDefaultNetwork()
                method == Method.GET && uri.startsWith("/network/metered_status") -> handleGetMeteredStatus()
                method == Method.GET && uri.startsWith("/network/vpn_status") -> handleGetVpnStatus()
                method == Method.GET && uri.startsWith("/network/traffic_stats") -> handleGetTrafficStats(session)
                method == Method.GET && uri.startsWith("/network/bandwidth") -> handleGetBandwidth(session)
                method == Method.GET && uri == "/network/connection_check" -> handleConnectionCheck()
                method == Method.GET && uri.startsWith("/network/network_info_detailed") -> handleGetDetailedNetworkInfo(session)
                method == Method.POST && uri == "/bluetooth/scan" -> handleBluetoothScan(session)
                // Bluetooth Classic endpoints
                method == Method.GET && uri == "/bluetooth/state" -> handleBluetoothState()
                method == Method.POST && uri == "/bluetooth/enable" -> handleBluetoothEnable()
                method == Method.POST && uri == "/bluetooth/disable" -> handleBluetoothDisable()
                method == Method.GET && uri == "/bluetooth/paired" -> handleBluetoothPaired()
                method == Method.POST && uri == "/bluetooth/pair" -> handleBluetoothPair(session)
                method == Method.POST && uri == "/bluetooth/unpair" -> handleBluetoothUnpair(session)
                method == Method.GET && uri == "/bluetooth/scan/results" -> handleBluetoothScanResults()
                // Bluetooth HFP Headset endpoints
                method == Method.GET && uri == "/bluetooth/headset/status" -> handleBluetoothHeadsetStatus()
                method == Method.POST && uri == "/bluetooth/headset/voice_recognition" -> handleBluetoothHeadsetVoiceRecognition(session)
                // BLE endpoints
                method == Method.POST && uri == "/ble/scan" -> handleBleScan(session)
                method == Method.GET && uri == "/ble/scan/results" -> handleBleScanResults()
                method == Method.POST && uri == "/ble/connect" -> handleBleConnect(session)
                method == Method.POST && uri == "/ble/read" -> handleBleRead(session)
                method == Method.POST && uri == "/ble/write" -> handleBleWrite(session)
                method == Method.POST && uri == "/ble/disconnect" -> handleBleDisconnect()
                // WiFi endpoints
                method == Method.GET && uri == "/wifi/state" -> handleWifiState()
                method == Method.POST && uri == "/wifi/enable" -> handleWifiEnable()
                method == Method.POST && uri == "/wifi/disable" -> handleWifiDisable()
                method == Method.GET && uri == "/wifi/connection" -> handleWifiConnectionInfo()
                method == Method.POST && uri == "/wifi/scan" -> handleWifiScanStart()
                method == Method.GET && uri == "/wifi/scan/results" -> handleWifiScanResults()
                method == Method.POST && uri == "/wifi/suggest" -> handleWifiSuggest(session)
                method == Method.GET && uri == "/wifi/suggest/list" -> handleWifiSuggestList()
                method == Method.GET && uri == "/wifi/scan" -> handleWifiScan()
                method == Method.POST && uri == "/brightness" -> handleSetBrightness(session)
                method == Method.GET && uri == "/volume" -> handleGetVolume()
                method == Method.POST && uri == "/volume" -> handleSetVolume(session)
                method == Method.POST && uri == "/vibrate" -> handleVibrate(session)
                method == Method.POST && uri == "/torch" -> handleTorch(session)
                method == Method.POST && uri == "/dnd" -> handleDnd(session)
                method == Method.GET && uri == "/sms/list" -> handleSmsList(session)
                method == Method.POST && uri == "/sms/send" -> handleSmsSend(session)
                method == Method.GET && uri == "/mms/list" -> handleMmsList(session)
                method == Method.GET && uri == "/mms/read" -> handleMmsRead(session)
                method == Method.POST && uri == "/mms/send" -> handleMmsSend(session)
                method == Method.GET && uri == "/call_log" -> handleCallLog(session)
                method == Method.GET && uri == "/contacts" -> handleContacts(session)
                method == Method.POST && uri == "/share" -> handleShare(session)
                method == Method.GET && uri == "/share/received" -> handleShareReceived()
                method == Method.GET && uri == "/calendar/list" -> handleCalendarList()
                method == Method.GET && uri == "/calendar/events" -> handleCalendarEvents(session)
                method == Method.POST && uri == "/calendar/create" -> handleCalendarCreate(session)
                method == Method.POST && uri == "/calendar/update" -> handleCalendarUpdate(session)
                (method == Method.DELETE || method == Method.POST) && uri.startsWith("/calendar/delete") -> handleCalendarDelete(session)
                method == Method.GET && uri == "/tasks/list" -> handleTasksList(session)
                method == Method.POST && uri == "/tasks/create" -> handleTasksCreate(session)
                method == Method.POST && uri == "/tasks/update" -> handleTasksUpdate(session)
                (method == Method.DELETE || method == Method.POST) && uri.startsWith("/tasks/delete") -> handleTasksDelete(session)
                method == Method.POST && uri == "/alarms/set" -> handleAlarmSet(session)
                method == Method.POST && uri == "/alarms/schedule" -> handleAlarmSchedule(session)
                method == Method.POST && uri == "/alarms/cancel" -> handleAlarmCancel(session)
                method == Method.POST && uri == "/notification/send" -> handleNotificationSend(session)
                method == Method.POST && uri == "/notification" -> handleNotificationSend(session) // backward compat
                method == Method.POST && uri == "/toast" -> handleToast(session)
                method == Method.POST && uri == "/tts" -> handleTts(session)
                method == Method.GET && uri == "/media/playing" -> handleMediaPlaying()
                method == Method.POST && uri == "/media/control" -> handleMediaControl(session)
                method == Method.GET && uri == "/apps/list" -> handleAppsList()
                method == Method.POST && uri == "/apps/launch" -> handleAppsLaunch(session)
                method == Method.GET && uri == "/apps/info" -> handleAppsInfo(session)
                method == Method.GET && uri == "/apps/resolve" -> handleAppsResolve(session)
                method == Method.GET && uri == "/apps/find-by-permission" -> handleAppsFindByPermission(session)
                method == Method.GET && uri == "/apps/changes" -> handleAppsChanges(session)
                method == Method.GET && uri == "/apps/components" -> handleAppsComponents(session)
                method == Method.GET && uri == "/apps/defaults" -> handleAppsDefaults()
                method == Method.GET && uri == "/device/features" -> handleDeviceFeatures()
                method == Method.GET && uri == "/device/modules" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    handleDeviceModules()
                } else {
                    errorResponse("Device modules requires API 29+", Response.Status.BAD_REQUEST)
                }
                method == Method.GET && uri == "/clipboard" -> handleClipboardGet()
                method == Method.POST && uri == "/clipboard" -> handleClipboardSet(session)
                method == Method.POST && uri == "/tasker/run" -> handleTaskerRun(session)
                method == Method.GET && uri == "/tasker/tasks" -> handleTaskerTasks()
                method == Method.POST && uri == "/tasker/variable" -> handleTaskerSetVariable(session)
                method == Method.POST && uri == "/tasker/profile" -> handleTaskerProfile(session)
                method == Method.GET && uri == "/photos/recent" -> handlePhotosRecent(session)
                method == Method.GET && uri == "/downloads" -> handleDownloads(session)
                method == Method.GET && uri == "/nfc/state" -> handleNfcState()
                method == Method.POST && uri == "/nfc/enable" -> handleNfcEnable()
                method == Method.GET && uri == "/nfc/tag/read" -> handleNfcTagRead()
                method == Method.POST && uri == "/nfc/tag/write" -> handleNfcTagWrite(session)
                method == Method.GET && uri == "/nfc/tag/tech" -> handleNfcTagTech()
                method == Method.POST && uri == "/nfc/ndef/create" -> handleNfcNdefCreate(session)
                method == Method.POST && uri == "/nfc/foreground/enable" -> handleNfcForegroundEnable()
                method == Method.POST && uri == "/nfc/foreground/disable" -> handleNfcForegroundDisable()
                // Camera + Audio endpoints
                method == Method.GET && uri == "/camera/list" -> handleCameraList()
                method == Method.POST && uri == "/camera/photo" -> handleCameraPhoto(session)
                method == Method.POST && uri == "/camera/video" -> handleCameraVideo(session)
                method == Method.POST && uri == "/camera/video/start" -> handleCameraVideoStartDirect(session)
                method == Method.POST && uri == "/camera/video/stop" -> handleCameraVideoStop()
                method == Method.POST && uri == "/camera/analyze" -> handleCameraAnalyze(session)
                method == Method.GET && uri == "/camera/status" -> handleCameraStatus()
                method == Method.GET && uri == "/biometric/status" -> handleBiometricStatus()
                method == Method.POST && uri == "/audio/record/start" -> handleAudioRecordStart(session)
                method == Method.POST && uri == "/audio/record/stop" -> handleAudioRecordStop()
                method == Method.GET && uri == "/audio/status" -> handleAudioStatus()
                // Phone endpoints
                method == Method.POST && uri == "/phone/call" -> handlePhoneCall(session)
                method == Method.POST && uri == "/phone/hangup" -> handlePhoneHangup()
                method == Method.GET && uri == "/phone/state" -> handlePhoneState()
                // WorkManager + Intent endpoints
                method == Method.POST && uri == "/work/schedule" -> handleWorkSchedule(session)
                method == Method.GET && uri == "/work/status" -> handleWorkStatus(session)
                method == Method.POST && uri == "/work/cancel" -> handleWorkCancel(session)
                method == Method.POST && uri == "/intent/broadcast" -> handleIntentBroadcast(session)
                method == Method.POST && uri == "/intent/activity" -> handleIntentActivity(session)
                // Geofencing + Download endpoints
                method == Method.POST && uri == "/geofence/add" -> handleGeofenceAdd(session)
                method == Method.POST && uri == "/geofence/remove" -> handleGeofenceRemove(session)
                method == Method.GET && uri == "/geofence/list" -> handleGeofenceList()
                method == Method.POST && uri == "/download/enqueue" -> handleDownloadEnqueue(session)
                method == Method.GET && uri.startsWith("/download/status") -> handleDownloadStatus(session)
                // Privacy backlog endpoints
                method == Method.GET && uri == "/privacy/backlog" -> handlePrivacyBacklogStatus()
                method == Method.POST && uri == "/privacy/backlog/process" -> handlePrivacyBacklogProcess()
                method == Method.POST && uri == "/privacy/backlog/clear" -> handlePrivacyBacklogClear()
                // Accessibility Service endpoints
                method == Method.POST && uri == "/accessibility/click" -> handleAccessibilityClick(session)
                method == Method.POST && uri == "/accessibility/gesture" -> handleAccessibilityGesture(session)
                method == Method.GET && uri == "/accessibility/screen_text" -> handleAccessibilityScreenText()
                method == Method.POST && uri == "/accessibility/global_action" -> handleAccessibilityGlobalAction(session)
                method == Method.POST && uri == "/accessibility/find_element" -> handleAccessibilityFindElement(session)
                method == Method.POST && uri == "/accessibility/set_text" -> handleAccessibilitySetText(session)
                method == Method.GET && uri == "/accessibility/screenshot" -> handleAccessibilityScreenshot(session)
                // Notification Listener endpoints
                method == Method.GET && uri == "/notifications/active" -> handleNotificationsActive()
                method == Method.POST && uri == "/notifications/dismiss" -> handleNotificationsDismiss(session)
                method == Method.POST && uri == "/notifications/dismiss_all" -> handleNotificationsDismissAll()
                method == Method.POST && uri == "/notifications/reply" -> handleNotificationsReply(session)
                method == Method.GET && uri == "/notifications/status" -> handleNotificationsStatus()
                // Device Admin endpoints
                method == Method.GET && uri == "/admin/status" -> handleAdminStatus()
                method == Method.POST && uri == "/admin/lock_screen" -> handleAdminLockScreen()
                // GNSS + Input endpoints
                method == Method.GET && uri == "/gnss/status" -> handleGnssStatus()
                method == Method.POST && uri == "/input/keyevent" -> handleInputKeyEvent(session)
                // Accounts + Logcat endpoints
                method == Method.GET && uri == "/accounts" -> handleAccounts()
                method == Method.GET && uri.startsWith("/logcat") -> handleLogcat(session)
                // Chrome Custom Tabs browser endpoints
                method == Method.POST && uri == "/browser/open" -> handleBrowserOpen(session)
                method == Method.POST && uri == "/browser/prefetch" -> handleBrowserPrefetch(session)
                // Gemma on-device LLM endpoints
                uri == "/gemma/status" -> if (method == Method.GET) {
                    try {
                        val textStatus = gemmaModelManager.textGenStatus.value
                        val embeddingStatus = gemmaModelManager.embeddingStatus.value
                        val json = JSONObject().apply {
                            put("text_gen", JSONObject().apply {
                                put("state", textStatus.state.name)
                                put("ready", textStatus.state == ModelState.READY)
                                put("vram_mb", textStatus.vramMb)
                                put("error", textStatus.error)
                            })
                            put("embedding", JSONObject().apply {
                                put("state", embeddingStatus.state.name)
                                put("ready", embeddingStatus.state == ModelState.READY)
                                put("vram_mb", embeddingStatus.vramMb)
                            })
                            put("vram_used_mb", gemmaModelManager.getCurrentVramUsageMb())
                            put("generator_ready", gemmaTextGenerator.isReady)
                        }
                        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting Gemma status", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                            """{"error":"${e.message?.replace("\"", "'")}"}""")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json",
                        """{"error":"GET required"}""")
                }

                uri == "/gemma/generate" -> if (method == Method.POST) {
                    try {
                        val bodyMap = mutableMapOf<String, String>()
                        session.parseBody(bodyMap)
                        val body = bodyMap["postData"] ?: ""
                        val json = JSONObject(body)
                        val prompt = json.getString("prompt")
                        val maxTokens = json.optInt("max_tokens", 1024)
                        val temperature = json.optDouble("temperature", 0.7).toFloat()

                        if (!gemmaTextGenerator.isReady) {
                            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                                """{"error":"Gemma text generator not ready","state":"${gemmaModelManager.textGenStatus.value.state.name}"}""")
                        } else {
                            val result = runBlocking {
                                withTimeoutOrNull(60_000L) {
                                    gemmaTextGenerator.generate(prompt, maxTokens, temperature)
                                }
                            }
                            if (result != null) {
                                val responseJson = JSONObject().apply {
                                    put("text", result)
                                    put("model", "gemma-3-1b-it-int4")
                                }
                                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
                            } else {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                    """{"error":"Gemma generation timed out after 60s"}""")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemma generate error", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                            """{"error":"${e.message?.replace("\"", "'")}"}""")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json",
                        """{"error":"POST required"}""")
                }

                uri == "/gemma/initialize" -> if (method == Method.POST) {
                    try {
                        gemmaTextGenerator.initialize()
                        val state = gemmaModelManager.textGenStatus.value.state
                        val json = JSONObject().apply {
                            put("success", true)
                            put("state", state.name)
                            put("ready", gemmaTextGenerator.isReady)
                        }
                        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemma initialize error", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                            """{"error":"${e.message?.replace("\"", "'")}"}""")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json",
                        """{"error":"POST required"}""")
                }

                uri == "/gemma/release" -> if (method == Method.POST) {
                    try {
                        gemmaTextGenerator.release()
                        newFixedLengthResponse(Response.Status.OK, "application/json",
                            """{"success":true,"message":"Gemma text generator released"}""")
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemma release error", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                            """{"error":"${e.message?.replace("\"", "'")}"}""")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json",
                        """{"error":"POST required"}""")
                }

                // Resolver endpoint
                method == Method.POST && uri == "/resolve" -> handleResolve(session)

                // === NEW ENDPOINTS: Notification Channels ===
                method == Method.GET && uri == "/notification/channels" -> handleNotificationChannelsList()
                method == Method.GET && uri == "/notification/channel" -> handleNotificationChannelGet(session)
                method == Method.POST && uri == "/notification/channel" -> handleNotificationChannelCreate(session)
                method == Method.DELETE && uri == "/notification/channel" -> handleNotificationChannelDelete(session)
                (method == Method.DELETE || method == Method.POST) && uri == "/notification/channel/delete" -> handleNotificationChannelDelete(session)
                method == Method.GET && uri == "/notification/channel_groups" -> handleNotificationChannelGroupsList()
                method == Method.POST && uri == "/notification/channel_group" -> handleNotificationChannelGroupCreate(session)
                method == Method.DELETE && uri == "/notification/channel_group" -> handleNotificationChannelGroupDelete(session)
                (method == Method.DELETE || method == Method.POST) && uri == "/notification/channel_group/delete" -> handleNotificationChannelGroupDelete(session)
                method == Method.POST && uri == "/notification/cancel" -> handleNotificationCancel(session)
                method == Method.POST && uri == "/notification/cancel_all" -> handleNotificationCancelAll()
                method == Method.GET && uri == "/notification/active" -> handleNotificationActiveList()
                method == Method.GET && uri == "/notification/policy" -> handleNotificationPolicyGet()
                method == Method.POST && uri == "/notification/policy" -> handleNotificationPolicySet(session)
                method == Method.GET && uri == "/notification/interruption_filter" -> handleNotificationInterruptionFilterGet()
                method == Method.POST && uri == "/notification/interruption_filter" -> handleNotificationInterruptionFilterSet(session)
                method == Method.POST && uri == "/notification/snooze" -> handleNotificationSnooze(session)
                method == Method.POST && uri == "/notification/unsnooze" -> handleNotificationUnsnooze(session)
                method == Method.GET && uri == "/notification/snoozed" -> handleNotificationSnoozedList()

                // === NEW ENDPOINTS: Zen / DND Rules ===
                method == Method.GET && uri == "/zen/rules" -> handleZenRuleList()
                method == Method.GET && uri == "/zen/rule" -> handleZenRuleGet(session)
                method == Method.POST && uri == "/zen/rule" -> handleZenRuleCreate(session)
                method == Method.POST && uri == "/zen/rule/update" -> handleZenRuleUpdate(session)
                method == Method.DELETE && uri == "/zen/rule" -> handleZenRuleDelete(session)
                (method == Method.DELETE || method == Method.POST) && uri == "/zen/rule/delete" -> handleZenRuleDelete(session)
                method == Method.POST && uri == "/zen/rule/state" -> handleZenRuleSetState(session)
                method == Method.GET && uri == "/zen/policy" -> handleZenPolicyGet()
                method == Method.GET && uri == "/zen/device_effects" -> handleZenDeviceEffectsGet(session)

                // === NEW ENDPOINTS: Clipboard (extended) ===
                method == Method.POST && uri == "/clipboard/clear" -> handleClipboardClear()
                method == Method.GET && uri == "/clipboard/has_clip" -> handleClipboardHasClip()
                method == Method.GET && uri == "/clipboard/rich" -> handleClipboardGetRich()
                method == Method.POST && uri == "/clipboard/html" -> handleClipboardSetHtml(session)
                method == Method.POST && uri == "/clipboard/uri" -> handleClipboardSetUri(session)

                // === NEW ENDPOINTS: SharedPreferences ===
                method == Method.GET && uri == "/preferences/all" -> handlePreferencesGetAll(session)
                method == Method.GET && uri == "/preferences/get" -> handlePreferencesGet(session)
                method == Method.POST && uri == "/preferences/set" -> handlePreferencesSet(session)
                method == Method.POST && uri == "/preferences/remove" -> handlePreferencesRemove(session)
                method == Method.POST && uri == "/preferences/clear" -> handlePreferencesClear(session)
                method == Method.GET && uri == "/preferences/contains" -> handlePreferencesContains(session)
                (method == Method.DELETE || method == Method.POST) && uri == "/preferences/file" -> handlePreferencesDeleteFile(session)

                // === NEW ENDPOINTS: Package Info ===
                method == Method.GET && uri == "/package/info" -> handlePackageInfoGet(session)
                method == Method.GET && uri == "/package/permission" -> handlePackagePermissionCheck(session)
                method == Method.GET && uri == "/package/feature" -> handlePackageSystemFeature(session)
                method == Method.POST && uri == "/package/component" -> handlePackageComponentEnabled(session)
                method == Method.GET && uri == "/package/signatures" -> handlePackageSignaturesCheck(session)

                // === NEW ENDPOINTS: Content Provider ===
                method == Method.POST && uri == "/content/query" -> handleContentQuery(session)
                method == Method.POST && uri == "/content/insert" -> handleContentInsert(session)
                method == Method.POST && uri == "/content/update" -> handleContentUpdate(session)
                (method == Method.DELETE || method == Method.POST) && uri == "/content/delete" -> handleContentDelete(session)

                // === NEW ENDPOINTS: Intents ===
                method == Method.POST && uri == "/intent/send" -> handleIntentSend(session)
                method == Method.POST && uri == "/intent/resolve" -> handleIntentResolve(session)

                // === NEW ENDPOINTS: Files & Databases ===
                method == Method.GET && uri == "/files/private" -> handleFileListPrivate()
                method == Method.GET && uri == "/databases/list" -> handleDatabaseList()

                // === NEW ENDPOINTS: Wallpaper ===
                method == Method.POST && uri == "/wallpaper/set" -> handleWallpaperSet(session)
                method == Method.POST && uri == "/wallpaper/clear" -> handleWallpaperClear()

                // === NEW ENDPOINTS: Sync ===
                method == Method.GET && uri == "/sync/adapters" -> handleSyncAdaptersList()
                method == Method.POST && uri == "/sync/request" -> handleSyncRequest(session)

                // === NEW ENDPOINTS: URI Permissions ===
                method == Method.POST && uri == "/permission/uri/grant" -> handleUriPermissionGrant(session)
                method == Method.POST && uri == "/permission/uri/revoke" -> handleUriPermissionRevoke(session)
                method == Method.GET && uri == "/permission/self" -> handlePermissionCheckSelf(session)

                // === NEW ENDPOINTS: Settings Launcher ===
                method == Method.POST && uri == "/settings/open" -> handleSettingsOpen(session)
                method == Method.GET && uri == "/settings/list" -> handleSettingsList()

                // === NEW ENDPOINTS: Volume Stream Extensions ===
                method == Method.GET && uri == "/volume/get_stream" -> handleGetStreamVolume(session)
                method == Method.GET && uri == "/volume/get_all" -> handleGetAllVolumes()
                method == Method.POST && uri == "/volume/set_stream" -> handleSetStreamVolume(session)
                method == Method.POST && uri == "/volume/adjust" -> handleAdjustVolume(session)

                // === NEW ENDPOINTS: Audio Mode Control ===
                method == Method.POST && uri == "/audio/ringer_mode" -> handleSetRingerMode(session)
                method == Method.GET && uri == "/audio/ringer_mode" -> handleGetRingerMode()
                method == Method.POST && uri == "/audio/mic_mute" -> handleSetMicMute(session)
                method == Method.GET && uri == "/audio/mic_mute" -> handleGetMicMute()
                method == Method.POST && uri == "/audio/speakerphone" -> handleSetSpeakerphone(session)
                method == Method.GET && uri == "/audio/speakerphone" -> handleGetSpeakerphone()
                method == Method.GET && uri == "/audio/mode" -> handleGetAudioMode()

                // === NEW ENDPOINTS: Display Settings ===
                method == Method.GET && uri == "/display/timeout" -> handleGetDisplayTimeout()
                method == Method.POST && uri == "/display/timeout" -> handleSetDisplayTimeout(session)
                method == Method.GET && uri == "/display/auto_brightness" -> handleGetAutoBrightness()
                method == Method.POST && uri == "/display/auto_brightness" -> handleSetAutoBrightness(session)
                method == Method.GET && uri == "/display/auto_rotate" -> handleGetAutoRotate()
                method == Method.POST && uri == "/display/auto_rotate" -> handleSetAutoRotate(session)
                method == Method.GET && uri == "/display/stay_on" -> handleGetStayOn()
                method == Method.POST && uri == "/display/stay_on" -> handleSetStayOn(session)
                method == Method.GET && uri == "/display/font_scale" -> handleGetFontScale()
                method == Method.POST && uri == "/display/font_scale" -> handleSetFontScale(session)

                // === NEW ENDPOINTS: Call Management ===
                method == Method.POST && uri == "/call/make" -> handleCallMake(session)
                method == Method.POST && uri == "/call/end" -> handleCallEnd()
                method == Method.POST && uri == "/call/answer" -> handleCallAnswer()
                method == Method.POST && uri == "/call/silence" -> handleCallSilence()
                method == Method.GET && uri == "/call/state" -> handleCallState()
                method == Method.POST && uri == "/call/reject" -> handleCallReject(session)
                method == Method.POST && uri == "/call/speaker" -> handleCallSpeaker(session)
                method == Method.POST && uri == "/call/hold" -> handleCallHold(session)
                method == Method.POST && uri == "/call/dtmf" -> handleCallDtmf(session)

                // === NEW ENDPOINTS: TTS Extensions ===
                method == Method.POST && uri == "/tts/speak" -> handleTtsSpeak(session)
                method == Method.POST && uri == "/tts/stop" -> handleTtsStop()
                method == Method.GET && uri == "/tts/engines" -> handleTtsEngines()
                method == Method.POST && uri == "/tts/engine" -> handleTtsSetEngine(session)
                method == Method.GET && uri == "/tts/voices" -> handleTtsVoices()
                method == Method.POST && uri == "/tts/synthesize" -> handleTtsSynthesize(session)

                // === NEW ENDPOINTS: Connectivity Toggles ===
                method == Method.POST && uri == "/connectivity/wifi" -> handleConnectivityWifiToggle(session)
                method == Method.GET && uri == "/connectivity/wifi" -> handleConnectivityWifiState()
                method == Method.POST && uri == "/connectivity/bluetooth" -> handleConnectivityBluetoothToggle(session)
                method == Method.GET && uri == "/connectivity/bluetooth" -> handleConnectivityBluetoothState()
                method == Method.POST && uri == "/connectivity/airplane" -> handleConnectivityAirplaneToggle(session)
                method == Method.GET && uri == "/connectivity/airplane" -> handleConnectivityAirplaneState()
                method == Method.GET && uri == "/connectivity/mobile_data" -> handleConnectivityMobileData()
                method == Method.POST && uri == "/connectivity/nfc" -> handleConnectivityNfcToggle(session)
                method == Method.GET && uri == "/connectivity/nfc" -> handleConnectivityNfcState()
                method == Method.GET && uri == "/connectivity/all" -> handleConnectivityAll()

                // === NEW ENDPOINTS: Media Playback Extensions ===
                method == Method.POST && uri == "/media/play_pause" -> handleMediaAction("play_pause")
                method == Method.POST && uri == "/media/next" -> handleMediaAction("next")
                method == Method.POST && uri == "/media/previous" -> handleMediaAction("previous")
                method == Method.POST && uri == "/media/stop" -> handleMediaAction("stop")
                method == Method.POST && uri == "/media/fast_forward" -> handleMediaAction("fast_forward")
                method == Method.POST && uri == "/media/rewind" -> handleMediaAction("rewind")
                method == Method.GET && uri == "/media/now_playing" -> handleMediaNowPlaying()
                method == Method.POST && uri == "/media/play_file" -> handleMediaPlayFile(session)

                // === LuxTTS (PC-based TTS server) ===
                method == Method.POST && uri == "/luxtts/speak" -> handleLuxTtsSpeak(session)
                method == Method.GET && uri == "/luxtts/voices" -> handleLuxTtsVoices()
                method == Method.GET && uri == "/luxtts/health" -> handleLuxTtsHealth()
                method == Method.POST && uri == "/luxtts/speak_stream" -> handleLuxTtsSpeakStream(session)

                else -> notFound(method, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: $method $uri", e)
            errorResponse("Internal error: ${e.message}")
        }
    }

    // Helper functions
    private fun jsonResponse(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())

    private fun errorResponse(msg: String, status: Response.Status = Response.Status.INTERNAL_ERROR): Response =
        newFixedLengthResponse(status, MIME_JSON, """{"error":"${msg.replace("\"", "'")}"}""")

    private fun notFound(method: Method, uri: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, """{"error":"Not found: $method $uri"}""")

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun permissionError(perm: String): Response =
        errorResponse("Permission not granted: $perm. Grant in Settings > Apps > mK:a", Response.Status.FORBIDDEN)

    private fun parseBody(session: IHTTPSession): String? {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"]
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing body", e)
            null
        }
    }

    // ==================== Resolver Endpoint ====================

    /**
     * Data class for parsed parameter definitions from RAG entry param_spec.
     * Example: "camera_id:s?" -> ParamDef("camera_id", 's', false)
     */
    private data class ParamDef(
        val name: String,
        val type: Char,       // 's'=String, 'i'=Int, 'b'=Boolean, 'n'=Number, 'l'=list
        val required: Boolean
    )

    /**
     * POST /resolve - Intent-to-action resolver.
     *
     * Accepts a natural language intent, searches RAG for matching tool definitions,
     * extracts parameters, assembles a tool call, and optionally executes it.
     *
     * Request: {"intent": "take a picture with flash", "auto_execute": false, "domain_filter": "TOOL"}
     * Response: See RESOLVER_SPEC.md Section 6 for full response format.
     */
    private fun handleResolve(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
        val requestJson: JSONObject
        try {
            requestJson = JSONObject(body)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}", Response.Status.BAD_REQUEST)
        }

        val intent = requestJson.optString("intent", "").trim()
        if (intent.isEmpty()) {
            return errorResponse("Missing required field: intent", Response.Status.BAD_REQUEST)
        }

        val autoExecute = requestJson.optBoolean("auto_execute", false)
        val domainFilter = requestJson.optString("domain_filter", "").takeIf { it.isNotEmpty() }

        return try {
            // Step 1: Query RAG at port 5562
            val ragQuery = if (domainFilter != null) "[$domainFilter] $intent" else intent
            val ragResults = queryRag(ragQuery, topK = 3)

            if (ragResults == null || ragResults.length() == 0) {
                return jsonResponse(JSONObject().apply {
                    put("resolved", false)
                    put("error", "no_match")
                    put("message", "RAG search returned no results")
                    put("intent", intent)
                })
            }

            // Collect alternatives (top 3 results regardless of threshold)
            val alternatives = JSONArray()
            for (i in 0 until ragResults.length()) {
                val result = ragResults.getJSONObject(i)
                val text = result.optString("text", "")
                val similarity = result.optDouble("similarity", 0.0)
                val parts = text.split(" | ")
                if (parts.size >= 2) {
                    alternatives.put(JSONObject().apply {
                        put("tool", parts[1].trim())
                        put("mcp_name", "mcp__unified-device__device_${parts[1].trim()}")
                        put("confidence", similarity)
                    })
                }
            }

            // Step 2: Check confidence threshold on top result
            val topResult = ragResults.getJSONObject(0)
            val topSimilarity = topResult.optDouble("similarity", 0.0)
            val topText = topResult.optString("text", "")

            if (topSimilarity < 0.60) {
                return jsonResponse(JSONObject().apply {
                    put("resolved", false)
                    put("error", "no_match")
                    put("message", "No RAG entry matched the intent with sufficient confidence")
                    put("intent", intent)
                    put("best_match", if (alternatives.length() > 0) alternatives.getJSONObject(0) else JSONObject())
                })
            }

            // Step 3: Parse top result (pipe-delimited format)
            val parts = topText.split(" | ")
            if (parts.size < 5) {
                return jsonResponse(JSONObject().apply {
                    put("resolved", false)
                    put("error", "parse_error")
                    put("message", "Top RAG result has unexpected format (expected 5 pipe-delimited fields, got ${parts.size})")
                    put("intent", intent)
                    put("raw_match", topText)
                })
            }

            val domain = parts[0].trim()     // e.g. "[TOOL]"
            val toolName = parts[1].trim()   // e.g. "camera_photo"
            val action = parts[2].trim()     // e.g. "Snap a picture with device camera"
            val paramSpec = parts[3].trim()  // e.g. "camera_id:s? flash:s? quality:i?"
            val returnDesc = parts[4].trim() // e.g. "file path to saved photo"

            // Step 4: Parse parameter spec
            val paramDefs = parseParamSpec(paramSpec)

            // Step 5: Extract parameter values from intent
            val extractedParams = extractParams(intent, paramDefs)

            // Step 6: Assemble tool call
            val mcpName = "mcp__unified-device__device_$toolName"

            // Build params JSON for response
            val paramsJson = JSONObject()
            for ((key, value) in extractedParams) {
                paramsJson.put(key, value)
            }

            // Step 7: Execute if auto_execute is true
            var executionResult: JSONObject? = null
            if (autoExecute) {
                executionResult = executeToolCall(toolName, paramDefs, extractedParams)
            }

            // Build success response
            jsonResponse(JSONObject().apply {
                put("resolved", true)
                put("tool", toolName)
                put("mcp_name", mcpName)
                put("params", paramsJson)
                put("confidence", topSimilarity)
                put("alternatives", alternatives)
                put("executed", autoExecute)
                if (executionResult != null) {
                    put("result", executionResult)
                }
                put("source", JSONObject().apply {
                    put("domain", domain)
                    put("tool_name", toolName)
                    put("description", action)
                    put("return_desc", returnDesc)
                })
            })

        } catch (e: Exception) {
            Log.e(TAG, "Resolver error", e)
            errorResponse("Resolver error: ${e.message}")
        }
    }

    /**
     * Query the on-device RAG server at port 5562.
     * Returns the "results" JSONArray from the response, or null on failure.
     */
    private fun queryRag(query: String, topK: Int = 3): JSONArray? {
        return try {
            val url = URL("http://127.0.0.1:5562/search")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 60000

            val payload = JSONObject().apply {
                put("query", query)
                put("top_k", topK)
            }

            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "RAG search returned HTTP $responseCode")
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val responseJson = JSONObject(responseBody)
            responseJson.optJSONArray("results")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query RAG: ${e.message}", e)
            null
        }
    }

    /**
     * Parse the parameter spec string from a RAG entry.
     * Example: "camera_id:s? flash:s? quality:i?" -> list of ParamDef
     * Returns empty list for "(none)".
     */
    private fun parseParamSpec(spec: String): List<ParamDef> {
        if (spec == "(none)") return emptyList()
        return spec.split(" ").mapNotNull { token ->
            val colonIdx = token.indexOf(':')
            if (colonIdx < 0) return@mapNotNull null
            val name = token.substring(0, colonIdx)
            val typeAndMod = token.substring(colonIdx + 1)
            val typeChar = typeAndMod[0]  // 's', 'i', 'b', 'n', 'l' (for list)
            val required = !token.endsWith('?')
            ParamDef(name, typeChar, required)
        }
    }

    /**
     * Extract parameter values from the natural language intent string.
     *
     * Strategies (in priority order):
     * 1. Explicit key-value: "flash on" -> flash = "on"
     * 2. Boolean keyword matching: "on/off/yes/no/true/false" near param name
     * 3. Numeric extraction: "quality 80" -> quality = 80
     * 4. Positional for single-param tools: "speak hello world" -> text = "hello world"
     */
    private fun extractParams(intent: String, paramDefs: List<ParamDef>): Map<String, Any> {
        if (paramDefs.isEmpty()) return emptyMap()

        val params = mutableMapOf<String, Any>()
        val intentLower = intent.lowercase(Locale.ROOT)
        val intentWords = intentLower.split("\\s+".toRegex())

        for (def in paramDefs) {
            val value = extractSingleParam(intent, intentLower, intentWords, def)
            if (value != null) {
                params[def.name] = value
            }
            // Optional params with no extracted value are simply omitted.
            // Required params with no value: we don't throw here -- the caller
            // (Claude or the consumer) can decide how to handle missing required params.
        }

        // Fallback for single-required-param tools: if exactly one required string param
        // exists and we found nothing for it, use the entire intent as the value.
        if (params.isEmpty() && paramDefs.size == 1 && paramDefs[0].type == 's') {
            params[paramDefs[0].name] = intent
        } else if (params.isEmpty()) {
            // For single required param of any type, try the whole intent for string params
            val requiredStringParams = paramDefs.filter { it.required && it.type == 's' }
            if (requiredStringParams.size == 1) {
                params[requiredStringParams[0].name] = intent
            }
        }

        return params
    }

    /**
     * Attempt to extract a single parameter value from the intent.
     * Returns the correctly-typed value, or null if not found.
     */
    private fun extractSingleParam(
        intent: String,
        intentLower: String,
        intentWords: List<String>,
        def: ParamDef
    ): Any? {
        val paramNameLower = def.name.lowercase(Locale.ROOT)
        // Also try with underscores replaced by spaces for matching
        val paramNameSpaced = paramNameLower.replace('_', ' ')

        return when (def.type) {
            'b' -> extractBooleanParam(intentLower, intentWords, paramNameLower, paramNameSpaced)
            'i' -> extractIntParam(intentLower, intentWords, paramNameLower, paramNameSpaced)
            'n' -> extractNumberParam(intentLower, intentWords, paramNameLower, paramNameSpaced)
            's' -> extractStringParam(intent, intentLower, intentWords, paramNameLower, paramNameSpaced, def)
            'l' -> extractListParam(intent, intentLower, paramNameLower)
            else -> null
        }
    }

    /**
     * Extract a boolean parameter. Looks for patterns like "flash on", "flash off",
     * "enabled true", etc.
     */
    private fun extractBooleanParam(
        intentLower: String,
        intentWords: List<String>,
        paramName: String,
        paramNameSpaced: String
    ): Boolean? {
        val trueWords = setOf("on", "yes", "true", "enable", "enabled", "start", "open")
        val falseWords = setOf("off", "no", "false", "disable", "disabled", "stop", "close")

        // Look for "{param} {true/false word}" pattern
        for (pattern in listOf(paramName, paramNameSpaced)) {
            val idx = intentLower.indexOf(pattern)
            if (idx >= 0) {
                val after = intentLower.substring(idx + pattern.length).trim().split("\\s+".toRegex()).firstOrNull()
                if (after != null) {
                    if (after in trueWords) return true
                    if (after in falseWords) return false
                }
            }
        }

        // Check if any true/false word appears anywhere (for single-boolean-param tools)
        for (word in intentWords) {
            if (word in trueWords) return true
            if (word in falseWords) return false
        }

        return null
    }

    /**
     * Extract an integer parameter. Looks for patterns like "quality 80", "level 5".
     */
    private fun extractIntParam(
        intentLower: String,
        intentWords: List<String>,
        paramName: String,
        paramNameSpaced: String
    ): Int? {
        // Look for "{param} {number}" pattern
        for (pattern in listOf(paramName, paramNameSpaced)) {
            val idx = intentLower.indexOf(pattern)
            if (idx >= 0) {
                val after = intentLower.substring(idx + pattern.length).trim().split("\\s+".toRegex()).firstOrNull()
                after?.toIntOrNull()?.let { return it }
            }
        }

        // Fallback: find any standalone number in the intent
        for (word in intentWords) {
            word.toIntOrNull()?.let { return it }
        }

        return null
    }

    /**
     * Extract a number (Double/Long) parameter.
     */
    private fun extractNumberParam(
        intentLower: String,
        intentWords: List<String>,
        paramName: String,
        paramNameSpaced: String
    ): Number? {
        // Look for "{param} {number}" pattern
        for (pattern in listOf(paramName, paramNameSpaced)) {
            val idx = intentLower.indexOf(pattern)
            if (idx >= 0) {
                val after = intentLower.substring(idx + pattern.length).trim().split("\\s+".toRegex()).firstOrNull()
                after?.toLongOrNull()?.let { return it }
                after?.toDoubleOrNull()?.let { return it }
            }
        }

        // Fallback: find any number
        for (word in intentWords) {
            word.toLongOrNull()?.let { return it }
            word.toDoubleOrNull()?.let { return it }
        }

        return null
    }

    /**
     * Extract a string parameter. Looks for the param name followed by a value,
     * or for well-known contextual values.
     */
    private fun extractStringParam(
        intent: String,
        intentLower: String,
        intentWords: List<String>,
        paramName: String,
        paramNameSpaced: String,
        def: ParamDef
    ): String? {
        // Look for "{param} {value}" pattern
        for (pattern in listOf(paramName, paramNameSpaced)) {
            val idx = intentLower.indexOf(pattern)
            if (idx >= 0) {
                val afterStr = intent.substring(idx + pattern.length).trim()
                val value = afterStr.split("\\s+".toRegex()).firstOrNull()
                if (!value.isNullOrEmpty()) return value
            }
        }

        // Contextual extraction for known parameter names
        when (paramName) {
            "camera_id" -> {
                if ("front" in intentLower || "selfie" in intentLower) return "1"
                if ("back" in intentLower || "rear" in intentLower) return "0"
            }
            "flash" -> {
                if ("flash on" in intentLower || "with flash" in intentLower) return "on"
                if ("flash off" in intentLower || "no flash" in intentLower || "without flash" in intentLower) return "off"
            }
            "stream" -> {
                if ("ring" in intentLower) return "ring"
                if ("media" in intentLower || "music" in intentLower) return "media"
                if ("alarm" in intentLower) return "alarm"
                if ("notification" in intentLower) return "notification"
            }
            "action" -> {
                if ("play" in intentLower) return "play"
                if ("pause" in intentLower) return "pause"
                if ("next" in intentLower || "skip" in intentLower) return "next"
                if ("previous" in intentLower || "prev" in intentLower || "back" in intentLower) return "previous"
                if ("stop" in intentLower) return "stop"
            }
            "mode" -> {
                if ("silent" in intentLower || "none" in intentLower) return "none"
                if ("priority" in intentLower) return "priority"
                if ("alarm" in intentLower) return "alarms"
                if ("total" in intentLower) return "total_silence"
            }
            "url" -> {
                // Try to extract a URL from the intent
                val urlPattern = Regex("https?://\\S+")
                urlPattern.find(intent)?.value?.let { return it }
            }
            "phone_number" -> {
                // Try to extract a phone number pattern
                val phonePattern = Regex("[+]?\\d[\\d\\s\\-()]{6,}")
                phonePattern.find(intent)?.value?.let { return it.replace(Regex("[\\s\\-()]"), "") }
            }
        }

        return null
    }

    /**
     * Extract a list parameter. Attempts to parse comma-separated values
     * or JSON array syntax from the intent.
     */
    private fun extractListParam(
        intent: String,
        intentLower: String,
        paramName: String
    ): JSONArray? {
        // Look for JSON array in intent
        val arrayPattern = Regex("\\[.*?]")
        arrayPattern.find(intent)?.value?.let { arrayStr ->
            try {
                return JSONArray(arrayStr)
            } catch (_: Exception) { }
        }

        // Look for comma-separated values after param name
        val idx = intentLower.indexOf(paramName)
        if (idx >= 0) {
            val afterStr = intent.substring(idx + paramName.length).trim()
            if (afterStr.contains(",")) {
                val items = afterStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) {
                    return JSONArray(items)
                }
            }
        }

        return null
    }

    /**
     * Execute a resolved tool call by making an HTTP request to the local
     * Tier 1 (port 5563) or Tier 2 (port 5564) server.
     *
     * The tool_name maps to an HTTP endpoint by replacing underscores with slashes:
     * "camera_photo" -> "/camera/photo"
     *
     * Tools with no required params use GET; tools with required params use POST.
     */
    private fun executeToolCall(
        toolName: String,
        paramDefs: List<ParamDef>,
        extractedParams: Map<String, Any>
    ): JSONObject {
        return try {
            // Map tool_name to HTTP endpoint path
            val endpointPath = "/" + toolName.replace('_', '/')
            val hasRequiredParams = paramDefs.any { it.required }

            // Determine base URL (Tier 1 Kotlin server)
            val baseUrl = "http://127.0.0.1:5563"
            val url = URL("$baseUrl$endpointPath")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            if (hasRequiredParams || extractedParams.isNotEmpty()) {
                // POST with JSON body
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val paramsJson = JSONObject()
                for ((key, value) in extractedParams) {
                    paramsJson.put(key, value)
                }

                connection.outputStream.use { os ->
                    os.write(paramsJson.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } else {
                // GET with no body
                connection.requestMethod = "GET"
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            JSONObject().apply {
                put("success", responseCode in 200..299)
                put("status_code", responseCode)
                try {
                    put("result", JSONObject(responseBody))
                } catch (_: Exception) {
                    put("result", responseBody)
                }
                if (responseCode !in 200..299) {
                    put("error", "HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error for $toolName: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Execution failed: ${e.message}")
            }
        }
    }

    // Health endpoint
    private fun handleHealth(): Response {
        val json = JSONObject().apply {
            put("status", "healthy")
            put("service", "DeviceApiServer")
            put("port", DEFAULT_PORT)
            put("version", "1.0.0")
        }
        return jsonResponse(json)
    }

    // Device info endpoint
    private fun handleDeviceInfo(): Response {
        val json = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("brand", Build.BRAND)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("build_id", Build.ID)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
        }
        return jsonResponse(json)
    }

    // Storage info endpoint
    private fun handleStorageInfo(): Response {
        val internalPath = Environment.getDataDirectory()
        val internalStat = StatFs(internalPath.path)
        val internalTotal = internalStat.blockCountLong * internalStat.blockSizeLong
        val internalAvailable = internalStat.availableBlocksLong * internalStat.blockSizeLong

        val json = JSONObject().apply {
            put("internal", JSONObject().apply {
                put("total_bytes", internalTotal)
                put("available_bytes", internalAvailable)
                put("used_bytes", internalTotal - internalAvailable)
                put("total_gb", internalTotal / (1024.0 * 1024.0 * 1024.0))
                put("available_gb", internalAvailable / (1024.0 * 1024.0 * 1024.0))
            })

            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val externalPath = Environment.getExternalStorageDirectory()
                val externalStat = StatFs(externalPath.path)
                val externalTotal = externalStat.blockCountLong * externalStat.blockSizeLong
                val externalAvailable = externalStat.availableBlocksLong * externalStat.blockSizeLong

                put("external", JSONObject().apply {
                    put("total_bytes", externalTotal)
                    put("available_bytes", externalAvailable)
                    put("used_bytes", externalTotal - externalAvailable)
                    put("total_gb", externalTotal / (1024.0 * 1024.0 * 1024.0))
                    put("available_gb", externalAvailable / (1024.0 * 1024.0 * 1024.0))
                })
            }
        }
        return jsonResponse(json)
    }

    // Battery endpoint
    private fun handleBattery(): Response {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusString = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

        val health = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthString = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val temperature = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val technology = batteryIntent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"

        val json = JSONObject().apply {
            put("percentage", percentage)
            put("level", level)
            put("scale", scale)
            put("status", statusString)
            put("health", healthString)
            put("temperature_celsius", temperature / 10.0)
            put("voltage_millivolts", voltage)
            put("technology", technology)
            put("is_charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING)
        }
        return jsonResponse(json)
    }

    // WiFi endpoint
    private fun handleWifi(): Response {
        if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
            return permissionError(Manifest.permission.ACCESS_WIFI_STATE)
        }

        val json = JSONObject().apply {
            put("enabled", wifiManager.isWifiEnabled)

            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null) {
                put("connected", wifiInfo.networkId != -1)
                put("ssid", wifiInfo.ssid?.replace("\"", ""))
                put("bssid", wifiInfo.bssid)
                put("rssi", wifiInfo.rssi)
                put("link_speed_mbps", wifiInfo.linkSpeed)
                put("ip_address", formatIpAddress(wifiInfo.ipAddress))
                put("mac_address", wifiInfo.macAddress)
            }
        }
        return jsonResponse(json)
    }

    private fun formatIpAddress(ip: Int): String {
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    /* --- OLD handleLocation (H1-Bug6: replaced with active location request) ---
    // Location endpoint
    private fun handleLocation(): Response {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        val json = JSONObject().apply {
            if (location != null) {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_meters", location.accuracy)
                put("altitude_meters", location.altitude)
                put("time", location.time)
                put("provider", location.provider)
                if (location.hasSpeed()) put("speed_mps", location.speed)
                if (location.hasBearing()) put("bearing_degrees", location.bearing)
            } else {
                put("error", "No location available")
            }
        }
        return jsonResponse(json)
    }
    --- END OLD handleLocation --- */

    // Location endpoint - H1-Bug6: Active location request with CountDownLatch
    private fun handleLocation(): Response {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val staleThresholdMs = 5 * 60 * 1000L // 5 minutes

        // Step 1: Try cached location first
        val cachedLocation: Location? =
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        // If cached location is fresh (less than 5 minutes old), return it immediately
        if (cachedLocation != null) {
            val age = System.currentTimeMillis() - cachedLocation.time
            if (age < staleThresholdMs) {
                return locationToResponse(cachedLocation, "cached_fresh")
            }
        }

        // Step 2: Cached is null or stale - request active location update
        val latch = CountDownLatch(1)
        val activeLocationHolder = arrayOfNulls<Location>(1)

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                activeLocationHolder[0] = location
                latch.countDown()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in API level 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            // Request single update on main looper (location callbacks require a looper thread)
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        @Suppress("DEPRECATION")
                        locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper())
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException requesting location update", e)
                        latch.countDown()
                    }
                }

                // Wait up to 10 seconds for active location
                val completed = latch.await(10, TimeUnit.SECONDS)

                val activeLocation = activeLocationHolder[0]
                if (completed && activeLocation != null) {
                    return locationToResponse(activeLocation, "active_" + provider)
                }
            }
        } finally {
            // ALWAYS remove the listener to prevent leaks
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing location listener", e)
            }
        }

        // Step 3: Timeout or no provider - fall back to cached (even if stale)
        if (cachedLocation != null) {
            return locationToResponse(cachedLocation, "cached_stale")
        }

        // Step 4: Both active and cached are null - return 503
        return newFixedLengthResponse(
            Response.Status.lookup(503),
            MIME_JSON,
            JSONObject().apply {
                put("error", "No location available from any provider")
                put("gps_enabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                put("network_enabled", locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            }.toString()
        )
    }

    // Helper to build location JSON response
    private fun locationToResponse(location: Location, source: String): Response {
        val json = JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_meters", location.accuracy)
            put("altitude_meters", location.altitude)
            put("time", location.time)
            put("provider", location.provider)
            put("source", source)
            put("age_seconds", (System.currentTimeMillis() - location.time) / 1000.0)
            if (location.hasSpeed()) put("speed_mps", location.speed)
            if (location.hasBearing()) put("bearing_degrees", location.bearing)
        }
        return jsonResponse(json)
    }

    // GNSS satellite status endpoint
    private fun handleGnssStatus(): Response {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val latch = CountDownLatch(1)
        val statusHolder = AtomicReference<GnssStatus?>(null)

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                statusHolder.set(status)
                latch.countDown()
            }

            override fun onStarted() {}
            override fun onStopped() {}
            override fun onFirstFix(ttffMillis: Int) {}
        }

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException registering GNSS callback", e)
                    latch.countDown()
                }
            }

            val completed = latch.await(15, TimeUnit.SECONDS)

            val gnssStatus = statusHolder.get()
            if (!completed || gnssStatus == null) {
                return jsonResponse(JSONObject().apply {
                    put("fixAcquired", false)
                    put("satelliteCount", 0)
                    put("usedInFix", 0)
                    put("satellites", JSONArray())
                    put("error", "Timed out waiting for GNSS status update")
                })
            }

            val satellites = JSONArray()
            var usedCount = 0
            for (i in 0 until gnssStatus.satelliteCount) {
                if (gnssStatus.usedInFix(i)) usedCount++
                satellites.put(JSONObject().apply {
                    put("id", i)
                    put("constellation", constellationName(gnssStatus.getConstellationType(i)))
                    put("cn0", gnssStatus.getCn0DbHz(i))
                    put("azimuth", gnssStatus.getAzimuthDegrees(i))
                    put("elevation", gnssStatus.getElevationDegrees(i))
                    put("usedInFix", gnssStatus.usedInFix(i))
                })
            }

            return jsonResponse(JSONObject().apply {
                put("fixAcquired", usedCount > 0)
                put("satelliteCount", gnssStatus.satelliteCount)
                put("usedInFix", usedCount)
                put("satellites", satellites)
            })
        } finally {
            try {
                locationManager.unregisterGnssStatusCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering GNSS callback", e)
            }
        }
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "UNKNOWN($type)"
    }

    // Sensors endpoint
    private fun handleSensors(): Response {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val sensorsArray = JSONArray()

        for (sensor in sensors) {
            sensorsArray.put(JSONObject().apply {
                put("name", sensor.name)
                put("type", sensor.type)
                put("type_string", sensor.stringType)
                put("vendor", sensor.vendor)
                put("version", sensor.version)
                put("power_ma", sensor.power)
                put("max_range", sensor.maximumRange)
                put("resolution", sensor.resolution)
            })
        }

        val json = JSONObject().apply {
            put("count", sensors.size)
            put("sensors", sensorsArray)
        }
        return jsonResponse(json)
    }

    // Screen state endpoint
    private fun handleScreenState(): Response {
        val json = JSONObject().apply {
            put("screen_on", powerManager.isInteractive)

            try {
                val brightness = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
                put("brightness", brightness)
                put("brightness_percent", (brightness * 100 / 255))
            } catch (e: Settings.SettingNotFoundException) {
                put("brightness", -1)
            }
        }
        return jsonResponse(json)
    }

    // Bluetooth endpoint
    private fun handleBluetooth(): Response {
        val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (!hasPermission(btPermission)) {
            return permissionError(btPermission)
        }

        val bluetoothAdapter = bluetoothManager.adapter
        val json = JSONObject().apply {
            put("supported", bluetoothAdapter != null)
            if (bluetoothAdapter != null) {
                put("enabled", bluetoothAdapter.isEnabled)
                put("name", bluetoothAdapter.name)
                put("address", bluetoothAdapter.address)
                put("state", when (bluetoothAdapter.state) {
                    BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_ON -> "on"
                    BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                    BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                    else -> "unknown"
                })
            }
        }
        return jsonResponse(json)
    }

    // Set brightness endpoint
    private fun handleSetBrightness(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val brightness = data.optInt("brightness", -1)

        if (brightness < 0 || brightness > 255) {
            return errorResponse("Brightness must be between 0 and 255")
        }

        if (!Settings.System.canWrite(context)) {
            return errorResponse("Modify system settings permission required", Response.Status.FORBIDDEN)
        }

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightness
        )

        val json = JSONObject().apply {
            put("success", true)
            put("brightness", brightness)
        }
        return jsonResponse(json)
    }

    // Get volume endpoint
    private fun handleGetVolume(): Response {
        val json = JSONObject().apply {
            put("ring", JSONObject().apply {
                put("current", audioManager.getStreamVolume(AudioManager.STREAM_RING))
                put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_RING))
            })
            put("media", JSONObject().apply {
                put("current", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            })
            put("alarm", JSONObject().apply {
                put("current", audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
                put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM))
            })
            put("notification", JSONObject().apply {
                put("current", audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                put("max", audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION))
            })
            put("ringer_mode", when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> "unknown"
            })
        }
        return jsonResponse(json)
    }

    // Set volume endpoint
    private fun handleSetVolume(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.ACCESS_NOTIFICATION_POLICY)) {
            return permissionError(Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val stream = data.optString("stream", "media")
        val level = data.optInt("level", -1)

        val streamType = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "media" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return errorResponse("Invalid stream type")
        }

        if (level < 0 || level > audioManager.getStreamMaxVolume(streamType)) {
            return errorResponse("Invalid volume level")
        }

        audioManager.setStreamVolume(streamType, level, 0)

        val json = JSONObject().apply {
            put("success", true)
            put("stream", stream)
            put("level", level)
        }
        return jsonResponse(json)
    }

    // Vibrate endpoint
    private fun handleVibrate(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val duration = data.optLong("duration_ms", 200)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }

        val json = JSONObject().apply {
            put("success", true)
            put("duration_ms", duration)
        }
        return jsonResponse(json)
    }

    // Torch endpoint
    private fun handleTorch(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val enabled = data.optBoolean("enabled", false)

        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)

            val json = JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to set torch: ${e.message}")
        }
    }

    // Do Not Disturb endpoint
    private fun handleDnd(session: IHTTPSession): Response {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return errorResponse("Do Not Disturb access not granted", Response.Status.FORBIDDEN)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val mode = data.optString("mode", "normal")

        val interruptionFilter = when (mode) {
            "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "normal" -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> return errorResponse("Invalid mode")
        }

        notificationManager.setInterruptionFilter(interruptionFilter)

        val json = JSONObject().apply {
            put("success", true)
            put("mode", mode)
        }
        return jsonResponse(json)
    }

    // SMS list endpoint
    private fun handleSmsList(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return permissionError(Manifest.permission.READ_SMS)
        }

        val type = session.parms["type"] ?: "inbox"
        val limit = session.parms["limit"]?.toIntOrNull() ?: 50

        val uri = when (type) {
            "inbox" -> Telephony.Sms.Inbox.CONTENT_URI
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            else -> Telephony.Sms.CONTENT_URI
        }

        val messages = JSONArray()
        context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("address", cursor.getString(1))
                    put("body", cursor.getString(2))
                    put("date", cursor.getLong(3))
                    put("read", cursor.getInt(4) == 1)
                    put("type", cursor.getInt(5))
                })
            }
        }

        val filtered = runBlocking {
            withTimeoutOrNull(15_000L) {
                privacyGate.filterSmsMessages(messages)
            } ?: messages  // Fall back to unfiltered if privacy gate times out
        }
        val json = JSONObject().apply {
            put("count", filtered.length())
            put("messages", filtered)
        }
        return jsonResponse(json)
    }

    // Send SMS endpoint
    private fun handleSmsSend(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return permissionError(Manifest.permission.SEND_SMS)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val phoneNumber = data.optString("phone_number", "")
        val message = data.optString("message", "")

        if (phoneNumber.isEmpty() || message.isEmpty()) {
            return errorResponse("Missing phone_number or message")
        }

        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)

            val json = JSONObject().apply {
                put("success", true)
                put("phone_number", phoneNumber)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to send SMS: ${e.message}")
        }
    }

    // MMS list endpoint
    private fun handleMmsList(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return permissionError(Manifest.permission.READ_SMS)
        }

        val box = session.parms["box"] ?: "inbox"
        val limit = (session.parms["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (session.parms["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

        // Determine content URI based on box filter
        val uri = when (box) {
            "inbox" -> Telephony.Mms.Inbox.CONTENT_URI
            "sent" -> Telephony.Mms.Sent.CONTENT_URI
            "drafts" -> Telephony.Mms.Draft.CONTENT_URI
            "outbox" -> Telephony.Mms.Outbox.CONTENT_URI
            "all" -> Telephony.Mms.CONTENT_URI
            else -> return errorResponse("Invalid box type: $box. Use: inbox, sent, drafts, outbox, all")
        }

        val messages = JSONArray()
        context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.DATE_SENT,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.MESSAGE_SIZE,
                Telephony.Mms.TEXT_ONLY
            ),
            null,
            null,
            "${Telephony.Mms.DATE} DESC LIMIT $limit OFFSET $offset"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mmsId = cursor.getLong(0)

                // Fetch addresses for this MMS
                val addresses = getMmsAddresses(mmsId)

                // Fetch parts summary for this MMS
                val partsSummary = getMmsPartsSummary(mmsId)

                messages.put(JSONObject().apply {
                    put("id", mmsId)
                    put("thread_id", cursor.getLong(1))
                    put("date", cursor.getLong(2))
                    put("date_sent", cursor.getLong(3))
                    put("msg_box", cursor.getInt(4))
                    put("read", cursor.getInt(5) == 1)
                    put("subject", cursor.getString(6))
                    put("message_size", cursor.getInt(7))
                    put("text_only", cursor.getInt(8) == 1)
                    put("addresses", addresses)
                    put("parts_summary", partsSummary)
                })
            }
        }

        val json = JSONObject().apply {
            put("count", messages.length())
            put("offset", offset)
            put("messages", messages)
        }
        return jsonResponse(json)
    }

    /**
     * Fetch addresses (from/to/cc/bcc) for an MMS message.
     * Queries content://mms/{id}/addr
     */
    private fun getMmsAddresses(mmsId: Long): JSONArray {
        val addresses = JSONArray()
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        context.contentResolver.query(
            addrUri,
            arrayOf("address", "type"),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0) ?: continue
                val typeInt = cursor.getInt(1)
                // Skip the "insert-address-token" placeholder used for self
                if (address == "insert-address-token") continue
                val typeStr = when (typeInt) {
                    137 -> "from"   // PduHeaders.FROM
                    151 -> "to"     // PduHeaders.TO
                    130 -> "cc"     // PduHeaders.CC
                    129 -> "bcc"    // PduHeaders.BCC
                    else -> "unknown"
                }
                addresses.put(JSONObject().apply {
                    put("address", address)
                    put("type", typeStr)
                })
            }
        }
        return addresses
    }

    /**
     * Get a summary of MMS parts (count of text, image, other).
     * Queries content://mms/{id}/part
     */
    private fun getMmsPartsSummary(mmsId: Long): JSONObject {
        var textCount = 0
        var imageCount = 0
        var otherCount = 0
        val partUri = Uri.parse("content://mms/$mmsId/part")
        context.contentResolver.query(
            partUri,
            arrayOf("ct"),  // content type column
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(0) ?: continue
                when {
                    contentType.startsWith("text/") -> textCount++
                    contentType.startsWith("image/") -> imageCount++
                    contentType == "application/smil" -> {} // Skip SMIL layout parts
                    else -> otherCount++
                }
            }
        }
        return JSONObject().apply {
            put("text_count", textCount)
            put("image_count", imageCount)
            put("other_count", otherCount)
        }
    }

    // MMS read single message endpoint
    private fun handleMmsRead(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return permissionError(Manifest.permission.READ_SMS)
        }

        val mmsId = session.parms["id"]?.toLongOrNull()
            ?: return errorResponse("Missing or invalid 'id' parameter", Response.Status.BAD_REQUEST)
        val includeMedia = session.parms["include_media"]?.toBoolean() ?: false

        // Query MMS message metadata
        val mmsUri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, mmsId.toString())
        val json = JSONObject()

        context.contentResolver.query(
            mmsUri,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.DATE_SENT,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.MESSAGE_SIZE,
                Telephony.Mms.TEXT_ONLY,
                Telephony.Mms.CONTENT_TYPE,
                Telephony.Mms.MESSAGE_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return errorResponse("MMS message not found: $mmsId", Response.Status.NOT_FOUND)
            }
            json.put("id", cursor.getLong(0))
            json.put("thread_id", cursor.getLong(1))
            json.put("date", cursor.getLong(2))
            json.put("date_sent", cursor.getLong(3))
            json.put("msg_box", cursor.getInt(4))
            json.put("read", cursor.getInt(5) == 1)
            json.put("subject", cursor.getString(6))
            json.put("message_size", cursor.getInt(7))
            json.put("text_only", cursor.getInt(8) == 1)
            json.put("content_type", cursor.getString(9))
            json.put("message_type", cursor.getInt(10))
        } ?: return errorResponse("Failed to query MMS message")

        // Fetch addresses
        json.put("addresses", getMmsAddresses(mmsId))

        // Fetch parts with full detail
        json.put("parts", getMmsParts(mmsId, includeMedia))

        return jsonResponse(json)
    }

    /**
     * Fetch all parts for an MMS message.
     * For text/plain: includes inline text.
     * For images: includes base64 data if includeMedia=true, otherwise metadata only.
     */
    private fun getMmsParts(mmsId: Long, includeMedia: Boolean): JSONArray {
        val parts = JSONArray()
        val partUri = Uri.parse("content://mms/$mmsId/part")
        context.contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "text", "fn", "name", "chset", "_data"),
            null,
            null,
            "seq ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(0)
                val contentType = cursor.getString(1) ?: continue
                val text = cursor.getString(2)
                val filename = cursor.getString(3)
                val name = cursor.getString(4)
                val charset = if (cursor.isNull(5)) null else cursor.getInt(5)
                val dataPath = cursor.getString(6)

                // Skip SMIL layout parts (not useful to API consumers)
                if (contentType == "application/smil") continue

                val partObj = JSONObject().apply {
                    put("part_id", partId)
                    put("content_type", contentType)
                    put("filename", filename)
                    put("name", name)
                    if (charset != null) put("charset", charset)
                }

                if (contentType.startsWith("text/")) {
                    // Text part: read inline text or from content provider
                    val textContent = text ?: readMmsPartText(partId)
                    partObj.put("text", textContent)
                } else if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                    if (includeMedia && contentType.startsWith("image/")) {
                        // Read image data and encode as base64
                        val base64Data = readMmsPartBase64(partId)
                        partObj.put("data_base64", base64Data)
                    } else {
                        partObj.put("data_path", dataPath)
                    }
                }

                parts.put(partObj)
            }
        }
        return parts
    }

    /**
     * Read text content from an MMS part via content provider.
     * Used when the 'text' column is null but data exists.
     */
    private fun readMmsPartText(partId: Long): String? {
        return try {
            val partUri = Uri.parse("content://mms/part/$partId")
            context.contentResolver.openInputStream(partUri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MMS part text: $partId", e)
            null
        }
    }

    /**
     * Read binary MMS part data and return as base64-encoded string.
     */
    private fun readMmsPartBase64(partId: Long): String? {
        return try {
            val partUri = Uri.parse("content://mms/part/$partId")
            context.contentResolver.openInputStream(partUri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MMS part binary: $partId", e)
            null
        }
    }

    // MMS send endpoint
    private fun handleMmsSend(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return permissionError(Manifest.permission.SEND_SMS)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        // Parse recipients array
        val recipientsArr = data.optJSONArray("recipients")
            ?: return errorResponse("Missing 'recipients' array")
        if (recipientsArr.length() == 0) {
            return errorResponse("'recipients' array is empty")
        }
        val recipients = mutableListOf<String>()
        for (i in 0 until recipientsArr.length()) {
            recipients.add(recipientsArr.getString(i))
        }

        val message = data.optString("message", "")
        val subject = data.optString("subject", null as String?)
        val imageUri = data.optString("image_uri", null as String?)
        val imageBase64 = data.optString("image_base64", null as String?)
        val imageContentType = data.optString("image_content_type", "image/jpeg")

        if (message.isEmpty() && imageUri == null && imageBase64 == null) {
            return errorResponse("MMS must have either 'message' text or an image attachment")
        }

        try {
            // Build PDU
            val pduBytes = buildMmsPdu(recipients, message, subject, imageUri, imageBase64, imageContentType)
                ?: return errorResponse("Failed to construct MMS PDU")

            // Write PDU to temp file
            val pduFile = java.io.File(context.cacheDir, "mms_send_${System.currentTimeMillis()}.pdu")
            pduFile.writeBytes(pduBytes)

            // Get content URI via FileProvider
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )

            // Create sent intent for tracking
            val sentIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                android.content.Intent("com.mobilekinetic.agent.MMS_SENT"),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // Send via SmsManager
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }

            smsManager.sendMultimediaMessage(
                context,
                contentUri,
                null,   // locationUrl: null = use carrier default MMSC
                null,   // configOverrides: null = use defaults
                sentIntent
            )

            val json = JSONObject().apply {
                put("success", true)
                put("recipients", JSONArray(recipients))
                put("has_attachment", imageUri != null || imageBase64 != null)
                put("message_length", message.length)
            }
            return jsonResponse(json)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send MMS", e)
            return errorResponse("Failed to send MMS: ${e.message}")
        }
    }

    /**
     * Build MMS PDU byte array manually.
     *
     * Since PduComposer is hidden API, we construct the PDU using ContentValues
     * and the Telephony provider, then read back the composed PDU.
     *
     * Alternative approach: Build SendReq manually using WAP MMS encoding.
     * This uses the simpler content-provider-based approach.
     */
    private fun buildMmsPdu(
        recipients: List<String>,
        message: String,
        subject: String?,
        imageUri: String?,
        imageBase64: String?,
        imageContentType: String
    ): ByteArray? {
        try {
            // --- Approach: Insert into drafts, compose PDU, then delete ---
            // This leverages the system MMS provider to handle PDU construction.

            // Step 1: Insert MMS message into drafts
            val values = android.content.ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128) // SEND_REQ
                put(Telephony.Mms.MMS_VERSION, 0x12) // MMS 1.2
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                if (subject != null) put(Telephony.Mms.SUBJECT, subject)
            }

            val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
                ?: return null
            val mmsId = android.content.ContentUris.parseId(mmsUri)

            try {
                // Step 2: Add recipient addresses
                for (recipient in recipients) {
                    val addrValues = android.content.ContentValues().apply {
                        put("address", recipient)
                        put("type", 151)  // PduHeaders.TO
                        put("charset", 106) // UTF-8
                    }
                    context.contentResolver.insert(
                        Uri.parse("content://mms/$mmsId/addr"),
                        addrValues
                    )
                }

                // Step 3: Add text part
                if (message.isNotEmpty()) {
                    val textValues = android.content.ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", "text/plain")
                        put("chset", 106)  // UTF-8
                        put("text", message)
                        put("seq", 0)
                    }
                    context.contentResolver.insert(
                        Uri.parse("content://mms/$mmsId/part"),
                        textValues
                    )
                }

                // Step 4: Add image part (if provided)
                if (imageBase64 != null) {
                    val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                    val imagePartValues = android.content.ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", imageContentType)
                        put("name", "image.${imageContentType.substringAfter("/")}")
                        put("fn", "image.${imageContentType.substringAfter("/")}")
                        put("cid", "<image>")
                        put("seq", 1)
                    }
                    val partUri = context.contentResolver.insert(
                        Uri.parse("content://mms/$mmsId/part"),
                        imagePartValues
                    )
                    // Write image bytes to the part's content stream
                    if (partUri != null) {
                        context.contentResolver.openOutputStream(partUri)?.use { os ->
                            os.write(imageBytes)
                        }
                    }
                } else if (imageUri != null) {
                    // Copy image from source URI to MMS part
                    val sourceUri = Uri.parse(imageUri)
                    val mimeType = context.contentResolver.getType(sourceUri) ?: imageContentType
                    val imagePartValues = android.content.ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", mimeType)
                        put("name", "image.${mimeType.substringAfter("/")}")
                        put("fn", "image.${mimeType.substringAfter("/")}")
                        put("cid", "<image>")
                        put("seq", 1)
                    }
                    val partUri = context.contentResolver.insert(
                        Uri.parse("content://mms/$mmsId/part"),
                        imagePartValues
                    )
                    if (partUri != null) {
                        context.contentResolver.openInputStream(sourceUri)?.use { input ->
                            context.contentResolver.openOutputStream(partUri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // Step 5: Read back the composed PDU as bytes for SmsManager
                // We use the telephony content provider to serialize the PDU
                // The PDU is the raw bytes from the MMS provider
                val pduData = serializeMmsPdu(mmsId, recipients, message, subject, imageBase64, imageUri, imageContentType)

                return pduData

            } finally {
                // Clean up: delete the temporary MMS entry from provider
                // (SmsManager will create its own on send)
                try {
                    context.contentResolver.delete(mmsUri, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up temp MMS: $mmsId", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error building MMS PDU", e)
            return null
        }
    }

    /**
     * Manually serialize MMS PDU in WAP MMS 1.2 binary format.
     * This builds a minimal M-Send.req PDU.
     *
     * WSP/MMS binary encoding reference:
     * - OMA-TS-MMS_ENC-V1_2-20050301-A
     * - WAP-230-WSP-20010705-a
     */
    private fun serializeMmsPdu(
        mmsId: Long,
        recipients: List<String>,
        message: String,
        subject: String?,
        imageBase64: String?,
        imageUri: String?,
        imageContentType: String
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // --- MMS Header ---

        // X-Mms-Message-Type: m-send-req (0x80)
        baos.write(0x8C) // header field: X-Mms-Message-Type
        baos.write(0x80) // value: m-send-req

        // X-Mms-Transaction-Id
        val transactionId = "T${System.currentTimeMillis()}"
        baos.write(0x98) // header field: X-Mms-Transaction-Id
        baos.write(transactionId.toByteArray())
        baos.write(0x00) // null terminator

        // X-Mms-MMS-Version: 1.2
        baos.write(0x8D) // header field: X-Mms-MMS-Version
        baos.write(0x12) // value: 1.2

        // From: insert-address-token (let carrier fill in)
        baos.write(0x89) // header field: From
        baos.write(0x01) // length: 1
        baos.write(0x81) // Address-present-token = insert-address-token

        // To: recipients
        for (recipient in recipients) {
            baos.write(0x97) // header field: To
            val encodedRecipient = "$recipient/TYPE=PLMN"
            baos.write(encodedRecipient.toByteArray())
            baos.write(0x00) // null terminator
        }

        // Subject (optional)
        if (subject != null) {
            baos.write(0x96) // header field: Subject
            baos.write(0xEA) // charset: UTF-8
            baos.write(subject.toByteArray(Charsets.UTF_8))
            baos.write(0x00) // null terminator
        }

        // Content-Type: multipart/mixed or multipart/related
        val hasImage = imageBase64 != null || imageUri != null
        if (hasImage) {
            baos.write(0x84) // header field: Content-Type
            // application/vnd.wap.multipart.related (0xB3)
            baos.write(0xB3.toByte().toInt())
        } else {
            baos.write(0x84) // header field: Content-Type
            // application/vnd.wap.multipart.mixed (0xA3)
            baos.write(0xA3.toByte().toInt())
        }

        // --- Multipart body ---

        // Number of parts
        val partCount = (if (message.isNotEmpty()) 1 else 0) + (if (hasImage) 1 else 0)
        baos.write(partCount)

        // Text part
        if (message.isNotEmpty()) {
            val textBytes = message.toByteArray(Charsets.UTF_8)
            val contentTypeHeader = "text/plain; charset=utf-8".toByteArray()

            // Part headers length
            writeUintVar(baos, contentTypeHeader.size + 1) // +1 for null terminator
            // Part data length
            writeUintVar(baos, textBytes.size)
            // Content-Type
            baos.write(contentTypeHeader)
            baos.write(0x00)
            // Data
            baos.write(textBytes)
        }

        // Image part
        if (hasImage) {
            val imageBytes = if (imageBase64 != null) {
                android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
            } else if (imageUri != null) {
                context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { it.readBytes() }
            } else null

            if (imageBytes != null) {
                val ctHeader = "$imageContentType\u0000".toByteArray()

                writeUintVar(baos, ctHeader.size)
                writeUintVar(baos, imageBytes.size)
                baos.write(ctHeader)
                baos.write(imageBytes)
            }
        }

        return baos.toByteArray()
    }

    /**
     * Write a variable-length unsigned integer (UintVar) per WAP spec.
     */
    private fun writeUintVar(baos: java.io.ByteArrayOutputStream, value: Int) {
        val bytes = mutableListOf<Int>()
        var v = value
        bytes.add(v and 0x7F)
        v = v shr 7
        while (v > 0) {
            bytes.add((v and 0x7F) or 0x80)
            v = v shr 7
        }
        for (b in bytes.reversed()) {
            baos.write(b)
        }
    }

    // Call log endpoint
    private fun handleCallLog(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return permissionError(Manifest.permission.READ_CALL_LOG)
        }

        val limit = session.parms["limit"]?.toIntOrNull() ?: 50

        val calls = JSONArray()
        context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(
                android.provider.CallLog.Calls._ID,
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.DATE,
                android.provider.CallLog.Calls.DURATION,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.CACHED_NAME
            ),
            null,
            null,
            "${android.provider.CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                count++
                val callType = when (cursor.getInt(4)) {
                    android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                    android.provider.CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "unknown"
                }

                calls.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("number", cursor.getString(1))
                    put("date", cursor.getLong(2))
                    put("duration_seconds", cursor.getLong(3))
                    put("type", callType)
                    put("name", cursor.getString(5) ?: "Unknown")
                })
            }
        }

        val json = JSONObject().apply {
            put("count", calls.length())
            put("calls", calls)
        }
        return jsonResponse(json)
    }

    // Contacts endpoint
    private fun handleContacts(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return permissionError(Manifest.permission.READ_CONTACTS)
        }

        val limit = session.parms["limit"]?.toIntOrNull() ?: 100

        val contacts = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $limit"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                contacts.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("name", cursor.getString(1))
                    put("number", cursor.getString(2))
                    put("type", cursor.getInt(3))
                })
            }
        }

        val json = JSONObject().apply {
            put("count", contacts.length())
            put("contacts", contacts)
        }
        return jsonResponse(json)
    }

    // Share endpoint (enhanced with ShareCompat.IntentBuilder)
    private fun handleShare(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        val htmlText = data.optString("htmlText", "")
        val type = data.optString("type", "text/plain")
        val streamUri = data.optString("streamUri", "")
        val streamUris = data.optJSONArray("streamUris")
        val subject = data.optString("subject", "")
        val emailTo = data.optJSONArray("emailTo")
        val emailCc = data.optJSONArray("emailCc")
        val emailBcc = data.optJSONArray("emailBcc")
        val chooserTitle = data.optString("chooserTitle", "Share via")

        // Must have at least one content field
        val hasText = text.isNotEmpty()
        val hasHtml = htmlText.isNotEmpty()
        val hasSingleStream = streamUri.isNotEmpty()
        val hasMultiStream = streamUris != null && streamUris.length() > 0
        val hasStream = hasSingleStream || hasMultiStream

        if (!hasText && !hasHtml && !hasStream) {
            return errorResponse("At least one of text, htmlText, streamUri, or streamUris is required")
        }

        try {
            val builder = ShareCompat.IntentBuilder(context)
                .setType(type)
                .setChooserTitle(chooserTitle)

            if (hasText) builder.setText(text)
            if (hasHtml) builder.setHtmlText(htmlText)
            if (subject.isNotEmpty()) builder.setSubject(subject)

            // Stream handling
            if (hasSingleStream) {
                builder.setStream(android.net.Uri.parse(streamUri))
            }
            if (hasMultiStream) {
                for (i in 0 until streamUris!!.length()) {
                    builder.addStream(android.net.Uri.parse(streamUris.getString(i)))
                }
            }

            // Email recipients
            if (emailTo != null && emailTo.length() > 0) {
                val addresses = Array(emailTo.length()) { emailTo.getString(it) }
                builder.setEmailTo(addresses)
            }
            if (emailCc != null && emailCc.length() > 0) {
                val addresses = Array(emailCc.length()) { emailCc.getString(it) }
                builder.setEmailCc(addresses)
            }
            if (emailBcc != null && emailBcc.length() > 0) {
                val addresses = Array(emailBcc.length()) { emailBcc.getString(it) }
                builder.setEmailBcc(addresses)
            }

            val hasEmail = (emailTo != null && emailTo.length() > 0) ||
                           (emailCc != null && emailCc.length() > 0) ||
                           (emailBcc != null && emailBcc.length() > 0)

            // Launch chooser
            val chooserIntent = builder.createChooserIntent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)

            val totalStreams = when {
                hasMultiStream -> streamUris!!.length()
                hasSingleStream -> 1
                else -> 0
            }

            val json = JSONObject().apply {
                put("success", true)
                put("type", type)
                put("hasText", hasText)
                put("hasHtml", hasHtml)
                put("hasStream", hasStream)
                put("streamCount", totalStreams)
                put("hasEmail", hasEmail)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to share: ${e.message}")
        }
    }

    // Share received endpoint - returns last incoming share parsed via ShareCompat.IntentReader
    private fun handleShareReceived(): Response {
        val share = lastReceivedShare
            ?: return jsonResponse(JSONObject().apply {
                put("hasShare", false)
            })

        val json = JSONObject().apply {
            put("hasShare", true)
            put("isSingleShare", share.isSingleShare)
            put("isMultipleShare", share.isMultipleShare)
            put("type", share.type ?: JSONObject.NULL)
            put("text", share.text ?: JSONObject.NULL)
            put("htmlText", share.htmlText ?: JSONObject.NULL)
            put("subject", share.subject ?: JSONObject.NULL)
            put("streamUri", share.streamUri ?: JSONObject.NULL)
            put("streamCount", share.streamCount)
            put("streamUris", org.json.JSONArray(share.streamUris))
            put("callingPackage", share.callingPackage ?: JSONObject.NULL)
            put("callingActivity", share.callingActivity ?: JSONObject.NULL)
            put("emailTo", org.json.JSONArray(share.emailTo))
            put("emailCc", org.json.JSONArray(share.emailCc))
            put("emailBcc", org.json.JSONArray(share.emailBcc))
            put("receivedAt", share.receivedAt)
        }
        return jsonResponse(json)
    }

    // Calendar list endpoint
    private fun handleCalendarList(): Response {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return permissionError(Manifest.permission.READ_CALENDAR)
        }

        val calendars = JSONArray()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("name", cursor.getString(1))
                    put("account_name", cursor.getString(2))
                    put("account_type", cursor.getString(3))
                    put("owner", cursor.getString(4))
                    put("display_name", cursor.getString(5))
                })
            }
        }

        val json = JSONObject().apply {
            put("count", calendars.length())
            put("calendars", calendars)
        }
        return jsonResponse(json)
    }

    // Calendar events endpoint
    private fun handleCalendarEvents(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return permissionError(Manifest.permission.READ_CALENDAR)
        }

        val startTime = session.parms["start_time"]?.toLongOrNull() ?: System.currentTimeMillis()
        val endTime = session.parms["end_time"]?.toLongOrNull() ?: (startTime + 30L * 24 * 60 * 60 * 1000) // 30 days

        val events = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.ALL_DAY
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("title", cursor.getString(1) ?: "")
                    put("description", cursor.getString(2) ?: "")
                    put("start_time", cursor.getLong(3))
                    put("end_time", cursor.getLong(4))
                    put("location", cursor.getString(5) ?: "")
                    put("calendar_id", cursor.getLong(6))
                    put("all_day", cursor.getInt(7) == 1)
                })
            }
        }

        val json = JSONObject().apply {
            put("count", events.length())
            put("events", events)
        }
        return jsonResponse(json)
    }

    // Create calendar event endpoint
    private fun handleCalendarCreate(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return permissionError(Manifest.permission.WRITE_CALENDAR)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val calendarId = data.optLong("calendar_id", -1)
        val title = data.optString("title", "")
        val description = data.optString("description", "")
        val location = data.optString("location", "")
        val startTime = data.optLong("start_time", -1)
        val endTime = data.optLong("end_time", -1)
        val allDay = data.optBoolean("all_day", false)

        if (calendarId == -1L || title.isEmpty() || startTime == -1L || endTime == -1L) {
            return errorResponse("Missing required fields")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull()

        val json = JSONObject().apply {
            put("success", eventId != null)
            if (eventId != null) put("event_id", eventId)
        }
        return jsonResponse(json)
    }

    // Update calendar event endpoint
    private fun handleCalendarUpdate(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return permissionError(Manifest.permission.WRITE_CALENDAR)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val eventId = data.optLong("event_id", -1)
        if (eventId == -1L) {
            return errorResponse("Missing event_id")
        }

        val values = ContentValues()
        if (data.has("title")) values.put(CalendarContract.Events.TITLE, data.getString("title"))
        if (data.has("description")) values.put(CalendarContract.Events.DESCRIPTION, data.getString("description"))
        if (data.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, data.getString("location"))
        if (data.has("start_time")) values.put(CalendarContract.Events.DTSTART, data.getLong("start_time"))
        if (data.has("end_time")) values.put(CalendarContract.Events.DTEND, data.getLong("end_time"))
        if (data.has("all_day")) values.put(CalendarContract.Events.ALL_DAY, if (data.getBoolean("all_day")) 1 else 0)

        val updated = context.contentResolver.update(
            CalendarContract.Events.CONTENT_URI,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString())
        )

        val json = JSONObject().apply {
            put("success", updated > 0)
            put("rows_updated", updated)
        }
        return jsonResponse(json)
    }

    // Delete calendar event endpoint
    private fun handleCalendarDelete(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return permissionError(Manifest.permission.WRITE_CALENDAR)
        }

        val eventId = session.parms["event_id"]?.toLongOrNull()
            ?: return errorResponse("Missing event_id parameter")

        val deleted = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString())
        )

        val json = JSONObject().apply {
            put("success", deleted > 0)
            put("rows_deleted", deleted)
        }
        return jsonResponse(json)
    }

    // JTX Board content provider requires sync adapter URI params for all operations.
    // Builds a URI with caller_is_syncadapter=true and account credentials.
    private fun jtxUri(accountName: String, accountType: String): Uri {
        return Uri.parse("content://at.techbee.jtx.provider/icalobject")
            .buildUpon()
            .appendQueryParameter("caller_is_syncadapter", "true")
            .appendQueryParameter("account_name", accountName)
            .appendQueryParameter("account_type", accountType)
            .build()
    }

    // Find a VTODO-supporting collection ID for a given account
    private fun findVtodoCollectionId(accountName: String, accountType: String): Long? {
        try {
            val collectionUri = Uri.parse("content://at.techbee.jtx.provider/collection")
                .buildUpon()
                .appendQueryParameter("caller_is_syncadapter", "true")
                .appendQueryParameter("account_name", accountName)
                .appendQueryParameter("account_type", accountType)
                .build()
            context.contentResolver.query(
                collectionUri,
                arrayOf("_id", "supportsVTODO"),
                "supportsVTODO = ?",
                arrayOf("1"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // Discover DAVx5 accounts that have JTX collections
    private fun discoverJtxAccounts(): List<Pair<String, String>> {
        val accounts = mutableListOf<Pair<String, String>>()
        try {
            val collectionUri = Uri.parse("content://at.techbee.jtx.provider/collection")
            context.contentResolver.query(
                collectionUri.buildUpon()
                    .appendQueryParameter("caller_is_syncadapter", "true")
                    .appendQueryParameter("account_name", "*")
                    .appendQueryParameter("account_type", "bitfire.at.davdroid")
                    .build(),
                arrayOf("account_name", "account_type"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val type = cursor.getString(1) ?: continue
                    if (accounts.none { it.first == name && it.second == type }) {
                        accounts.add(name to type)
                    }
                }
            }
        } catch (_: Exception) { }
        // Fallback: if collection query fails, try known DAVx5 account type
        if (accounts.isEmpty()) {
            val am = android.accounts.AccountManager.get(context)
            am.getAccountsByType("bitfire.at.davdroid").forEach { acct ->
                accounts.add(acct.name to acct.type)
            }
        }
        return accounts
    }

    // Tasks list endpoint (jtx Board)
    private fun handleTasksList(session: IHTTPSession): Response {
        val limit = session.parms["limit"]?.toIntOrNull() ?: 100
        val accountName = session.parms["account_name"]
        val accountType = session.parms["account_type"] ?: "bitfire.at.davdroid"

        try {
            val tasks = JSONArray()
            // Determine which accounts to query
            val accounts = if (accountName != null) {
                listOf(accountName to accountType)
            } else {
                discoverJtxAccounts()
            }

            if (accounts.isEmpty()) {
                return errorResponse("No DAVx5/CalDAV accounts found for JTX Board")
            }

            for ((acctName, acctType) in accounts) {
                val uri = jtxUri(acctName, acctType)
                context.contentResolver.query(
                    uri,
                    arrayOf("_id", "summary", "description", "status", "priority", "dtstart", "due", "percent", "completed", "classification"),
                    "component = ?",
                    arrayOf("VTODO"),
                    "_id DESC LIMIT $limit"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        tasks.put(JSONObject().apply {
                            put("id", cursor.getLong(0))
                            put("summary", cursor.getString(1) ?: "")
                            put("description", cursor.getString(2) ?: "")
                            put("status", cursor.getString(3) ?: "NEEDS-ACTION")
                            put("priority", cursor.getInt(4))
                            put("start", cursor.getLong(5))
                            put("due", cursor.getLong(6))
                            put("percent", cursor.getInt(7))
                            put("completed", cursor.getLong(8))
                            put("classification", cursor.getString(9) ?: "PUBLIC")
                            put("account_name", acctName)
                        })
                    }
                }
            }

            val json = JSONObject().apply {
                put("count", tasks.length())
                put("tasks", tasks)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("jtx Board not installed or provider not available: ${e.message}")
        }
    }

    // Create task endpoint (jtx Board)
    private fun handleTasksCreate(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val summary = data.optString("summary", "")
        if (summary.isEmpty()) {
            return errorResponse("Missing summary")
        }

        val accountName = data.optString("account_name", "")
        val accountType = data.optString("account_type", "bitfire.at.davdroid")

        try {
            // If no account specified, use first discovered account
            val (acctName, acctType) = if (accountName.isNotEmpty()) {
                accountName to accountType
            } else {
                discoverJtxAccounts().firstOrNull()
                    ?: return errorResponse("No DAVx5/CalDAV accounts found for JTX Board")
            }

            val uri = jtxUri(acctName, acctType)

            // Find a VTODO-supporting collection for this account
            val collectionId = data.optLong("collection_id", 0).takeIf { it > 0 }
                ?: findVtodoCollectionId(acctName, acctType)
                ?: return errorResponse("No VTODO-supporting collection found for account: $acctName")

            val now = System.currentTimeMillis()
            val uid = java.util.UUID.randomUUID().toString()

            val values = ContentValues().apply {
                put("component", "VTODO")
                put("collectionId", collectionId)
                put("summary", summary)
                put("description", data.optString("description", ""))
                put("status", data.optString("status", "NEEDS-ACTION"))
                put("priority", data.optInt("priority", 0))
                if (data.has("start")) put("dtstart", data.getLong("start"))
                if (data.has("due")) put("due", data.getLong("due"))
                put("percent", data.optInt("percent", 0))
                if (data.has("classification")) put("classification", data.getString("classification"))
                // CalDAV sync-critical fields
                put("uid", uid)
                put("dirty", 1)
                put("dtstamp", now)
                put("created", now)
                put("lastModified", now)
                put("sequence", 0)
                put("filename", "$uid.ics")
            }

            val resultUri = context.contentResolver.insert(uri, values)
            val taskId = resultUri?.lastPathSegment?.toLongOrNull()

            val json = JSONObject().apply {
                put("success", taskId != null)
                if (taskId != null) put("task_id", taskId)
                put("account_name", acctName)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to create task: ${e.message}")
        }
    }

    // Update task endpoint (jtx Board)
    private fun handleTasksUpdate(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val taskId = data.optLong("task_id", -1)
        if (taskId == -1L) {
            return errorResponse("Missing task_id")
        }

        val accountName = data.optString("account_name", "")
        val accountType = data.optString("account_type", "bitfire.at.davdroid")

        try {
            val (acctName, acctType) = if (accountName.isNotEmpty()) {
                accountName to accountType
            } else {
                discoverJtxAccounts().firstOrNull()
                    ?: return errorResponse("No DAVx5/CalDAV accounts found for JTX Board")
            }

            val uri = jtxUri(acctName, acctType)
            val values = ContentValues()

            if (data.has("summary")) values.put("summary", data.getString("summary"))
            if (data.has("description")) values.put("description", data.getString("description"))
            if (data.has("status")) values.put("status", data.getString("status"))
            if (data.has("priority")) values.put("priority", data.getInt("priority"))
            if (data.has("start")) values.put("dtstart", data.getLong("start"))
            if (data.has("due")) values.put("due", data.getLong("due"))
            if (data.has("percent")) values.put("percent", data.getInt("percent"))
            if (data.has("classification")) values.put("classification", data.getString("classification"))
            // Mark dirty so DAVx5 syncs the change to CalDAV server
            values.put("dirty", 1)
            values.put("lastModified", System.currentTimeMillis())

            val updated = context.contentResolver.update(
                uri,
                values,
                "_id = ?",
                arrayOf(taskId.toString())
            )

            val json = JSONObject().apply {
                put("success", updated > 0)
                put("rows_updated", updated)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to update task: ${e.message}")
        }
    }

    // Delete task endpoint (jtx Board)
    private fun handleTasksDelete(session: IHTTPSession): Response {
        val taskId = session.parms["task_id"]?.toLongOrNull()
            ?: return errorResponse("Missing task_id parameter")
        val accountName = session.parms["account_name"] ?: ""
        val accountType = session.parms["account_type"] ?: "bitfire.at.davdroid"

        try {
            val (acctName, acctType) = if (accountName.isNotEmpty()) {
                accountName to accountType
            } else {
                discoverJtxAccounts().firstOrNull()
                    ?: return errorResponse("No DAVx5/CalDAV accounts found for JTX Board")
            }

            val uri = jtxUri(acctName, acctType)
            // Soft-delete: mark deleted + dirty so DAVx5 sends DELETE to CalDAV server
            val values = ContentValues().apply {
                put("deleted", 1)
                put("dirty", 1)
            }
            val updated = context.contentResolver.update(
                uri,
                values,
                "_id = ?",
                arrayOf(taskId.toString())
            )

            val json = JSONObject().apply {
                put("success", updated > 0)
                put("rows_affected", updated)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to delete task: ${e.message}")
        }
    }

    // Set alarm endpoint
    private fun handleAlarmSet(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val time = data.optLong("time", -1)
        val message = data.optString("message", "Alarm")

        if (time == -1L) {
            return errorResponse("Missing time")
        }

        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                val cal = Calendar.getInstance().apply { timeInMillis = time }
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("message", message)
                put("time", time)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to set alarm: ${e.message}")
        }
    }

    // Advanced alarm scheduling with AlarmManager
    private fun handleAlarmSchedule(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val triggerTimeMs = requestBody.getLong("trigger_time_ms")
        val alarmType = requestBody.optString("alarm_type", "exact")
        val message = requestBody.optString("message", "")
        val wakeDevice = requestBody.optBoolean("wake_device", true)
        val intervalMs = requestBody.optLong("interval_ms", 0)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create PendingIntent for the alarm
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.mobilekinetic.agent.ALARM_TRIGGERED"
            putExtra("message", message)
            putExtra("alarm_id", System.currentTimeMillis().toString())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            triggerTimeMs.toInt(), // Use trigger time as request code for uniqueness
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            when (alarmType) {
                "exact" -> {
                    // Exact alarm - requires permission check on Android 12+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (!alarmManager.canScheduleExactAlarms()) {
                            val response = JSONObject().apply {
                                put("success", false)
                                put("error", "SCHEDULE_EXACT_ALARM permission not granted")
                                put("action_required", "Request permission in app settings")
                            }
                            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", response.toString())
                        }
                    }

                    if (wakeDevice) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTimeMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC,
                            triggerTimeMs,
                            pendingIntent
                        )
                    }
                }

                "inexact" -> {
                    if (wakeDevice) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTimeMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC,
                            triggerTimeMs,
                            pendingIntent
                        )
                    }
                }

                "window" -> {
                    val windowMs = requestBody.optLong("window_ms", 600000) // 10 min default
                    alarmManager.setWindow(
                        if (wakeDevice) AlarmManager.RTC_WAKEUP else AlarmManager.RTC,
                        triggerTimeMs,
                        windowMs,
                        pendingIntent
                    )
                }

                "repeating" -> {
                    val interval = if (intervalMs > 0) intervalMs else AlarmManager.INTERVAL_HOUR
                    alarmManager.setInexactRepeating(
                        if (wakeDevice) AlarmManager.RTC_WAKEUP else AlarmManager.RTC,
                        triggerTimeMs,
                        interval,
                        pendingIntent
                    )
                }

                "alarm_clock" -> {
                    // Most critical alarm type - shows in system UI
                    val info = AlarmManager.AlarmClockInfo(
                        triggerTimeMs,
                        pendingIntent
                    )
                    alarmManager.setAlarmClock(info, pendingIntent)
                }

                else -> {
                    val response = JSONObject().apply {
                        put("success", false)
                        put("error", "Unknown alarm type: $alarmType")
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", response.toString())
                }
            }

            val response = JSONObject().apply {
                put("success", true)
                put("alarm_id", triggerTimeMs.toString())
                put("trigger_time_ms", triggerTimeMs)
                put("alarm_type", alarmType)
                put("wake_device", wakeDevice)
                if (intervalMs > 0) put("interval_ms", intervalMs)
                put("message", message)
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())

        } catch (e: Exception) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", response.toString())
        }
    }

    // Cancel scheduled alarm
    private fun handleAlarmCancel(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val alarmId = requestBody.optString("alarm_id", "")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (alarmId.isNotEmpty()) {
            // Cancel specific alarm by ID
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.mobilekinetic.agent.ALARM_TRIGGERED"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId.toLongOrNull()?.toInt() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            val response = JSONObject().apply {
                put("success", true)
                put("action", "alarm_cancelled")
                put("alarm_id", alarmId)
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } else {
            // Cancel all alarms (requires maintaining a list of alarm IDs)
            val response = JSONObject().apply {
                put("success", false)
                put("error", "Alarm ID required for cancellation")
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", response.toString())
        }
    }

    // Enhanced notification endpoint
    private fun handleNotificationSend(session: IHTTPSession): Response {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                return permissionError(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val title = data.optString("title", "Notification")
        val message = data.optString("message", "")
        val id = data.optInt("id", System.currentTimeMillis().toInt())

        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(data.optBoolean("auto_cancel", true))

            // Priority
            val priority = when (data.optString("priority", "default")) {
                "min" -> NotificationCompat.PRIORITY_MIN
                "low" -> NotificationCompat.PRIORITY_LOW
                "high" -> NotificationCompat.PRIORITY_HIGH
                "max" -> NotificationCompat.PRIORITY_MAX
                else -> NotificationCompat.PRIORITY_DEFAULT
            }
            builder.setPriority(priority)

            // Category
            val category = data.optString("category", "")
            if (category.isNotBlank()) {
                val androidCategory = when (category) {
                    "msg" -> NotificationCompat.CATEGORY_MESSAGE
                    "email" -> NotificationCompat.CATEGORY_EMAIL
                    "call" -> NotificationCompat.CATEGORY_CALL
                    "alarm" -> NotificationCompat.CATEGORY_ALARM
                    "reminder" -> NotificationCompat.CATEGORY_REMINDER
                    "event" -> NotificationCompat.CATEGORY_EVENT
                    "promo" -> NotificationCompat.CATEGORY_PROMO
                    "social" -> NotificationCompat.CATEGORY_SOCIAL
                    "err" -> NotificationCompat.CATEGORY_ERROR
                    "transport" -> NotificationCompat.CATEGORY_TRANSPORT
                    "sys" -> NotificationCompat.CATEGORY_SYSTEM
                    "service" -> NotificationCompat.CATEGORY_SERVICE
                    "progress" -> NotificationCompat.CATEGORY_PROGRESS
                    "status" -> NotificationCompat.CATEGORY_STATUS
                    else -> category // pass through raw string
                }
                builder.setCategory(androidCategory)
            }

            // Group
            val group = data.optString("group", "")
            if (group.isNotBlank()) {
                builder.setGroup(group)
                if (data.optBoolean("group_summary", false)) {
                    builder.setGroupSummary(true)
                }
            }

            // Ongoing
            if (data.optBoolean("ongoing", false)) {
                builder.setOngoing(true)
            }

            // Silent
            if (data.optBoolean("silent", false)) {
                builder.setSilent(true)
            }

            // Sub-text
            val subText = data.optString("sub_text", "")
            if (subText.isNotBlank()) {
                builder.setSubText(subText)
            }

            // Ticker
            val ticker = data.optString("ticker", "")
            if (ticker.isNotBlank()) {
                builder.setTicker(ticker)
            }

            // Timestamp
            if (data.has("timestamp")) {
                builder.setWhen(data.getLong("timestamp"))
                builder.setShowWhen(true)
            }
            if (data.has("show_timestamp")) {
                builder.setShowWhen(data.getBoolean("show_timestamp"))
            }

            // Timeout
            if (data.has("timeout_after")) {
                builder.setTimeoutAfter(data.getLong("timeout_after"))
            }

            // Color
            val color = data.optString("color", "")
            if (color.isNotBlank() && color.startsWith("#")) {
                try {
                    builder.setColor(android.graphics.Color.parseColor(color))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid color: $color")
                }
            }

            // Large icon (URL-based, loaded synchronously on NanoHTTPD thread)
            val largeIconUrl = data.optString("large_icon_url", "")
            if (largeIconUrl.isNotBlank()) {
                try {
                    val bitmap = BitmapFactory.decodeStream(URL(largeIconUrl).openStream())
                    if (bitmap != null) {
                        builder.setLargeIcon(bitmap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load large icon from URL: $largeIconUrl", e)
                }
            }

            // Deep link (content intent)
            val deepLink = data.optString("deep_link", "")
            if (deepLink.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
            }

            // Action buttons (max 3)
            val actionsArray = data.optJSONArray("actions")
            if (actionsArray != null) {
                val count = minOf(actionsArray.length(), 3)
                for (i in 0 until count) {
                    val actionObj = actionsArray.getJSONObject(i)
                    val actionTitle = actionObj.getString("title")
                    val actionDeepLink = actionObj.getString("deep_link")

                    val actionIntent = Intent(Intent.ACTION_VIEW, Uri.parse(actionDeepLink))
                    actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val actionPendingIntent = PendingIntent.getActivity(
                        context, id + i + 1, actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    // Map icon name to resource, default to ic_menu_send
                    val iconName = actionObj.optString("icon", "ic_menu_send")
                    val iconRes = try {
                        val field = android.R.drawable::class.java.getField(iconName)
                        field.getInt(null)
                    } catch (e: Exception) {
                        android.R.drawable.ic_menu_send
                    }

                    builder.addAction(iconRes, actionTitle, actionPendingIntent)
                }
            }

            // Style
            val style = data.optString("style", "")
            val styleData = data.optJSONObject("style_data")
            if (style.isNotBlank() && styleData != null) {
                when (style) {
                    "big_text" -> {
                        val bigTextStyle = NotificationCompat.BigTextStyle()
                            .bigText(styleData.optString("big_text", message))
                        val bigContentTitle = styleData.optString("big_content_title", "")
                        if (bigContentTitle.isNotBlank()) {
                            bigTextStyle.setBigContentTitle(bigContentTitle)
                        }
                        val summaryText = styleData.optString("summary_text", "")
                        if (summaryText.isNotBlank()) {
                            bigTextStyle.setSummaryText(summaryText)
                        }
                        builder.setStyle(bigTextStyle)
                    }
                    "big_picture" -> {
                        val pictureUrl = styleData.optString("picture_url", "")
                        if (pictureUrl.isNotBlank()) {
                            try {
                                val pictureBitmap = BitmapFactory.decodeStream(URL(pictureUrl).openStream())
                                if (pictureBitmap != null) {
                                    val bigPicStyle = NotificationCompat.BigPictureStyle()
                                        .bigPicture(pictureBitmap)
                                    val bigContentTitle = styleData.optString("big_content_title", "")
                                    if (bigContentTitle.isNotBlank()) {
                                        bigPicStyle.setBigContentTitle(bigContentTitle)
                                    }
                                    val summaryText = styleData.optString("summary_text", "")
                                    if (summaryText.isNotBlank()) {
                                        bigPicStyle.setSummaryText(summaryText)
                                    }
                                    if (styleData.optBoolean("show_when_collapsed", false)) {
                                        bigPicStyle.showBigPictureWhenCollapsed(true)
                                    }
                                    builder.setStyle(bigPicStyle)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load big picture from URL: $pictureUrl", e)
                            }
                        }
                    }
                    "messaging" -> {
                        val userName = styleData.optString("user_name", "Me")
                        val user = Person.Builder().setName(userName).build()
                        val messagingStyle = NotificationCompat.MessagingStyle(user)

                        val convTitle = styleData.optString("conversation_title", "")
                        if (convTitle.isNotBlank()) {
                            messagingStyle.setConversationTitle(convTitle)
                        }
                        if (styleData.optBoolean("is_group", false)) {
                            messagingStyle.setGroupConversation(true)
                        }

                        val messagesArr = styleData.optJSONArray("messages")
                        if (messagesArr != null) {
                            for (i in 0 until messagesArr.length()) {
                                val msgObj = messagesArr.getJSONObject(i)
                                val msgText = msgObj.optString("text", "")
                                val msgTimestamp = msgObj.optLong("timestamp", System.currentTimeMillis())
                                val senderName = msgObj.optString("sender", "")

                                val sender = if (senderName.isNotBlank()) {
                                    Person.Builder().setName(senderName).build()
                                } else {
                                    null // null sender = current user
                                }

                                messagingStyle.addMessage(msgText, msgTimestamp, sender)
                            }
                        }

                        builder.setStyle(messagingStyle)
                    }
                    else -> {
                        Log.w(TAG, "Unknown notification style: $style")
                    }
                }
            }

            val notification = builder.build()
            notificationManager.notify(id, notification)

            val json = JSONObject().apply {
                put("success", true)
                put("notification_id", id)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to show notification: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device API Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from Device API Server"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Toast endpoint
    private fun handleToast(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val message = data.optString("message", "")
        val duration = if (data.optString("duration", "short") == "long")
            Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        if (message.isEmpty()) {
            return errorResponse("Missing message")
        }

        val latch = CountDownLatch(1)
        var success = false

        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, message, duration).show()
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show toast", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)

        val json = JSONObject().apply {
            put("success", success)
        }
        return jsonResponse(json)
    }

    // TTS endpoint
    private fun handleTts(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse("Missing text")
        }

        if (!ttsReady || tts == null) {
            return errorResponse("Text-to-speech not ready")
        }

        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)

            val json = JSONObject().apply {
                put("success", true)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to speak: ${e.message}")
        }
    }

    // Media playing endpoint
    private fun handleMediaPlaying(): Response {
        val json = JSONObject().apply {
            put("music_active", audioManager.isMusicActive)
            put("mode", when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "normal"
                AudioManager.MODE_RINGTONE -> "ringtone"
                AudioManager.MODE_IN_CALL -> "in_call"
                AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
                else -> "unknown"
            })
        }
        return jsonResponse(json)
    }

    // Media control endpoint
    private fun handleMediaControl(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val action = data.optString("action", "")
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return errorResponse("Invalid action")
        }

        try {
            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)

            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)

            val json = JSONObject().apply {
                put("success", true)
                put("action", action)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to control media: ${e.message}")
        }
    }

    // Apps list endpoint
    private fun handleAppsList(): Response {
        val apps = JSONArray()
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (app in packages) {
            apps.put(JSONObject().apply {
                put("package_name", app.packageName)
                put("app_name", pm.getApplicationLabel(app).toString())
                put("enabled", app.enabled)
                put("system_app", (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            })
        }

        val json = JSONObject().apply {
            put("count", apps.length())
            put("apps", apps)
        }
        return jsonResponse(json)
    }

    // Launch app endpoint
    private fun handleAppsLaunch(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val packageName = data.optString("package_name", "")
        if (packageName.isEmpty()) {
            return errorResponse("Missing package_name")
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                val json = JSONObject().apply {
                    put("success", true)
                    put("package_name", packageName)
                }
                return jsonResponse(json)
            } else {
                return errorResponse("App not found or not launchable")
            }
        } catch (e: Exception) {
            return errorResponse("Failed to launch app: ${e.message}")
        }
    }

    // App info endpoint - detailed package information
    private fun handleAppsInfo(session: IHTTPSession): Response {
        val packageName = session.parms["package"] ?: return errorResponse("Missing 'package' query param", Response.Status.BAD_REQUEST)
        val pm = context.packageManager
        try {
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
            val pkgInfo = pm.getPackageInfo(packageName, flags)
            val appInfo = pkgInfo.applicationInfo
                ?: return errorResponse("No applicationInfo for: $packageName", Response.Status.NOT_FOUND)

            val json = JSONObject().apply {
                put("package_name", pkgInfo.packageName)
                put("app_name", pm.getApplicationLabel(appInfo).toString())
                put("version_name", pkgInfo.versionName ?: "")
                put("version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                })
                put("target_sdk", appInfo.targetSdkVersion)
                put("min_sdk", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else -1)
                put("enabled", appInfo.enabled)
                put("system_app", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                put("debuggable", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                put("first_install_time", pkgInfo.firstInstallTime)
                put("last_update_time", pkgInfo.lastUpdateTime)
                put("data_dir", appInfo.dataDir ?: "")
                put("source_dir", appInfo.sourceDir ?: "")

                // Installer source
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val installSource = pm.getInstallSourceInfo(packageName)
                        put("installing_package", installSource.installingPackageName ?: "unknown")
                        put("initiating_package", installSource.initiatingPackageName ?: "unknown")
                        put("originating_package", installSource.originatingPackageName ?: "unknown")
                    } else {
                        @Suppress("DEPRECATION")
                        put("installer_package", pm.getInstallerPackageName(packageName) ?: "unknown")
                    }
                } catch (e: Exception) {
                    put("installer_package", "unknown")
                }

                // Permissions
                val permissions = JSONArray()
                val permFlags = pkgInfo.requestedPermissionsFlags
                pkgInfo.requestedPermissions?.forEachIndexed { index, perm ->
                    val granted = if (permFlags != null) {
                        (permFlags[index] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    } else false
                    permissions.put(JSONObject().apply {
                        put("permission", perm)
                        put("granted", granted)
                    })
                }
                put("permissions", permissions)
            }
            return jsonResponse(json)
        } catch (e: PackageManager.NameNotFoundException) {
            return errorResponse("Package not found: $packageName", Response.Status.NOT_FOUND)
        } catch (e: Exception) {
            return errorResponse("Failed to get app info: ${e.message}")
        }
    }

    // Resolve intent activities endpoint
    private fun handleAppsResolve(session: IHTTPSession): Response {
        val action = session.parms["action"] ?: return errorResponse("Missing 'action' query param", Response.Status.BAD_REQUEST)
        val dataUri = session.parms["data"]
        val mimeType = session.parms["mimeType"]

        try {
            val pm = context.packageManager
            val intent = Intent(action).apply {
                if (dataUri != null && mimeType != null) {
                    setDataAndType(Uri.parse(dataUri), mimeType)
                } else if (dataUri != null) {
                    data = Uri.parse(dataUri)
                } else if (mimeType != null) {
                    type = mimeType
                }
            }

            val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val resolvedArray = JSONArray()
            for (ri in activities) {
                resolvedArray.put(JSONObject().apply {
                    put("packageName", ri.activityInfo.packageName)
                    put("activityName", ri.activityInfo.name)
                    put("label", ri.loadLabel(pm).toString())
                })
            }

            val json = JSONObject().apply {
                put("action", action)
                put("count", resolvedArray.length())
                put("resolvedActivities", resolvedArray)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to resolve intent: ${e.message}")
        }
    }

    // Find apps by permission endpoint
    private fun handleAppsFindByPermission(session: IHTTPSession): Response {
        val permission = session.parms["permission"] ?: return errorResponse("Missing 'permission' query param", Response.Status.BAD_REQUEST)
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_PERMISSIONS
            val packages = JSONArray()

            for (pkgInfo in pm.getInstalledPackages(flags)) {
                val perms = pkgInfo.requestedPermissions ?: continue
                val permFlags = pkgInfo.requestedPermissionsFlags ?: continue
                perms.forEachIndexed { index, perm ->
                    if (perm == permission && (permFlags[index] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        val ai = pkgInfo.applicationInfo
                        packages.put(JSONObject().apply {
                            put("packageName", pkgInfo.packageName)
                            put("appName", if (ai != null) pm.getApplicationLabel(ai).toString() else pkgInfo.packageName)
                        })
                    }
                }
            }

            val json = JSONObject().apply {
                put("permission", permission)
                put("count", packages.length())
                put("packages", packages)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to find apps by permission: ${e.message}")
        }
    }

    // Get changed packages endpoint
    private fun handleAppsChanges(session: IHTTPSession): Response {
        val sequenceNumber = session.parms["sequenceNumber"]?.toIntOrNull() ?: 0
        try {
            val pm = context.packageManager
            val changed = pm.getChangedPackages(sequenceNumber)
            val json = JSONObject().apply {
                if (changed != null) {
                    put("sequenceNumber", changed.sequenceNumber)
                    val pkgArray = JSONArray()
                    for (pkg in changed.packageNames) {
                        pkgArray.put(pkg)
                    }
                    put("changedPackages", pkgArray)
                } else {
                    put("sequenceNumber", sequenceNumber)
                    put("changedPackages", JSONArray())
                    put("note", "No changes since sequence $sequenceNumber or sequence invalid")
                }
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get changed packages: ${e.message}")
        }
    }

    // Get app components endpoint
    private fun handleAppsComponents(session: IHTTPSession): Response {
        val packageName = session.parms["package"] ?: return errorResponse("Missing 'package' query param", Response.Status.BAD_REQUEST)
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
            val pkgInfo = pm.getPackageInfo(packageName, flags)

            val activitiesArray = JSONArray()
            pkgInfo.activities?.forEach { ai ->
                activitiesArray.put(JSONObject().apply {
                    put("name", ai.name)
                    put("exported", ai.exported)
                    put("enabled", ai.enabled)
                    put("label", ai.loadLabel(pm)?.toString() ?: ai.name)
                })
            }

            val servicesArray = JSONArray()
            pkgInfo.services?.forEach { si ->
                servicesArray.put(JSONObject().apply {
                    put("name", si.name)
                    put("exported", si.exported)
                    put("enabled", si.enabled)
                })
            }

            val receiversArray = JSONArray()
            pkgInfo.receivers?.forEach { ri ->
                receiversArray.put(JSONObject().apply {
                    put("name", ri.name)
                    put("exported", ri.exported)
                    put("enabled", ri.enabled)
                })
            }

            val json = JSONObject().apply {
                put("package_name", packageName)
                put("activities", activitiesArray)
                put("activity_count", activitiesArray.length())
                put("services", servicesArray)
                put("service_count", servicesArray.length())
                put("receivers", receiversArray)
                put("receiver_count", receiversArray.length())
            }
            return jsonResponse(json)
        } catch (e: PackageManager.NameNotFoundException) {
            return errorResponse("Package not found: $packageName", Response.Status.NOT_FOUND)
        } catch (e: Exception) {
            return errorResponse("Failed to get components: ${e.message}")
        }
    }

    // Get default app handlers endpoint
    private fun handleAppsDefaults(): Response {
        try {
            val pm = context.packageManager

            fun resolveDefault(intent: Intent): JSONObject {
                val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                return JSONObject().apply {
                    if (ri != null && ri.activityInfo != null) {
                        put("package", ri.activityInfo.packageName)
                        put("activity", ri.activityInfo.name)
                        put("label", ri.loadLabel(pm).toString())
                    } else {
                        put("package", JSONObject.NULL)
                        put("activity", JSONObject.NULL)
                    }
                }
            }

            val json = JSONObject().apply {
                // Default browser
                put("browser", resolveDefault(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))))
                // Default SMS
                put("sms", resolveDefault(Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))))
                // Default dialer
                put("dialer", resolveDefault(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))))
                // Default launcher
                put("launcher", resolveDefault(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
                // Default email
                put("email", resolveDefault(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))))
                // Default camera
                put("camera", resolveDefault(Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
                // Default maps
                put("maps", resolveDefault(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))))
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get default apps: ${e.message}")
        }
    }

    // Get device system features endpoint
    private fun handleDeviceFeatures(): Response {
        try {
            val pm = context.packageManager
            val featureInfos = pm.systemAvailableFeatures
            val features = JSONArray()

            for (fi in featureInfos) {
                features.put(JSONObject().apply {
                    put("name", fi.name ?: "OpenGL ES")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        put("version", fi.version)
                    } else {
                        put("version", 0)
                    }
                    if (fi.name == null) {
                        // GL ES version
                        put("glEsVersion", fi.glEsVersion)
                    }
                })
            }

            val json = JSONObject().apply {
                put("count", features.length())
                put("features", features)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get device features: ${e.message}")
        }
    }

    // Get device modules endpoint (API 29+)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleDeviceModules(): Response {
        try {
            val pm = context.packageManager
            val moduleInfos = pm.getInstalledModules(0)
            val modules = JSONArray()

            for (mi in moduleInfos) {
                modules.put(JSONObject().apply {
                    put("name", mi.name)
                    put("packageName", mi.packageName)
                })
            }

            val json = JSONObject().apply {
                put("count", modules.length())
                put("modules", modules)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get device modules: ${e.message}")
        }
    }

    // Get clipboard endpoint
    private fun handleClipboardGet(): Response {
        val latch = CountDownLatch(1)
        var clipText = ""
        var success = false

        Handler(Looper.getMainLooper()).post {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clipText = clip.getItemAt(0).text?.toString() ?: ""
                    success = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get clipboard", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)

        val json = JSONObject().apply {
            put("success", success)
            put("text", clipText)
        }
        return jsonResponse(json)
    }

    // Set clipboard endpoint
    private fun handleClipboardSet(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")

        val latch = CountDownLatch(1)
        var success = false

        Handler(Looper.getMainLooper()).post {
            try {
                val clip = ClipData.newPlainText("text", text)
                clipboardManager.setPrimaryClip(clip)
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set clipboard", e)
            } finally {
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)

        val json = JSONObject().apply {
            put("success", success)
        }
        return jsonResponse(json)
    }

    // Run Tasker task endpoint
    private fun handleTaskerRun(session: IHTTPSession): Response {
        try {
            context.packageManager.getPackageInfo("net.dinglisch.android.taskerm", 0)
        } catch (e: Exception) {
            return errorResponse("Tasker is not installed")
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val taskName = data.optString("task_name", "")
        if (taskName.isEmpty()) {
            return errorResponse("Missing task_name")
        }

        try {
            val intent = Intent("net.dinglisch.android.tasker.ACTION_TASK").apply {
                putExtra("task_name", taskName)
                setPackage("net.dinglisch.android.taskerm")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            context.sendBroadcast(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("task_name", taskName)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to run Tasker task: ${e.message}")
        }
    }

    // Get Tasker tasks endpoint
    private fun handleTaskerTasks(): Response {
        val json = JSONObject().apply {
            put("message", "Tasker does not expose task list via API")
            put("tasks", JSONArray())
        }
        return jsonResponse(json)
    }

    // Set Tasker variable endpoint
    private fun handleTaskerSetVariable(session: IHTTPSession): Response {
        try {
            context.packageManager.getPackageInfo("net.dinglisch.android.taskerm", 0)
        } catch (e: Exception) {
            return errorResponse("Tasker is not installed")
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val name = data.optString("name", "")
        val value = data.optString("value", "")

        if (name.isEmpty()) {
            return errorResponse("Missing variable name")
        }

        try {
            val intent = Intent("net.dinglisch.android.tasker.ACTION_SET_VARIABLE").apply {
                putExtra("name", name)
                putExtra("value", value)
                setPackage("net.dinglisch.android.taskerm")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            context.sendBroadcast(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("name", name)
                put("value", value)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to set Tasker variable: ${e.message}")
        }
    }

    // Tasker profile endpoint
    private fun handleTaskerProfile(session: IHTTPSession): Response {
        try {
            context.packageManager.getPackageInfo("net.dinglisch.android.taskerm", 0)
        } catch (e: Exception) {
            return errorResponse("Tasker is not installed")
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val profileName = data.optString("profile_name", "")
        val enabled = data.optBoolean("enabled", true)

        if (profileName.isEmpty()) {
            return errorResponse("Missing profile_name")
        }

        try {
            val intent = Intent("net.dinglisch.android.tasker.ACTION_PROFILE").apply {
                putExtra("profile_name", profileName)
                putExtra("enabled", enabled)
                setPackage("net.dinglisch.android.taskerm")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            context.sendBroadcast(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("profile_name", profileName)
                put("enabled", enabled)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to control Tasker profile: ${e.message}")
        }
    }

    // Recent photos endpoint
    private fun handlePhotosRecent(session: IHTTPSession): Response {
        val photosPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasPermission(photosPermission)) {
            return permissionError(photosPermission)
        }

        val limit = session.parms["limit"]?.toIntOrNull() ?: 20

        val photos = JSONArray()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        )
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: LIMIT in sortOrder is rejected; use Bundle-based query
            val queryArgs = Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_TAKEN))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null)
        } else {
            // API 26-29: LIMIT in sortOrder still works
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"
            )
        }
        cursor?.use {
            while (cursor.moveToNext()) {
                photos.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("name", cursor.getString(1))
                    put("date_taken", cursor.getLong(2))
                    put("size_bytes", cursor.getLong(3))
                    put("width", cursor.getInt(4))
                    put("height", cursor.getInt(5))
                    put("path", cursor.getString(6))
                })
            }
        }

        val json = JSONObject().apply {
            put("count", photos.length())
            put("photos", photos)
        }
        return jsonResponse(json)
    }

    // Downloads endpoint
    private fun handleDownloads(session: IHTTPSession): Response {
        val limit = session.parms["limit"]?.toIntOrNull() ?: 50

        val downloads = JSONArray()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.DATA
            ),
            null,
            null,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                count++
                downloads.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("name", cursor.getString(1))
                    put("date_added", cursor.getLong(2))
                    put("size_bytes", cursor.getLong(3))
                    put("mime_type", cursor.getString(4) ?: "unknown")
                    put("path", cursor.getString(5))
                })
            }
        }

        val json = JSONObject().apply {
            put("count", downloads.length())
            put("downloads", downloads)
        }
        return jsonResponse(json)
    }

    // Lifecycle methods
    fun startServer() {
        try {
            start()
            Log.i(TAG, "DeviceApiServer started on port $DEFAULT_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DeviceApiServer", e)
        }
    }

    // Network capabilities endpoint
    private fun handleNetworkCapabilities(): Response {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val response = JSONObject()

        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

        if (activeNetwork != null && networkCapabilities != null) {
            response.put("connected", true)

            // Transport types
            val transports = JSONArray()
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                transports.put("WIFI")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                transports.put("CELLULAR")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                transports.put("ETHERNET")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
                transports.put("BLUETOOTH")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                transports.put("VPN")
            response.put("transports", transports)

            // Capabilities
            val capabilities = JSONArray()
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                capabilities.put("INTERNET")
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS))
                capabilities.put("MMS")
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                capabilities.put("VALIDATED")
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                capabilities.put("NOT_METERED")
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
                capabilities.put("NOT_ROAMING")
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                capabilities.put("NOT_SUSPENDED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED))
                    capabilities.put("NOT_CONGESTED")
            }
            response.put("capabilities", capabilities)

            // Bandwidth
            response.put("link_downstream_bandwidth_kbps", networkCapabilities.linkDownstreamBandwidthKbps)
            response.put("link_upstream_bandwidth_kbps", networkCapabilities.linkUpstreamBandwidthKbps)

            // Signal strength (Android Q+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                response.put("signal_strength", networkCapabilities.signalStrength)
            }

            // Link properties
            if (linkProperties != null) {
                val linkInfo = JSONObject()
                linkInfo.put("interface_name", linkProperties.interfaceName ?: "")

                val dnsServers = JSONArray()
                linkProperties.dnsServers.forEach { dns ->
                    dnsServers.put(dns.hostAddress)
                }
                linkInfo.put("dns_servers", dnsServers)

                linkInfo.put("mtu", linkProperties.mtu)
                response.put("link_properties", linkInfo)
            }

            // Metered status
            val isMetered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            response.put("is_metered", isMetered)

            // Get network type for cellular
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val networkType = when (telephonyManager.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "3G+"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                    else -> "Unknown"
                }
                response.put("cellular_network_type", networkType)
            }

        } else {
            response.put("connected", false)
            response.put("message", "No active network connection")
        }

        return jsonResponse(response)
    }

    // Network request endpoint
    private fun handleNetworkRequest(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val transportType = requestBody.optString("transport_type", "any")
        val capabilitiesArray = requestBody.optJSONArray("capabilities")
        val minBandwidthKbps = requestBody.optInt("min_bandwidth_kbps", 0)

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Build network request
        val networkRequest = NetworkRequest.Builder()

        // Add transport type
        when (transportType) {
            "wifi" -> networkRequest.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            "cellular" -> networkRequest.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            "ethernet" -> networkRequest.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            "bluetooth" -> networkRequest.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        }

        // Add capabilities
        if (capabilitiesArray != null) {
            for (i in 0 until capabilitiesArray.length()) {
                when (capabilitiesArray.getString(i)) {
                    "internet" -> networkRequest.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    "not_metered" -> networkRequest.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    "validated" -> networkRequest.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    "not_roaming" -> networkRequest.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                }
            }
        }

        // Register network callback
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                // Network was lost
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                // Capabilities changed
            }
        }

        return try {
            connectivityManager.requestNetwork(networkRequest.build(), networkCallback)

            val response = JSONObject().apply {
                put("success", true)
                put("request_registered", true)
                put("transport_type", transportType)
                put("min_bandwidth_kbps", minBandwidthKbps)
            }
            jsonResponse(response)

        } catch (e: Exception) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
            errorResponse(response.toString())
        }
    }

    // Data usage endpoint
    private fun handleDataUsage(session: IHTTPSession): Response {
        val days = session.parameters["days"]?.get(0)?.toIntOrNull() ?: 30

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (days * 24 * 60 * 60 * 1000L)

            val response = JSONObject()

            try {
                // Get subscriber ID for mobile data
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val subscriberId = if (ContextCompat.checkSelfPermission(context,
                        Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        null // subscriberId deprecated in Q
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.subscriberId
                    }
                } else {
                    null
                }

                // Mobile data usage
                if (subscriberId != null) {
                    @Suppress("DEPRECATION")
                    val mobileBucket = networkStatsManager.querySummaryForDevice(
                        ConnectivityManager.TYPE_MOBILE,
                        subscriberId,
                        startTime,
                        endTime
                    )

                    val mobileData = JSONObject()
                    mobileBucket?.let {
                        mobileData.put("rx_bytes", it.rxBytes)
                        mobileData.put("tx_bytes", it.txBytes)
                        mobileData.put("total_bytes", it.rxBytes + it.txBytes)
                    }
                    response.put("mobile", mobileData)
                }

                // WiFi data usage
                @Suppress("DEPRECATION")
                val wifiBucket = networkStatsManager.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    null,
                    startTime,
                    endTime
                )

                val wifiData = JSONObject()
                wifiBucket?.let {
                    wifiData.put("rx_bytes", it.rxBytes)
                    wifiData.put("tx_bytes", it.txBytes)
                    wifiData.put("total_bytes", it.rxBytes + it.txBytes)
                }
                response.put("wifi", wifiData)

                response.put("period_days", days)
                response.put("start_time", startTime)
                response.put("end_time", endTime)

            } catch (e: SecurityException) {
                response.put("error", "Permission denied: READ_PHONE_STATE required for mobile data stats")
            }

            return jsonResponse(response)

        } else {
            val response = JSONObject().apply {
                put("error", "Data usage statistics require Android 6.0 or higher")
            }
            return jsonResponse(response)
        }
    }

    // Bluetooth scan endpoint
    private fun handleBluetoothScan(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val durationSeconds = requestBody.optInt("duration_seconds", 10).coerceIn(1, 30)

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", "Bluetooth not supported on this device")
            }
            return jsonResponse(response)
        }

        if (!bluetoothAdapter.isEnabled) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", "Bluetooth is disabled")
            }
            return jsonResponse(response)
        }

        val devices = JSONArray()

        // Get paired devices
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevices = bluetoothAdapter.bondedDevices
            pairedDevices.forEach { device ->
                val deviceInfo = JSONObject()
                deviceInfo.put("name", device.name ?: "Unknown")
                deviceInfo.put("address", device.address)
                deviceInfo.put("paired", true)
                deviceInfo.put("device_class", device.bluetoothClass.deviceClass)
                devices.put(deviceInfo)
            }
        }

        // Start discovery for new devices
        val discoveredDevices = mutableListOf<JSONObject>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                val deviceInfo = JSONObject()
                                deviceInfo.put("name", it.name ?: "Unknown")
                                deviceInfo.put("address", it.address)
                                deviceInfo.put("paired", false)
                                deviceInfo.put("rssi", intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0))
                                discoveredDevices.add(deviceInfo)
                            }
                        }
                    }
                }
            }
        }

        // Register receiver and start discovery
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.startDiscovery()

            // Wait for duration
            Thread.sleep(durationSeconds * 1000L)

            // Stop discovery and unregister
            bluetoothAdapter.cancelDiscovery()
        }

        context.unregisterReceiver(receiver)

        // Add discovered devices
        discoveredDevices.forEach { devices.put(it) }

        val response = JSONObject().apply {
            put("success", true)
            put("devices", devices)
            put("scan_duration_seconds", durationSeconds)
        }

        return jsonResponse(response)
    }

    // WiFi scan endpoint
    private fun handleWifiScan(): Response {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!wifiManager.isWifiEnabled) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", "WiFi is disabled")
            }
            return jsonResponse(response)
        }

        // Start scan
        @Suppress("DEPRECATION")
        val scanSuccess = wifiManager.startScan()

        if (!scanSuccess) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", "WiFi scan failed to start")
            }
            return jsonResponse(response)
        }

        // Get scan results
        @Suppress("DEPRECATION")
        val scanResults = wifiManager.scanResults
        val networks = JSONArray()

        scanResults.forEach { result ->
            val network = JSONObject()
            network.put("ssid", result.SSID)
            network.put("bssid", result.BSSID)
            network.put("level", result.level)
            network.put("frequency", result.frequency)

            // Security
            val security = when {
                result.capabilities.contains("WPA3") -> "WPA3"
                result.capabilities.contains("WPA2") -> "WPA2"
                result.capabilities.contains("WPA") -> "WPA"
                result.capabilities.contains("WEP") -> "WEP"
                else -> "Open"
            }
            network.put("security", security)

            // Band
            val band = when {
                result.frequency in 2400..2500 -> "2.4GHz"
                result.frequency in 5150..5850 -> "5GHz"
                result.frequency in 5925..7125 -> "6GHz"
                else -> "Unknown"
            }
            network.put("band", band)

            // Channel width (Android M+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val channelWidth = when (result.channelWidth) {
                    android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> "20MHz"
                    android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> "40MHz"
                    android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ -> "80MHz"
                    android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> "160MHz"
                    android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80MHz"
                    else -> "Unknown"
                }
                network.put("channel_width", channelWidth)
            }

            networks.put(network)
        }

        val response = JSONObject().apply {
            put("success", true)
            put("networks", networks)
            put("network_count", networks.length())
            put("scan_timestamp", System.currentTimeMillis())
        }

        return jsonResponse(response)
    }

    // Sensor reading endpoint - on-demand sensor reads with configurable sampling rate
    private fun handleSensorsRead(session: IHTTPSession): Response {
        val bodyStr = parseBody(session) ?: return errorResponse("No request body", Response.Status.BAD_REQUEST)
        val requestBody = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}", Response.Status.BAD_REQUEST)
        }

        val sensorTypes = requestBody.getJSONArray("sensor_types")

        // Parse sampling_rate: string name or microsecond integer
        val samplingRate = when (val rateValue = requestBody.opt("sampling_rate")) {
            is Int -> rateValue
            is Number -> rateValue.toInt()
            is String -> when (rateValue.lowercase()) {
                "fastest" -> SensorManager.SENSOR_DELAY_FASTEST
                "game" -> SensorManager.SENSOR_DELAY_GAME
                "ui" -> SensorManager.SENSOR_DELAY_UI
                "normal" -> SensorManager.SENSOR_DELAY_NORMAL
                else -> {
                    // Try parsing as integer microseconds
                    rateValue.toIntOrNull()
                        ?: return errorResponse(
                            "Invalid sampling_rate: '$rateValue'. Use 'fastest', 'game', 'ui', 'normal', or microsecond integer.",
                            Response.Status.BAD_REQUEST
                        )
                }
            }
            null -> SensorManager.SENSOR_DELAY_NORMAL
            else -> SensorManager.SENSOR_DELAY_NORMAL
        }

        val samplingRateName = when (samplingRate) {
            SensorManager.SENSOR_DELAY_FASTEST -> "fastest"
            SensorManager.SENSOR_DELAY_GAME -> "game"
            SensorManager.SENSOR_DELAY_UI -> "ui"
            SensorManager.SENSOR_DELAY_NORMAL -> "normal"
            else -> "${samplingRate}us"
        }

        val results = JSONObject()

        for (i in 0 until sensorTypes.length()) {
            val sensorType = sensorTypes.getString(i)
            val sensorData = readSensorOnce(sensorType, samplingRate)
            results.put(sensorType, sensorData)
        }

        val response = JSONObject().apply {
            put("success", true)
            put("readings", results)
            put("timestamp", System.currentTimeMillis())
            put("mode", "on-demand")
            put("sampling_rate", samplingRateName)
        }

        return jsonResponse(response)
    }

    private fun readSensorOnce(sensorType: String, samplingRate: Int = SensorManager.SENSOR_DELAY_NORMAL): JSONObject {
        val result = JSONObject()

        // Map friendly names to Android sensor constants
        val androidSensorType = when(sensorType) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
            "light" -> Sensor.TYPE_LIGHT
            "pressure" -> Sensor.TYPE_PRESSURE
            "proximity" -> Sensor.TYPE_PROXIMITY
            "gravity" -> Sensor.TYPE_GRAVITY
            "linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
            "rotation_vector" -> Sensor.TYPE_ROTATION_VECTOR
            "relative_humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "ambient_temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            "hinge_angle" -> 36  // TYPE_HINGE_ANGLE for foldables
            else -> -1
        }

        if (androidSensorType == -1) {
            result.put("error", "Unknown sensor type: $sensorType")
            return result
        }

        val sensor = sensorManager.getDefaultSensor(androidSensorType)
        if (sensor == null) {
            result.put("error", "Sensor not available: $sensorType")
            return result
        }

        // Create a one-shot sensor listener with timeout
        val sensorData = AtomicReference<FloatArray>()
        val latch = CountDownLatch(1)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sensorData.set(event.values.clone())
                latch.countDown()
                // Immediately unregister after getting one reading
                sensorManager.unregisterListener(this)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        // Register listener with caller-specified sampling rate
        sensorManager.registerListener(
            listener,
            sensor,
            samplingRate
        )

        // Wait up to 1 second for sensor data
        try {
            if (latch.await(1000, TimeUnit.MILLISECONDS)) {
                val values = sensorData.get()

                // Format based on sensor type
                when(sensorType) {
                    "accelerometer", "gyroscope", "magnetic_field", "gravity", "linear_acceleration" -> {
                        result.put("x", values[0])
                        result.put("y", values[1])
                        result.put("z", values[2])
                        result.put("unit", getUnitForSensor(sensorType))
                    }
                    "light" -> {
                        result.put("value", values[0])
                        result.put("unit", "lux")
                    }
                    "pressure" -> {
                        result.put("value", values[0])
                        result.put("unit", "hPa")
                    }
                    "proximity" -> {
                        result.put("distance", values[0])
                        result.put("unit", "cm")
                    }
                    "hinge_angle" -> {
                        result.put("angle", values[0])
                        result.put("unit", "degrees")
                    }
                    "rotation_vector" -> {
                        result.put("x", values[0])
                        result.put("y", values[1])
                        result.put("z", values[2])
                        if (values.size > 3) result.put("w", values[3])
                        result.put("unit", "quaternion")
                    }
                    "relative_humidity" -> {
                        result.put("value", values[0])
                        result.put("unit", "%")
                    }
                    "ambient_temperature" -> {
                        result.put("value", values[0])
                        result.put("unit", "°C")
                    }
                    else -> {
                        // Generic format for unknown sensors
                        val valuesArray = JSONArray()
                        values.forEach { valuesArray.put(it) }
                        result.put("values", valuesArray)
                    }
                }

                result.put("timestamp", System.currentTimeMillis())
            } else {
                // Timeout - unregister listener
                sensorManager.unregisterListener(listener)
                result.put("error", "Sensor read timeout")
            }
        } catch (e: Exception) {
            sensorManager.unregisterListener(listener)
            result.put("error", e.message)
        }

        return result
    }

    private fun getUnitForSensor(sensorType: String): String {
        return when(sensorType) {
            "accelerometer", "gravity", "linear_acceleration" -> "m/s²"
            "gyroscope" -> "rad/s"
            "magnetic_field" -> "μT"
            else -> ""
        }
    }

    private fun handleSensorsOrientation(): Response {
        // Get accelerometer (gravity) data
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return errorResponse("Accelerometer sensor not available")
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            ?: return errorResponse("Magnetic field sensor not available")

        val gravityData = AtomicReference<FloatArray>()
        val magneticData = AtomicReference<FloatArray>()
        val gravityLatch = CountDownLatch(1)
        val magneticLatch = CountDownLatch(1)

        val gravityListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                gravityData.set(event.values.clone())
                gravityLatch.countDown()
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        val magneticListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                magneticData.set(event.values.clone())
                magneticLatch.countDown()
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        // Register both listeners
        sensorManager.registerListener(gravityListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(magneticListener, magnetometer, SensorManager.SENSOR_DELAY_GAME)

        try {
            val gotGravity = gravityLatch.await(1000, TimeUnit.MILLISECONDS)
            val gotMagnetic = magneticLatch.await(1000, TimeUnit.MILLISECONDS)

            if (!gotGravity) {
                sensorManager.unregisterListener(gravityListener)
                return errorResponse("Accelerometer read timeout")
            }
            if (!gotMagnetic) {
                sensorManager.unregisterListener(magneticListener)
                return errorResponse("Magnetic field read timeout")
            }

            val gravity = gravityData.get()
            val geomagnetic = magneticData.get()

            val rotationMatrix = FloatArray(9)
            val inclinationMatrix = FloatArray(9)

            val success = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)
            if (!success) {
                return errorResponse("Could not compute rotation matrix (device may be in free-fall or magnetic interference)")
            }

            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            // orientation[0] = azimuth (rad), orientation[1] = pitch (rad), orientation[2] = roll (rad)
            val azimuthRad = orientation[0].toDouble()
            val pitchRad = orientation[1].toDouble()
            val rollRad = orientation[2].toDouble()

            val azimuthDeg = ((Math.toDegrees(azimuthRad) + 360) % 360)
            val pitchDeg = Math.toDegrees(pitchRad)
            val rollDeg = Math.toDegrees(rollRad)

            val compassDirection = when {
                azimuthDeg < 22.5 || azimuthDeg >= 337.5 -> "N"
                azimuthDeg < 67.5 -> "NE"
                azimuthDeg < 112.5 -> "E"
                azimuthDeg < 157.5 -> "SE"
                azimuthDeg < 202.5 -> "S"
                azimuthDeg < 247.5 -> "SW"
                azimuthDeg < 292.5 -> "W"
                azimuthDeg < 337.5 -> "NW"
                else -> "N"
            }

            val json = JSONObject().apply {
                put("success", true)
                put("azimuth_degrees", Math.round(azimuthDeg * 100.0) / 100.0)
                put("pitch_degrees", Math.round(pitchDeg * 100.0) / 100.0)
                put("roll_degrees", Math.round(rollDeg * 100.0) / 100.0)
                put("azimuth_rad", azimuthRad)
                put("pitch_rad", pitchRad)
                put("roll_rad", rollRad)
                put("compass_direction", compassDirection)
                put("timestamp", System.currentTimeMillis())
            }
            return jsonResponse(json)

        } catch (e: Exception) {
            sensorManager.unregisterListener(gravityListener)
            sensorManager.unregisterListener(magneticListener)
            return errorResponse("Orientation computation failed: ${e.message}")
        }
    }

    private fun handleSensorsTrigger(session: IHTTPSession): Response {
        val bodyStr = parseBody(session)
        val requestBody = if (bodyStr != null) {
            try { JSONObject(bodyStr) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }

        val timeoutMs = requestBody.optLong("timeout_ms", 5000L)
            .coerceIn(100L, 30000L)

        // Get the significant motion sensor
        val significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            ?: return errorResponse("Significant motion sensor not available on this device")

        val triggered = AtomicReference(false)
        val latch = CountDownLatch(1)
        val startTime = System.currentTimeMillis()

        val triggerListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                triggered.set(true)
                latch.countDown()
            }
        }

        // Request one-shot trigger
        val registered = sensorManager.requestTriggerSensor(triggerListener, significantMotionSensor)
        if (!registered) {
            return errorResponse("Failed to register trigger sensor listener")
        }

        try {
            // Block until trigger fires or timeout
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)

            val elapsedMs = System.currentTimeMillis() - startTime
            val wasTriggered = triggered.get()

            // If we timed out, cancel the trigger request
            if (!wasTriggered) {
                sensorManager.cancelTriggerSensor(triggerListener, significantMotionSensor)
            }

            val json = JSONObject().apply {
                put("success", true)
                put("triggered", wasTriggered)
                put("timestamp", System.currentTimeMillis())
                put("elapsed_ms", elapsedMs)
                put("sensor_name", significantMotionSensor.name)
            }
            return jsonResponse(json)

        } catch (e: Exception) {
            sensorManager.cancelTriggerSensor(triggerListener, significantMotionSensor)
            return errorResponse("Trigger sensor failed: ${e.message}")
        }
    }

    // Predefined vibration effects
    private fun handleVibrateEffect(session: IHTTPSession): Response {
        val bodyStr = parseBody(session) ?: return errorResponse("No request body", Response.Status.BAD_REQUEST)
        val requestBody = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}", Response.Status.BAD_REQUEST)
        }

        val effectName = requestBody.getString("effect")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effectId = when(effectName) {
                "EFFECT_CLICK" -> VibrationEffect.EFFECT_CLICK
                "EFFECT_TICK" -> VibrationEffect.EFFECT_TICK
                "EFFECT_DOUBLE_CLICK" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                "EFFECT_HEAVY_CLICK" -> VibrationEffect.EFFECT_HEAVY_CLICK
                else -> VibrationEffect.EFFECT_CLICK
            }

            try {
                val effect = VibrationEffect.createPredefined(effectId)
                vibrator.vibrate(effect)

                val response = JSONObject().apply {
                    put("success", true)
                    put("effect", effectName)
                    put("executed", true)
                }
                return jsonResponse(response)
            } catch (e: Exception) {
                return errorResponse("Failed to vibrate: ${e.message}")
            }
        } else {
            // Fallback for older Android versions
            @Suppress("DEPRECATION")
            vibrator.vibrate(100) // Default click duration

            val response = JSONObject().apply {
                put("success", true)
                put("effect", effectName)
                put("fallback", true)
                put("note", "Using simple vibration on pre-Android Q device")
            }
            return jsonResponse(response)
        }
    }

    // Pattern vibration
    private fun handleVibratePattern(session: IHTTPSession): Response {
        val bodyStr = parseBody(session) ?: return errorResponse("No request body", Response.Status.BAD_REQUEST)
        val requestBody = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}", Response.Status.BAD_REQUEST)
        }

        val patternArray = requestBody.getJSONArray("pattern")
        val repeat = requestBody.optInt("repeat", -1) // -1 = no repeat

        // Convert JSONArray to long array
        val pattern = LongArray(patternArray.length())
        for (i in 0 until patternArray.length()) {
            pattern[i] = patternArray.getLong(i)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use VibrationEffect for newer Android versions
            val amplitudes = IntArray(pattern.size) { VibrationEffect.DEFAULT_AMPLITUDE }

            val effect = if (repeat >= 0) {
                // Create repeating pattern
                VibrationEffect.createWaveform(pattern, amplitudes, repeat)
            } else {
                // One-shot pattern
                VibrationEffect.createWaveform(pattern, amplitudes, -1)
            }

            vibrator.vibrate(effect)

            val response = JSONObject().apply {
                put("success", true)
                put("pattern_length", pattern.size)
                put("repeat", repeat)
                put("using_effect", true)
            }
            return jsonResponse(response)
        } else {
            // Legacy vibration for older Android versions
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, repeat)

            val response = JSONObject().apply {
                put("success", true)
                put("pattern_length", pattern.size)
                put("repeat", repeat)
                put("using_legacy", true)
            }
            return jsonResponse(response)
        }
    }

    // Cancel vibration
    private fun handleVibrateCancel(): Response {
        vibrator.cancel()

        val response = JSONObject().apply {
            put("success", true)
            put("action", "vibration_cancelled")
        }
        return jsonResponse(response)
    }

    // ========================================================================
    // NFC ENDPOINTS
    // ========================================================================

    // GET /nfc/state - Get NFC adapter state
    private fun handleNfcState(): Response {
        val json = JSONObject().apply {
            put("supported", nfcAdapter != null)
            put("enabled", nfcAdapter?.isEnabled ?: false)

            // Android 16+ allowlist status
            if (Build.VERSION.SDK_INT >= 35) { // VANILLA_ICE_CREAM
                nfcAdapter?.let { adapter ->
                    try {
                        // This API may not be available yet, handle gracefully
                        put("tagIntentAllowed", true) // Default to true for now
                    } catch (e: Exception) {
                        put("tagIntentAllowed", true)
                    }
                }
            }
        }
        return jsonResponse(json)
    }

    // POST /nfc/enable - Enable NFC (requires system settings intent)
    private fun handleNfcEnable(): Response {
        if (nfcAdapter == null) {
            return errorResponse("NFC not supported on this device")
        }

        val json = JSONObject().apply {
            if (nfcAdapter?.isEnabled == true) {
                put("success", true)
                put("message", "NFC already enabled")
            } else {
                put("success", false)
                put("message", "NFC is disabled. Please enable in Settings > Connected devices > Connection preferences > NFC")
                put("action", "android.settings.NFC_SETTINGS")
            }
        }
        return jsonResponse(json)
    }

    // GET /nfc/tag/read - Read current NFC tag
    private fun handleNfcTagRead(): Response {
        val tag = lastScannedTag
        if (tag == null) {
            return errorResponse("No NFC tag has been scanned yet", Response.Status.NOT_FOUND)
        }

        val json = JSONObject().apply {
            put("success", true)
            put("tagId", tag.id.joinToString(":") { "%02X".format(it) })
            put("technologies", JSONArray(tag.techList.map { it.substringAfterLast(".") }))
            put("fullTechList", JSONArray(tag.techList.toList()))

            // Include NDEF message if available
            lastNdefMessage?.let { ndefMsg ->
                put("ndefMessage", ndefMessageToJson(ndefMsg))
            }
        }
        return jsonResponse(json)
    }

    // POST /nfc/tag/write - Write NDEF to tag
    private fun handleNfcTagWrite(session: IHTTPSession): Response {
        val tag = lastScannedTag
        if (tag == null) {
            return errorResponse("No NFC tag available for writing", Response.Status.NOT_FOUND)
        }

        val bodyStr = parseBody(session) ?: return errorResponse("Missing request body")
        val body = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}")
        }

        return try {
            val recordsJson = body.getJSONArray("records")
            val records = mutableListOf<NdefRecord>()

            for (i in 0 until recordsJson.length()) {
                val recordJson = recordsJson.getJSONObject(i)
                val record = createNdefRecord(recordJson)
                records.add(record)
            }

            val ndefMessage = NdefMessage(records.toTypedArray())

            // Try to write to NDEF tag
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) {
                        return errorResponse("Tag is read-only")
                    }
                    if (ndefMessage.byteArrayLength > it.maxSize) {
                        return errorResponse("Message size ${ndefMessage.byteArrayLength} exceeds tag capacity ${it.maxSize}")
                    }
                    it.writeNdefMessage(ndefMessage)
                    lastNdefMessage = ndefMessage
                }

                val json = JSONObject().apply {
                    put("success", true)
                    put("bytesWritten", ndefMessage.byteArrayLength)
                    put("message", "NDEF message written successfully")
                }
                jsonResponse(json)
            } else {
                // Try to format as NDEF
                val format = NdefFormatable.get(tag)
                if (format != null) {
                    format.use {
                        it.connect()
                        it.format(ndefMessage)
                        lastNdefMessage = ndefMessage
                    }
                    val json = JSONObject().apply {
                        put("success", true)
                        put("bytesWritten", ndefMessage.byteArrayLength)
                        put("message", "Tag formatted and NDEF message written")
                    }
                    jsonResponse(json)
                } else {
                    errorResponse("Tag is not NDEF compatible")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing NFC tag", e)
            errorResponse("Failed to write tag: ${e.message}")
        }
    }

    // GET /nfc/tag/tech - Detect tag technologies
    private fun handleNfcTagTech(): Response {
        val tag = lastScannedTag
        if (tag == null) {
            return errorResponse("No NFC tag has been scanned", Response.Status.NOT_FOUND)
        }

        val json = JSONObject().apply {
            put("success", true)
            put("tagId", tag.id.joinToString(":") { "%02X".format(it) })

            val techDetails = JSONArray()
            tag.techList.forEach { techClass ->
                techDetails.put(JSONObject().apply {
                    put("class", techClass)
                    put("shortName", techClass.substringAfterLast("."))
                })
            }
            put("technologies", techDetails)

            // Check for specific technologies
            put("supportsNdef", tag.techList.contains("android.nfc.tech.Ndef"))
            put("supportsNdefFormatable", tag.techList.contains("android.nfc.tech.NdefFormatable"))
            put("supportsIsoDep", tag.techList.contains("android.nfc.tech.IsoDep"))
            put("supportsMifareClassic", tag.techList.contains("android.nfc.tech.MifareClassic"))
            put("supportsMifareUltralight", tag.techList.contains("android.nfc.tech.MifareUltralight"))
        }
        return jsonResponse(json)
    }

    // POST /nfc/ndef/create - Create NDEF message
    private fun handleNfcNdefCreate(session: IHTTPSession): Response {
        val bodyStr = parseBody(session) ?: return errorResponse("Missing request body")
        val body = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return errorResponse("Invalid JSON: ${e.message}")
        }

        return try {
            val recordsJson = body.getJSONArray("records")
            val records = mutableListOf<NdefRecord>()

            for (i in 0 until recordsJson.length()) {
                val recordJson = recordsJson.getJSONObject(i)
                val record = createNdefRecord(recordJson)
                records.add(record)
            }

            val ndefMessage = NdefMessage(records.toTypedArray())

            val json = JSONObject().apply {
                put("success", true)
                put("recordCount", records.size)
                put("totalBytes", ndefMessage.byteArrayLength)
                put("message", ndefMessageToJson(ndefMessage))
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating NDEF message", e)
            errorResponse("Failed to create NDEF: ${e.message}")
        }
    }

    // POST /nfc/foreground/enable - Enable foreground dispatch
    private fun handleNfcForegroundEnable(): Response {
        // Note: Foreground dispatch requires Activity context and must be called from onResume()
        // This endpoint documents the requirement but cannot directly enable it from a service
        val json = JSONObject().apply {
            put("success", false)
            put("message", "Foreground dispatch requires Activity context")
            put("note", "Enable NFC reading in your Activity's onResume() using NfcAdapter.enableForegroundDispatch()")
            put("documentation", "See NfcEndpointHandler.kt implementation in the guide")
        }
        return jsonResponse(json)
    }

    // POST /nfc/foreground/disable - Disable foreground dispatch
    private fun handleNfcForegroundDisable(): Response {
        // Note: Foreground dispatch requires Activity context and must be called from onPause()
        val json = JSONObject().apply {
            put("success", false)
            put("message", "Foreground dispatch requires Activity context")
            put("note", "Disable NFC reading in your Activity's onPause() using NfcAdapter.disableForegroundDispatch()")
        }
        return jsonResponse(json)
    }

    // Helper: Create NDEF record from JSON
    private fun createNdefRecord(recordJson: JSONObject): NdefRecord {
        return when (recordJson.getString("type").uppercase()) {
            "TEXT" -> {
                val text = recordJson.getString("payload")
                val languageCode = recordJson.optString("languageCode", "en")
                val textBytes = text.toByteArray(Charsets.UTF_8)
                val languageCodeBytes = languageCode.toByteArray(Charsets.US_ASCII)

                val data = ByteArray(1 + languageCodeBytes.size + textBytes.size)
                data[0] = languageCodeBytes.size.toByte()
                System.arraycopy(languageCodeBytes, 0, data, 1, languageCodeBytes.size)
                System.arraycopy(textBytes, 0, data, 1 + languageCodeBytes.size, textBytes.size)

                NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), data)
            }
            "URI" -> {
                NdefRecord.createUri(recordJson.getString("uri"))
            }
            "MIME" -> {
                NdefRecord.createMime(
                    recordJson.getString("mimeType"),
                    recordJson.getString("payload").toByteArray(Charsets.UTF_8)
                )
            }
            "EXTERNAL" -> {
                NdefRecord.createExternal(
                    recordJson.getString("domain"),
                    recordJson.getString("externalType"),
                    recordJson.getString("payload").toByteArray(Charsets.UTF_8)
                )
            }
            "AAR" -> {
                NdefRecord.createApplicationRecord(recordJson.getString("packageName"))
            }
            else -> throw IllegalArgumentException("Unsupported record type: ${recordJson.getString("type")}")
        }
    }

    // Helper: Convert NDEF message to JSON
    private fun ndefMessageToJson(message: NdefMessage): JSONObject {
        return JSONObject().apply {
            put("recordCount", message.records.size)
            val recordsArray = JSONArray()

            message.records.forEach { record ->
                val recordJson = JSONObject().apply {
                    put("tnf", record.tnf)
                    put("type", record.type.joinToString("") { "%02X".format(it) })
                    put("id", record.id.joinToString("") { "%02X".format(it) })
                    put("payload", record.payload.joinToString("") { "%02X".format(it) })

                    // Attempt to decode payload
                    when (record.tnf) {
                        NdefRecord.TNF_WELL_KNOWN -> {
                            when {
                                record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                                    put("recordType", "TEXT")
                                    try {
                                        val payload = record.payload
                                        val languageCodeLength = (payload[0].toInt() and 0x3F)
                                        val text = String(
                                            payload,
                                            languageCodeLength + 1,
                                            payload.size - languageCodeLength - 1,
                                            Charsets.UTF_8
                                        )
                                        put("decodedPayload", text)
                                    } catch (e: Exception) {
                                        put("decodeError", e.message)
                                    }
                                }
                                record.type.contentEquals(NdefRecord.RTD_URI) -> {
                                    put("recordType", "URI")
                                    try {
                                        val payload = record.payload
                                        val prefixCode = payload[0].toInt() and 0xFF
                                        val uriBytes = payload.copyOfRange(1, payload.size)
                                        put("decodedPayload", String(uriBytes, Charsets.UTF_8))
                                    } catch (e: Exception) {
                                        put("decodeError", e.message)
                                    }
                                }
                            }
                        }
                        NdefRecord.TNF_MIME_MEDIA -> {
                            put("recordType", "MIME")
                            put("mimeType", String(record.type, Charsets.US_ASCII))
                            try {
                                put("decodedPayload", String(record.payload, Charsets.UTF_8))
                            } catch (e: Exception) {
                                put("decodeError", e.message)
                            }
                        }
                    }
                }
                recordsArray.put(recordJson)
            }

            put("records", recordsArray)
        }
    }

    // Public method to update scanned tag (called from Activity)
    fun updateScannedTag(tag: Tag?, ndefMessage: NdefMessage?) {
        lastScannedTag = tag
        lastNdefMessage = ndefMessage
        Log.d(TAG, "NFC tag updated: ${tag?.id?.joinToString(":") { "%02X".format(it) }}")
    }

    // ============= NEW NETWORK CONNECTIVITY ENDPOINTS =============

    // 1. Bind process to network
    private fun handleBindProcess(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)

        return try {
            val unbind = requestBody.optBoolean("unbind", false)

            if (unbind) {
                connectivityManager.bindProcessToNetwork(null)
                val response = JSONObject().apply {
                    put("success", true)
                    put("bound_network_id", JSONObject.NULL)
                    put("message", "Process unbound from network")
                }
                jsonResponse(response)
            } else {
                val networkId = requestBody.optString("network_id", null)
                val network = if (networkId != null) {
                    connectivityManager.allNetworks.find { it.toString() == networkId }
                } else {
                    connectivityManager.activeNetwork
                }

                if (network == null) {
                    errorResponse("Network not found or not available")
                } else {
                    val success = connectivityManager.bindProcessToNetwork(network)
                    val response = JSONObject().apply {
                        put("success", success)
                        put("bound_network_id", network.toString())
                        put("message", if (success) "Process bound successfully" else "Failed to bind process")
                    }
                    jsonResponse(response)
                }
            }
        } catch (e: Exception) {
            errorResponse("Error binding process: ${e.message}")
        }
    }

    // 2. Get active network
    private fun handleGetActiveNetwork(): Response {
        return try {
            val network = connectivityManager.activeNetwork
            val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val info = network?.let { connectivityManager.getNetworkInfo(it) }

            if (network == null) {
                val response = JSONObject().apply {
                    put("success", true)
                    put("active_network", JSONObject.NULL)
                    put("is_connected", false)
                }
                jsonResponse(response)
            } else {
                val networkType = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                    else -> "UNKNOWN"
                }

                val response = JSONObject().apply {
                    put("success", true)
                    put("network_id", network.toString())
                    put("type", networkType)
                    put("is_connected", info?.isConnected ?: false)
                    put("has_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
                    put("is_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                }
                jsonResponse(response)
            }
        } catch (e: Exception) {
            errorResponse("Error getting active network: ${e.message}")
        }
    }

    // 3. Get all networks
    private fun handleGetAllNetworks(): Response {
        return try {
            val networks = connectivityManager.allNetworks.mapNotNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                val info = connectivityManager.getNetworkInfo(network)

                if (caps != null && info != null) {
                    val networkType = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                        else -> "UNKNOWN"
                    }

                    JSONObject().apply {
                        put("network_id", network.toString())
                        put("type", networkType)
                        put("is_connected", info.isConnected)
                        put("has_internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        put("is_validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        put("is_metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                        put("downstream_kbps", caps.linkDownstreamBandwidthKbps)
                        put("upstream_kbps", caps.linkUpstreamBandwidthKbps)
                    }
                } else null
            }

            val response = JSONObject().apply {
                put("success", true)
                put("network_count", networks.size)
                put("networks", JSONArray(networks))
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error getting all networks: ${e.message}")
        }
    }

    // 4. Register network callback
    private fun handleRegisterCallback(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val callbackId = requestBody.optString("callback_id", null)
            ?: return errorResponse("callback_id is required")

        return try {
            val requestBuilder = NetworkRequest.Builder()

            // Add capabilities
            val capabilities = requestBody.optJSONArray("capabilities")
            if (capabilities != null) {
                for (i in 0 until capabilities.length()) {
                    when (capabilities.getString(i)) {
                        "INTERNET" -> requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        "VALIDATED" -> requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        "NOT_METERED" -> requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        "NOT_VPN" -> requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        "TRUSTED" -> requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    }
                }
            }

            // Add transports
            val transports = requestBody.optJSONArray("transports")
            if (transports != null) {
                for (i in 0 until transports.length()) {
                    when (transports.getString(i)) {
                        "WIFI" -> requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        "CELLULAR" -> requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        "BLUETOOTH" -> requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                        "ETHERNET" -> requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        "VPN" -> requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            }

            val networkRequest = requestBuilder.build()

            // Create callback
            callbackEvents[callbackId] = mutableListOf()
            val callback = object : NetworkCallback() {
                override fun onAvailable(network: Network) {
                    callbackEvents[callbackId]?.add(mapOf(
                        "event" to "available",
                        "network_id" to network.toString(),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onLost(network: Network) {
                    callbackEvents[callbackId]?.add(mapOf(
                        "event" to "lost",
                        "network_id" to network.toString(),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    callbackEvents[callbackId]?.add(mapOf(
                        "event" to "capabilities_changed",
                        "network_id" to network.toString(),
                        "has_internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        "has_validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        "is_metered" to !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                        "downstream_kbps" to caps.linkDownstreamBandwidthKbps,
                        "upstream_kbps" to caps.linkUpstreamBandwidthKbps,
                        "timestamp" to System.currentTimeMillis()
                    ))
                }
            }

            networkCallbacks[callbackId] = callback
            connectivityManager.registerNetworkCallback(networkRequest, callback)

            val response = JSONObject().apply {
                put("success", true)
                put("callback_id", callbackId)
                put("registered", true)
                put("message", "Network callback registered successfully")
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error registering callback: ${e.message}")
        }
    }

    // 5. Unregister network callback
    private fun handleUnregisterCallback(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val callbackId = requestBody.optString("callback_id", null)
            ?: return errorResponse("callback_id is required")

        return try {
            val callback = networkCallbacks.remove(callbackId)
            callbackEvents.remove(callbackId)

            if (callback == null) {
                errorResponse("Callback not found")
            } else {
                connectivityManager.unregisterNetworkCallback(callback)
                val response = JSONObject().apply {
                    put("success", true)
                    put("callback_id", callbackId)
                    put("message", "Callback unregistered successfully")
                }
                jsonResponse(response)
            }
        } catch (e: Exception) {
            errorResponse("Error unregistering callback: ${e.message}")
        }
    }

    // 6. Get callback events
    private fun handleGetCallbackEvents(session: IHTTPSession): Response {
        val callbackId = session.parameters["callback_id"]?.get(0)
            ?: return errorResponse("callback_id parameter is required")
        val clear = session.parameters["clear"]?.get(0)?.toBoolean() ?: false

        return try {
            val events = callbackEvents[callbackId]

            if (events == null) {
                errorResponse("Callback not found")
            } else {
                val eventList = events.toList()

                if (clear) {
                    events.clear()
                }

                val response = JSONObject().apply {
                    put("success", true)
                    put("callback_id", callbackId)
                    put("event_count", eventList.size)
                    put("events", JSONArray(eventList.map { JSONObject(it) }))
                }
                jsonResponse(response)
            }
        } catch (e: Exception) {
            errorResponse("Error getting callback events: ${e.message}")
        }
    }

    // 7. Get link properties
    private fun handleGetLinkProperties(session: IHTTPSession): Response {
        val networkId = session.parameters["network_id"]?.get(0)

        return try {
            val network = if (networkId != null) {
                connectivityManager.allNetworks.find { it.toString() == networkId }
            } else {
                connectivityManager.activeNetwork
            }

            if (network == null) {
                errorResponse("Network not found")
            } else {
                val linkProps = connectivityManager.getLinkProperties(network)

                if (linkProps == null) {
                    errorResponse("Link properties not available")
                } else {
                    val response = JSONObject().apply {
                        put("success", true)
                        put("network_id", network.toString())
                        put("interface_name", linkProps.interfaceName ?: "")
                        put("dns_servers", JSONArray(linkProps.dnsServers.map { it.hostAddress }))
                        put("link_addresses", JSONArray(linkProps.linkAddresses.map { addr ->
                            JSONObject().apply {
                                put("address", addr.address.hostAddress)
                                put("prefix_length", addr.prefixLength)
                                put("flags", addr.flags)
                                put("scope", addr.scope)
                            }
                        }))
                        put("routes", JSONArray(linkProps.routes.map { route ->
                            JSONObject().apply {
                                put("destination", route.destination.toString())
                                put("gateway", route.gateway?.hostAddress)
                                put("interface", route.`interface`)
                            }
                        }))
                        put("http_proxy", linkProps.httpProxy?.toString())
                        put("mtu", linkProps.mtu)
                    }
                    jsonResponse(response)
                }
            }
        } catch (e: Exception) {
            errorResponse("Error getting link properties: ${e.message}")
        }
    }

    // 8. Report bad network
    private fun handleReportBadNetwork(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val requestBody = JSONObject(body)
        val networkId = requestBody.optString("network_id", null)

        return try {
            val network = if (networkId != null) {
                connectivityManager.allNetworks.find { it.toString() == networkId }
            } else {
                connectivityManager.activeNetwork
            }

            if (network == null) {
                errorResponse("Network not found")
            } else {
                connectivityManager.reportNetworkConnectivity(network, false)
                val response = JSONObject().apply {
                    put("success", true)
                    put("network_id", network.toString())
                    put("message", "Network reported as bad")
                }
                jsonResponse(response)
            }
        } catch (e: Exception) {
            errorResponse("Error reporting bad network: ${e.message}")
        }
    }

    // 9. Restore default network
    private fun handleRestoreDefaultNetwork(): Response {
        return try {
            connectivityManager.bindProcessToNetwork(null)
            val response = JSONObject().apply {
                put("success", true)
                put("message", "Default network restored")
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error restoring default network: ${e.message}")
        }
    }

    // 10. Get metered status
    private fun handleGetMeteredStatus(): Response {
        return try {
            val isMetered = connectivityManager.isActiveNetworkMetered
            val network = connectivityManager.activeNetwork
            val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }

            val networkType = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                else -> "UNKNOWN"
            }

            val response = JSONObject().apply {
                put("success", true)
                put("is_metered", isMetered)
                put("network_id", network?.toString())
                put("network_type", networkType)
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error getting metered status: ${e.message}")
        }
    }

    // 11. Get VPN status
    private fun handleGetVpnStatus(): Response {
        return try {
            var vpnConnected = false
            var vpnNetworkId: String? = null
            val underlyingNetworks = mutableListOf<String>()

            connectivityManager.allNetworks.forEach { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    vpnConnected = true
                    vpnNetworkId = network.toString()

                    // Get underlying networks if available (Android 11+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val linkProps = connectivityManager.getLinkProperties(network)
                            linkProps?.interfaceName?.let {
                                underlyingNetworks.add(it)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not get underlying network info", e)
                        }
                    }
                }
            }

            val response = JSONObject().apply {
                put("success", true)
                put("vpn_connected", vpnConnected)
                put("vpn_network_id", vpnNetworkId)
                put("underlying_networks", JSONArray(underlyingNetworks))
                put("vpn_type", if (vpnConnected) "VPN" else JSONObject.NULL)
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error getting VPN status: ${e.message}")
        }
    }

    // 12. Get traffic stats
    private fun handleGetTrafficStats(session: IHTTPSession): Response {
        val scope = session.parameters["scope"]?.get(0) ?: "app"

        return try {
            when (scope) {
                "app" -> {
                    val uid = android.os.Process.myUid()
                    val rxBytes = TrafficStats.getUidRxBytes(uid)
                    val txBytes = TrafficStats.getUidTxBytes(uid)

                    if (rxBytes == TrafficStats.UNSUPPORTED.toLong()) {
                        errorResponse("Traffic stats not supported on this device")
                    } else {
                        val response = JSONObject().apply {
                            put("success", true)
                            put("scope", "app")
                            put("rx_bytes", rxBytes)
                            put("tx_bytes", txBytes)
                            put("total_bytes", rxBytes + txBytes)
                            put("uid", uid)
                        }
                        jsonResponse(response)
                    }
                }
                "mobile" -> {
                    val rxBytes = TrafficStats.getMobileRxBytes()
                    val txBytes = TrafficStats.getMobileTxBytes()

                    val response = JSONObject().apply {
                        put("success", true)
                        put("scope", "mobile")
                        put("rx_bytes", rxBytes)
                        put("tx_bytes", txBytes)
                        put("total_bytes", rxBytes + txBytes)
                    }
                    jsonResponse(response)
                }
                "total" -> {
                    val rxBytes = TrafficStats.getTotalRxBytes()
                    val txBytes = TrafficStats.getTotalTxBytes()

                    val response = JSONObject().apply {
                        put("success", true)
                        put("scope", "total")
                        put("rx_bytes", rxBytes)
                        put("tx_bytes", txBytes)
                        put("total_bytes", rxBytes + txBytes)
                    }
                    jsonResponse(response)
                }
                else -> {
                    errorResponse("Invalid scope. Use: app, mobile, or total")
                }
            }
        } catch (e: Exception) {
            errorResponse("Error getting traffic stats: ${e.message}")
        }
    }

    // 13. Get bandwidth
    private fun handleGetBandwidth(session: IHTTPSession): Response {
        val networkId = session.parameters["network_id"]?.get(0)

        return try {
            val network = if (networkId != null) {
                connectivityManager.allNetworks.find { it.toString() == networkId }
            } else {
                connectivityManager.activeNetwork
            }

            if (network == null) {
                errorResponse("Network not found")
            } else {
                val caps = connectivityManager.getNetworkCapabilities(network)

                if (caps == null) {
                    errorResponse("Network capabilities not available")
                } else {
                    val downKbps = caps.linkDownstreamBandwidthKbps
                    val upKbps = caps.linkUpstreamBandwidthKbps

                    val response = JSONObject().apply {
                        put("success", true)
                        put("network_id", network.toString())
                        put("downstream_kbps", downKbps)
                        put("upstream_kbps", upKbps)
                        put("downstream_mbps", downKbps / 1024.0)
                        put("upstream_mbps", upKbps / 1024.0)
                    }
                    jsonResponse(response)
                }
            }
        } catch (e: Exception) {
            errorResponse("Error getting bandwidth: ${e.message}")
        }
    }

    // 14. Connection check
    private fun handleConnectionCheck(): Response {
        return try {
            val network = connectivityManager.activeNetwork
            val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val info = network?.let { connectivityManager.getNetworkInfo(it) }

            val networkType = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                else -> "NONE"
            }

            val response = JSONObject().apply {
                put("success", true)
                put("is_connected", info?.isConnected == true)
                put("has_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                put("is_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                put("network_type", networkType)
                put("is_metered", connectivityManager.isActiveNetworkMetered)
                put("can_reach_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            jsonResponse(response)
        } catch (e: Exception) {
            errorResponse("Error checking connection: ${e.message}")
        }
    }

    // 15. Get detailed network info
    private fun handleGetDetailedNetworkInfo(session: IHTTPSession): Response {
        val networkId = session.parameters["network_id"]?.get(0)

        return try {
            val network = if (networkId != null) {
                connectivityManager.allNetworks.find { it.toString() == networkId }
            } else {
                connectivityManager.activeNetwork
            }

            if (network == null) {
                errorResponse("Network not found")
            } else {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val linkProps = connectivityManager.getLinkProperties(network)
                val info = connectivityManager.getNetworkInfo(network)

                val transportTypes = JSONArray()
                caps?.let {
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transportTypes.put("WIFI")
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transportTypes.put("CELLULAR")
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transportTypes.put("ETHERNET")
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transportTypes.put("VPN")
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transportTypes.put("BLUETOOTH")
                }

                val response = JSONObject().apply {
                    put("success", true)
                    put("network_id", network.toString())
                    put("capabilities", JSONObject().apply {
                        put("has_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                        put("is_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                        put("is_metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
                        put("has_captive_portal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
                        put("transport_types", transportTypes)
                        put("downstream_kbps", caps?.linkDownstreamBandwidthKbps)
                        put("upstream_kbps", caps?.linkUpstreamBandwidthKbps)
                    })
                    put("link_properties", JSONObject().apply {
                        put("interface_name", linkProps?.interfaceName)
                        put("dns_servers", JSONArray(linkProps?.dnsServers?.map { it.hostAddress } ?: emptyList<String>()))
                        put("mtu", linkProps?.mtu)
                    })
                    put("network_info", JSONObject().apply {
                        put("type", info?.typeName)
                        put("is_connected", info?.isConnected)
                        put("is_available", info?.isAvailable)
                        put("state", info?.state?.name)
                    })
                }
                jsonResponse(response)
            }
        } catch (e: Exception) {
            errorResponse("Error getting detailed network info: ${e.message}")
        }
    }


    // =====================================================================
    // Bluetooth Classic handlers
    // =====================================================================

    // GET /bluetooth/state - return adapter state, name, address, enabled
    private fun handleBluetoothState(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
            val json = JSONObject().apply {
                put("supported", adapter != null)
                if (adapter != null) {
                    put("enabled", adapter.isEnabled)
                    put("name", adapter.name ?: "unknown")
                    put("address", adapter.address ?: "unknown")
                    put("state", when (adapter.state) {
                        BluetoothAdapter.STATE_OFF -> "off"
                        BluetoothAdapter.STATE_ON -> "on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                        BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                        else -> "unknown"
                    })
                    put("scan_mode", when (adapter.scanMode) {
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "connectable_discoverable"
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "connectable"
                        BluetoothAdapter.SCAN_MODE_NONE -> "none"
                        else -> "unknown"
                    })
                    put("is_discovering", adapter.isDiscovering)
                }
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Error getting bluetooth state: ${e.message}")
        }
    }

    // POST /bluetooth/enable - enable adapter
    private fun handleBluetoothEnable(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (adapter.isEnabled) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "Bluetooth is already enabled")
                })
            }

            // On Android 13+ (TIRAMISU), enabling Bluetooth programmatically is restricted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return errorResponse("Cannot enable Bluetooth programmatically on Android 13+. Use system settings.")
            }

            @Suppress("DEPRECATION")
            val result = adapter.enable()
            jsonResponse(JSONObject().apply {
                put("success", result)
                put("message", if (result) "Bluetooth enable requested" else "Failed to request Bluetooth enable")
            })
        } catch (e: Exception) {
            errorResponse("Error enabling bluetooth: ${e.message}")
        }
    }

    // POST /bluetooth/disable - disable adapter
    private fun handleBluetoothDisable(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!adapter.isEnabled) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "Bluetooth is already disabled")
                })
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return errorResponse("Cannot disable Bluetooth programmatically on Android 13+. Use system settings.")
            }

            @Suppress("DEPRECATION")
            val result = adapter.disable()
            jsonResponse(JSONObject().apply {
                put("success", result)
                put("message", if (result) "Bluetooth disable requested" else "Failed to request Bluetooth disable")
            })
        } catch (e: Exception) {
            errorResponse("Error disabling bluetooth: ${e.message}")
        }
    }

    // GET /bluetooth/paired - list paired/bonded devices
    private fun handleBluetoothPaired(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            val devicesArray = JSONArray()
            adapter.bondedDevices?.forEach { device ->
                devicesArray.put(JSONObject().apply {
                    put("name", device.name ?: "Unknown")
                    put("address", device.address)
                    put("bond_state", when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> "bonded"
                        BluetoothDevice.BOND_BONDING -> "bonding"
                        BluetoothDevice.BOND_NONE -> "none"
                        else -> "unknown"
                    })
                    put("type", when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                        BluetoothDevice.DEVICE_TYPE_LE -> "le"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                        else -> "unknown"
                    })
                    put("device_class", device.bluetoothClass?.deviceClass ?: -1)
                    put("major_class", device.bluetoothClass?.majorDeviceClass ?: -1)
                    val uuids = device.uuids
                    if (uuids != null) {
                        put("uuids", JSONArray().apply {
                            uuids.forEach { put(it.uuid.toString()) }
                        })
                    }
                })
            }

            jsonResponse(JSONObject().apply {
                put("count", devicesArray.length())
                put("devices", devicesArray)
            })
        } catch (e: Exception) {
            errorResponse("Error getting paired devices: ${e.message}")
        }
    }

    // POST /bluetooth/pair - pair with address from body
    private fun handleBluetoothPair(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val address = data.optString("address", "")

            if (address.isBlank()) {
                return errorResponse("Missing 'address' field in request body")
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return errorResponse("Invalid Bluetooth address: $address")
            }

            val device = adapter.getRemoteDevice(address)

            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "Device is already paired")
                    put("address", address)
                })
            }

            val result = device.createBond()
            jsonResponse(JSONObject().apply {
                put("success", result)
                put("address", address)
                put("message", if (result) "Pairing initiated" else "Failed to initiate pairing")
            })
        } catch (e: Exception) {
            errorResponse("Error pairing bluetooth device: ${e.message}")
        }
    }

    // POST /bluetooth/unpair - unpair device by address
    private fun handleBluetoothUnpair(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val address = data.optString("address", "")

            if (address.isBlank()) {
                return errorResponse("Missing 'address' field in request body")
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return errorResponse("Invalid Bluetooth address: $address")
            }

            val device = adapter.getRemoteDevice(address)

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "Device is not paired")
                    put("address", address)
                })
            }

            // Use reflection to call removeBond() which is a hidden API
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as Boolean

            jsonResponse(JSONObject().apply {
                put("success", result)
                put("address", address)
                put("message", if (result) "Unpairing initiated" else "Failed to initiate unpairing")
            })
        } catch (e: Exception) {
            errorResponse("Error unpairing bluetooth device: ${e.message}")
        }
    }

    // GET /bluetooth/scan/results - return bluetoothDiscoveredDevices list
    private fun handleBluetoothScanResults(): Response {
        return try {
            val devicesArray = JSONArray()
            synchronized(bluetoothDiscoveredDevices) {
                bluetoothDiscoveredDevices.forEach { devicesArray.put(it) }
            }
            jsonResponse(JSONObject().apply {
                put("count", devicesArray.length())
                put("devices", devicesArray)
            })
        } catch (e: Exception) {
            errorResponse("Error getting bluetooth scan results: ${e.message}")
        }
    }

    // =====================================================================
    // Bluetooth HFP Headset handlers
    // =====================================================================

    private fun ensureHeadsetProfile(): Boolean {
        if (headsetProfile != null && headsetProfileReady) return true

        val adapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false

        val latch = java.util.concurrent.CountDownLatch(1)
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                headsetProfile = proxy as BluetoothHeadset
                headsetProfileReady = true
                latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) {
                headsetProfile = null
                headsetProfileReady = false
                latch.countDown()
            }
        }, BluetoothProfile.HEADSET)

        // Wait up to 3 seconds for the proxy to connect
        return latch.await(3, java.util.concurrent.TimeUnit.SECONDS) && headsetProfileReady
    }

    // GET /bluetooth/headset/status
    private fun handleBluetoothHeadsetStatus(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!adapter.isEnabled) {
                return errorResponse("Bluetooth is disabled")
            }

            val profileAvailable = ensureHeadsetProfile()
            val headset = headsetProfile

            val json = JSONObject().apply {
                put("profile_available", profileAvailable && headset != null)

                val devicesArray = JSONArray()
                if (profileAvailable && headset != null) {
                    val connectedDevices = headset.connectedDevices
                    for (device in connectedDevices) {
                        val deviceJson = JSONObject().apply {
                            put("name", device.name ?: "unknown")
                            put("address", device.address)
                            put("connection_state", when (headset.getConnectionState(device)) {
                                BluetoothProfile.STATE_CONNECTED -> "connected"
                                BluetoothProfile.STATE_CONNECTING -> "connecting"
                                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                                BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                                else -> "unknown"
                            })
                            put("audio_connected", headset.isAudioConnected(device))
                            put("noise_reduction_supported", headset.isNoiseReductionSupported(device))
                            put("voice_recognition_supported", headset.isVoiceRecognitionSupported(device))
                        }
                        devicesArray.put(deviceJson)
                    }
                }
                put("devices", devicesArray)
                put("device_count", devicesArray.length())
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Error getting headset status: ${e.message}")
        }
    }

    // POST /bluetooth/headset/voice_recognition
    private fun handleBluetoothHeadsetVoiceRecognition(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!adapter.isEnabled) {
                return errorResponse("Bluetooth is disabled")
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)

            val action = data.optString("action", "")
            if (action != "start" && action != "stop") {
                return errorResponse("Invalid action: '$action'. Must be 'start' or 'stop'",
                    Response.Status.BAD_REQUEST)
            }

            if (!ensureHeadsetProfile()) {
                return errorResponse("HFP headset profile not available. No headset connected?")
            }
            val headset = headsetProfile
                ?: return errorResponse("HFP headset profile not available")

            val connectedDevices = headset.connectedDevices
            if (connectedDevices.isEmpty()) {
                return errorResponse("No HFP headset devices connected")
            }

            // Resolve target device
            val deviceAddress = data.optString("device_address", "")
            val targetDevice = if (deviceAddress.isNotEmpty()) {
                connectedDevices.find { it.address.equals(deviceAddress, ignoreCase = true) }
                    ?: return errorResponse("Device $deviceAddress is not a connected HFP headset")
            } else {
                connectedDevices[0]
            }

            val result = when (action) {
                "start" -> headset.startVoiceRecognition(targetDevice)
                "stop" -> headset.stopVoiceRecognition(targetDevice)
                else -> false
            }

            val json = JSONObject().apply {
                put("success", result)
                put("action", action)
                put("device_name", targetDevice.name ?: "unknown")
                put("device_address", targetDevice.address)
                if (!result) {
                    put("error", "Voice recognition ${action} failed. Device may not support VR or audio channel unavailable.")
                }
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Error with headset voice recognition: ${e.message}")
        }
    }

    // =====================================================================
    // BLE handlers
    // =====================================================================

    // POST /ble/scan - start BLE scan with optional filters
    private fun handleBleScan(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!adapter.isEnabled) {
                return errorResponse("Bluetooth is disabled")
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val durationMs = data.optLong("duration_ms", 10000L).coerceIn(1000L, 30000L)
            val serviceUuid = data.optString("service_uuid", "")

            // Stop any existing scan
            bleScanCallback?.let {
                try {
                    adapter.bluetoothLeScanner?.stopScan(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping previous BLE scan", e)
                }
            }

            bleScanResults.clear()
            val scanner = adapter.bluetoothLeScanner
                ?: return errorResponse("BLE scanner not available")
            bleScanner = scanner

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: BleScanResult) {
                    bleScanResults[result.device.address] = result
                }

                override fun onBatchScanResults(results: MutableList<BleScanResult>) {
                    results.forEach { result ->
                        bleScanResults[result.device.address] = result
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                }
            }
            bleScanCallback = callback

            // Build scan filters
            val filters = mutableListOf<ScanFilter>()
            if (serviceUuid.isNotBlank()) {
                try {
                    filters.add(ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString(serviceUuid))
                        .build())
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid service UUID filter: $serviceUuid", e)
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            if (filters.isNotEmpty()) {
                scanner.startScan(filters, settings, callback)
            } else {
                scanner.startScan(null, settings, callback)
            }

            // Schedule scan stop
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    scanner.stopScan(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping BLE scan after timeout", e)
                }
            }, durationMs)

            jsonResponse(JSONObject().apply {
                put("success", true)
                put("message", "BLE scan started")
                put("duration_ms", durationMs)
                if (serviceUuid.isNotBlank()) put("filter_uuid", serviceUuid)
            })
        } catch (e: Exception) {
            errorResponse("Error starting BLE scan: ${e.message}")
        }
    }

    // GET /ble/scan/results - return bleScanResults map
    private fun handleBleScanResults(): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val devicesArray = JSONArray()
            bleScanResults.values.forEach { result ->
                devicesArray.put(JSONObject().apply {
                    put("address", result.device.address)
                    put("name", result.device.name ?: "Unknown")
                    put("rssi", result.rssi)
                    put("timestamp_nanos", result.timestampNanos)
                    val uuids = result.scanRecord?.serviceUuids
                    if (uuids != null) {
                        put("service_uuids", JSONArray().apply {
                            uuids.forEach { put(it.uuid.toString()) }
                        })
                    }
                    result.scanRecord?.let { record ->
                        put("tx_power_level", record.txPowerLevel)
                        put("advertise_flags", record.advertiseFlags)
                    }
                })
            }

            jsonResponse(JSONObject().apply {
                put("count", devicesArray.length())
                put("devices", devicesArray)
            })
        } catch (e: Exception) {
            errorResponse("Error getting BLE scan results: ${e.message}")
        }
    }

    // POST /ble/connect - connect to GATT server by address
    private fun handleBleConnect(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val address = data.optString("address", "")

            if (address.isBlank()) {
                return errorResponse("Missing 'address' field in request body")
            }

            val adapter = bluetoothManager.adapter
                ?: return errorResponse("Bluetooth not supported on this device")

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return errorResponse("Invalid Bluetooth address: $address")
            }

            // Disconnect existing GATT connection
            bluetoothGatt?.let {
                it.disconnect()
                it.close()
                bluetoothGatt = null
                gattServices = emptyList()
            }

            val device = adapter.getRemoteDevice(address)
            val latch = CountDownLatch(1)
            val connectionResult = AtomicReference<String>("timeout")

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectionResult.set("connected")
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connectionResult.set("disconnected")
                            latch.countDown()
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gattServices = gatt.services
                        connectionResult.set("connected")
                    } else {
                        connectionResult.set("service_discovery_failed")
                    }
                    latch.countDown()
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        @Suppress("DEPRECATION")
                        val value = characteristic.value
                        if (value != null) {
                            gattCharacteristicReadCache[characteristic.uuid.toString()] = value
                        }
                    }
                }
            }

            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            val completed = latch.await(15, TimeUnit.SECONDS)

            val result = connectionResult.get()
            jsonResponse(JSONObject().apply {
                put("success", result == "connected")
                put("address", address)
                put("status", result)
                if (result == "connected") {
                    put("services_count", gattServices.size)
                    put("services", JSONArray().apply {
                        gattServices.forEach { service ->
                            put(JSONObject().apply {
                                put("uuid", service.uuid.toString())
                                put("type", if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "primary" else "secondary")
                                put("characteristics_count", service.characteristics.size)
                                put("characteristics", JSONArray().apply {
                                    service.characteristics.forEach { char ->
                                        put(JSONObject().apply {
                                            put("uuid", char.uuid.toString())
                                            put("properties", char.properties)
                                            put("readable", (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                                            put("writable", (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                                            put("notifiable", (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                if (!completed) put("warning", "Connection timed out after 15 seconds")
            })
        } catch (e: Exception) {
            errorResponse("Error connecting to BLE device: ${e.message}")
        }
    }

    // POST /ble/read - read GATT characteristic
    private fun handleBleRead(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val gatt = bluetoothGatt
                ?: return errorResponse("No active BLE connection. Connect first via /ble/connect")

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val serviceUuid = data.optString("service_uuid", "")
            val characteristicUuid = data.optString("characteristic_uuid", "")

            if (serviceUuid.isBlank() || characteristicUuid.isBlank()) {
                return errorResponse("Missing 'service_uuid' and/or 'characteristic_uuid' in request body")
            }

            val service = gatt.getService(UUID.fromString(serviceUuid))
                ?: return errorResponse("Service not found: $serviceUuid")

            val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
                ?: return errorResponse("Characteristic not found: $characteristicUuid")

            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                return errorResponse("Characteristic is not readable")
            }

            // Clear cache for this characteristic
            gattCharacteristicReadCache.remove(characteristicUuid)

            @Suppress("DEPRECATION")
            val readInitiated = gatt.readCharacteristic(characteristic)
            if (!readInitiated) {
                return errorResponse("Failed to initiate characteristic read")
            }

            // Wait briefly for the callback to populate the cache
            Thread.sleep(2000)

            val value = gattCharacteristicReadCache[characteristicUuid]
            jsonResponse(JSONObject().apply {
                put("success", value != null)
                put("service_uuid", serviceUuid)
                put("characteristic_uuid", characteristicUuid)
                if (value != null) {
                    put("value_hex", value.joinToString("") { String.format("%02x", it) })
                    put("value_bytes", JSONArray().apply { value.forEach { put(it.toInt() and 0xFF) } })
                    // Try to interpret as UTF-8 string
                    try {
                        put("value_string", String(value, Charsets.UTF_8))
                    } catch (_: Exception) {}
                } else {
                    put("message", "Read initiated but no value received within timeout")
                }
            })
        } catch (e: Exception) {
            errorResponse("Error reading BLE characteristic: ${e.message}")
        }
    }

    // POST /ble/write - write GATT characteristic
    private fun handleBleWrite(session: IHTTPSession): Response {
        return try {
            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            val gatt = bluetoothGatt
                ?: return errorResponse("No active BLE connection. Connect first via /ble/connect")

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val serviceUuid = data.optString("service_uuid", "")
            val characteristicUuid = data.optString("characteristic_uuid", "")
            val valueHex = data.optString("value_hex", "")
            val valueString = data.optString("value_string", "")

            if (serviceUuid.isBlank() || characteristicUuid.isBlank()) {
                return errorResponse("Missing 'service_uuid' and/or 'characteristic_uuid' in request body")
            }

            if (valueHex.isBlank() && valueString.isBlank()) {
                return errorResponse("Provide either 'value_hex' or 'value_string' in request body")
            }

            val service = gatt.getService(UUID.fromString(serviceUuid))
                ?: return errorResponse("Service not found: $serviceUuid")

            val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
                ?: return errorResponse("Characteristic not found: $characteristicUuid")

            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                return errorResponse("Characteristic is not writable")
            }

            val bytes = if (valueHex.isNotBlank()) {
                valueHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                valueString.toByteArray(Charsets.UTF_8)
            }

            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            val writeInitiated = gatt.writeCharacteristic(characteristic)

            jsonResponse(JSONObject().apply {
                put("success", writeInitiated)
                put("service_uuid", serviceUuid)
                put("characteristic_uuid", characteristicUuid)
                put("bytes_written", bytes.size)
                put("message", if (writeInitiated) "Write initiated" else "Failed to initiate write")
            })
        } catch (e: Exception) {
            errorResponse("Error writing BLE characteristic: ${e.message}")
        }
    }

    // POST /ble/disconnect - disconnect GATT
    private fun handleBleDisconnect(): Response {
        return try {
            val gatt = bluetoothGatt
            if (gatt == null) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "No active BLE connection")
                })
            }

            val btPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!hasPermission(btPermission)) {
                return permissionError(btPermission)
            }

            gatt.disconnect()
            gatt.close()
            bluetoothGatt = null
            gattServices = emptyList()
            gattCharacteristicReadCache.clear()

            jsonResponse(JSONObject().apply {
                put("success", true)
                put("message", "BLE device disconnected")
            })
        } catch (e: Exception) {
            errorResponse("Error disconnecting BLE device: ${e.message}")
        }
    }

    // =====================================================================
    // WiFi handlers
    // =====================================================================

    // GET /wifi/state - return wifi enabled state
    private fun handleWifiState(): Response {
        return try {
            if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
                return permissionError(Manifest.permission.ACCESS_WIFI_STATE)
            }

            jsonResponse(JSONObject().apply {
                put("enabled", wifiManager.isWifiEnabled)
                put("wifi_state", when (wifiManager.wifiState) {
                    WifiManager.WIFI_STATE_DISABLED -> "disabled"
                    WifiManager.WIFI_STATE_DISABLING -> "disabling"
                    WifiManager.WIFI_STATE_ENABLED -> "enabled"
                    WifiManager.WIFI_STATE_ENABLING -> "enabling"
                    WifiManager.WIFI_STATE_UNKNOWN -> "unknown"
                    else -> "unknown"
                })
                put("is_5ghz_supported", wifiManager.is5GHzBandSupported)
            })
        } catch (e: Exception) {
            errorResponse("Error getting wifi state: ${e.message}")
        }
    }

    // POST /wifi/enable - enable wifi
    private fun handleWifiEnable(): Response {
        return try {
            if (!hasPermission(Manifest.permission.CHANGE_WIFI_STATE)) {
                return permissionError(Manifest.permission.CHANGE_WIFI_STATE)
            }

            if (wifiManager.isWifiEnabled) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "WiFi is already enabled")
                })
            }

            // On Android 10+ (Q), cannot toggle wifi programmatically
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return errorResponse("Cannot enable WiFi programmatically on Android 10+. Use system settings panel.")
            }

            @Suppress("DEPRECATION")
            val result = wifiManager.setWifiEnabled(true)
            jsonResponse(JSONObject().apply {
                put("success", result)
                put("message", if (result) "WiFi enable requested" else "Failed to enable WiFi")
            })
        } catch (e: Exception) {
            errorResponse("Error enabling wifi: ${e.message}")
        }
    }

    // POST /wifi/disable - disable wifi
    private fun handleWifiDisable(): Response {
        return try {
            if (!hasPermission(Manifest.permission.CHANGE_WIFI_STATE)) {
                return permissionError(Manifest.permission.CHANGE_WIFI_STATE)
            }

            if (!wifiManager.isWifiEnabled) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("message", "WiFi is already disabled")
                })
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return errorResponse("Cannot disable WiFi programmatically on Android 10+. Use system settings panel.")
            }

            @Suppress("DEPRECATION")
            val result = wifiManager.setWifiEnabled(false)
            jsonResponse(JSONObject().apply {
                put("success", result)
                put("message", if (result) "WiFi disable requested" else "Failed to disable WiFi")
            })
        } catch (e: Exception) {
            errorResponse("Error disabling wifi: ${e.message}")
        }
    }

    // GET /wifi/connection - return current connection details
    private fun handleWifiConnectionInfo(): Response {
        return try {
            if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
                return permissionError(Manifest.permission.ACCESS_WIFI_STATE)
            }

            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo

            jsonResponse(JSONObject().apply {
                put("connected", wifiInfo != null && wifiInfo.networkId != -1)
                if (wifiInfo != null) {
                    put("ssid", wifiInfo.ssid?.replace("\"", "") ?: "unknown")
                    put("bssid", wifiInfo.bssid ?: "unknown")
                    put("rssi", wifiInfo.rssi)
                    put("link_speed_mbps", wifiInfo.linkSpeed)
                    put("frequency_mhz", wifiInfo.frequency)
                    put("ip_address", formatIpAddress(wifiInfo.ipAddress))
                    put("mac_address", wifiInfo.macAddress ?: "unknown")
                    put("network_id", wifiInfo.networkId)
                    put("hidden_ssid", wifiInfo.hiddenSSID)
                    // Signal level (0-4)
                    @Suppress("DEPRECATION")
                    put("signal_level", WifiManager.calculateSignalLevel(wifiInfo.rssi, 5))
                    // Band
                    val band = when {
                        wifiInfo.frequency in 2400..2500 -> "2.4GHz"
                        wifiInfo.frequency in 5150..5850 -> "5GHz"
                        wifiInfo.frequency in 5925..7125 -> "6GHz"
                        else -> "unknown"
                    }
                    put("band", band)
                }

                // DHCP info
                val dhcpInfo = wifiManager.dhcpInfo
                if (dhcpInfo != null) {
                    put("dhcp", JSONObject().apply {
                        put("gateway", formatIpAddress(dhcpInfo.gateway))
                        put("netmask", formatIpAddress(dhcpInfo.netmask))
                        put("dns1", formatIpAddress(dhcpInfo.dns1))
                        put("dns2", formatIpAddress(dhcpInfo.dns2))
                        put("server_address", formatIpAddress(dhcpInfo.serverAddress))
                        put("lease_duration", dhcpInfo.leaseDuration)
                    })
                }
            })
        } catch (e: Exception) {
            errorResponse("Error getting wifi connection info: ${e.message}")
        }
    }

    // POST /wifi/scan - trigger wifi scan
    private fun handleWifiScanStart(): Response {
        return try {
            if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
                return permissionError(Manifest.permission.ACCESS_WIFI_STATE)
            }
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (!wifiManager.isWifiEnabled) {
                return errorResponse("WiFi is disabled")
            }

            @Suppress("DEPRECATION")
            val scanStarted = wifiManager.startScan()

            jsonResponse(JSONObject().apply {
                put("success", scanStarted)
                put("message", if (scanStarted) "WiFi scan started. Retrieve results at /wifi/scan/results" else "WiFi scan failed to start (throttled or disabled)")
            })
        } catch (e: Exception) {
            errorResponse("Error starting wifi scan: ${e.message}")
        }
    }

    // GET /wifi/scan/results - return scan results
    private fun handleWifiScanResults(): Response {
        return try {
            if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) {
                return permissionError(Manifest.permission.ACCESS_WIFI_STATE)
            }
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            val scanResults = wifiManager.scanResults
            val networksArray = JSONArray()

            scanResults?.forEach { result ->
                networksArray.put(JSONObject().apply {
                    put("ssid", result.SSID)
                    put("bssid", result.BSSID)
                    put("level", result.level)
                    put("frequency", result.frequency)
                    put("capabilities", result.capabilities)

                    val security = when {
                        result.capabilities.contains("WPA3") -> "WPA3"
                        result.capabilities.contains("WPA2") -> "WPA2"
                        result.capabilities.contains("WPA") -> "WPA"
                        result.capabilities.contains("WEP") -> "WEP"
                        else -> "Open"
                    }
                    put("security", security)

                    val band = when {
                        result.frequency in 2400..2500 -> "2.4GHz"
                        result.frequency in 5150..5850 -> "5GHz"
                        result.frequency in 5925..7125 -> "6GHz"
                        else -> "Unknown"
                    }
                    put("band", band)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val channelWidth = when (result.channelWidth) {
                            android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> "20MHz"
                            android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> "40MHz"
                            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ -> "80MHz"
                            android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> "160MHz"
                            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80MHz"
                            else -> "Unknown"
                        }
                        put("channel_width", channelWidth)
                    }

                    @Suppress("DEPRECATION")
                    put("signal_level", WifiManager.calculateSignalLevel(result.level, 5))
                })
            }

            jsonResponse(JSONObject().apply {
                put("count", networksArray.length())
                put("networks", networksArray)
            })
        } catch (e: Exception) {
            errorResponse("Error getting wifi scan results: ${e.message}")
        }
    }

    // POST /wifi/suggest - add network suggestion
    private fun handleWifiSuggest(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return errorResponse("WiFi network suggestions require Android 10+")
            }

            if (!hasPermission(Manifest.permission.CHANGE_WIFI_STATE)) {
                return permissionError(Manifest.permission.CHANGE_WIFI_STATE)
            }

            val body = parseBody(session) ?: return errorResponse("Missing request body")
            val data = JSONObject(body)
            val ssid = data.optString("ssid", "")
            val password = data.optString("password", "")
            val isWpa3 = data.optBoolean("wpa3", false)
            val isHidden = data.optBoolean("hidden", false)

            if (ssid.isBlank()) {
                return errorResponse("Missing 'ssid' field in request body")
            }

            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(isHidden)

            if (password.isNotBlank()) {
                if (isWpa3) {
                    suggestionBuilder.setWpa3Passphrase(password)
                } else {
                    suggestionBuilder.setWpa2Passphrase(password)
                }
            }

            val suggestion = suggestionBuilder.build()
            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

            val statusMsg = when (status) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "success"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL -> "internal_error"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> "app_disallowed"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "duplicate"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> "exceeds_max"
                else -> "unknown_error"
            }

            jsonResponse(JSONObject().apply {
                put("success", status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
                put("status", statusMsg)
                put("ssid", ssid)
            })
        } catch (e: Exception) {
            errorResponse("Error adding wifi suggestion: ${e.message}")
        }
    }

    // GET /wifi/suggest/list - list current suggestions
    private fun handleWifiSuggestList(): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return errorResponse("Listing WiFi suggestions requires Android 11+")
            }

            val suggestions = wifiManager.networkSuggestions
            val suggestionsArray = JSONArray()

            suggestions.forEach { suggestion ->
                suggestionsArray.put(JSONObject().apply {
                    put("ssid", suggestion.ssid ?: "unknown")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        put("bssid", suggestion.bssid?.toString() ?: "any")
                    }
                })
            }

            jsonResponse(JSONObject().apply {
                put("count", suggestionsArray.length())
                put("suggestions", suggestionsArray)
            })
        } catch (e: Exception) {
            errorResponse("Error listing wifi suggestions: ${e.message}")
        }
    }

    // ==================== Camera Endpoints ====================

    // GET /camera/list - List available cameras with facing direction and capabilities
    private fun handleCameraList(): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }

        try {
            val camerasArray = JSONArray()
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingString = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }

                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val capabilityNames = capabilities?.map { cap ->
                    when (cap) {
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "backward_compatible"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "manual_sensor"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "manual_post_processing"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "raw"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "read_sensor_settings"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "burst_capture"
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "depth_output"
                        else -> "capability_$cap"
                    }
                } ?: emptyList()

                val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpegSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
                val resolutionsArray = JSONArray()
                jpegSizes?.take(5)?.forEach { size ->
                    resolutionsArray.put(JSONObject().apply {
                        put("width", size.width)
                        put("height", size.height)
                    })
                }

                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val hwLevelString = when (hwLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
                    else -> "unknown"
                }

                camerasArray.put(JSONObject().apply {
                    put("camera_id", cameraId)
                    put("facing", facingString)
                    put("has_flash", hasFlash)
                    put("sensor_orientation", sensorOrientation)
                    put("hardware_level", hwLevelString)
                    put("capabilities", JSONArray(capabilityNames))
                    put("resolutions", resolutionsArray)
                })
            }

            // Add CameraX concurrent camera support info
            val concurrentPairs = try {
                cameraProvider.availableConcurrentCameraInfos.map { pair ->
                    JSONArray().apply {
                        pair.forEach { info ->
                            put(JSONObject().apply {
                                put("camera_id", try { Camera2CameraInfo.from(info).cameraId } catch (_: Exception) { "unknown" })
                                put("lens_facing", when (info.lensFacing) {
                                    CameraSelector.LENS_FACING_FRONT -> "front"
                                    CameraSelector.LENS_FACING_BACK -> "back"
                                    else -> "external"
                                })
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }

            val concurrentArray = JSONArray()
            concurrentPairs.forEach { concurrentArray.put(it) }

            val json = JSONObject().apply {
                put("cameras", camerasArray)
                put("count", camerasArray.length())
                put("supports_concurrent_cameras", concurrentPairs.isNotEmpty())
                put("concurrent_camera_pairs", concurrentArray)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to list cameras: ${e.message}")
        }
    }

    // POST /camera/photo - Take a photo using CameraX ImageCapture
    // Body: {"camera_id": "0", "quality": 90, "flash": "auto"}
    private fun handleCameraPhoto(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val cameraId = data.optString("camera_id", "0")
        val quality = data.optInt("quality", 90).coerceIn(1, 100)
        val flashMode = when (data.optString("flash", "off")) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        // Map camera_id to CameraSelector
        val cameraSelector = when (cameraId) {
            "0" -> CameraSelector.DEFAULT_BACK_CAMERA
            "1" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                try {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { try { Camera2CameraInfo.from(it).cameraId == cameraId } catch (_: Exception) { false } }
                        }
                        .build()
                } catch (e: Exception) {
                    return errorResponse("Invalid camera_id: $cameraId")
                }
            }
        }

        try {
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .setJpegQuality(quality)
                .build()

            // Bind on main thread (CameraX requirement)
            val latch = CountDownLatch(1)
            val errorHolder = arrayOfNulls<String>(1)

            Handler(Looper.getMainLooper()).post {
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ProcessLifecycleOwner.get(),
                        cameraSelector,
                        imageCapture
                    )
                    latch.countDown()
                } catch (e: Exception) {
                    errorHolder[0] = "Failed to bind camera: ${e.message}"
                    latch.countDown()
                }
            }

            if (!latch.await(5, TimeUnit.SECONDS)) {
                return errorResponse("Timeout binding camera")
            }
            if (errorHolder[0] != null) {
                return errorResponse(errorHolder[0]!!)
            }

            // Allow auto-focus/auto-exposure to settle
            Thread.sleep(500)

            // Take photo
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val photoFile = File(context.cacheDir, "photo_${timestamp}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            val captureLatch = CountDownLatch(1)
            val resultHolder = arrayOfNulls<ImageCapture.OutputFileResults>(1)
            val captureErrorHolder = arrayOfNulls<String>(1)

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        resultHolder[0] = output
                        captureLatch.countDown()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        captureErrorHolder[0] = "Capture failed: ${exception.message}"
                        captureLatch.countDown()
                    }
                }
            )

            if (!captureLatch.await(15, TimeUnit.SECONDS)) {
                val cleanupLatch = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    cameraProvider.unbindAll()
                    cleanupLatch.countDown()
                }
                cleanupLatch.await(2, TimeUnit.SECONDS)
                return errorResponse("Timeout waiting for photo capture")
            }

            val unbindLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                cameraProvider.unbindAll()
                unbindLatch.countDown()
            }
            unbindLatch.await(2, TimeUnit.SECONDS)

            if (captureErrorHolder[0] != null) {
                return errorResponse(captureErrorHolder[0]!!)
            }

            // Create thumbnail
            val imageBytes = photoFile.readBytes()
            val fullBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val thumbWidth = 200
            val thumbHeight = (fullBitmap.height.toFloat() / fullBitmap.width * thumbWidth).toInt()
            val thumbnail = Bitmap.createScaledBitmap(fullBitmap, thumbWidth, thumbHeight, true)
            val thumbStream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 60, thumbStream)
            val thumbBase64 = Base64.encodeToString(thumbStream.toByteArray(), Base64.NO_WRAP)

            val json = JSONObject().apply {
                put("success", true)
                put("file_path", photoFile.absolutePath)
                put("file_size_bytes", photoFile.length())
                put("width", fullBitmap.width)
                put("height", fullBitmap.height)
                put("quality", quality)
                put("camera_id", cameraId)
                put("flash_mode", data.optString("flash", "off"))
                put("thumbnail_base64", thumbBase64)
                put("thumbnail_width", thumbWidth)
                put("thumbnail_height", thumbHeight)
            }

            fullBitmap.recycle()
            thumbnail.recycle()

            return jsonResponse(json)
        } catch (e: SecurityException) {
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return permissionError(Manifest.permission.CAMERA)
        } catch (e: Exception) {
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return errorResponse("Failed to take photo: ${e.message}")
        }
    }

    // POST /camera/analyze - Analyze camera frames using CameraX ImageAnalysis
    // Body: {"duration_ms": 3000, "analyzer_mode": "latest", "camera_id": "0", "max_frames": 10}
    private fun handleCameraAnalyze(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }

        if (isVideoRecording) {
            return errorResponse("Cannot analyze while video recording is in progress")
        }
        if (isAnalyzing) {
            return errorResponse("Analysis already in progress")
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val durationMs = data.optLong("duration_ms", 3000L).coerceIn(100L, 30000L)
        val analyzerMode = data.optString("analyzer_mode", "latest")
        val cameraId = data.optString("camera_id", "0")
        val maxFrames = data.optInt("max_frames", 10).coerceIn(1, 100)

        val backpressureStrategy = when (analyzerMode) {
            "block" -> ImageAnalysis.STRATEGY_BLOCK_PRODUCER
            else -> ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }

        val cameraSelector = when (cameraId) {
            "0" -> CameraSelector.DEFAULT_BACK_CAMERA
            "1" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                try {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { try { Camera2CameraInfo.from(it).cameraId == cameraId } catch (_: Exception) { false } }
                        }
                        .build()
                } catch (e: Exception) {
                    return errorResponse("Invalid camera_id: $cameraId")
                }
            }
        }

        try {
            isAnalyzing = true

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(backpressureStrategy)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            // Collect frame metadata
            val frameDataList = Collections.synchronizedList(mutableListOf<JSONObject>())
            val analysisComplete = CountDownLatch(1)
            val startTime = System.currentTimeMillis()
            val frameCount = java.util.concurrent.atomic.AtomicInteger(0)

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                try {
                    val elapsed = System.currentTimeMillis() - startTime
                    val currentFrame = frameCount.incrementAndGet()

                    if (elapsed < durationMs && currentFrame <= maxFrames) {
                        val frameInfo = JSONObject().apply {
                            put("frame_number", currentFrame)
                            put("timestamp_ms", elapsed)
                            put("width", imageProxy.width)
                            put("height", imageProxy.height)
                            put("format", imageProxy.format)
                            put("format_name", when (imageProxy.format) {
                                ImageFormat.YUV_420_888 -> "YUV_420_888"
                                ImageFormat.JPEG -> "JPEG"
                                else -> "FORMAT_${imageProxy.format}"
                            })
                            put("rotation_degrees", imageProxy.imageInfo.rotationDegrees)
                            put("image_timestamp_ns", imageProxy.imageInfo.timestamp)
                            put("plane_count", imageProxy.planes.size)

                            // Plane details
                            val planesArray = JSONArray()
                            imageProxy.planes.forEachIndexed { index, plane ->
                                planesArray.put(JSONObject().apply {
                                    put("index", index)
                                    put("pixel_stride", plane.pixelStride)
                                    put("row_stride", plane.rowStride)
                                    put("buffer_size", plane.buffer.remaining())
                                })
                            }
                            put("planes", planesArray)

                            // Calculate approximate data rate
                            val totalBytes = imageProxy.planes.sumOf { it.buffer.remaining() }
                            put("frame_size_bytes", totalBytes)
                        }
                        frameDataList.add(frameInfo)
                    } else if (currentFrame > maxFrames || elapsed >= durationMs) {
                        analysisComplete.countDown()
                    }
                } finally {
                    imageProxy.close()  // CRITICAL: Must always close
                }
            }

            // Bind on main thread
            val bindLatch = CountDownLatch(1)
            val bindError = arrayOfNulls<String>(1)

            Handler(Looper.getMainLooper()).post {
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ProcessLifecycleOwner.get(),
                        cameraSelector,
                        imageAnalysis
                    )
                    bindLatch.countDown()
                } catch (e: Exception) {
                    bindError[0] = "Failed to bind camera for analysis: ${e.message}"
                    bindLatch.countDown()
                    analysisComplete.countDown()
                }
            }

            if (!bindLatch.await(5, TimeUnit.SECONDS)) {
                isAnalyzing = false
                return errorResponse("Timeout binding camera for analysis")
            }
            if (bindError[0] != null) {
                isAnalyzing = false
                return errorResponse(bindError[0]!!)
            }

            // Wait for analysis duration (plus a little buffer)
            analysisComplete.await(durationMs + 2000, TimeUnit.MILLISECONDS)

            // Cleanup — unbindAll must run on main thread (CameraX requirement)
            imageAnalysis.clearAnalyzer()
            val unbindAnalyzeLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                cameraProvider.unbindAll()
                unbindAnalyzeLatch.countDown()
            }
            unbindAnalyzeLatch.await(2, TimeUnit.SECONDS)
            isAnalyzing = false

            val totalDuration = System.currentTimeMillis() - startTime
            val totalFrames = frameDataList.size
            val fps = if (totalDuration > 0) (totalFrames * 1000.0 / totalDuration) else 0.0

            val json = JSONObject().apply {
                put("success", true)
                put("camera_id", cameraId)
                put("analyzer_mode", analyzerMode)
                put("requested_duration_ms", durationMs)
                put("actual_duration_ms", totalDuration)
                put("total_frames_analyzed", totalFrames)
                put("effective_fps", String.format("%.1f", fps))
                put("backpressure_strategy", analyzerMode)

                // Summary from first frame (representative dimensions)
                if (frameDataList.isNotEmpty()) {
                    val first = frameDataList[0]
                    put("resolution", JSONObject().apply {
                        put("width", first.getInt("width"))
                        put("height", first.getInt("height"))
                    })
                    put("image_format", first.getString("format_name"))
                    put("rotation_degrees", first.getInt("rotation_degrees"))
                }

                // Frame details
                val framesArray = JSONArray()
                frameDataList.forEach { framesArray.put(it) }
                put("frames", framesArray)

                // Throughput stats
                if (frameDataList.size >= 2) {
                    val avgFrameSize = frameDataList.map { it.getLong("frame_size_bytes") }.average()
                    put("avg_frame_size_bytes", avgFrameSize.toLong())
                    put("throughput_mbps", String.format("%.1f", avgFrameSize * fps * 8 / 1_000_000))
                }
            }
            return jsonResponse(json)
        } catch (e: SecurityException) {
            isAnalyzing = false
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return permissionError(Manifest.permission.CAMERA)
        } catch (e: Exception) {
            isAnalyzing = false
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return errorResponse("Failed to analyze camera frames: ${e.message}")
        }
    }

    // POST /camera/video - Start/stop/pause/resume/status video recording using CameraX
    // Body: {"action": "start|stop|pause|resume|status", "camera_id": "0", "max_duration_seconds": 30, "quality": "hd"}
    private fun handleCameraVideo(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return permissionError(Manifest.permission.RECORD_AUDIO)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val action = data.optString("action", "")

        return when (action) {
            "start" -> handleCameraVideoStart(data)
            "stop" -> handleCameraVideoStop()
            "pause" -> handleCameraVideoPause()
            "resume" -> handleCameraVideoResume()
            "status" -> {
                val json = JSONObject().apply {
                    put("is_recording", isVideoRecording)
                    if (isVideoRecording) {
                        put("file_path", videoRecordingFile?.absolutePath)
                        put("duration_seconds", (System.currentTimeMillis() - videoRecordingStartTime) / 1000)
                        put("max_duration_seconds", videoMaxDurationSeconds)
                    }
                }
                jsonResponse(json)
            }
            else -> errorResponse("Invalid action: '$action'. Use 'start', 'stop', 'pause', 'resume', or 'status'")
        }
    }

    private fun handleCameraVideoStart(data: JSONObject): Response {
        if (isVideoRecording) {
            return errorResponse("Video recording already in progress")
        }

        val cameraId = data.optString("camera_id", "0")
        val maxDuration = data.optInt("max_duration_seconds", 30).coerceIn(1, 300)
        val qualityStr = data.optString("quality", "hd")

        val cameraSelector = when (cameraId) {
            "0" -> CameraSelector.DEFAULT_BACK_CAMERA
            "1" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                try {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { try { Camera2CameraInfo.from(it).cameraId == cameraId } catch (_: Exception) { false } }
                        }
                        .build()
                } catch (e: Exception) {
                    return errorResponse("Invalid camera_id: $cameraId")
                }
            }
        }

        val quality = when (qualityStr.lowercase()) {
            "sd", "low" -> Quality.SD
            "hd", "medium" -> Quality.HD
            "fhd", "high" -> Quality.FHD
            "uhd", "4k" -> Quality.UHD
            else -> Quality.HD
        }

        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            // Bind on main thread
            val latch = CountDownLatch(1)
            val errorHolder = arrayOfNulls<String>(1)

            Handler(Looper.getMainLooper()).post {
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ProcessLifecycleOwner.get(),
                        cameraSelector,
                        videoCapture
                    )
                    latch.countDown()
                } catch (e: Exception) {
                    errorHolder[0] = "Failed to bind camera: ${e.message}"
                    latch.countDown()
                }
            }

            if (!latch.await(5, TimeUnit.SECONDS)) {
                return errorResponse("Timeout binding camera for video")
            }
            if (errorHolder[0] != null) {
                return errorResponse(errorHolder[0]!!)
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val videoFile = File(context.cacheDir, "video_${timestamp}.mp4")
            val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

            val recording = videoCapture.output
                .prepareRecording(context, fileOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "Video recording error: ${event.error}")
                            }
                            Log.d(TAG, "Video recording finalized")
                        }
                        is VideoRecordEvent.Status -> {
                            // Could track recording stats here
                        }
                    }
                }

            activeRecording = recording
            videoRecordingFile = videoFile
            videoRecordingStartTime = System.currentTimeMillis()
            videoMaxDurationSeconds = maxDuration

            // Schedule auto-stop
            Handler(Looper.getMainLooper()).postDelayed({
                if (isVideoRecording) {
                    stopVideoRecording()
                }
            }, maxDuration * 1000L)

            val json = JSONObject().apply {
                put("success", true)
                put("action", "started")
                put("camera_id", cameraId)
                put("file_path", videoFile.absolutePath)
                put("quality", qualityStr)
                put("max_duration_seconds", maxDuration)
            }
            return jsonResponse(json)
        } catch (e: SecurityException) {
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return permissionError(Manifest.permission.CAMERA)
        } catch (e: Exception) {
            // Ensure camera is unbound on error (main thread required)
            val cleanupLatch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { cameraProvider.unbindAll() } catch (_: Exception) {}
                cleanupLatch.countDown()
            }
            cleanupLatch.await(2, TimeUnit.SECONDS)
            return errorResponse("Failed to start video recording: ${e.message}")
        }
    }

    private fun handleCameraVideoStop(): Response {
        if (!isVideoRecording) {
            return errorResponse("No video recording in progress")
        }

        return try {
            val file = videoRecordingFile
            val duration = (System.currentTimeMillis() - videoRecordingStartTime) / 1000

            stopVideoRecording()

            val json = JSONObject().apply {
                put("success", true)
                put("action", "stopped")
                put("file_path", file?.absolutePath)
                put("file_size_bytes", file?.length() ?: 0)
                put("duration_seconds", duration)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to stop video recording: ${e.message}")
        }
    }

    // Pause support (CameraX-only feature)
    private fun handleCameraVideoPause(): Response {
        val recording = activeRecording ?: return errorResponse("No video recording in progress")
        return try {
            recording.pause()
            val json = JSONObject().apply {
                put("success", true)
                put("action", "paused")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to pause video recording: ${e.message}")
        }
    }

    // Resume support (CameraX-only feature)
    private fun handleCameraVideoResume(): Response {
        val recording = activeRecording ?: return errorResponse("No video recording in progress")
        return try {
            recording.resume()
            val json = JSONObject().apply {
                put("success", true)
                put("action", "resumed")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to resume video recording: ${e.message}")
        }
    }

    // POST /camera/video/start - Direct start endpoint (called by MCP tool)
    // Body: {"camera_id": "0", "max_duration_seconds": 30, "quality": "hd"}
    private fun handleCameraVideoStartDirect(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return permissionError(Manifest.permission.RECORD_AUDIO)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        return handleCameraVideoStart(data)
    }

    private fun stopVideoRecording() {
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping video recording", e)
        }
        activeRecording = null
        // unbindAll must run on main thread (CameraX requirement)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cameraProvider.unbindAll()
        } else {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                cameraProvider.unbindAll()
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
        }
    }

    // GET /camera/status - Get overall camera system status (CameraX-based)
    private fun handleCameraStatus(): Response {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return permissionError(Manifest.permission.CAMERA)
        }

        try {
            val json = JSONObject().apply {
                // Video recording state
                put("is_recording", isVideoRecording)
                if (isVideoRecording) {
                    put("recording", JSONObject().apply {
                        put("file_path", videoRecordingFile?.absolutePath)
                        put("duration_seconds", (System.currentTimeMillis() - videoRecordingStartTime) / 1000)
                        put("max_duration_seconds", videoMaxDurationSeconds)
                    })
                }

                // Image analysis state
                put("is_analyzing", isAnalyzing)

                // Camera availability
                put("camera_count", cameraManager.cameraIdList.size)
                put("available_cameras", JSONArray(cameraManager.cameraIdList.toList()))

                // CameraX concurrent camera support
                try {
                    val concurrentSupported = cameraProvider.availableConcurrentCameraInfos.isNotEmpty()
                    put("supports_concurrent_cameras", concurrentSupported)
                } catch (e: Exception) {
                    put("supports_concurrent_cameras", false)
                }

                // Torch state (if applicable)
                val torchAvailable = try {
                    val chars = cameraManager.getCameraCharacteristics("0")
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (e: Exception) { false }
                put("torch_available", torchAvailable)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get camera status: ${e.message}")
        }
    }

    // ==================== Privacy Backlog Endpoints ====================

    // GET /privacy/backlog - Check backlog status
    private fun handlePrivacyBacklogStatus(): Response {
        val snapshot = privacyGate.getBacklogSnapshot()
        val items = JSONArray()
        for (entry in snapshot) {
            items.put(JSONObject().apply {
                put("source", entry.source)
                put("queued_at", entry.timestamp)
                put("preview", entry.item.optString("body",
                    entry.item.optString("text", "(no preview)")).take(80))
            })
        }
        val json = JSONObject().apply {
            put("backlog_size", snapshot.size)
            put("items", items)
        }
        return jsonResponse(json)
    }

    // POST /privacy/backlog/process - Run Gemma on all backlog items (no timeout)
    private fun handlePrivacyBacklogProcess(): Response {
        return try {
            val results = runBlocking { privacyGate.processBacklog() }
            val json = JSONObject().apply {
                put("success", true)
                put("processed", results.length())
                put("results", results)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Backlog processing failed: ${e.message}")
        }
    }

    // POST /privacy/backlog/clear - Clear backlog without processing
    private fun handlePrivacyBacklogClear(): Response {
        privacyGate.clearBacklog()
        val json = JSONObject().apply {
            put("success", true)
            put("message", "Backlog cleared")
        }
        return jsonResponse(json)
    }

    // GET /biometric/status - Biometric auth capabilities and enrollment
    private fun handleBiometricStatus(): Response {
        try {
            val biometricManager = BiometricManager.from(context)

            fun checkAuth(authenticator: Int): JSONObject {
                val result = biometricManager.canAuthenticate(authenticator)
                val hasHardware = result != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
                        result != BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
                val isEnrolled = result == BiometricManager.BIOMETRIC_SUCCESS
                return JSONObject().apply {
                    put("available", result == BiometricManager.BIOMETRIC_SUCCESS)
                    put("enrolled", isEnrolled)
                    put("hardware_present", hasHardware)
                    put("status", when (result) {
                        BiometricManager.BIOMETRIC_SUCCESS -> "ready"
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "not_enrolled"
                        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "security_update_required"
                        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "unknown"
                        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "unsupported"
                        else -> "unknown_code_$result"
                    })
                }
            }

            val json = JSONObject().apply {
                put("strongBiometric", checkAuth(BiometricManager.Authenticators.BIOMETRIC_STRONG))
                put("weakBiometric", checkAuth(BiometricManager.Authenticators.BIOMETRIC_WEAK))
                put("deviceCredential", checkAuth(BiometricManager.Authenticators.DEVICE_CREDENTIAL))
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get biometric status: ${e.message}")
        }
    }

    // ==================== Audio Recording Endpoints ====================

    // POST /audio/record/start - Start audio recording using MediaRecorder
    // Body: {"format": "mp4", "max_duration_seconds": 60}
    private fun handleAudioRecordStart(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return permissionError(Manifest.permission.RECORD_AUDIO)
        }

        if (isAudioRecording) {
            return errorResponse("Audio recording already in progress")
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val format = data.optString("format", "mp4")
        val maxDuration = data.optInt("max_duration_seconds", 60).coerceIn(1, 600)

        val outputFormat: Int
        val audioEncoder: Int
        val extension: String

        when (format) {
            "mp4", "m4a", "aac" -> {
                outputFormat = MediaRecorder.OutputFormat.MPEG_4
                audioEncoder = MediaRecorder.AudioEncoder.AAC
                extension = "m4a"
            }
            "3gp", "amr" -> {
                outputFormat = MediaRecorder.OutputFormat.THREE_GPP
                audioEncoder = MediaRecorder.AudioEncoder.AMR_NB
                extension = "3gp"
            }
            "ogg" -> {
                outputFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.OutputFormat.OGG
                } else {
                    return errorResponse("OGG format requires Android 10+")
                }
                audioEncoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioEncoder.OPUS
                } else {
                    MediaRecorder.AudioEncoder.AAC
                }
                extension = "ogg"
            }
            else -> return errorResponse("Unsupported format: '$format'. Use 'mp4', 'm4a', 'aac', '3gp', 'amr', or 'ogg'")
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val recordFile = File(context.cacheDir, "audio_${timestamp}.$extension")

            @Suppress("DEPRECATION")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(outputFormat)
                setAudioEncoder(audioEncoder)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                setOutputFile(recordFile.absolutePath)
                setMaxDuration(maxDuration * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "Audio max duration reached, stopping recording")
                        stopAudioRecording()
                    }
                }
                prepare()
                start()
            }

            audioMediaRecorder = recorder
            audioRecordingFile = recordFile
            audioRecordingStartTime = System.currentTimeMillis()
            audioRecordingMaxDuration = maxDuration
            isAudioRecording = true

            // Schedule auto-stop
            audioRecordingHandler.postDelayed({
                if (isAudioRecording) {
                    stopAudioRecording()
                }
            }, maxDuration * 1000L)

            val json = JSONObject().apply {
                put("success", true)
                put("status", "recording")
                put("file_path", recordFile.absolutePath)
                put("format", format)
                put("max_duration_seconds", maxDuration)
            }
            return jsonResponse(json)
        } catch (e: SecurityException) {
            return permissionError(Manifest.permission.RECORD_AUDIO)
        } catch (e: Exception) {
            return errorResponse("Failed to start audio recording: ${e.message}")
        }
    }

    // POST /audio/record/stop - Stop audio recording
    private fun handleAudioRecordStop(): Response {
        if (!isAudioRecording) {
            return errorResponse("No audio recording in progress")
        }

        try {
            val file = audioRecordingFile
            val durationMs = System.currentTimeMillis() - audioRecordingStartTime

            stopAudioRecording()

            val json = JSONObject().apply {
                put("success", true)
                put("status", "stopped")
                put("file_path", file?.absolutePath)
                put("file_size_bytes", file?.length() ?: 0)
                put("duration_seconds", durationMs / 1000.0)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to stop audio recording: ${e.message}")
        }
    }

    // GET /audio/status - Get current recording status
    private fun handleAudioStatus(): Response {
        val json = JSONObject().apply {
            put("is_recording", isAudioRecording)
            if (isAudioRecording) {
                val durationMs = System.currentTimeMillis() - audioRecordingStartTime
                put("file_path", audioRecordingFile?.absolutePath)
                put("duration_seconds", durationMs / 1000.0)
                put("max_duration_seconds", audioRecordingMaxDuration)
                put("remaining_seconds", maxOf(0.0, audioRecordingMaxDuration - durationMs / 1000.0))
            }
            // Also report video recording state
            put("is_video_recording", isVideoRecording)
            if (isVideoRecording) {
                val videoDurationMs = System.currentTimeMillis() - videoRecordingStartTime
                put("video_file_path", videoRecordingFile?.absolutePath)
                put("video_duration_seconds", videoDurationMs / 1000.0)
                put("video_max_duration_seconds", videoMaxDurationSeconds)
            }
        }
        return jsonResponse(json)
    }

    private fun stopAudioRecording() {
        try {
            audioMediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio MediaRecorder", e)
        }
        try {
            audioMediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio MediaRecorder", e)
        }
        audioMediaRecorder = null
        isAudioRecording = false
        audioRecordingHandler.removeCallbacksAndMessages(null)
    }

    // ==================== Phone Endpoints ====================

    // POST /phone/call - Initiate a phone call
    private fun handlePhoneCall(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return permissionError(Manifest.permission.CALL_PHONE)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val number = data.optString("number", "")

        if (number.isEmpty()) {
            return errorResponse("Missing number")
        }

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                this.data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            val json = JSONObject().apply {
                put("success", true)
                put("number", number)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to initiate call: ${e.message}")
        }
    }

    // POST /phone/hangup - End current phone call
    private fun handlePhoneHangup(): Response {
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return permissionError(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                @Suppress("DEPRECATION")
                val ended = telecomManager.endCall()

                val json = JSONObject().apply {
                    put("success", ended)
                    if (!ended) {
                        put("reason", "No active call to end")
                    }
                }
                return jsonResponse(json)
            } else {
                return errorResponse("Hangup requires API 28+ (Android 9 Pie)", Response.Status.BAD_REQUEST)
            }
        } catch (e: Exception) {
            return errorResponse("Failed to end call: ${e.message}")
        }
    }

    // GET /phone/state - Get current phone call state
    private fun handlePhoneState(): Response {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return permissionError(Manifest.permission.READ_PHONE_STATE)
        }

        try {
            val callState = telephonyManager.callState
            val stateStr = when (callState) {
                TelephonyManager.CALL_STATE_IDLE -> "idle"
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                else -> "unknown"
            }

            val simStateStr = when (telephonyManager.simState) {
                TelephonyManager.SIM_STATE_ABSENT -> "absent"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
                TelephonyManager.SIM_STATE_READY -> "ready"
                TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
                TelephonyManager.SIM_STATE_PERM_DISABLED -> "permanently_disabled"
                TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card_io_error"
                TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "card_restricted"
                else -> "unknown"
            }

            val networkTypeStr = when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "NR"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
                else -> "other"
            }

            val json = JSONObject().apply {
                put("call_state", stateStr)
                put("network_type", networkTypeStr)
                put("operator_name", telephonyManager.networkOperatorName ?: "")
                put("sim_state", simStateStr)
                put("sim_operator", telephonyManager.simOperatorName ?: "")
                put("phone_type", when (telephonyManager.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                    TelephonyManager.PHONE_TYPE_NONE -> "none"
                    else -> "unknown"
                })
                put("is_data_enabled", telephonyManager.isDataEnabled)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get phone state: ${e.message}")
        }
    }

    // ========== WorkManager endpoints ==========

    private fun handleWorkSchedule(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val workType = data.optString("work_type", "one_time")
        val taskName = data.optString("task_name", "")
        val delayMinutes = data.optLong("delay_minutes", 0L)
        val intervalMinutes = data.optLong("interval_minutes", 15L)

        if (taskName.isEmpty()) {
            return errorResponse("Missing task_name", Response.Status.BAD_REQUEST)
        }

        try {
            // Build constraints
            val constraintsJson = data.optJSONObject("constraints")
            val constraints = Constraints.Builder().apply {
                if (constraintsJson != null) {
                    if (constraintsJson.optBoolean("network", false)) {
                        setRequiredNetworkType(NetworkType.CONNECTED)
                    }
                    if (constraintsJson.optBoolean("charging", false)) {
                        setRequiresCharging(true)
                    }
                    if (constraintsJson.optBoolean("idle", false)) {
                        setRequiresDeviceIdle(true)
                    }
                    if (constraintsJson.optBoolean("storage_not_low", false)) {
                        setRequiresStorageNotLow(true)
                    }
                    if (constraintsJson.optBoolean("battery_not_low", false)) {
                        setRequiresBatteryNotLow(true)
                    }
                }
            }.build()

            // Build input data
            val inputData = Data.Builder()
                .putString("task_name", taskName)
                .build()

            val workManager = WorkManager.getInstance(context)

            when (workType) {
                "one_time" -> {
                    val request = OneTimeWorkRequestBuilder<DeviceApiWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .addTag(taskName)
                        .apply {
                            if (delayMinutes > 0) {
                                setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                            }
                        }
                        .build()

                    workManager.enqueue(request)

                    val json = JSONObject().apply {
                        put("success", true)
                        put("work_type", "one_time")
                        put("task_name", taskName)
                        put("work_id", request.id.toString())
                        put("delay_minutes", delayMinutes)
                    }
                    return jsonResponse(json)
                }
                "periodic" -> {
                    if (intervalMinutes < 15) {
                        return errorResponse("Periodic interval must be at least 15 minutes", Response.Status.BAD_REQUEST)
                    }

                    val request = PeriodicWorkRequestBuilder<DeviceApiWorker>(
                        intervalMinutes, TimeUnit.MINUTES
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .addTag(taskName)
                        .apply {
                            if (delayMinutes > 0) {
                                setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                            }
                        }
                        .build()

                    workManager.enqueue(request)

                    val json = JSONObject().apply {
                        put("success", true)
                        put("work_type", "periodic")
                        put("task_name", taskName)
                        put("work_id", request.id.toString())
                        put("interval_minutes", intervalMinutes)
                        put("delay_minutes", delayMinutes)
                    }
                    return jsonResponse(json)
                }
                else -> {
                    return errorResponse("Invalid work_type: must be 'one_time' or 'periodic'", Response.Status.BAD_REQUEST)
                }
            }
        } catch (e: Exception) {
            return errorResponse("Failed to schedule work: ${e.message}")
        }
    }

    private fun handleWorkStatus(session: IHTTPSession): Response {
        val tag = session.parms["tag"] ?: return errorResponse("Missing query param: tag", Response.Status.BAD_REQUEST)

        try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosByTag(tag).get()

            val results = JSONArray()
            for (workInfo in workInfos) {
                results.put(JSONObject().apply {
                    put("id", workInfo.id.toString())
                    put("state", workInfo.state.name)
                    put("tags", JSONArray(workInfo.tags.toList()))
                    put("run_attempt_count", workInfo.runAttemptCount)
                    // Progress data
                    val progressData = workInfo.progress
                    val progressJson = JSONObject()
                    for (key in progressData.keyValueMap.keys) {
                        progressJson.put(key, progressData.keyValueMap[key])
                    }
                    put("progress", progressJson)
                    // Output data
                    val outputData = workInfo.outputData
                    val outputJson = JSONObject()
                    for (key in outputData.keyValueMap.keys) {
                        outputJson.put(key, outputData.keyValueMap[key])
                    }
                    put("output", outputJson)
                })
            }

            val json = JSONObject().apply {
                put("tag", tag)
                put("count", results.length())
                put("work_infos", results)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to get work status: ${e.message}")
        }
    }

    private fun handleWorkCancel(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val tag = data.optString("tag", "")
        val id = data.optString("id", "")

        if (tag.isEmpty() && id.isEmpty()) {
            return errorResponse("Must provide 'tag' or 'id'", Response.Status.BAD_REQUEST)
        }

        try {
            val workManager = WorkManager.getInstance(context)

            if (id.isNotEmpty()) {
                workManager.cancelWorkById(UUID.fromString(id))
                val json = JSONObject().apply {
                    put("success", true)
                    put("cancelled_by", "id")
                    put("id", id)
                }
                return jsonResponse(json)
            } else {
                workManager.cancelAllWorkByTag(tag)
                val json = JSONObject().apply {
                    put("success", true)
                    put("cancelled_by", "tag")
                    put("tag", tag)
                }
                return jsonResponse(json)
            }
        } catch (e: Exception) {
            return errorResponse("Failed to cancel work: ${e.message}")
        }
    }

    // ========== Intent endpoints ==========

    private fun handleIntentBroadcast(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val action = data.optString("action", "")
        if (action.isEmpty()) {
            return errorResponse("Missing action", Response.Status.BAD_REQUEST)
        }

        try {
            val intent = Intent(action)

            // Set target package if specified
            val packageName = data.optString("package", "")
            if (packageName.isNotEmpty()) {
                intent.setPackage(packageName)
            }

            // Set permission if specified
            val receiverPermission = data.optString("permission", "")

            // Add extras
            val extras = data.optJSONObject("extras")
            if (extras != null) {
                val keys = extras.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = extras.get(key)) {
                        is String -> intent.putExtra(key, value)
                        is Int -> intent.putExtra(key, value)
                        is Long -> intent.putExtra(key, value)
                        is Double -> intent.putExtra(key, value)
                        is Boolean -> intent.putExtra(key, value)
                        else -> intent.putExtra(key, value.toString())
                    }
                }
            }

            // BroadcastOptions (API 34+ / UPSIDE_DOWN_CAKE)
            val optionsObj = data.optJSONObject("broadcastOptions")
            var usedBroadcastOptions = false

            if (optionsObj != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()

                // Deferral policy: -1=default, 0=none, 1=until_active
                if (optionsObj.has("deferralPolicy")) {
                    options.setDeferralPolicy(optionsObj.getInt("deferralPolicy"))
                }

                // Delivery group policy: 0=all, 1=most_recent
                if (optionsObj.has("deliveryGroupPolicy")) {
                    options.setDeliveryGroupPolicy(optionsObj.getInt("deliveryGroupPolicy"))
                }

                // Delivery group matching key (requires namespace + key)
                if (optionsObj.has("deliveryGroupMatchingNamespace") && optionsObj.has("deliveryGroupMatchingKey")) {
                    options.setDeliveryGroupMatchingKey(
                        optionsObj.getString("deliveryGroupMatchingNamespace"),
                        optionsObj.getString("deliveryGroupMatchingKey")
                    )
                }

                // Share identity
                if (optionsObj.has("shareIdentity")) {
                    options.setShareIdentityEnabled(optionsObj.getBoolean("shareIdentity"))
                }

                context.sendBroadcast(
                    intent,
                    if (receiverPermission.isNotEmpty()) receiverPermission else null,
                    options.toBundle()
                )
                usedBroadcastOptions = true
            } else {
                if (receiverPermission.isNotEmpty()) {
                    context.sendBroadcast(intent, receiverPermission)
                } else {
                    context.sendBroadcast(intent)
                }
            }

            val json = JSONObject().apply {
                put("success", true)
                put("action", action)
                if (packageName.isNotEmpty()) put("package", packageName)
                if (receiverPermission.isNotEmpty()) put("permission", receiverPermission)
                put("broadcastOptionsApplied", usedBroadcastOptions)
                if (optionsObj != null && !usedBroadcastOptions) {
                    put("broadcastOptionsSkipped", "requires API 34+ (current: ${Build.VERSION.SDK_INT})")
                }
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to send broadcast: ${e.message}")
        }
    }

    private fun handleIntentActivity(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val action = data.optString("action", "")
        if (action.isEmpty()) {
            return errorResponse("Missing action", Response.Status.BAD_REQUEST)
        }

        try {
            val intent = Intent(action)

            // Set data URI if specified
            val dataUri = data.optString("data", "")
            if (dataUri.isNotEmpty()) {
                intent.data = Uri.parse(dataUri)
            }

            // Set target package if specified
            val packageName = data.optString("package", "")
            if (packageName.isNotEmpty()) {
                intent.setPackage(packageName)
            }

            // Set MIME type if specified (handles data+type combo)
            val mimeType = data.optString("mimeType", data.optString("type", ""))
            if (mimeType.isNotEmpty()) {
                if (dataUri.isNotEmpty()) {
                    intent.setDataAndType(Uri.parse(dataUri), mimeType)
                } else {
                    intent.type = mimeType
                }
            }

            // Add categories
            val categories = data.optJSONArray("categories")
            if (categories != null) {
                for (i in 0 until categories.length()) {
                    intent.addCategory(categories.getString(i))
                }
            }

            // Add extras
            val extras = data.optJSONObject("extras")
            if (extras != null) {
                val keys = extras.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = extras.get(key)) {
                        is String -> intent.putExtra(key, value)
                        is Int -> intent.putExtra(key, value)
                        is Long -> intent.putExtra(key, value)
                        is Double -> intent.putExtra(key, value)
                        is Boolean -> intent.putExtra(key, value)
                        else -> intent.putExtra(key, value.toString())
                    }
                }
            }

            // Optional intent flags (user can pass additional flags as integer bitmask)
            val flagsValue = data.optInt("flags", 0)

            // Required for starting activity from non-activity context
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or flagsValue)

            // Resolve check: verify an activity can handle this intent before launching
            val resolveFirst = data.optBoolean("resolveFirst", false)
            if (resolveFirst) {
                val resolveInfo = context.packageManager.resolveActivity(intent, 0)
                if (resolveInfo == null) {
                    val json = JSONObject().apply {
                        put("success", false)
                        put("resolved", false)
                        put("action", action)
                        put("error", "No activity found to handle this intent")
                        if (dataUri.isNotEmpty()) put("data", dataUri)
                        if (mimeType.isNotEmpty()) put("mimeType", mimeType)
                        if (packageName.isNotEmpty()) put("package", packageName)
                    }
                    return jsonResponse(json)
                }
            }

            context.startActivity(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("action", action)
                if (resolveFirst) put("resolved", true)
                if (dataUri.isNotEmpty()) put("data", dataUri)
                if (mimeType.isNotEmpty()) put("mimeType", mimeType)
                if (packageName.isNotEmpty()) put("package", packageName)
                if (categories != null && categories.length() > 0) {
                    put("categories", categories)
                }
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to start activity: ${e.message}")
        }
    }

    // ============================================================
    // Geofencing endpoints
    // ============================================================

    private fun handleGeofenceAdd(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return permissionError(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return permissionError(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val id = data.optString("id", "")
        if (id.isBlank()) return errorResponse("Missing required field: id", Response.Status.BAD_REQUEST)

        val latitude = data.optDouble("latitude", Double.NaN)
        val longitude = data.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) {
            return errorResponse("Missing required fields: latitude, longitude", Response.Status.BAD_REQUEST)
        }

        val radiusMeters = data.optDouble("radius_meters", 100.0).toFloat()
        val expirationMs = data.optLong("expiration_ms", -1L)

        val transitionsArr = data.optJSONArray("transitions")
        var transitionTypes = 0
        if (transitionsArr != null) {
            for (i in 0 until transitionsArr.length()) {
                when (transitionsArr.optString(i)) {
                    "enter" -> transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_ENTER
                    "exit" -> transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_EXIT
                    "dwell" -> transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_DWELL
                }
            }
        }
        if (transitionTypes == 0) {
            transitionTypes = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
        }

        val loiteringDelayMs = data.optInt("loitering_delay_ms", 30000)

        try {
            val geofence = Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(latitude, longitude, radiusMeters)
                .setExpirationDuration(expirationMs)
                .setTransitionTypes(transitionTypes)
                .setLoiteringDelay(loiteringDelayMs)
                .build()

            val geofencingRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val latch = CountDownLatch(1)
            var success = false
            var failureMessage = ""

            geofencingClient.addGeofences(geofencingRequest, pendingIntent)
                .addOnSuccessListener {
                    success = true
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    failureMessage = e.message ?: "Unknown error"
                    latch.countDown()
                }

            latch.await(10, TimeUnit.SECONDS)

            if (success) {
                // Track the geofence in our in-memory map
                val transitionNames = mutableListOf<String>()
                if (transitionTypes and Geofence.GEOFENCE_TRANSITION_ENTER != 0) transitionNames.add("enter")
                if (transitionTypes and Geofence.GEOFENCE_TRANSITION_EXIT != 0) transitionNames.add("exit")
                if (transitionTypes and Geofence.GEOFENCE_TRANSITION_DWELL != 0) transitionNames.add("dwell")

                activeGeofences[id] = JSONObject().apply {
                    put("id", id)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("radius_meters", radiusMeters)
                    put("expiration_ms", expirationMs)
                    put("transitions", JSONArray(transitionNames))
                    put("loitering_delay_ms", loiteringDelayMs)
                    put("added_at", System.currentTimeMillis())
                }

                val json = JSONObject().apply {
                    put("success", true)
                    put("geofence_id", id)
                    put("message", "Geofence added successfully")
                }
                return jsonResponse(json)
            } else {
                return errorResponse("Failed to add geofence: $failureMessage")
            }
        } catch (e: SecurityException) {
            return errorResponse("Security exception: ${e.message}. Ensure location permissions are granted.", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            return errorResponse("Failed to add geofence: ${e.message}")
        }
    }

    private fun handleGeofenceRemove(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val idsArray = data.optJSONArray("ids")
        if (idsArray == null || idsArray.length() == 0) {
            return errorResponse("Missing required field: ids (array of geofence IDs)", Response.Status.BAD_REQUEST)
        }

        val ids = mutableListOf<String>()
        for (i in 0 until idsArray.length()) {
            ids.add(idsArray.getString(i))
        }

        try {
            val latch = CountDownLatch(1)
            var success = false
            var failureMessage = ""

            geofencingClient.removeGeofences(ids)
                .addOnSuccessListener {
                    success = true
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    failureMessage = e.message ?: "Unknown error"
                    latch.countDown()
                }

            latch.await(10, TimeUnit.SECONDS)

            if (success) {
                // Remove from in-memory tracking
                val removed = mutableListOf<String>()
                val notFound = mutableListOf<String>()
                for (id in ids) {
                    if (activeGeofences.remove(id) != null) {
                        removed.add(id)
                    } else {
                        notFound.add(id)
                    }
                }

                val json = JSONObject().apply {
                    put("success", true)
                    put("removed", JSONArray(removed))
                    if (notFound.isNotEmpty()) {
                        put("not_tracked", JSONArray(notFound))
                        put("message", "Geofences removed from system. Some IDs were not in tracking map.")
                    } else {
                        put("message", "Geofences removed successfully")
                    }
                }
                return jsonResponse(json)
            } else {
                return errorResponse("Failed to remove geofences: $failureMessage")
            }
        } catch (e: Exception) {
            return errorResponse("Failed to remove geofences: ${e.message}")
        }
    }

    private fun handleGeofenceList(): Response {
        try {
            val geofences = JSONArray()
            for ((_, info) in activeGeofences) {
                geofences.put(info)
            }

            val json = JSONObject().apply {
                put("count", activeGeofences.size)
                put("geofences", geofences)
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to list geofences: ${e.message}")
        }
    }

    // ============================================================
    // Download Manager endpoints
    // ============================================================

    private fun handleDownloadEnqueue(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val url = data.optString("url", "")
        if (url.isBlank()) return errorResponse("Missing required field: url", Response.Status.BAD_REQUEST)

        val title = data.optString("title", "Download")
        val description = data.optString("description", "")
        val destination = data.optString("destination", "")
        val wifiOnly = data.optBoolean("wifi_only", false)
        val visibleInDownloads = data.optBoolean("visible_in_downloads", true)
        val showNotification = data.optBoolean("show_notification", true)

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(title)
                setDescription(description)

                if (destination.isNotBlank()) {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        destination.removePrefix("Downloads/").removePrefix("downloads/")
                    )
                }

                if (wifiOnly) {
                    setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                } else {
                    setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                    )
                }

                if (showNotification) {
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                } else {
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                }

                setVisibleInDownloadsUi(visibleInDownloads)
            }

            val downloadId = downloadManager.enqueue(request)

            val json = JSONObject().apply {
                put("success", true)
                put("download_id", downloadId)
                put("message", "Download enqueued successfully")
            }
            return jsonResponse(json)
        } catch (e: IllegalArgumentException) {
            return errorResponse("Invalid download URL or parameters: ${e.message}", Response.Status.BAD_REQUEST)
        } catch (e: Exception) {
            return errorResponse("Failed to enqueue download: ${e.message}")
        }
    }

    private fun handleDownloadStatus(session: IHTTPSession): Response {
        val idStr = session.parms["id"]
            ?: return errorResponse("Missing required query parameter: id", Response.Status.BAD_REQUEST)
        val downloadId = idStr.toLongOrNull()
            ?: return errorResponse("Invalid download ID: must be a number", Response.Status.BAD_REQUEST)

        try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)

            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                return errorResponse("Download not found with ID: $downloadId", Response.Status.NOT_FOUND)
            }

            cursor.use { c ->
                val statusCode = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytesDownloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) ?: ""
                val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: ""
                val mediaType = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)) ?: ""
                val lastModified = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))

                val statusStr = when (statusCode) {
                    DownloadManager.STATUS_PENDING -> "pending"
                    DownloadManager.STATUS_RUNNING -> "running"
                    DownloadManager.STATUS_PAUSED -> "paused"
                    DownloadManager.STATUS_SUCCESSFUL -> "successful"
                    DownloadManager.STATUS_FAILED -> "failed"
                    else -> "unknown"
                }

                val reasonStr = when {
                    statusCode == DownloadManager.STATUS_PAUSED -> when (reason) {
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting_to_retry"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting_for_network"
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "queued_for_wifi"
                        DownloadManager.PAUSED_UNKNOWN -> "unknown"
                        else -> "paused_reason_$reason"
                    }
                    statusCode == DownloadManager.STATUS_FAILED -> when (reason) {
                        DownloadManager.ERROR_FILE_ERROR -> "file_error"
                        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "unhandled_http_code"
                        DownloadManager.ERROR_HTTP_DATA_ERROR -> "http_data_error"
                        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too_many_redirects"
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient_space"
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "device_not_found"
                        DownloadManager.ERROR_CANNOT_RESUME -> "cannot_resume"
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file_already_exists"
                        DownloadManager.ERROR_UNKNOWN -> "unknown"
                        else -> "error_$reason"
                    }
                    else -> null
                }

                val progress = if (totalBytes > 0) {
                    ((bytesDownloaded.toDouble() / totalBytes) * 100).toInt()
                } else {
                    -1
                }

                val json = JSONObject().apply {
                    put("download_id", downloadId)
                    put("status", statusStr)
                    put("bytes_downloaded", bytesDownloaded)
                    put("total_bytes", totalBytes)
                    put("progress_percent", progress)
                    put("title", title)
                    put("url", uri)
                    put("local_uri", localUri)
                    put("media_type", mediaType)
                    put("last_modified", lastModified)
                    if (reasonStr != null) {
                        put("reason", reasonStr)
                    }
                }
                return jsonResponse(json)
            }
        } catch (e: Exception) {
            return errorResponse("Failed to query download status: ${e.message}")
        }
    }

    // ==================== ACCESSIBILITY SERVICE HANDLERS ====================

    /**
     * POST /accessibility/click
     * Click at coordinates: {"x": 100, "y": 200}
     * Click by selector:    {"text": "OK"} or {"resource_id": "com.app:id/btn"}
     */
    private fun handleAccessibilityClick(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        // Coordinate-based click
        if (data.has("x") && data.has("y")) {
            val x = data.getDouble("x").toFloat()
            val y = data.getDouble("y").toFloat()

            val latch = CountDownLatch(1)
            var success = false

            service.clickAtCoordinates(x, y) { result ->
                success = result
                latch.countDown()
            }

            latch.await(5, TimeUnit.SECONDS)

            val json = JSONObject().apply {
                put("success", success)
                put("method", "coordinates")
                put("x", x)
                put("y", y)
            }
            return jsonResponse(json)
        }

        // Selector-based click
        val text = data.optString("text", null)
        val contentDescription = data.optString("content_description", null)
        val resourceId = data.optString("resource_id", null)
        val className = data.optString("class", null)

        if (text == null && contentDescription == null && resourceId == null && className == null) {
            return errorResponse("Provide x/y coordinates or at least one selector (text, content_description, resource_id, class)")
        }

        val success = service.clickBySelector(
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            className = className
        )

        val json = JSONObject().apply {
            put("success", success)
            put("method", "selector")
            if (text != null) put("text", text)
            if (contentDescription != null) put("content_description", contentDescription)
            if (resourceId != null) put("resource_id", resourceId)
            if (className != null) put("class", className)
        }
        return jsonResponse(json)
    }

    /**
     * POST /accessibility/gesture
     * Body: {"type": "swipe", "start_x": 100, "start_y": 500, "end_x": 100, "end_y": 200, "duration_ms": 300}
     * Also supports: {"type": "scroll", "direction": "up|down|left|right", "duration_ms": 300}
     * Also supports: {"type": "pinch", "center_x": 540, "center_y": 960, "zoom_in": true, "span": 200, "duration_ms": 400}
     */
    private fun handleAccessibilityGesture(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val type = data.optString("type", "swipe")

        val latch = CountDownLatch(1)
        var success = false

        when (type.lowercase()) {
            "swipe" -> {
                val startX = data.optDouble("start_x", -1.0).toFloat()
                val startY = data.optDouble("start_y", -1.0).toFloat()
                val endX = data.optDouble("end_x", -1.0).toFloat()
                val endY = data.optDouble("end_y", -1.0).toFloat()
                val durationMs = data.optLong("duration_ms", 300)

                if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
                    return errorResponse("Swipe requires start_x, start_y, end_x, end_y")
                }

                service.swipe(startX, startY, endX, endY, durationMs) { result ->
                    success = result
                    latch.countDown()
                }
            }
            "scroll" -> {
                val direction = data.optString("direction", "")
                val durationMs = data.optLong("duration_ms", 300)

                if (direction.isBlank()) {
                    return errorResponse("Scroll requires direction (up, down, left, right)")
                }

                service.scroll(direction, durationMs) { result ->
                    success = result
                    latch.countDown()
                }
            }
            "pinch" -> {
                val centerX = data.optDouble("center_x", -1.0).toFloat()
                val centerY = data.optDouble("center_y", -1.0).toFloat()
                val zoomIn = data.optBoolean("zoom_in", true)
                val span = data.optDouble("span", 200.0).toFloat()
                val durationMs = data.optLong("duration_ms", 400)

                if (centerX < 0 || centerY < 0) {
                    return errorResponse("Pinch requires center_x, center_y")
                }

                service.pinch(centerX, centerY, zoomIn, span, durationMs) { result ->
                    success = result
                    latch.countDown()
                }
            }
            else -> {
                return errorResponse("Unknown gesture type: $type. Supported: swipe, scroll, pinch")
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        val json = JSONObject().apply {
            put("success", success)
            put("gesture_type", type)
        }
        return jsonResponse(json)
    }

    /**
     * GET /accessibility/screen_text
     * Returns all visible text on screen as a JSON array of elements.
     */
    private fun handleAccessibilityScreenText(): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        return try {
            val elements = service.getScreenText()
            val json = JSONObject().apply {
                put("success", true)
                put("element_count", elements.length())
                put("elements", elements)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen text", e)
            errorResponse("Failed to get screen text: ${e.message}")
        }
    }

    /**
     * GET /accessibility/screenshot?quality=60&max_width=1080
     * Captures screenshot via AccessibilityService.takeScreenshot() (API 30+).
     * Returns base64-encoded JPEG image data.
     */
    private fun handleAccessibilityScreenshot(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return errorResponse("Screenshot capture requires API 30+", Response.Status.BAD_REQUEST)
        }

        val params = session.parms ?: emptyMap()
        val quality = params["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: 60
        val maxWidth = params["max_width"]?.toIntOrNull()?.coerceIn(100, 2000) ?: 1080

        return try {
            val base64 = service.captureScreenshot(quality, maxWidth)
            if (base64 != null) {
                val json = JSONObject().apply {
                    put("success", true)
                    put("format", "jpeg")
                    put("encoding", "base64")
                    put("quality", quality)
                    put("max_width", maxWidth)
                    put("data", base64)
                }
                jsonResponse(json)
            } else {
                errorResponse("Screenshot capture failed or timed out")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot", e)
            errorResponse("Screenshot capture error: ${e.message}")
        }
    }

    /**
     * POST /accessibility/global_action
     * Body: {"action": "home|back|recents|notifications|quick_settings|power_dialog|lock_screen|take_screenshot"}
     */
    private fun handleAccessibilityGlobalAction(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val action = data.optString("action", "")

        if (action.isBlank()) {
            return errorResponse("Missing 'action' field. Supported: home, back, recents, notifications, quick_settings, power_dialog, lock_screen, take_screenshot")
        }

        val success = service.performGlobalActionByName(action)

        val json = JSONObject().apply {
            put("success", success)
            put("action", action)
        }
        return jsonResponse(json)
    }

    /**
     * POST /accessibility/find_element
     * Body: {"text": "Submit", "class": "android.widget.Button", "resource_id": "...", "content_description": "...", "limit": 20}
     */
    private fun handleAccessibilityFindElement(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", null)
        val contentDescription = data.optString("content_description", null)
        val resourceId = data.optString("resource_id", null)
        val className = data.optString("class", null)
        val limit = data.optInt("limit", 20)

        if (text == null && contentDescription == null && resourceId == null && className == null) {
            return errorResponse("Provide at least one selector: text, content_description, resource_id, class")
        }

        return try {
            val elements = service.findElements(
                text = text,
                contentDescription = contentDescription,
                resourceId = resourceId,
                className = className,
                limit = limit
            )

            val json = JSONObject().apply {
                put("success", true)
                put("count", elements.length())
                put("elements", elements)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding elements", e)
            errorResponse("Failed to find elements: ${e.message}")
        }
    }

    /**
     * POST /accessibility/set_text
     * Body: {"text": "Hello world"} - sets text in currently focused editable field
     * Body: {"text": "Hello world", "resource_id": "com.app:id/input"} - sets text in specific field
     */
    private fun handleAccessibilitySetText(session: IHTTPSession): Response {
        val service = MobileKineticAccessibilityService.instance
            ?: return errorResponse("Accessibility service not running. Enable it in Settings > Accessibility > mK:a", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val value = data.optString("text", "")
        if (value.isEmpty() && !data.has("text")) {
            return errorResponse("Missing 'text' field")
        }

        // Optional selectors to target a specific editable field
        val selectorText = data.optString("selector_text", null)
        val contentDescription = data.optString("content_description", null)
        val resourceId = data.optString("resource_id", null)
        val className = data.optString("class", null)

        val success = service.setText(
            value = value,
            text = selectorText,
            contentDescription = contentDescription,
            resourceId = resourceId,
            className = className
        )

        val json = JSONObject().apply {
            put("success", success)
            put("text_set", value)
            if (!success) put("hint", "Ensure an editable field is focused or provide a valid selector")
        }
        return jsonResponse(json)
    }

    // ==================== NOTIFICATION LISTENER HANDLERS ====================

    /**
     * GET /notifications/active
     * Returns all currently posted notifications.
     */
    private fun handleNotificationsActive(): Response {
        val service = MobileKineticNotificationListener.instance
            ?: return errorResponse("Notification listener not running. Enable it in Settings > Apps & notifications > Special app access > Notification access", Response.Status.SERVICE_UNAVAILABLE)

        return try {
            val notifications = service.getActiveNotificationsJson()
            val filtered = runBlocking {
                withTimeoutOrNull(15_000L) {
                    privacyGate.filterNotifications(notifications)
                } ?: notifications  // Fall back to unfiltered if privacy gate times out
            }
            val json = JSONObject().apply {
                put("success", true)
                put("count", filtered.length())
                put("notifications", filtered)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active notifications", e)
            errorResponse("Failed to get notifications: ${e.message}")
        }
    }

    /**
     * POST /notifications/dismiss
     * Body: {"key": "notification_key"}
     */
    private fun handleNotificationsDismiss(session: IHTTPSession): Response {
        val service = MobileKineticNotificationListener.instance
            ?: return errorResponse("Notification listener not running. Enable it in Settings > Apps & notifications > Special app access > Notification access", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val key = data.optString("key", "")

        if (key.isBlank()) {
            return errorResponse("Missing 'key' field. Get notification keys from GET /notifications/active")
        }

        val success = service.dismissNotification(key)

        val json = JSONObject().apply {
            put("success", success)
            put("key", key)
            if (!success) put("hint", "Notification may not exist or may not be clearable")
        }
        return jsonResponse(json)
    }

    /**
     * POST /notifications/dismiss_all
     * Dismisses all clearable notifications.
     */
    private fun handleNotificationsDismissAll(): Response {
        val service = MobileKineticNotificationListener.instance
            ?: return errorResponse("Notification listener not running. Enable it in Settings > Apps & notifications > Special app access > Notification access", Response.Status.SERVICE_UNAVAILABLE)

        return try {
            val count = service.dismissAllNotifications()
            val json = JSONObject().apply {
                put("success", true)
                put("dismissed_count", count)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing all notifications", e)
            errorResponse("Failed to dismiss all notifications: ${e.message}")
        }
    }

    /**
     * POST /notifications/reply
     * Body: {"key": "notification_key", "reply": "message text", "action_index": 0}
     * action_index is optional - auto-detects first reply action if omitted.
     */
    private fun handleNotificationsReply(session: IHTTPSession): Response {
        val service = MobileKineticNotificationListener.instance
            ?: return errorResponse("Notification listener not running. Enable it in Settings > Apps & notifications > Special app access > Notification access", Response.Status.SERVICE_UNAVAILABLE)

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val key = data.optString("key", "")
        val reply = data.optString("reply", "")
        val actionIndex = if (data.has("action_index")) data.getInt("action_index") else null

        if (key.isBlank()) {
            return errorResponse("Missing 'key' field. Get notification keys from GET /notifications/active")
        }
        if (reply.isBlank()) {
            return errorResponse("Missing 'reply' field")
        }

        return try {
            val result = service.replyToNotification(key, reply, actionIndex)
            jsonResponse(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error replying to notification", e)
            errorResponse("Failed to reply to notification: ${e.message}")
        }
    }

    /**
     * GET /notifications/status
     * Returns listener connection status and permission state.
     */
    private fun handleNotificationsStatus(): Response {
        val isRunning = MobileKineticNotificationListener.isRunning()
        val isEnabled = MobileKineticNotificationListener.isEnabled(context)

        val json = JSONObject().apply {
            put("success", true)
            put("listener_connected", isRunning)
            put("permission_enabled", isEnabled)
            if (!isEnabled) {
                put("hint", "Enable notification access in Settings > Apps & notifications > Special app access > Notification access")
            } else if (!isRunning) {
                put("hint", "Permission is granted but listener is not connected. It may need a restart.")
            }
        }
        return jsonResponse(json)
    }

    // ==================== DEVICE ADMIN HANDLERS ====================

    /**
     * GET /admin/status
     * Returns whether device admin is enabled.
     */
    private fun handleAdminStatus(): Response {
        val isActive = MobileKineticDeviceAdmin.isAdminActive(context)

        val json = JSONObject().apply {
            put("success", true)
            put("admin_active", isActive)
            if (!isActive) {
                put("hint", "Enable device admin in Settings > Security > Device admin apps > mK:a")
            }
        }
        return jsonResponse(json)
    }

    /**
     * POST /admin/lock_screen
     * Locks the screen immediately. Requires device admin to be enabled.
     */
    private fun handleAdminLockScreen(): Response {
        if (!MobileKineticDeviceAdmin.isAdminActive(context)) {
            return errorResponse("Device admin not enabled. Enable it in Settings > Security > Device admin apps > mK:a", Response.Status.FORBIDDEN)
        }

        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.lockNow()

            val json = JSONObject().apply {
                put("success", true)
                put("message", "Screen locked")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error locking screen", e)
            errorResponse("Failed to lock screen: ${e.message}")
        }
    }

    // Input key event endpoint
    private fun handleInputKeyEvent(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val keycode = data.optInt("keycode", -1)
        if (keycode < 0) {
            return errorResponse("Missing or invalid required field: keycode (int >= 0)", Response.Status.BAD_REQUEST)
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycode.toString()))
            val exitCode = process.waitFor()

            jsonResponse(JSONObject().apply {
                put("success", exitCode == 0)
                put("keycode", keycode)
                put("executed", true)
                if (exitCode != 0) {
                    put("exit_code", exitCode)
                    val stderr = process.errorStream.bufferedReader().readText()
                    if (stderr.isNotBlank()) put("error", stderr.trim())
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error executing key event $keycode", e)
            errorResponse("Failed to execute key event: ${e.message}")
        }
    }

    /**
     * GET /accounts
     * Lists all accounts registered on the device (type + name only, no auth tokens).
     */
    private fun handleAccounts(): Response {
        return try {
            val am = AccountManager.get(context)
            val accounts = am.accounts
            val jsonArray = JSONArray()
            for (account in accounts) {
                val obj = JSONObject().apply {
                    put("type", account.type)
                    put("name", account.name)
                }
                jsonArray.put(obj)
            }
            val json = JSONObject().apply {
                put("accounts", jsonArray)
                put("count", accounts.size)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing accounts", e)
            errorResponse("Failed to list accounts: ${e.message}")
        }
    }

    /**
     * GET /logcat?lines=100&tag=MyTag&priority=E
     * Retrieves recent logcat output.
     * - lines: number of lines (default 100)
     * - tag: optional tag filter
     * - priority: V/D/I/W/E/F (optional)
     */
    private fun handleLogcat(session: IHTTPSession): Response {
        return try {
            val lines = session.parms["lines"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 100
            val tag = session.parms["tag"]
            val priority = session.parms["priority"]?.uppercase()

            val cmdParts = mutableListOf("logcat", "-d", "-t", lines.toString())

            // Build filter spec
            if (tag != null && priority != null) {
                cmdParts.add("${tag}:${priority}")
                cmdParts.add("*:S")
            } else if (tag != null) {
                cmdParts.add("${tag}:V")
                cmdParts.add("*:S")
            } else if (priority != null) {
                cmdParts.add("*:${priority}")
            }

            val proc = Runtime.getRuntime().exec(cmdParts.toTypedArray())
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, TimeUnit.SECONDS)

            val logLines = output.trim().split("\n").filter { it.isNotBlank() }
            val jsonArray = JSONArray()
            logLines.forEach { jsonArray.put(it) }

            val json = JSONObject().apply {
                put("lines", jsonArray)
                put("count", logLines.size)
                put("tag_filter", tag ?: JSONObject.NULL)
                put("priority_filter", priority ?: JSONObject.NULL)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logcat", e)
            errorResponse("Failed to read logcat: ${e.message}")
        }
    }

    // ── Chrome Custom Tabs ──────────────────────────────────────────

    private fun handleBrowserOpen(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val url = data.optString("url", "")
        if (url.isEmpty()) {
            return errorResponse("Missing required parameter: url", Response.Status.BAD_REQUEST)
        }

        // Validate URL scheme
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") {
            return errorResponse("Invalid URL scheme: must be http or https", Response.Status.BAD_REQUEST)
        }

        val toolbarColorHex = data.optString("toolbarColor", "#FCB45B")
        val showTitle = data.optBoolean("showTitle", true)
        val shareStateStr = data.optString("shareState", "on")

        val shareState = when (shareStateStr.lowercase()) {
            "on" -> CustomTabsIntent.SHARE_STATE_ON
            "off" -> CustomTabsIntent.SHARE_STATE_OFF
            "default" -> CustomTabsIntent.SHARE_STATE_DEFAULT
            else -> CustomTabsIntent.SHARE_STATE_ON
        }

        try {
            val toolbarColor = Color.parseColor(toolbarColorHex)

            val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .build()

            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorSchemeParams)
                .setShowTitle(showTitle)
                .setShareState(shareState)
                .build()

            val browser = data.optString("browser", "brave")
            // Build a preferred-package list based on the requested browser, then discover
            // with ignoreDefault=true so Brave is found even when it's not the system default.
            val preferredPackages = when (browser) {
                "brave" -> listOf("com.brave.browser", "com.android.chrome")
                "chrome" -> listOf("com.android.chrome", "com.brave.browser")
                else -> null
            }
            val browserPackage: String? = if (preferredPackages != null) {
                // Try with default browser first, then ignoring default
                var pkg = CustomTabsClient.getPackageName(context, preferredPackages)
                    ?: CustomTabsClient.getPackageName(context, preferredPackages, true)
                // Direct service-binding fallback: resolve CustomTabsService for known browsers
                if (pkg == null) {
                    val serviceIntent = Intent("android.support.customtabs.action.CustomTabsService")
                    for (candidate in preferredPackages) {
                        serviceIntent.setPackage(candidate)
                        val resolveInfo = context.packageManager.resolveService(serviceIntent, 0)
                        if (resolveInfo != null) {
                            pkg = candidate
                            break
                        }
                    }
                }
                pkg
            } else {
                CustomTabsClient.getPackageName(context, null)
            }
            browserPackage?.let { customTabsIntent.intent.setPackage(it) }

            // FLAG_ACTIVITY_NEW_TASK required when launching from non-Activity context
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)

            val json = JSONObject().apply {
                put("success", true)
                put("url", url)
                put("toolbarColor", toolbarColorHex)
                put("showTitle", showTitle)
                put("shareState", shareStateStr)
            }
            return jsonResponse(json)
        } catch (e: IllegalArgumentException) {
            return errorResponse("Invalid color format: $toolbarColorHex", Response.Status.BAD_REQUEST)
        } catch (e: Exception) {
            return errorResponse("Failed to open browser: ${e.message}")
        }
    }

    private fun handleBrowserPrefetch(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val url = data.optString("url", "")
        if (url.isEmpty()) {
            return errorResponse("Missing required parameter: url", Response.Status.BAD_REQUEST)
        }

        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") {
            return errorResponse("Invalid URL scheme: must be http or https", Response.Status.BAD_REQUEST)
        }

        val usePrefetchApi = data.optBoolean("use_prefetch_api", true)
        val anonymizeIp = data.optBoolean("anonymize_ip", false)
        val sourceOriginStr = data.optString("source_origin", "")

        try {
            // Bind to Custom Tabs service if not already bound
            if (!customTabsBound) {
                val preferredPackages = listOf("com.brave.browser", "com.android.chrome")
                // Try with default browser first, then ignoring default
                var packageName = CustomTabsClient.getPackageName(context, preferredPackages)
                    ?: CustomTabsClient.getPackageName(context, preferredPackages, true)

                // Direct service-binding fallback: resolve CustomTabsService for known browsers
                if (packageName == null) {
                    val serviceIntent = Intent("android.support.customtabs.action.CustomTabsService")
                    for (candidate in preferredPackages) {
                        serviceIntent.setPackage(candidate)
                        val resolveInfo = context.packageManager.resolveService(serviceIntent, 0)
                        if (resolveInfo != null) {
                            packageName = candidate
                            break
                        }
                    }
                }

                if (packageName != null) {
                    // bindCustomTabsServicePreservePriority is preferred when browser is about to launch
                    customTabsBound = CustomTabsClient.bindCustomTabsServicePreservePriority(
                        context, packageName, customTabsConnection
                    )
                } else {
                    return jsonResponse(JSONObject().apply {
                        put("success", false)
                        put("url", url)
                        put("warmed_up", false)
                        put("prefetch_requested", false)
                        put("prefetch_method", if (usePrefetchApi) "prefetch_api_1.9" else "legacy_mayLaunchUrl")
                        put("anonymize_ip", anonymizeIp)
                        put("message", "No Custom Tabs compatible browser found on device")
                    })
                }
            }

            // Request speculative loading if session is available
            val prefetchRequested: Boolean
            if (usePrefetchApi && customTabsSession != null) {
                @OptIn(ExperimentalPrefetch::class)
                val prefetchOptions = PrefetchOptions.Builder()
                    .setRequiresAnonymousIpWhenCrossOrigin(anonymizeIp)
                    .apply {
                        if (sourceOriginStr.isNotEmpty()) {
                            setSourceOrigin(Uri.parse(sourceOriginStr))
                        }
                    }
                    .build()
                customTabsSession!!.prefetch(uri, prefetchOptions)
                prefetchRequested = true
            } else {
                // Fallback to legacy mayLaunchUrl
                prefetchRequested = customTabsSession?.mayLaunchUrl(uri, null, null) ?: false
            }

            val json = JSONObject().apply {
                put("success", true)
                put("url", url)
                put("warmed_up", customTabsClient != null)
                put("prefetch_requested", prefetchRequested)
                put("prefetch_method", if (usePrefetchApi && customTabsSession != null) "prefetch_api_1.9" else "legacy_mayLaunchUrl")
                put("anonymize_ip", anonymizeIp)
                put("message", when {
                    prefetchRequested && usePrefetchApi && customTabsSession != null -> "URL queued via PrefetchOptions API (1.9)"
                    prefetchRequested -> "URL queued for speculative loading"
                    customTabsBound && customTabsClient == null -> "Browser warming up, prefetch will be available shortly. Retry in ~500ms."
                    else -> "Service bound, awaiting connection callback"
                })
            }
            return jsonResponse(json)
        } catch (e: Exception) {
            return errorResponse("Failed to prefetch: ${e.message}")
        }
    }

    // ==================== Notification Channel Endpoints ====================

    private fun handleNotificationChannelsList(): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channels require API 26+")
            }
            val channels = notificationManager.notificationChannels
            val result = JSONArray()
            for (channel in channels) {
                result.put(JSONObject().apply {
                    put("id", channel.id)
                    put("name", channel.name?.toString())
                    put("importance", channel.importance)
                    put("description", channel.description)
                })
            }
            jsonResponse(JSONObject().apply { put("channels", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelsList error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelGet(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channels require API 26+")
            }
            val channelId = session.parms["channelId"]
                ?: return errorResponse("channelId required", Response.Status.BAD_REQUEST)
            val channel = notificationManager.getNotificationChannel(channelId)
                ?: return errorResponse("Channel not found: $channelId", Response.Status.NOT_FOUND)
            val json = JSONObject().apply {
                put("id", channel.id)
                put("name", channel.name?.toString())
                put("description", channel.description)
                put("importance", channel.importance)
                put("sound", channel.sound?.toString())
                put("vibrationPattern", channel.vibrationPattern?.let { arr ->
                    JSONArray().apply { arr.forEach { put(it) } }
                })
                put("lightColor", channel.lightColor)
                put("lockscreenVisibility", channel.lockscreenVisibility)
                put("canBypassDnd", channel.canBypassDnd())
                put("canBubble", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) channel.canBubble() else false)
                put("showBadge", channel.canShowBadge())
                put("group", channel.group)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelCreate(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channels require API 26+")
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val channelId = json.getString("channelId")
            val name = json.getString("name")
            val importance = json.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT)
            val channel = NotificationChannel(channelId, name, importance).apply {
                json.optString("description", "").takeIf { it.isNotEmpty() }?.let { description = it }
                json.optString("groupId", "").takeIf { it.isNotEmpty() }?.let { group = it }
                if (json.has("vibration")) enableVibration(json.getBoolean("vibration"))
                if (json.has("lights")) enableLights(json.getBoolean("lights"))
                if (json.has("soundUri")) {
                    val soundUri = Uri.parse(json.getString("soundUri"))
                    setSound(soundUri, android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build())
                }
                if (json.has("lightColor")) lightColor = json.getInt("lightColor")
                if (json.has("lockscreenVisibility")) lockscreenVisibility = json.getInt("lockscreenVisibility")
                if (json.has("bypassDnd")) setBypassDnd(json.getBoolean("bypassDnd"))
                if (json.has("showBadge")) setShowBadge(json.getBoolean("showBadge"))
                if (json.has("allowBubbles") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(json.getBoolean("allowBubbles"))
                }
            }
            notificationManager.createNotificationChannel(channel)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("channelId", channelId)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelCreate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelDelete(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channels require API 26+")
            }
            val channelId = session.parms["channelId"]
                ?: run {
                    val body = parseBody(session)
                    body?.let { JSONObject(it).optString("channelId", "") }?.takeIf { it.isNotEmpty() }
                }
                ?: return errorResponse("channelId required", Response.Status.BAD_REQUEST)
            notificationManager.deleteNotificationChannel(channelId)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelDelete error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelGroupsList(): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channel groups require API 26+")
            }
            val groups = notificationManager.notificationChannelGroups
            val result = JSONArray()
            for (group in groups) {
                result.put(JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name?.toString())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        put("description", group.description)
                    }
                    put("channelCount", group.channels.size)
                    val channelIds = JSONArray()
                    group.channels.forEach { channelIds.put(it.id) }
                    put("channelIds", channelIds)
                })
            }
            jsonResponse(JSONObject().apply { put("groups", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelGroupsList error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelGroupCreate(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channel groups require API 26+")
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val groupId = json.getString("groupId")
            val name = json.getString("name")
            val group = NotificationChannelGroup(groupId, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                json.optString("description", "").takeIf { it.isNotEmpty() }?.let { group.description = it }
            }
            notificationManager.createNotificationChannelGroup(group)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("groupId", groupId)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelGroupCreate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationChannelGroupDelete(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return errorResponse("Notification channel groups require API 26+")
            }
            val groupId = session.parms["groupId"]
                ?: run {
                    val body = parseBody(session)
                    body?.let { JSONObject(it).optString("groupId", "") }?.takeIf { it.isNotEmpty() }
                }
                ?: return errorResponse("groupId required", Response.Status.BAD_REQUEST)
            notificationManager.deleteNotificationChannelGroup(groupId)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationChannelGroupDelete error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationCancel(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val id = json.getInt("id")
            val tag = json.optString("tag", "").takeIf { it.isNotEmpty() }
            if (tag != null) {
                notificationManager.cancel(tag, id)
            } else {
                notificationManager.cancel(id)
            }
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationCancel error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationCancelAll(): Response {
        return try {
            notificationManager.cancelAll()
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationCancelAll error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationActiveList(): Response {
        return try {
            val notifications = notificationManager.activeNotifications
            val result = JSONArray()
            for (sbn in notifications) {
                result.put(JSONObject().apply {
                    put("id", sbn.id)
                    put("key", sbn.key)
                    put("tag", sbn.tag)
                    put("packageName", sbn.packageName)
                    put("postTime", sbn.postTime)
                    put("isOngoing", sbn.isOngoing)
                    put("isClearable", sbn.isClearable)
                    val extras = sbn.notification.extras
                    put("title", extras?.getString("android.title"))
                    put("text", extras?.getCharSequence("android.text")?.toString())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        put("channelId", sbn.notification.channelId)
                    }
                })
            }
            jsonResponse(JSONObject().apply { put("notifications", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationActiveList error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationPolicyGet(): Response {
        return try {
            val policy = notificationManager.notificationPolicy
            val json = JSONObject().apply {
                // Priority categories are bitmask flags
                put("allowAlarms", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0)
                put("allowMedia", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) != 0)
                put("allowSystem", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) != 0)
                put("allowCalls", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0)
                put("allowMessages", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES) != 0)
                put("allowRepeatCallers", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS) != 0)
                put("allowEvents", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS) != 0)
                put("allowReminders", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS) != 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    put("allowConversations", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS) != 0)
                }
                put("allowCallsFrom", policy.priorityCallSenders)
                put("allowMessagesFrom", policy.priorityMessageSenders)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put("allowConversationsFrom", policy.priorityConversationSenders)
                }
                put("suppressedVisualEffects", policy.suppressedVisualEffects)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationPolicyGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationPolicySet(session: IHTTPSession): Response {
        return try {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted. Enable in Settings > Apps > Special Access > Do Not Disturb", Response.Status.FORBIDDEN)
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)

            // Build priority categories bitmask
            var categories = 0
            if (json.optBoolean("allowAlarms", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
            if (json.optBoolean("allowMedia", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
            if (json.optBoolean("allowSystem", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM
            if (json.optBoolean("allowCalls", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
            if (json.optBoolean("allowMessages", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES
            if (json.optBoolean("allowRepeatCallers", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
            if (json.optBoolean("allowEvents", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS
            if (json.optBoolean("allowReminders", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (json.optBoolean("allowConversations", false)) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS
            }

            val callSenders = json.optInt("allowCallsFrom", NotificationManager.Policy.PRIORITY_SENDERS_ANY)
            val messageSenders = json.optInt("allowMessagesFrom", NotificationManager.Policy.PRIORITY_SENDERS_ANY)
            val suppressedEffects = json.optInt("suppressedVisualEffects", 0)

            val policy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val convSenders = json.optInt("allowConversationsFrom", NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE)
                NotificationManager.Policy(categories, callSenders, messageSenders, suppressedEffects, convSenders)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                NotificationManager.Policy(categories, callSenders, messageSenders, suppressedEffects)
            } else {
                NotificationManager.Policy(categories, callSenders, messageSenders)
            }
            notificationManager.setNotificationPolicy(policy)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationPolicySet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationInterruptionFilterGet(): Response {
        return try {
            val filter = notificationManager.currentInterruptionFilter
            val filterName = when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                else -> "unknown"
            }
            jsonResponse(JSONObject().apply {
                put("filter", filter)
                put("filterName", filterName)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationInterruptionFilterGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationInterruptionFilterSet(session: IHTTPSession): Response {
        return try {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted. Enable in Settings > Apps > Special Access > Do Not Disturb", Response.Status.FORBIDDEN)
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val filter = json.getInt("filter")
            notificationManager.setInterruptionFilter(filter)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationInterruptionFilterSet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationSnooze(session: IHTTPSession): Response {
        return try {
            // Note: snoozeNotification requires NotificationListenerService access
            // We use reflection or status bar notification service if available
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val key = json.getString("key")
            val durationMs = json.getLong("durationMs")

            // Snooze requires NotificationListenerService - check if our listener is active
            val listenerComponent = ComponentName(context, "com.mobilekinetic.agent.service.NotificationListenerServiceImpl")
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            if (!enabledListeners.contains(listenerComponent.flattenToString())) {
                return errorResponse("NotificationListenerService not enabled. Enable in Settings > Apps > Special Access > Notification Access", Response.Status.FORBIDDEN)
            }

            // Send broadcast to our NotificationListenerService to perform the snooze
            val intent = Intent("com.mobilekinetic.agent.SNOOZE_NOTIFICATION").apply {
                putExtra("key", key)
                putExtra("durationMs", durationMs)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("note", "Snooze request sent to NotificationListenerService")
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationSnooze error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationUnsnooze(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val key = json.getString("key")

            val listenerComponent = ComponentName(context, "com.mobilekinetic.agent.service.NotificationListenerServiceImpl")
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            if (!enabledListeners.contains(listenerComponent.flattenToString())) {
                return errorResponse("NotificationListenerService not enabled. Enable in Settings > Apps > Special Access > Notification Access", Response.Status.FORBIDDEN)
            }

            val intent = Intent("com.mobilekinetic.agent.UNSNOOZE_NOTIFICATION").apply {
                putExtra("key", key)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("note", "Unsnooze request sent to NotificationListenerService")
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationUnsnooze error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleNotificationSnoozedList(): Response {
        return try {
            val listenerComponent = ComponentName(context, "com.mobilekinetic.agent.service.NotificationListenerServiceImpl")
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            if (!enabledListeners.contains(listenerComponent.flattenToString())) {
                return errorResponse("NotificationListenerService not enabled. Enable in Settings > Apps > Special Access > Notification Access", Response.Status.FORBIDDEN)
            }

            // Request snoozed notifications from our listener service via broadcast
            val intent = Intent("com.mobilekinetic.agent.GET_SNOOZED_NOTIFICATIONS").apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("note", "Request sent to NotificationListenerService. Snoozed notifications will be logged.")
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationSnoozedList error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Zen / DND Rule Endpoints ====================

    private fun handleZenRuleList(): Response {
        return try {
            val rules = notificationManager.automaticZenRules
            val result = JSONArray()
            for ((id, rule) in rules) {
                result.put(JSONObject().apply {
                    put("id", id)
                    put("name", rule.name)
                    put("enabled", rule.isEnabled)
                    put("interruptionFilter", rule.interruptionFilter)
                    put("conditionId", rule.conditionId?.toString())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put("creationTime", rule.creationTime)
                    }
                })
            }
            jsonResponse(JSONObject().apply { put("rules", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleList error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenRuleGet(session: IHTTPSession): Response {
        return try {
            val ruleId = session.parms["ruleId"]
                ?: return errorResponse("ruleId required", Response.Status.BAD_REQUEST)
            val rule = notificationManager.getAutomaticZenRule(ruleId)
                ?: return errorResponse("Zen rule not found: $ruleId", Response.Status.NOT_FOUND)
            val json = JSONObject().apply {
                put("id", ruleId)
                put("name", rule.name)
                put("enabled", rule.isEnabled)
                put("interruptionFilter", rule.interruptionFilter)
                put("conditionId", rule.conditionId?.toString())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put("creationTime", rule.creationTime)
                }
                // ZenPolicy is API 29+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rule.zenPolicy?.let { zp ->
                        put("zenPolicy", JSONObject().apply {
                            put("allowAlarms", zp.getPriorityCategoryAlarms())
                            put("allowMedia", zp.getPriorityCategoryMedia())
                            put("allowSystem", zp.getPriorityCategorySystem())
                            put("allowCalls", zp.getPriorityCategoryCalls())
                            put("allowMessages", zp.getPriorityCategoryMessages())
                            put("allowRepeatCallers", zp.getPriorityCategoryRepeatCallers())
                            put("allowEvents", zp.getPriorityCategoryEvents())
                            put("allowReminders", zp.getPriorityCategoryReminders())
                            put("allowConversations", zp.getPriorityCategoryConversations())
                            put("showBadges", zp.getVisualEffectBadge())
                            put("showFullScreenIntents", zp.getVisualEffectFullScreenIntent())
                            put("showLights", zp.getVisualEffectLights())
                            put("showPeeking", zp.getVisualEffectPeek())
                            put("showStatusBarIcons", zp.getVisualEffectStatusBar())
                            put("showInAmbientDisplay", zp.getVisualEffectAmbient())
                            put("showInNotificationList", zp.getVisualEffectNotificationList())
                        })
                    }
                }
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenRuleCreate(session: IHTTPSession): Response {
        return try {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted", Response.Status.FORBIDDEN)
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val name = json.getString("name")
            val interruptionFilter = json.getInt("interruptionFilter")
            val conditionIdStr = json.optString("conditionId", "")
            val conditionId = if (conditionIdStr.isNotEmpty()) Uri.parse(conditionIdStr) else
                Uri.parse("condition://${context.packageName}/zen_rule_${System.currentTimeMillis()}")

            val ownerComponent = ComponentName(context, context.javaClass)

            val rule = AutomaticZenRule(
                name,
                ownerComponent,
                conditionId,
                interruptionFilter,
                json.optBoolean("enabled", true)
            )

            val ruleId = notificationManager.addAutomaticZenRule(rule)
            jsonResponse(JSONObject().apply { put("ruleId", ruleId) })
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleCreate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenRuleUpdate(session: IHTTPSession): Response {
        return try {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted", Response.Status.FORBIDDEN)
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val ruleId = json.getString("ruleId")
            val existing = notificationManager.getAutomaticZenRule(ruleId)
                ?: return errorResponse("Zen rule not found: $ruleId", Response.Status.NOT_FOUND)

            // Update fields that were provided
            val updatedName = json.optString("name", "").takeIf { it.isNotEmpty() } ?: existing.name
            val updatedFilter = if (json.has("interruptionFilter")) json.getInt("interruptionFilter") else existing.interruptionFilter
            val updatedEnabled = if (json.has("enabled")) json.getBoolean("enabled") else existing.isEnabled

            val updated = AutomaticZenRule(
                updatedName,
                existing.owner,
                existing.conditionId,
                updatedFilter,
                updatedEnabled
            )

            val success = notificationManager.updateAutomaticZenRule(ruleId, updated)
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleUpdate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenRuleDelete(session: IHTTPSession): Response {
        return try {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted", Response.Status.FORBIDDEN)
            }
            val ruleId = session.parms["ruleId"]
                ?: run {
                    val body = parseBody(session)
                    body?.let { JSONObject(it).optString("ruleId", "") }?.takeIf { it.isNotEmpty() }
                }
                ?: return errorResponse("ruleId required", Response.Status.BAD_REQUEST)
            val success = notificationManager.removeAutomaticZenRule(ruleId)
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleDelete error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenRuleSetState(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return errorResponse("Zen rule state requires API 29+")
            }
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return errorResponse("DND policy access not granted", Response.Status.FORBIDDEN)
            }
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val ruleId = json.getString("ruleId")
            val state = json.getInt("state")
            val summary = json.optString("summary", "").takeIf { it.isNotEmpty() }

            // setAutomaticZenRuleState requires API 30 (Condition)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rule = notificationManager.getAutomaticZenRule(ruleId)
                    ?: return errorResponse("Zen rule not found: $ruleId", Response.Status.NOT_FOUND)
                val conditionId = rule.conditionId
                val condition = android.service.notification.Condition(
                    conditionId,
                    summary ?: "",
                    state
                )
                notificationManager.setAutomaticZenRuleState(ruleId, condition)
                jsonResponse(JSONObject().apply { put("success", true) })
            } else {
                errorResponse("setAutomaticZenRuleState requires API 30+")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleZenRuleSetState error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenPolicyGet(): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return errorResponse("ZenPolicy requires API 29+")
            }
            // Get the consolidated zen policy from the current interruption filter
            // We read from all automatic zen rules and combine
            val rules = notificationManager.automaticZenRules
            val enabledRules = rules.filter { it.value.isEnabled }

            // Return the policy from the first enabled rule that has one, or the notification policy
            val policy = notificationManager.notificationPolicy
            val json = JSONObject().apply {
                put("allowAlarms", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0)
                put("allowCalls", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0)
                put("allowCallsFrom", policy.priorityCallSenders)
                put("allowMessages", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES) != 0)
                put("allowMessagesFrom", policy.priorityMessageSenders)
                put("allowEvents", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS) != 0)
                put("allowMedia", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) != 0)
                put("allowReminders", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS) != 0)
                put("allowRepeatCallers", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS) != 0)
                put("allowSystem", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) != 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    put("allowConversations", (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS) != 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put("allowConversationsFrom", policy.priorityConversationSenders)
                }
                // Suppressed visual effects
                val sve = policy.suppressedVisualEffects
                put("showBadges", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE) == 0)
                put("showFullScreenIntents", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) == 0)
                put("showLights", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS) == 0)
                put("showPeeking", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) == 0)
                put("showStatusBarIcons", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR) == 0)
                put("showInAmbientDisplay", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT) == 0)
                put("showInNotificationList", (sve and NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) == 0)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleZenPolicyGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleZenDeviceEffectsGet(session: IHTTPSession): Response {
        return try {
            if (Build.VERSION.SDK_INT < 35) {
                return errorResponse("ZenDeviceEffects requires API 35+")
            }
            val ruleId = session.parms["ruleId"]
                ?: return errorResponse("ruleId required", Response.Status.BAD_REQUEST)
            val rule = notificationManager.getAutomaticZenRule(ruleId)
                ?: return errorResponse("Zen rule not found: $ruleId", Response.Status.NOT_FOUND)

            // ZenDeviceEffects is available from API 35 - use reflection to be safe
            val json = JSONObject().apply {
                try {
                    val getDeviceEffects = rule.javaClass.getMethod("getDeviceEffects")
                    val effects = getDeviceEffects.invoke(rule)
                    if (effects != null) {
                        val effectsClass = effects.javaClass
                        put("dimWallpaper", effectsClass.getMethod("shouldDimWallpaper").invoke(effects) as Boolean)
                        put("displayGrayscale", effectsClass.getMethod("shouldDisplayGrayscale").invoke(effects) as Boolean)
                        put("useNightMode", effectsClass.getMethod("shouldUseNightMode").invoke(effects) as Boolean)
                        put("disableAOD", effectsClass.getMethod("shouldDisableAlwaysOnDisplay").invoke(effects) as Boolean)
                        put("disableAutoBrightness", effectsClass.getMethod("shouldDisableAutoBrightness").invoke(effects) as Boolean)
                        put("disableTapToWake", effectsClass.getMethod("shouldDisableTapToWake").invoke(effects) as Boolean)
                        put("disableTiltToWake", effectsClass.getMethod("shouldDisableTiltToWake").invoke(effects) as Boolean)
                        put("disableTouch", effectsClass.getMethod("shouldDisableTouch").invoke(effects) as Boolean)
                        put("maximizeDoze", effectsClass.getMethod("shouldMaximizeDoze").invoke(effects) as Boolean)
                        put("suppressAmbientDisplay", effectsClass.getMethod("shouldSuppressAmbientDisplay").invoke(effects) as Boolean)
                    } else {
                        put("note", "No device effects configured for this rule")
                    }
                } catch (e: NoSuchMethodException) {
                    put("error", "ZenDeviceEffects API not available on this device")
                }
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleZenDeviceEffectsGet error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Clipboard Extended Endpoints ====================

    private fun handleClipboardClear(): Response {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return errorResponse("clearPrimaryClip requires API 28+")
            }
            val latch = CountDownLatch(1)
            var success = false
            Handler(Looper.getMainLooper()).post {
                try {
                    clipboardManager.clearPrimaryClip()
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear clipboard", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardClear error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleClipboardHasClip(): Response {
        return try {
            val latch = CountDownLatch(1)
            var hasClip = false
            Handler(Looper.getMainLooper()).post {
                try {
                    hasClip = clipboardManager.hasPrimaryClip()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check clipboard", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            jsonResponse(JSONObject().apply { put("hasClip", hasClip) })
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardHasClip error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleClipboardGetRich(): Response {
        return try {
            val latch = CountDownLatch(1)
            val json = JSONObject()
            Handler(Looper.getMainLooper()).post {
                try {
                    val hasClip = clipboardManager.hasPrimaryClip()
                    json.put("hasClip", hasClip)
                    if (hasClip) {
                        val clip = clipboardManager.primaryClip
                        if (clip != null) {
                            json.put("itemCount", clip.itemCount)
                            val description = clip.description
                            val mimeTypes = JSONArray()
                            for (i in 0 until (description?.mimeTypeCount ?: 0)) {
                                mimeTypes.put(description?.getMimeType(i))
                            }
                            json.put("mimeTypes", mimeTypes)
                            val items = JSONArray()
                            for (i in 0 until clip.itemCount) {
                                val item = clip.getItemAt(i)
                                items.put(JSONObject().apply {
                                    put("text", item.text?.toString())
                                    put("htmlText", item.htmlText)
                                    put("uri", item.uri?.toString())
                                    put("intent", item.intent?.toUri(Intent.URI_INTENT_SCHEME))
                                })
                            }
                            json.put("items", items)
                        }
                    } else {
                        json.put("itemCount", 0)
                        json.put("mimeTypes", JSONArray())
                        json.put("items", JSONArray())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get rich clipboard", e)
                    json.put("error", e.message)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardGetRich error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleClipboardSetHtml(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val data = JSONObject(body)
            val label = data.getString("label")
            val text = data.getString("text")
            val htmlText = data.getString("htmlText")

            val latch = CountDownLatch(1)
            var success = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val clip = ClipData.newHtmlText(label, text, htmlText)
                    clipboardManager.setPrimaryClip(clip)
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set HTML clipboard", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardSetHtml error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleClipboardSetUri(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val data = JSONObject(body)
            val label = data.getString("label")
            val uriStr = data.getString("uri")

            val latch = CountDownLatch(1)
            var success = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val clip = ClipData.newRawUri(label, Uri.parse(uriStr))
                    clipboardManager.setPrimaryClip(clip)
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set URI clipboard", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handleClipboardSetUri error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== SharedPreferences Endpoints ====================

    private fun handlePreferencesGetAll(session: IHTTPSession): Response {
        return try {
            val name = session.parms["name"]
                ?: return errorResponse("name required", Response.Status.BAD_REQUEST)
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val allPrefs = prefs.all
            val result = JSONObject()
            for ((key, value) in allPrefs) {
                when (value) {
                    is String -> result.put(key, value)
                    is Int -> result.put(key, value)
                    is Long -> result.put(key, value)
                    is Float -> result.put(key, value.toDouble())
                    is Boolean -> result.put(key, value)
                    is Set<*> -> result.put(key, JSONArray(value))
                    else -> result.put(key, value.toString())
                }
            }
            jsonResponse(JSONObject().apply { put("preferences", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesGetAll error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesGet(session: IHTTPSession): Response {
        return try {
            val name = session.parms["name"]
                ?: return errorResponse("name required", Response.Status.BAD_REQUEST)
            val key = session.parms["key"]
                ?: return errorResponse("key required", Response.Status.BAD_REQUEST)
            val type = session.parms["type"] ?: "string"
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val exists = prefs.contains(key)

            val json = JSONObject().apply {
                put("key", key)
                put("type", type)
                put("exists", exists)
                if (exists) {
                    when (type) {
                        "int" -> put("value", prefs.getInt(key, 0))
                        "long" -> put("value", prefs.getLong(key, 0L))
                        "float" -> put("value", prefs.getFloat(key, 0f).toDouble())
                        "boolean" -> put("value", prefs.getBoolean(key, false))
                        "string_set" -> put("value", JSONArray(prefs.getStringSet(key, emptySet())))
                        else -> put("value", prefs.getString(key, ""))
                    }
                }
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesSet(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val name = json.getString("name")
            val key = json.getString("key")
            val value = json.getString("value")
            val type = json.optString("type", "string")

            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            when (type) {
                "int" -> editor.putInt(key, value.toInt())
                "long" -> editor.putLong(key, value.toLong())
                "float" -> editor.putFloat(key, value.toFloat())
                "boolean" -> editor.putBoolean(key, value.toBoolean())
                "string_set" -> {
                    val arr = JSONArray(value)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    editor.putStringSet(key, set)
                }
                else -> editor.putString(key, value)
            }
            editor.apply()
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesSet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesRemove(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val name = json.getString("name")
            val key = json.getString("key")
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.edit().remove(key).apply()
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesRemove error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesClear(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val name = json.getString("name")
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesClear error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesContains(session: IHTTPSession): Response {
        return try {
            val name = session.parms["name"]
                ?: return errorResponse("name required", Response.Status.BAD_REQUEST)
            val key = session.parms["key"]
                ?: return errorResponse("key required", Response.Status.BAD_REQUEST)
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            jsonResponse(JSONObject().apply { put("exists", prefs.contains(key)) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesContains error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePreferencesDeleteFile(session: IHTTPSession): Response {
        return try {
            val name = session.parms["name"]
                ?: run {
                    val body = parseBody(session)
                    body?.let { JSONObject(it).optString("name", "") }?.takeIf { it.isNotEmpty() }
                }
                ?: return errorResponse("name required", Response.Status.BAD_REQUEST)

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(name)
            } else {
                // Pre-API 24 fallback: clear and delete file manually
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                val prefsFile = File(prefsDir, "$name.xml")
                prefsFile.delete()
            }
            jsonResponse(JSONObject().apply { put("success", success) })
        } catch (e: Exception) {
            Log.e(TAG, "handlePreferencesDeleteFile error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Package Info Endpoints ====================

    private fun handlePackageInfoGet(session: IHTTPSession): Response {
        return try {
            val packageName = session.parms["packageName"]
                ?: return errorResponse("packageName required", Response.Status.BAD_REQUEST)
            val includePermissions = session.parms["includePermissions"]?.toBoolean() ?: false

            val flags = if (includePermissions) PackageManager.GET_PERMISSIONS else 0
            val pkgInfo = context.packageManager.getPackageInfo(packageName, flags)

            val json = JSONObject().apply {
                put("packageName", pkgInfo.packageName)
                put("versionName", pkgInfo.versionName)
                put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong())
                put("firstInstallTime", pkgInfo.firstInstallTime)
                put("lastUpdateTime", pkgInfo.lastUpdateTime)
                val appInfo = pkgInfo.applicationInfo
                if (appInfo != null) {
                    put("targetSdkVersion", appInfo.targetSdkVersion)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        put("minSdkVersion", appInfo.minSdkVersion)
                    }
                }
                val reqPerms = pkgInfo.requestedPermissions
                val reqFlags = pkgInfo.requestedPermissionsFlags
                if (includePermissions && reqPerms != null) {
                    val perms = JSONArray()
                    for (i in reqPerms.indices) {
                        perms.put(JSONObject().apply {
                            put("name", reqPerms[i])
                            val granted = if (reqFlags != null) {
                                (reqFlags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                            } else false
                            put("granted", granted)
                        })
                    }
                    put("permissions", perms)
                }
                // Signing info
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signingInfo = pkgInfo.signingInfo
                    if (signingInfo != null) {
                        put("signingInfo", JSONObject().apply {
                            put("hasMultipleSigners", signingInfo.hasMultipleSigners())
                            put("hasPastSigningCertificates", signingInfo.hasPastSigningCertificates())
                        })
                    }
                }
            }
            jsonResponse(json)
        } catch (e: PackageManager.NameNotFoundException) {
            errorResponse("Package not found: ${session.parms["packageName"]}", Response.Status.NOT_FOUND)
        } catch (e: Exception) {
            Log.e(TAG, "handlePackageInfoGet error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePackagePermissionCheck(session: IHTTPSession): Response {
        return try {
            val permission = session.parms["permission"]
                ?: return errorResponse("permission required", Response.Status.BAD_REQUEST)
            val packageName = session.parms["packageName"] ?: context.packageName
            val granted = context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
            jsonResponse(JSONObject().apply {
                put("permission", permission)
                put("packageName", packageName)
                put("granted", granted)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handlePackagePermissionCheck error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePackageSystemFeature(session: IHTTPSession): Response {
        return try {
            val featureName = session.parms["featureName"]
                ?: return errorResponse("featureName required", Response.Status.BAD_REQUEST)
            val hasFeature = context.packageManager.hasSystemFeature(featureName)
            jsonResponse(JSONObject().apply {
                put("featureName", featureName)
                put("hasFeature", hasFeature)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handlePackageSystemFeature error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePackageComponentEnabled(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val componentNameStr = json.getString("componentName")
            val component = ComponentName.unflattenFromString(componentNameStr)
                ?: return errorResponse("Invalid component name: $componentNameStr", Response.Status.BAD_REQUEST)

            if (json.has("newState")) {
                val newState = json.getInt("newState")
                val flags = json.optInt("flags", PackageManager.DONT_KILL_APP)
                context.packageManager.setComponentEnabledSetting(component, newState, flags)
            }

            val currentState = context.packageManager.getComponentEnabledSetting(component)
            val stateName = when (currentState) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default"
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disabled_user"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disabled_until_used"
                else -> "unknown"
            }
            jsonResponse(JSONObject().apply {
                put("componentName", componentNameStr)
                put("currentState", currentState)
                put("stateName", stateName)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handlePackageComponentEnabled error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePackageSignaturesCheck(session: IHTTPSession): Response {
        return try {
            val package1 = session.parms["package1"]
                ?: return errorResponse("package1 required", Response.Status.BAD_REQUEST)
            val package2 = session.parms["package2"]
                ?: return errorResponse("package2 required", Response.Status.BAD_REQUEST)

            @Suppress("DEPRECATION")
            val result = context.packageManager.checkSignatures(package1, package2)
            val match = result == PackageManager.SIGNATURE_MATCH
            jsonResponse(JSONObject().apply {
                put("package1", package1)
                put("package2", package2)
                put("result", result)
                put("match", match)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handlePackageSignaturesCheck error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Content Provider Endpoints ====================

    private fun handleContentQuery(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val uriStr = json.getString("uri")
            val uri = Uri.parse(uriStr)
            val projection = json.optString("projection", "").takeIf { it.isNotEmpty() }?.split(",")?.toTypedArray()
            val selection = json.optString("selection", "").takeIf { it.isNotEmpty() }
            val selectionArgs = json.optString("selectionArgs", "").takeIf { it.isNotEmpty() }?.split(",")?.toTypedArray()
            val sortOrder = json.optString("sortOrder", "").takeIf { it.isNotEmpty() }
            val limit = json.optInt("limit", 100)

            val finalSortOrder = if (sortOrder != null && limit > 0) "$sortOrder LIMIT $limit"
                else if (limit > 0) "ROWID ASC LIMIT $limit"
                else sortOrder

            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, finalSortOrder)
            val rows = JSONArray()
            var columnNames = JSONArray()
            var rowCount = 0

            cursor?.use { c ->
                val cols = c.columnNames
                columnNames = JSONArray(cols.toList())
                while (c.moveToNext() && rowCount < limit) {
                    val row = JSONObject()
                    for (i in cols.indices) {
                        val colName = cols[i]
                        try {
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> row.put(colName, JSONObject.NULL)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(colName, c.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(colName, c.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> row.put(colName, c.getString(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> row.put(colName, "[BLOB ${c.getBlob(i)?.size ?: 0} bytes]")
                            }
                        } catch (e: Exception) {
                            row.put(colName, "<error: ${e.message}>")
                        }
                    }
                    rows.put(row)
                    rowCount++
                }
            }
            jsonResponse(JSONObject().apply {
                put("rows", rows)
                put("columnNames", columnNames)
                put("rowCount", rowCount)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleContentQuery error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleContentInsert(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val uriStr = json.getString("uri")
            val uri = Uri.parse(uriStr)
            val valuesJson = if (json.get("values") is JSONObject) {
                json.getJSONObject("values")
            } else {
                JSONObject(json.getString("values"))
            }

            val contentValues = ContentValues()
            for (key in valuesJson.keys()) {
                val value = valuesJson.get(key)
                when (value) {
                    is String -> contentValues.put(key, value)
                    is Int -> contentValues.put(key, value)
                    is Long -> contentValues.put(key, value)
                    is Double -> contentValues.put(key, value)
                    is Boolean -> contentValues.put(key, value)
                    is Float -> contentValues.put(key, value)
                    JSONObject.NULL -> contentValues.putNull(key)
                    else -> contentValues.put(key, value.toString())
                }
            }

            val insertedUri = context.contentResolver.insert(uri, contentValues)
            jsonResponse(JSONObject().apply {
                put("insertedUri", insertedUri?.toString())
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleContentInsert error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleContentUpdate(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val uriStr = json.getString("uri")
            val uri = Uri.parse(uriStr)
            val valuesJson = if (json.get("values") is JSONObject) {
                json.getJSONObject("values")
            } else {
                JSONObject(json.getString("values"))
            }
            val selection = json.optString("selection", "").takeIf { it.isNotEmpty() }
            val selectionArgs = json.optString("selectionArgs", "").takeIf { it.isNotEmpty() }?.split(",")?.toTypedArray()

            val contentValues = ContentValues()
            for (key in valuesJson.keys()) {
                val value = valuesJson.get(key)
                when (value) {
                    is String -> contentValues.put(key, value)
                    is Int -> contentValues.put(key, value)
                    is Long -> contentValues.put(key, value)
                    is Double -> contentValues.put(key, value)
                    is Boolean -> contentValues.put(key, value)
                    is Float -> contentValues.put(key, value)
                    JSONObject.NULL -> contentValues.putNull(key)
                    else -> contentValues.put(key, value.toString())
                }
            }

            val rowsUpdated = context.contentResolver.update(uri, contentValues, selection, selectionArgs)
            jsonResponse(JSONObject().apply { put("rowsUpdated", rowsUpdated) })
        } catch (e: Exception) {
            Log.e(TAG, "handleContentUpdate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleContentDelete(session: IHTTPSession): Response {
        return try {
            // Support both query params and body for flexibility
            val uriStr: String
            val selection: String?
            val selectionArgs: Array<String>?

            val body = parseBody(session)
            if (body != null && body.isNotEmpty() && body.startsWith("{")) {
                val json = JSONObject(body)
                uriStr = json.getString("uri")
                selection = json.optString("selection", "").takeIf { it.isNotEmpty() }
                selectionArgs = json.optString("selectionArgs", "").takeIf { it.isNotEmpty() }?.split(",")?.toTypedArray()
            } else {
                uriStr = session.parms["uri"]
                    ?: return errorResponse("uri required", Response.Status.BAD_REQUEST)
                selection = session.parms["selection"]
                selectionArgs = session.parms["selectionArgs"]?.split(",")?.toTypedArray()
            }

            val uri = Uri.parse(uriStr)
            val rowsDeleted = context.contentResolver.delete(uri, selection, selectionArgs)
            jsonResponse(JSONObject().apply { put("rowsDeleted", rowsDeleted) })
        } catch (e: Exception) {
            Log.e(TAG, "handleContentDelete error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Intent Endpoints ====================

    private fun handleIntentSend(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val action = json.getString("action")
            val target = json.optString("target", "activity")

            val intent = Intent(action).apply {
                json.optString("data", "").takeIf { it.isNotEmpty() }?.let { data = Uri.parse(it) }
                json.optString("type", "").takeIf { it.isNotEmpty() }?.let { type = it }
                json.optString("packageName", "").takeIf { it.isNotEmpty() }?.let { setPackage(it) }
                json.optString("component", "").takeIf { it.isNotEmpty() }?.let { compStr ->
                    ComponentName.unflattenFromString(compStr)?.let { component = it }
                }
                json.optString("category", "").takeIf { it.isNotEmpty() }?.let { addCategory(it) }
                if (json.has("flags")) flags = json.getInt("flags")

                // Handle extras
                json.optString("extras", "").takeIf { it.isNotEmpty() && it != "{}" }?.let { extrasStr ->
                    val extras = JSONObject(extrasStr)
                    for (key in extras.keys()) {
                        val value = extras.get(key)
                        when (value) {
                            is String -> putExtra(key, value)
                            is Int -> putExtra(key, value)
                            is Long -> putExtra(key, value)
                            is Double -> putExtra(key, value)
                            is Boolean -> putExtra(key, value)
                            is Float -> putExtra(key, value)
                            else -> putExtra(key, value.toString())
                        }
                    }
                }

                // If target is activity, add NEW_TASK flag
                if (target == "activity") {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val componentResult: String? = when (target) {
                "activity" -> {
                    context.startActivity(intent)
                    intent.component?.flattenToString()
                }
                "service" -> {
                    val cn = context.startService(intent)
                    cn?.flattenToString()
                }
                "broadcast" -> {
                    context.sendBroadcast(intent)
                    null
                }
                else -> return errorResponse("Invalid target: $target. Use activity, service, or broadcast", Response.Status.BAD_REQUEST)
            }

            jsonResponse(JSONObject().apply {
                put("success", true)
                put("component", componentResult)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleIntentSend error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleIntentResolve(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val action = json.getString("action")
            val target = json.optString("target", "activity")

            val intent = Intent(action).apply {
                json.optString("data", "").takeIf { it.isNotEmpty() }?.let { data = Uri.parse(it) }
                json.optString("type", "").takeIf { it.isNotEmpty() }?.let { type = it }
                json.optString("category", "").takeIf { it.isNotEmpty() }?.let { addCategory(it) }
            }

            val resolvedComponents = JSONArray()
            when (target) {
                "activity" -> {
                    val activities = context.packageManager.queryIntentActivities(intent, 0)
                    for (ri in activities) {
                        resolvedComponents.put(JSONObject().apply {
                            put("packageName", ri.activityInfo.packageName)
                            put("name", ri.activityInfo.name)
                            put("label", ri.loadLabel(context.packageManager)?.toString())
                        })
                    }
                }
                "service" -> {
                    val services = context.packageManager.queryIntentServices(intent, 0)
                    for (ri in services) {
                        resolvedComponents.put(JSONObject().apply {
                            put("packageName", ri.serviceInfo.packageName)
                            put("name", ri.serviceInfo.name)
                            put("label", ri.loadLabel(context.packageManager)?.toString())
                        })
                    }
                }
                "broadcast" -> {
                    val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
                    for (ri in receivers) {
                        resolvedComponents.put(JSONObject().apply {
                            put("packageName", ri.activityInfo.packageName)
                            put("name", ri.activityInfo.name)
                            put("label", ri.loadLabel(context.packageManager)?.toString())
                        })
                    }
                }
            }

            jsonResponse(JSONObject().apply { put("resolvedComponents", resolvedComponents) })
        } catch (e: Exception) {
            Log.e(TAG, "handleIntentResolve error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Files & Databases Endpoints ====================

    private fun handleFileListPrivate(): Response {
        return try {
            val fileList = context.fileList()
            val files = JSONArray()
            for (name in fileList) {
                val file = File(context.filesDir, name)
                files.put(JSONObject().apply {
                    put("name", name)
                    put("size", file.length())
                    put("lastModified", file.lastModified())
                    put("isDirectory", file.isDirectory)
                    put("path", file.absolutePath)
                })
            }
            jsonResponse(JSONObject().apply { put("files", files) })
        } catch (e: Exception) {
            Log.e(TAG, "handleFileListPrivate error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleDatabaseList(): Response {
        return try {
            val dbList = context.databaseList()
            val databases = JSONArray()
            for (name in dbList) {
                val dbFile = context.getDatabasePath(name)
                databases.put(JSONObject().apply {
                    put("name", name)
                    put("size", dbFile.length())
                    put("lastModified", dbFile.lastModified())
                    put("path", dbFile.absolutePath)
                    put("exists", dbFile.exists())
                })
            }
            jsonResponse(JSONObject().apply { put("databases", databases) })
        } catch (e: Exception) {
            Log.e(TAG, "handleDatabaseList error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Wallpaper Endpoints ====================

    private fun handleWallpaperSet(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val imagePath = json.getString("imagePath")

            val wallpaperManager = WallpaperManager.getInstance(context)

            if (imagePath.startsWith("content://")) {
                // Content URI
                val uri = Uri.parse(imagePath)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return errorResponse("Cannot open URI: $imagePath")
                wallpaperManager.setStream(inputStream)
                inputStream.close()
            } else {
                // File path
                val file = File(imagePath)
                if (!file.exists()) return errorResponse("File not found: $imagePath", Response.Status.NOT_FOUND)
                val bitmap = BitmapFactory.decodeFile(imagePath)
                    ?: return errorResponse("Cannot decode image: $imagePath")
                wallpaperManager.setBitmap(bitmap)
                bitmap.recycle()
            }

            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleWallpaperSet error", e)
            errorResponse("${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWallpaperClear(): Response {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.clear()
            } else {
                wallpaperManager.clear()
            }
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleWallpaperClear error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== Sync Endpoints ====================

    private fun handleSyncAdaptersList(): Response {
        return try {
            val adapters = ContentResolver.getSyncAdapterTypes()
            val result = JSONArray()
            for (adapter in adapters) {
                result.put(JSONObject().apply {
                    put("authority", adapter.authority)
                    put("accountType", adapter.accountType)
                    put("isUserVisible", adapter.isUserVisible)
                    put("supportsUploading", adapter.supportsUploading())
                    put("isAlwaysSyncable", adapter.isAlwaysSyncable)
                    put("allowParallelSyncs", adapter.allowParallelSyncs())
                })
            }
            jsonResponse(JSONObject().apply { put("adapters", result) })
        } catch (e: Exception) {
            Log.e(TAG, "handleSyncAdaptersList error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleSyncRequest(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val accountName = json.getString("accountName")
            val accountType = json.getString("accountType")
            val authority = json.getString("authority")

            val account = Account(accountName, accountType)
            val extras = Bundle().apply {
                // Force immediate sync
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)

                // Parse additional extras if provided
                json.optString("extras", "").takeIf { it.isNotEmpty() && it != "{}" }?.let { extrasStr ->
                    val extrasJson = JSONObject(extrasStr)
                    for (key in extrasJson.keys()) {
                        val value = extrasJson.get(key)
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Double -> putDouble(key, value)
                            is Float -> putFloat(key, value)
                            else -> putString(key, value.toString())
                        }
                    }
                }
            }
            ContentResolver.requestSync(account, authority, extras)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleSyncRequest error", e)
            errorResponse("${e.message}")
        }
    }

    // ==================== URI Permission Endpoints ====================

    private fun handleUriPermissionGrant(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val packageName = json.getString("packageName")
            val uriStr = json.getString("uri")
            val modeFlags = json.getInt("modeFlags")

            context.grantUriPermission(packageName, Uri.parse(uriStr), modeFlags)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleUriPermissionGrant error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handleUriPermissionRevoke(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session) ?: return errorResponse("Missing request body", Response.Status.BAD_REQUEST)
            val json = JSONObject(body)
            val uriStr = json.getString("uri")
            val modeFlags = json.getInt("modeFlags")

            context.revokeUriPermission(Uri.parse(uriStr), modeFlags)
            jsonResponse(JSONObject().apply { put("success", true) })
        } catch (e: Exception) {
            Log.e(TAG, "handleUriPermissionRevoke error", e)
            errorResponse("${e.message}")
        }
    }

    private fun handlePermissionCheckSelf(session: IHTTPSession): Response {
        return try {
            val permission = session.parms["permission"]
                ?: return errorResponse("permission required", Response.Status.BAD_REQUEST)
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            jsonResponse(JSONObject().apply {
                put("permission", permission)
                put("granted", granted)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handlePermissionCheckSelf error", e)
            errorResponse("${e.message}")
        }
    }

    // =========================================================================
    // Settings Launcher endpoints (Batch 1)
    // =========================================================================

    /**
     * POST /settings/open
     * Opens an Android settings screen by friendly name.
     * Body: {"screen": "wifi"} — see /settings/list for valid names
     */
    private fun handleSettingsOpen(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val screen = data.optString("screen", "")
        if (screen.isEmpty()) {
            return errorResponse("Missing 'screen' parameter. Use GET /settings/list for valid names.", Response.Status.BAD_REQUEST)
        }

        val action = settingsScreenMap[screen.lowercase()]
        if (action == null) {
            return errorResponse(
                "Unknown settings screen: '$screen'. Use GET /settings/list for valid names.",
                Response.Status.BAD_REQUEST
            )
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val json = JSONObject().apply {
                put("success", true)
                put("screen", screen.lowercase())
                put("action", action)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings screen: $screen", e)
            errorResponse("Failed to open settings screen '$screen': ${e.message}")
        }
    }

    /**
     * GET /settings/list
     * Returns the list of all supported settings screen names.
     */
    private fun handleSettingsList(): Response {
        val json = JSONObject().apply {
            put("screens", JSONArray(settingsScreenMap.keys.sorted()))
            put("count", settingsScreenMap.size)
            put("usage", "POST /settings/open with {\"screen\": \"<name>\"}")
        }
        return jsonResponse(json)
    }

    // =========================================================================
    // Volume Stream Extensions (Batch 2)
    // =========================================================================

    /**
     * GET /volume/get_stream?stream=music
     * Get volume for a specific audio stream.
     * Supports: music, ring, notification, alarm, system, call, dtmf, accessibility
     */
    private fun handleGetStreamVolume(session: IHTTPSession): Response {
        val stream = session.parms["stream"] ?: return errorResponse("Missing 'stream' parameter", Response.Status.BAD_REQUEST)

        val streamType = resolveStreamType(stream)
            ?: return errorResponse("Invalid stream type: $stream. Valid: music, ring, notification, alarm, system, call, dtmf, accessibility", Response.Status.BAD_REQUEST)

        val json = JSONObject().apply {
            put("stream", stream)
            put("current", audioManager.getStreamVolume(streamType))
            put("max", audioManager.getStreamMaxVolume(streamType))
        }
        return jsonResponse(json)
    }

    /**
     * GET /volume/get_all
     * Get volumes for ALL audio streams plus ringer mode.
     * Returns current and max for each stream type.
     */
    private fun handleGetAllVolumes(): Response {
        val streams = mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "system" to AudioManager.STREAM_SYSTEM,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "dtmf" to AudioManager.STREAM_DTMF,
            "accessibility" to AudioManager.STREAM_ACCESSIBILITY
        )

        val json = JSONObject().apply {
            val streamsJson = JSONObject()
            for ((name, streamType) in streams) {
                streamsJson.put(name, JSONObject().apply {
                    put("current", audioManager.getStreamVolume(streamType))
                    put("max", audioManager.getStreamMaxVolume(streamType))
                })
            }
            put("streams", streamsJson)
            put("ringer_mode", when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> "unknown"
            })
        }
        return jsonResponse(json)
    }

    /**
     * POST /volume/set_stream
     * Set volume for a specific audio stream.
     * Body: {"stream": "music", "level": 7, "flags": 0}
     */
    private fun handleSetStreamVolume(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.ACCESS_NOTIFICATION_POLICY)) {
            return permissionError(Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val stream = data.optString("stream", "music")
        val level = data.optInt("level", -1)
        val flags = data.optInt("flags", 0)

        val streamType = resolveStreamType(stream)
            ?: return errorResponse("Invalid stream type: $stream. Valid: music, ring, notification, alarm, system, call, dtmf, accessibility")

        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        if (level < 0 || level > maxVolume) {
            return errorResponse("Invalid volume level. Range: 0-$maxVolume for stream '$stream'")
        }

        audioManager.setStreamVolume(streamType, level, flags)

        val json = JSONObject().apply {
            put("success", true)
            put("stream", stream)
            put("level", level)
            put("max", maxVolume)
            put("flags", flags)
        }
        return jsonResponse(json)
    }

    /**
     * POST /volume/adjust
     * Adjust volume up/down/same for a specific stream.
     * Body: {"stream": "music", "direction": "raise", "flags": 0}
     */
    private fun handleAdjustVolume(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.ACCESS_NOTIFICATION_POLICY)) {
            return permissionError(Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val stream = data.optString("stream", "music")
        val direction = data.optString("direction", "same")
        val flags = data.optInt("flags", 0)

        val streamType = resolveStreamType(stream)
            ?: return errorResponse("Invalid stream type: $stream. Valid: music, ring, notification, alarm, system, call, dtmf, accessibility")

        val adjustDirection = when (direction) {
            "raise" -> AudioManager.ADJUST_RAISE
            "lower" -> AudioManager.ADJUST_LOWER
            "same" -> AudioManager.ADJUST_SAME
            else -> return errorResponse("Invalid direction: $direction. Valid: raise, lower, same")
        }

        audioManager.adjustStreamVolume(streamType, adjustDirection, flags)

        val json = JSONObject().apply {
            put("success", true)
            put("stream", stream)
            put("direction", direction)
            put("current", audioManager.getStreamVolume(streamType))
            put("max", audioManager.getStreamMaxVolume(streamType))
        }
        return jsonResponse(json)
    }

    /**
     * Helper: Resolve stream name string to AudioManager stream type constant.
     */
    private fun resolveStreamType(stream: String): Int? {
        return when (stream) {
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "system" -> AudioManager.STREAM_SYSTEM
            "call", "voice_call" -> AudioManager.STREAM_VOICE_CALL
            "dtmf" -> AudioManager.STREAM_DTMF
            "accessibility" -> AudioManager.STREAM_ACCESSIBILITY
            else -> null
        }
    }

    // =========================================================================
    // Audio Mode Control (Batch 3)
    // =========================================================================

    /**
     * POST /audio/ringer_mode
     * Set the device ringer mode.
     * Body: {"mode": "normal"} — "normal", "silent", or "vibrate"
     */
    private fun handleSetRingerMode(session: IHTTPSession): Response {
        if (!hasPermission(Manifest.permission.ACCESS_NOTIFICATION_POLICY)) {
            return permissionError(Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        }

        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val mode = data.optString("mode", "")

        val ringerMode = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> return errorResponse("Invalid ringer mode: $mode. Valid: normal, silent, vibrate")
        }

        audioManager.ringerMode = ringerMode

        val json = JSONObject().apply {
            put("success", true)
            put("mode", mode)
            put("ringer_mode_int", ringerMode)
        }
        return jsonResponse(json)
    }

    /**
     * GET /audio/ringer_mode
     * Get the current ringer mode.
     */
    private fun handleGetRingerMode(): Response {
        val mode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        }

        val json = JSONObject().apply {
            put("mode", mode)
            put("ringer_mode_int", audioManager.ringerMode)
        }
        return jsonResponse(json)
    }

    /**
     * POST /audio/mic_mute
     * Mute or unmute the microphone.
     * Body: {"muted": true}
     */
    private fun handleSetMicMute(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("muted")) {
            return errorResponse("Missing 'muted' parameter (boolean)", Response.Status.BAD_REQUEST)
        }

        val muted = data.getBoolean("muted")
        audioManager.isMicrophoneMute = muted

        val json = JSONObject().apply {
            put("success", true)
            put("muted", audioManager.isMicrophoneMute)
        }
        return jsonResponse(json)
    }

    /**
     * GET /audio/mic_mute
     * Get the current microphone mute state.
     */
    private fun handleGetMicMute(): Response {
        val json = JSONObject().apply {
            put("muted", audioManager.isMicrophoneMute)
        }
        return jsonResponse(json)
    }

    /**
     * POST /audio/speakerphone
     * Enable or disable speakerphone.
     * Body: {"enabled": true}
     */
    @Suppress("DEPRECATION")
    private fun handleSetSpeakerphone(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' parameter (boolean)", Response.Status.BAD_REQUEST)
        }

        val enabled = data.getBoolean("enabled")
        audioManager.isSpeakerphoneOn = enabled

        val json = JSONObject().apply {
            put("success", true)
            put("enabled", audioManager.isSpeakerphoneOn)
        }
        return jsonResponse(json)
    }

    /**
     * GET /audio/speakerphone
     * Get the current speakerphone state.
     */
    @Suppress("DEPRECATION")
    private fun handleGetSpeakerphone(): Response {
        val json = JSONObject().apply {
            put("enabled", audioManager.isSpeakerphoneOn)
        }
        return jsonResponse(json)
    }

    /**
     * GET /audio/mode
     * Get the current audio mode (normal, ringtone, in_call, in_communication).
     */
    private fun handleGetAudioMode(): Response {
        val modeName = when (audioManager.mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in_call"
            AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
            AudioManager.MODE_CALL_SCREENING -> "call_screening"
            else -> "unknown"
        }

        val json = JSONObject().apply {
            put("mode", modeName)
            put("mode_int", audioManager.mode)
            put("music_active", audioManager.isMusicActive)
            put("wired_headset_on", audioManager.isWiredHeadsetOn)
            put("bluetooth_a2dp_on", audioManager.isBluetoothA2dpOn)
            @Suppress("DEPRECATION")
            put("speakerphone_on", audioManager.isSpeakerphoneOn)
            put("mic_mute", audioManager.isMicrophoneMute)
        }
        return jsonResponse(json)
    }

    // =========================================================================
    // Display Settings (Batch 4) — Converted from Ktor to NanoHTTPD
    // =========================================================================

    /** GET /display/timeout */
    private fun handleGetDisplayTimeout(): Response {
        return try {
            val timeoutMs = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                30000
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("timeout_ms", timeoutMs)
                put("timeout_seconds", timeoutMs / 1000)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get screen timeout: ${e.message}")
        }
    }

    /** POST /display/timeout — Body: {"timeout_ms": 30000} */
    private fun handleSetDisplayTimeout(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val timeoutMs = data.optInt("timeout_ms", -1)
        if (timeoutMs < 0) {
            return errorResponse("Missing 'timeout_ms' parameter", Response.Status.BAD_REQUEST)
        }
        if (timeoutMs < 1000) {
            return errorResponse("timeout_ms must be >= 1000 (1 second minimum)", Response.Status.BAD_REQUEST)
        }

        if (!Settings.System.canWrite(context)) {
            return errorResponse("WRITE_SETTINGS permission not granted", Response.Status.FORBIDDEN)
        }

        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                timeoutMs
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("timeout_ms", timeoutMs)
                put("message", "Screen timeout set to ${timeoutMs}ms")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to set screen timeout: ${e.message}")
        }
    }

    /** GET /display/auto_brightness */
    private fun handleGetAutoBrightness(): Response {
        return try {
            val mode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val enabled = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val json = JSONObject().apply {
                put("status", "ok")
                put("enabled", enabled)
                put("mode", if (enabled) "automatic" else "manual")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get auto brightness state: ${e.message}")
        }
    }

    /** POST /display/auto_brightness — Body: {"enabled": true} */
    private fun handleSetAutoBrightness(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' parameter (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        if (!Settings.System.canWrite(context)) {
            return errorResponse("WRITE_SETTINGS permission not granted", Response.Status.FORBIDDEN)
        }

        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("enabled", enabled)
                put("message", "Auto brightness ${if (enabled) "enabled" else "disabled"}")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to set auto brightness: ${e.message}")
        }
    }

    /** GET /display/auto_rotate */
    private fun handleGetAutoRotate(): Response {
        return try {
            val autoRotate = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("enabled", autoRotate == 1)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get auto rotate state: ${e.message}")
        }
    }

    /** POST /display/auto_rotate — Body: {"enabled": true} */
    private fun handleSetAutoRotate(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' parameter (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        if (!Settings.System.canWrite(context)) {
            return errorResponse("WRITE_SETTINGS permission not granted", Response.Status.FORBIDDEN)
        }

        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("enabled", enabled)
                put("message", "Auto rotate ${if (enabled) "enabled" else "disabled"}")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to set auto rotate: ${e.message}")
        }
    }

    /** GET /display/stay_on */
    private fun handleGetStayOn(): Response {
        return try {
            val mode = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                0
            )
            val description = when (mode) {
                0 -> "never"
                1 -> "ac_only"
                2 -> "usb_only"
                3 -> "ac_and_usb"
                7 -> "all_sources"
                else -> "unknown ($mode)"
            }
            val json = JSONObject().apply {
                put("status", "ok")
                put("mode", mode)
                put("description", description)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get stay-on mode: ${e.message}")
        }
    }

    /** POST /display/stay_on — Body: {"mode": 0} (0=never, 1=AC, 2=USB, 3=AC+USB, 7=all) */
    private fun handleSetStayOn(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val mode = data.optInt("mode", -1)
        if (mode < 0) {
            return errorResponse("Missing 'mode' parameter (0=never, 1=AC, 2=USB, 3=AC+USB, 7=all)", Response.Status.BAD_REQUEST)
        }
        if (mode !in listOf(0, 1, 2, 3, 7)) {
            return errorResponse("Invalid mode. Use 0=never, 1=AC, 2=USB, 3=AC+USB, 7=all", Response.Status.BAD_REQUEST)
        }

        if (!Settings.System.canWrite(context)) {
            return errorResponse("WRITE_SETTINGS permission not granted", Response.Status.FORBIDDEN)
        }

        return try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                mode
            )
            val description = when (mode) {
                0 -> "never"
                1 -> "ac_only"
                2 -> "usb_only"
                3 -> "ac_and_usb"
                7 -> "all_sources"
                else -> "unknown"
            }
            val json = JSONObject().apply {
                put("status", "ok")
                put("mode", mode)
                put("description", description)
                put("message", "Stay on while plugged in set to $description")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to set stay-on mode: ${e.message}")
        }
    }

    /** GET /display/font_scale */
    private fun handleGetFontScale(): Response {
        return try {
            val scale = Settings.System.getFloat(
                context.contentResolver,
                Settings.System.FONT_SCALE,
                1.0f
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("scale", scale.toDouble())
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get font scale: ${e.message}")
        }
    }

    /** POST /display/font_scale — Body: {"scale": 1.0} */
    private fun handleSetFontScale(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val scale = data.optDouble("scale", -1.0)
        if (scale < 0) {
            return errorResponse("Missing 'scale' parameter (float, e.g. 1.0)", Response.Status.BAD_REQUEST)
        }
        if (scale < 0.5 || scale > 3.0) {
            return errorResponse("Font scale must be between 0.5 and 3.0", Response.Status.BAD_REQUEST)
        }

        if (!Settings.System.canWrite(context)) {
            return errorResponse("WRITE_SETTINGS permission not granted", Response.Status.FORBIDDEN)
        }

        return try {
            Settings.System.putFloat(
                context.contentResolver,
                Settings.System.FONT_SCALE,
                scale.toFloat()
            )
            val json = JSONObject().apply {
                put("status", "ok")
                put("scale", scale)
                put("message", "Font scale set to $scale")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to set font scale: ${e.message}")
        }
    }

    // =========================================================================
    // Call Management (Batch 5) — Converted from Ktor to NanoHTTPD
    // =========================================================================

    /** POST /call/make — Body: {"number": "+1234567890"} */
    private fun handleCallMake(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val number = data.optString("number", "")
        if (number.isEmpty()) {
            return errorResponse("Missing 'number' parameter (e.g. '+1234567890')", Response.Status.BAD_REQUEST)
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                this.data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            val json = JSONObject().apply {
                put("status", "ok")
                put("number", number)
                put("message", "Calling $number")
            }
            jsonResponse(json)
        } catch (e: SecurityException) {
            errorResponse("CALL_PHONE permission not granted", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            errorResponse("Failed to make call: ${e.message}")
        }
    }

    /** POST /call/end */
    private fun handleCallEnd(): Response {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                val ended = telecomManager.endCall()
                val json = JSONObject().apply {
                    put("status", "ok")
                    put("ended", ended)
                    put("message", if (ended) "Call ended" else "No active call to end")
                }
                jsonResponse(json)
            } else {
                errorResponse("endCall() requires API 28+ (current: ${Build.VERSION.SDK_INT})", Response.Status.BAD_REQUEST)
            }
        } catch (e: SecurityException) {
            errorResponse("ANSWER_PHONE_CALLS permission not granted", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            errorResponse("Failed to end call: ${e.message}")
        }
    }

    /** POST /call/answer */
    private fun handleCallAnswer(): Response {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                val json = JSONObject().apply {
                    put("status", "ok")
                    put("message", "Ringing call accepted")
                }
                jsonResponse(json)
            } else {
                errorResponse("acceptRingingCall() requires API 26+ (current: ${Build.VERSION.SDK_INT})", Response.Status.BAD_REQUEST)
            }
        } catch (e: SecurityException) {
            errorResponse("ANSWER_PHONE_CALLS permission not granted", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            errorResponse("Failed to answer call: ${e.message}")
        }
    }

    /** POST /call/silence */
    private fun handleCallSilence(): Response {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.silenceRinger()
            val json = JSONObject().apply {
                put("status", "ok")
                put("message", "Ringer silenced")
            }
            jsonResponse(json)
        } catch (e: SecurityException) {
            errorResponse("MODIFY_PHONE_STATE permission not granted", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            errorResponse("Failed to silence ringer: ${e.message}")
        }
    }

    /** GET /call/state */
    private fun handleCallState(): Response {
        return try {
            @Suppress("DEPRECATION")
            val callState = telephonyManager.callState
            val stateStr = when (callState) {
                TelephonyManager.CALL_STATE_IDLE -> "idle"
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                else -> "unknown"
            }
            val json = JSONObject().apply {
                put("status", "ok")
                put("call_state", stateStr)
                put("call_state_code", callState)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get call state: ${e.message}")
        }
    }

    /** POST /call/reject — Body: {"sms_text": "Can't talk now"} (optional) */
    private fun handleCallReject(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val smsText = if (data.has("sms_text")) data.optString("sms_text", null) else null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                val rejected = telecomManager.endCall()

                if (rejected && smsText != null) {
                    val cursor = context.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(android.provider.CallLog.Calls.NUMBER),
                        "${android.provider.CallLog.Calls.TYPE} = ?",
                        arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString()),
                        "${android.provider.CallLog.Calls.DATE} DESC LIMIT 1"
                    )
                    val number = cursor?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(c.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER))
                        } else null
                    }

                    if (number != null) {
                        @Suppress("DEPRECATION")
                        val smsManager = android.telephony.SmsManager.getDefault()
                        smsManager.sendTextMessage(number, null, smsText, null, null)
                    }
                }

                val json = JSONObject().apply {
                    put("status", "ok")
                    put("rejected", rejected)
                    put("sms_sent", rejected && smsText != null)
                    put("message", if (rejected) "Call rejected" else "No ringing call to reject")
                }
                jsonResponse(json)
            } else {
                errorResponse("endCall() requires API 28+ (current: ${Build.VERSION.SDK_INT})", Response.Status.BAD_REQUEST)
            }
        } catch (e: SecurityException) {
            errorResponse("ANSWER_PHONE_CALLS or SEND_SMS permission not granted", Response.Status.FORBIDDEN)
        } catch (e: Exception) {
            errorResponse("Failed to reject call: ${e.message}")
        }
    }

    /** POST /call/speaker — Body: {"enabled": true} */
    private fun handleCallSpeaker(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' parameter (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        return try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
            val json = JSONObject().apply {
                put("status", "ok")
                put("speakerphone", enabled)
                put("message", "Speakerphone ${if (enabled) "on" else "off"}")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to toggle speakerphone: ${e.message}")
        }
    }

    /** POST /call/hold — Body: {"hold": true} */
    private fun handleCallHold(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)
        val hold = data.optBoolean("hold", true)

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "input keyevent KEYCODE_MEDIA_PAUSE")
            )
            process.waitFor()

            val json = JSONObject().apply {
                put("status", "ok")
                put("hold", hold)
                put("message", "Hold ${if (hold) "activated" else "deactivated"} (via keyevent, may not be supported on all devices)")
                put("note", "Full hold support requires InCallService registration as default dialer")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to toggle call hold: ${e.message}")
        }
    }

    /** POST /call/dtmf — Body: {"digit": "5"} */
    private fun handleCallDtmf(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val digit = data.optString("digit", "")
        if (digit.isEmpty()) {
            return errorResponse("Missing 'digit' parameter (0-9, *, #)", Response.Status.BAD_REQUEST)
        }
        if (digit.length != 1 || digit[0] !in "0123456789*#") {
            return errorResponse("Invalid digit. Must be single character: 0-9, *, or #", Response.Status.BAD_REQUEST)
        }

        return try {
            val toneType = when (digit[0]) {
                '0' -> android.media.ToneGenerator.TONE_DTMF_0
                '1' -> android.media.ToneGenerator.TONE_DTMF_1
                '2' -> android.media.ToneGenerator.TONE_DTMF_2
                '3' -> android.media.ToneGenerator.TONE_DTMF_3
                '4' -> android.media.ToneGenerator.TONE_DTMF_4
                '5' -> android.media.ToneGenerator.TONE_DTMF_5
                '6' -> android.media.ToneGenerator.TONE_DTMF_6
                '7' -> android.media.ToneGenerator.TONE_DTMF_7
                '8' -> android.media.ToneGenerator.TONE_DTMF_8
                '9' -> android.media.ToneGenerator.TONE_DTMF_9
                '*' -> android.media.ToneGenerator.TONE_DTMF_S
                '#' -> android.media.ToneGenerator.TONE_DTMF_P
                else -> android.media.ToneGenerator.TONE_DTMF_0
            }

            val toneGenerator = android.media.ToneGenerator(
                AudioManager.STREAM_DTMF, 100
            )
            toneGenerator.startTone(toneType, 200)

            val keyCode = when (digit[0]) {
                '0' -> "KEYCODE_0"
                '1' -> "KEYCODE_1"
                '2' -> "KEYCODE_2"
                '3' -> "KEYCODE_3"
                '4' -> "KEYCODE_4"
                '5' -> "KEYCODE_5"
                '6' -> "KEYCODE_6"
                '7' -> "KEYCODE_7"
                '8' -> "KEYCODE_8"
                '9' -> "KEYCODE_9"
                '*' -> "KEYCODE_STAR"
                '#' -> "KEYCODE_POUND"
                else -> "KEYCODE_0"
            }
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyCode"))

            // Clean up tone generator after delay
            scope.launch {
                kotlinx.coroutines.delay(250)
                toneGenerator.release()
            }

            val json = JSONObject().apply {
                put("status", "ok")
                put("digit", digit)
                put("message", "DTMF tone '$digit' sent")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to send DTMF tone: ${e.message}")
        }
    }

    // =========================================================================
    // TTS Extensions (Batch 6) — Converted from Ktor to NanoHTTPD
    // =========================================================================

    /** POST /tts/speak — Enhanced text-to-speech with full control */
    private fun handleTtsSpeak(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse("Missing 'text' field", Response.Status.BAD_REQUEST)
        }

        val ttsEngine = tts
        if (ttsEngine == null || !ttsReady) {
            return errorResponse("TTS engine not ready", Response.Status.SERVICE_UNAVAILABLE)
        }

        val locale = data.optString("locale", "en-US")
        val pitch = data.optDouble("pitch", 1.0).toFloat()
        val speed = data.optDouble("speed", 1.0).toFloat()
        val stream = data.optString("stream", "music")
        val queueMode = data.optString("queue", "flush")

        val loc = Locale.forLanguageTag(locale.replace("_", "-"))
        val langResult = ttsEngine.setLanguage(loc)
        val localeApplied = when (langResult) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                ttsEngine.language = Locale.US
                "en-US (fallback)"
            }
            else -> locale
        }

        ttsEngine.setPitch(pitch.coerceIn(0.1f, 4.0f))
        ttsEngine.setSpeechRate(speed.coerceIn(0.1f, 4.0f))

        val audioStream = when (stream.lowercase()) {
            "music" -> AudioManager.STREAM_MUSIC
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "ring" -> AudioManager.STREAM_RING
            "system" -> AudioManager.STREAM_SYSTEM
            "voice_call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioStream)
        }

        val queue = when (queueMode.lowercase()) {
            "add" -> TextToSpeech.QUEUE_ADD
            else -> TextToSpeech.QUEUE_FLUSH
        }

        val utteranceId = "tts_${System.currentTimeMillis()}"
        ttsEngine.speak(text, queue, params, utteranceId)

        val json = JSONObject().apply {
            put("success", true)
            put("text", text)
            put("utterance_id", utteranceId)
            put("locale", localeApplied)
            put("pitch", pitch.toDouble())
            put("speed", speed.toDouble())
            put("stream", stream)
            put("queue", queueMode)
        }
        return jsonResponse(json)
    }

    /** POST /tts/stop — Stop all TTS playback */
    private fun handleTtsStop(): Response {
        val ttsEngine = tts
        if (ttsEngine == null || !ttsReady) {
            return errorResponse("TTS engine not ready", Response.Status.SERVICE_UNAVAILABLE)
        }

        val wasSpeaking = ttsEngine.isSpeaking
        ttsEngine.stop()

        val json = JSONObject().apply {
            put("success", true)
            put("was_speaking", wasSpeaking)
        }
        return jsonResponse(json)
    }

    /** GET /tts/engines — List all available TTS engines */
    private fun handleTtsEngines(): Response {
        val ttsEngine = tts
        if (ttsEngine == null || !ttsReady) {
            return errorResponse("TTS engine not ready", Response.Status.SERVICE_UNAVAILABLE)
        }

        val engines = JSONArray()
        for (engine in ttsEngine.engines) {
            engines.put(JSONObject().apply {
                put("name", engine.name)
                put("label", engine.label)
                put("icon", engine.icon)
            })
        }

        val json = JSONObject().apply {
            put("engines", engines)
            put("default_engine", ttsEngine.defaultEngine)
            put("count", engines.length())
        }
        return jsonResponse(json)
    }

    /** POST /tts/engine — Set the active TTS engine. Body: {"engine": "com.google.android.tts"} */
    private fun handleTtsSetEngine(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val enginePackage = data.optString("engine", "")
        if (enginePackage.isEmpty()) {
            return errorResponse("Missing 'engine' field", Response.Status.BAD_REQUEST)
        }

        val currentEngines = tts?.engines?.map { it.name } ?: emptyList()
        if (enginePackage !in currentEngines) {
            val json = JSONObject().apply {
                put("error", "Engine not found: $enginePackage")
                put("available_engines", JSONArray(currentEngines))
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, json.toString())
        }

        tts?.stop()
        tts?.shutdown()
        ttsReady = false

        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }, enginePackage)

        val json = JSONObject().apply {
            put("success", true)
            put("engine", enginePackage)
            put("note", "Engine initializing — TTS will be available in ~1 second")
        }
        return jsonResponse(json)
    }

    /** GET /tts/voices — List available voices for the current engine */
    private fun handleTtsVoices(): Response {
        val ttsEngine = tts
        if (ttsEngine == null || !ttsReady) {
            return errorResponse("TTS engine not ready", Response.Status.SERVICE_UNAVAILABLE)
        }

        val voices = JSONArray()
        try {
            ttsEngine.voices?.forEach { voice ->
                voices.put(JSONObject().apply {
                    put("name", voice.name)
                    put("locale", voice.locale.toLanguageTag())
                    put("quality", voice.quality)
                    put("latency", voice.latency)
                    put("requires_network", voice.isNetworkConnectionRequired)
                    put("features", JSONArray(voice.features.toList()))
                })
            }
        } catch (_: Exception) { }

        val currentVoice = try {
            ttsEngine.voice?.let { v ->
                JSONObject().apply {
                    put("name", v.name)
                    put("locale", v.locale.toLanguageTag())
                }
            }
        } catch (_: Exception) { null }

        val json = JSONObject().apply {
            put("voices", voices)
            put("current_voice", currentVoice ?: JSONObject.NULL)
            put("current_engine", ttsEngine.defaultEngine)
            put("count", voices.length())
        }
        return jsonResponse(json)
    }

    /** POST /tts/synthesize — Synthesize text to audio file. Body: {"text": "...", "output_path": "/path/to/file.wav"} */
    private fun handleTtsSynthesize(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse("Missing 'text' field", Response.Status.BAD_REQUEST)
        }
        val outputPath = data.optString("output_path", "")
        if (outputPath.isEmpty()) {
            return errorResponse("Missing 'output_path' field", Response.Status.BAD_REQUEST)
        }

        val ttsEngine = tts
        if (ttsEngine == null || !ttsReady) {
            return errorResponse("TTS engine not ready", Response.Status.SERVICE_UNAVAILABLE)
        }

        val locale = data.optString("locale", "en-US")
        val loc = Locale.forLanguageTag(locale.replace("_", "-"))
        val langResult = ttsEngine.setLanguage(loc)
        val localeApplied = when (langResult) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                ttsEngine.language = Locale.US
                "en-US (fallback)"
            }
            else -> locale
        }

        val pitch = data.optDouble("pitch", 1.0).toFloat()
        val speed = data.optDouble("speed", 1.0).toFloat()
        ttsEngine.setPitch(pitch.coerceIn(0.1f, 4.0f))
        ttsEngine.setSpeechRate(speed.coerceIn(0.1f, 4.0f))

        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val utteranceId = "synth_${System.currentTimeMillis()}"
        val latch = CountDownLatch(1)
        var synthSuccess = false

        ttsEngine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) { synthSuccess = true; latch.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) { synthSuccess = false; latch.countDown() }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) { synthSuccess = false; latch.countDown() }
            }
        })

        val result = ttsEngine.synthesizeToFile(text, Bundle(), outputFile, utteranceId)

        if (result != TextToSpeech.SUCCESS) {
            return errorResponse("synthesizeToFile returned error code: $result")
        }

        val completed = latch.await(30, TimeUnit.SECONDS)

        return if (completed && synthSuccess && outputFile.exists()) {
            val json = JSONObject().apply {
                put("success", true)
                put("output_path", outputPath)
                put("file_size_bytes", outputFile.length())
                put("text", text)
                put("locale", localeApplied)
                put("utterance_id", utteranceId)
            }
            jsonResponse(json)
        } else {
            errorResponse("Synthesis failed or timed out")
        }
    }

    // =========================================================================
    // Connectivity Toggles (Batch 7) — Converted from Ktor to NanoHTTPD
    // =========================================================================

    /** POST /connectivity/wifi — Toggle WiFi on/off. Body: {"enabled": true} */
    @Suppress("DEPRECATION")
    private fun handleConnectivityWifiToggle(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' field (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        var success = false
        var method = ""

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            success = wifiManager.setWifiEnabled(enabled)
            method = "WifiManager.setWifiEnabled"
        } else {
            try {
                val state = if (enabled) "enable" else "disable"
                val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", state))
                val exitCode = process.waitFor()
                success = exitCode == 0
                method = "svc wifi $state"
            } catch (e: Exception) {
                success = false
                method = "svc (failed: ${e.message})"
            }

            if (!success) {
                val json = JSONObject().apply {
                    put("success", false)
                    put("method", method)
                    put("current_enabled", wifiManager.isWifiEnabled)
                    put("settings_panel", "android.settings.panel.action.WIFI")
                    put("note", "WiFi toggle requires Settings panel on Android 10+. Use 'adb shell svc wifi enable/disable' from PC, or grant CHANGE_WIFI_STATE.")
                }
                return jsonResponse(json)
            }
        }

        val json = JSONObject().apply {
            put("success", success)
            put("enabled", wifiManager.isWifiEnabled)
            put("requested", enabled)
            put("method", method)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/wifi — Get WiFi enabled/disabled state (compact) */
    private fun handleConnectivityWifiState(): Response {
        val state = when (wifiManager.wifiState) {
            WifiManager.WIFI_STATE_DISABLED -> "disabled"
            WifiManager.WIFI_STATE_DISABLING -> "disabling"
            WifiManager.WIFI_STATE_ENABLED -> "enabled"
            WifiManager.WIFI_STATE_ENABLING -> "enabling"
            else -> "unknown"
        }

        val json = JSONObject().apply {
            put("enabled", wifiManager.isWifiEnabled)
            put("state", state)
        }
        return jsonResponse(json)
    }

    /** POST /connectivity/bluetooth — Toggle Bluetooth on/off. Body: {"enabled": true} */
    @Suppress("DEPRECATION")
    private fun handleConnectivityBluetoothToggle(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' field (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            val json = JSONObject().apply {
                put("success", false)
                put("error", "Bluetooth adapter not available on this device")
            }
            return jsonResponse(json)
        }

        var success = false
        var method = ""

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            success = if (enabled) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            method = if (enabled) "BluetoothAdapter.enable" else "BluetoothAdapter.disable"
        } else {
            try {
                val state = if (enabled) "enable" else "disable"
                val process = Runtime.getRuntime().exec(arrayOf("svc", "bluetooth", state))
                val exitCode = process.waitFor()
                success = exitCode == 0
                method = "svc bluetooth $state"
            } catch (e: Exception) {
                success = false
                method = "svc (failed: ${e.message})"
            }

            if (!success) {
                val json = JSONObject().apply {
                    put("success", false)
                    put("method", method)
                    put("current_enabled", bluetoothAdapter.isEnabled)
                    put("settings_panel", "android.settings.panel.action.BLUETOOTH")
                    put("note", "Bluetooth toggle requires Settings panel on Android 13+. Use 'adb shell svc bluetooth enable/disable' from PC.")
                }
                return jsonResponse(json)
            }
        }

        val json = JSONObject().apply {
            put("success", success)
            put("enabled", bluetoothAdapter.isEnabled)
            put("requested", enabled)
            put("method", method)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/bluetooth — Get Bluetooth state (compact) */
    private fun handleConnectivityBluetoothState(): Response {
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            val json = JSONObject().apply {
                put("available", false)
                put("enabled", false)
            }
            return jsonResponse(json)
        }

        val state = when (bluetoothAdapter.state) {
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
            else -> "unknown"
        }

        val connectedProfiles = mutableListOf<String>()
        if (audioManager.isBluetoothA2dpOn) connectedProfiles.add("a2dp")
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) connectedProfiles.add("sco")

        val json = JSONObject().apply {
            put("available", true)
            put("enabled", bluetoothAdapter.isEnabled)
            put("state", state)
            put("connected_audio_profiles", JSONArray(connectedProfiles))
        }
        return jsonResponse(json)
    }

    /** POST /connectivity/airplane — Toggle airplane mode. Body: {"enabled": true} */
    private fun handleConnectivityAirplaneToggle(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' field (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        val currentValue = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val requestedValue = if (enabled) 1 else 0

        if (currentValue == requestedValue) {
            val json = JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
                put("note", "Already in requested state")
            }
            return jsonResponse(json)
        }

        var success = false
        var method = ""

        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, requestedValue)

            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                putExtra("state", enabled)
            }
            context.sendBroadcast(intent)

            success = true
            method = "Settings.Global + broadcast"
        } catch (e: SecurityException) {
            try {
                val value = if (enabled) "1" else "0"
                val p1 = Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "airplane_mode_on", value))
                p1.waitFor()

                val action = if (enabled) "true" else "false"
                val p2 = Runtime.getRuntime().exec(arrayOf(
                    "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                    "--ez", "state", action
                ))
                p2.waitFor()

                success = p1.exitValue() == 0
                method = "shell settings + am broadcast"
            } catch (shellEx: Exception) {
                success = false
                method = "all methods failed"
            }
        }

        if (!success) {
            val json = JSONObject().apply {
                put("success", false)
                put("method", method)
                put("current_enabled", currentValue == 1)
                put("settings_panel", "android.settings.panel.action.INTERNET_CONNECTIVITY")
                put("note", "Airplane mode toggle failed. Grant WRITE_SECURE_SETTINGS via: adb shell pm grant com.mobilekinetic.agent android.permission.WRITE_SECURE_SETTINGS")
            }
            return jsonResponse(json)
        }

        val newValue = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val json = JSONObject().apply {
            put("success", true)
            put("enabled", newValue == 1)
            put("requested", enabled)
            put("method", method)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/airplane — Get airplane mode state */
    private fun handleConnectivityAirplaneState(): Response {
        val airplaneMode = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) == 1

        val json = JSONObject().apply {
            put("enabled", airplaneMode)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/mobile_data — Get mobile data state */
    private fun handleConnectivityMobileData(): Response {
        return try {
            if (telephonyManager == null) {
                val json = JSONObject().apply {
                    put("available", false)
                    put("enabled", false)
                    put("note", "Telephony not available")
                }
                return jsonResponse(json)
            }

            val mobileDataEnabled = try {
                Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
            } catch (e: Exception) { false }

            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isActiveCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            val networkType = try {
                @Suppress("DEPRECATION")
                when (telephonyManager.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                    TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                    else -> "unknown"
                }
            } catch (e: SecurityException) { "permission_denied" }

            val simState = when (telephonyManager.simState) {
                TelephonyManager.SIM_STATE_READY -> "ready"
                TelephonyManager.SIM_STATE_ABSENT -> "absent"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
                else -> "unknown"
            }

            val dataState = when (telephonyManager.dataState) {
                TelephonyManager.DATA_CONNECTED -> "connected"
                TelephonyManager.DATA_CONNECTING -> "connecting"
                TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                TelephonyManager.DATA_SUSPENDED -> "suspended"
                else -> "unknown"
            }

            val json = JSONObject().apply {
                put("available", true)
                put("mobile_data_enabled", mobileDataEnabled)
                put("active_cellular", isActiveCellular)
                put("data_state", dataState)
                put("network_type", networkType)
                put("sim_state", simState)
                put("operator", telephonyManager.networkOperatorName ?: "unknown")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get mobile data state: ${e.message}")
        }
    }

    /** POST /connectivity/nfc — "Toggle" NFC. Body: {"enabled": true} */
    private fun handleConnectivityNfcToggle(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        if (!data.has("enabled")) {
            return errorResponse("Missing 'enabled' field (boolean)", Response.Status.BAD_REQUEST)
        }
        val enabled = data.getBoolean("enabled")

        val nfc = nfcAdapter
        if (nfc == null) {
            val json = JSONObject().apply {
                put("success", false)
                put("available", false)
                put("error", "NFC not available on this device")
            }
            return jsonResponse(json)
        }

        val currentState = nfc.isEnabled

        if (currentState == enabled) {
            val json = JSONObject().apply {
                put("success", true)
                put("enabled", currentState)
                put("note", "Already in requested state")
            }
            return jsonResponse(json)
        }

        var success = false
        var method = ""

        try {
            val state = if (enabled) "enable" else "disable"
            val process = Runtime.getRuntime().exec(arrayOf("svc", "nfc", state))
            val exitCode = process.waitFor()
            success = exitCode == 0
            method = "svc nfc $state"
        } catch (e: Exception) {
            success = false
        }

        if (!success) {
            try {
                val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                method = "opened NFC settings"
            } catch (e: Exception) {
                method = "all methods failed"
            }

            val json = JSONObject().apply {
                put("success", false)
                put("current_enabled", currentState)
                put("requested", enabled)
                put("method", method)
                put("settings_action", "android.settings.NFC_SETTINGS")
                put("note", "NFC cannot be toggled programmatically. NFC Settings screen has been opened.")
            }
            return jsonResponse(json)
        }

        val json = JSONObject().apply {
            put("success", true)
            put("enabled", nfc.isEnabled)
            put("requested", enabled)
            put("method", method)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/nfc — Get NFC state */
    private fun handleConnectivityNfcState(): Response {
        val nfc = nfcAdapter
        if (nfc == null) {
            val json = JSONObject().apply {
                put("available", false)
                put("enabled", false)
            }
            return jsonResponse(json)
        }

        val json = JSONObject().apply {
            put("available", true)
            put("enabled", nfc.isEnabled)
        }
        return jsonResponse(json)
    }

    /** GET /connectivity/all — Get all connectivity states in one call */
    private fun handleConnectivityAll(): Response {
        return try {
            // WiFi
            val wifiEnabled = wifiManager.isWifiEnabled
            val wifiState = when (wifiManager.wifiState) {
                WifiManager.WIFI_STATE_DISABLED -> "disabled"
                WifiManager.WIFI_STATE_DISABLING -> "disabling"
                WifiManager.WIFI_STATE_ENABLED -> "enabled"
                WifiManager.WIFI_STATE_ENABLING -> "enabling"
                else -> "unknown"
            }

            // Bluetooth
            val btAdapter = bluetoothManager.adapter
            val btAvailable = btAdapter != null
            val btEnabled = btAdapter?.isEnabled ?: false
            val btState = when (btAdapter?.state) {
                BluetoothAdapter.STATE_OFF -> "off"
                BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                BluetoothAdapter.STATE_ON -> "on"
                BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                else -> "unknown"
            }

            // Airplane mode
            val airplaneMode = Settings.Global.getInt(
                context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
            ) == 1

            // Mobile data
            val mobileDataEnabled = try {
                Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
            } catch (e: Exception) { false }

            val dataState = when (telephonyManager.dataState) {
                TelephonyManager.DATA_CONNECTED -> "connected"
                TelephonyManager.DATA_CONNECTING -> "connecting"
                TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                TelephonyManager.DATA_SUSPENDED -> "suspended"
                else -> "unknown"
            }

            // NFC
            val nfc = nfcAdapter
            val nfcAvailable = nfc != null
            val nfcEnabled = nfc?.isEnabled ?: false

            // Active network type
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val activeTransport = when {
                capabilities == null -> "none"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val json = JSONObject().apply {
                put("wifi", JSONObject().apply {
                    put("enabled", wifiEnabled)
                    put("state", wifiState)
                })
                put("bluetooth", JSONObject().apply {
                    put("available", btAvailable)
                    put("enabled", btEnabled)
                    put("state", btState)
                })
                put("airplane_mode", JSONObject().apply {
                    put("enabled", airplaneMode)
                })
                put("mobile_data", JSONObject().apply {
                    put("enabled", mobileDataEnabled)
                    put("data_state", dataState)
                })
                put("nfc", JSONObject().apply {
                    put("available", nfcAvailable)
                    put("enabled", nfcEnabled)
                })
                put("network", JSONObject().apply {
                    put("active_transport", activeTransport)
                    put("has_internet", hasInternet)
                })
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to get connectivity states: ${e.message}")
        }
    }

    // =========================================================================
    // Media Playback Extensions (Batch 8) — Converted from Ktor to NanoHTTPD
    // =========================================================================

    /**
     * Helper: Dispatch a media action via MediaSessionManager or AudioManager fallback.
     * Used by convenience route handlers (/media/play_pause, /media/next, etc.)
     */
    private fun handleMediaAction(action: String): Response {
        return try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

            try {
                val componentName = ComponentName(context, DeviceApiServer::class.java)
                val sessions = mediaSessionManager.getActiveSessions(componentName)

                if (sessions.isEmpty()) {
                    val keyCode = when (action.lowercase()) {
                        "play", "pause", "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                        "fast_forward" -> android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        "rewind" -> android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                        else -> null
                    }

                    if (keyCode != null) {
                        val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
                        val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
                        audioManager.dispatchMediaKeyEvent(downEvent)
                        audioManager.dispatchMediaKeyEvent(upEvent)

                        val json = JSONObject().apply {
                            put("success", true)
                            put("action", action)
                            put("method", "AudioManager.dispatchMediaKeyEvent")
                            put("note", "No active media sessions — used key event fallback")
                        }
                        return jsonResponse(json)
                    } else {
                        return errorResponse("Unknown action: $action", Response.Status.BAD_REQUEST)
                    }
                }

                val targetSession = sessions.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                } ?: sessions.first()

                val transport = targetSession.transportControls

                when (action.lowercase()) {
                    "play" -> transport.play()
                    "pause" -> transport.pause()
                    "play_pause" -> {
                        val state = targetSession.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING) {
                            transport.pause()
                        } else {
                            transport.play()
                        }
                    }
                    "next" -> transport.skipToNext()
                    "previous" -> transport.skipToPrevious()
                    "stop" -> transport.stop()
                    "fast_forward" -> transport.fastForward()
                    "rewind" -> transport.rewind()
                    else -> return errorResponse("Unknown action: $action. Valid: play, pause, play_pause, next, previous, stop, fast_forward, rewind", Response.Status.BAD_REQUEST)
                }

                val json = JSONObject().apply {
                    put("success", true)
                    put("action", action)
                    put("session_package", targetSession.packageName)
                    put("method", "MediaController.transportControls")
                }
                jsonResponse(json)

            } catch (e: SecurityException) {
                errorResponse("Notification listener permission required for media session access", Response.Status.FORBIDDEN)
            }
        } catch (e: Exception) {
            errorResponse("Media action failed: ${e.message}")
        }
    }

    /** GET /media/now_playing — Enhanced now playing with all active sessions */
    private fun handleMediaNowPlaying(): Response {
        return try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

            try {
                val componentName = ComponentName(context, DeviceApiServer::class.java)
                val sessions = mediaSessionManager.getActiveSessions(componentName)

                if (sessions.isEmpty()) {
                    val json = JSONObject().apply {
                        put("playing", false)
                        put("active_sessions", 0)
                        put("sessions", JSONArray())
                    }
                    return jsonResponse(json)
                }

                val sessionList = JSONArray()
                var isPlaying = false

                for (session in sessions) {
                    val metadata = session.metadata
                    val playbackState = session.playbackState

                    val state = when (playbackState?.state) {
                        PlaybackState.STATE_PLAYING -> "playing"
                        PlaybackState.STATE_PAUSED -> "paused"
                        PlaybackState.STATE_STOPPED -> "stopped"
                        PlaybackState.STATE_BUFFERING -> "buffering"
                        PlaybackState.STATE_CONNECTING -> "connecting"
                        PlaybackState.STATE_ERROR -> "error"
                        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
                        PlaybackState.STATE_REWINDING -> "rewinding"
                        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
                        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
                        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_queue"
                        PlaybackState.STATE_NONE -> "none"
                        else -> "unknown"
                    }

                    if (state == "playing") isPlaying = true

                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    val albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    val genre = metadata?.getString(MediaMetadata.METADATA_KEY_GENRE)
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                    val trackNumber = metadata?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: -1L
                    val discNumber = metadata?.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER) ?: -1L
                    val year = metadata?.getLong(MediaMetadata.METADATA_KEY_YEAR) ?: -1L
                    val position = playbackState?.position ?: -1L
                    val speed = playbackState?.playbackSpeed ?: 1.0f
                    val albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

                    val actions = playbackState?.actions ?: 0L
                    val availableActions = JSONArray()
                    if (actions and PlaybackState.ACTION_PLAY != 0L) availableActions.put("play")
                    if (actions and PlaybackState.ACTION_PAUSE != 0L) availableActions.put("pause")
                    if (actions and PlaybackState.ACTION_STOP != 0L) availableActions.put("stop")
                    if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) availableActions.put("next")
                    if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) availableActions.put("previous")
                    if (actions and PlaybackState.ACTION_FAST_FORWARD != 0L) availableActions.put("fast_forward")
                    if (actions and PlaybackState.ACTION_REWIND != 0L) availableActions.put("rewind")
                    if (actions and PlaybackState.ACTION_SEEK_TO != 0L) availableActions.put("seek")

                    sessionList.put(JSONObject().apply {
                        put("package", session.packageName)
                        put("state", state)
                        put("title", title ?: JSONObject.NULL)
                        put("artist", artist ?: JSONObject.NULL)
                        put("album", album ?: JSONObject.NULL)
                        put("album_artist", albumArtist ?: JSONObject.NULL)
                        put("genre", genre ?: JSONObject.NULL)
                        put("duration_ms", duration)
                        put("position_ms", position)
                        put("playback_speed", speed.toDouble())
                        put("track_number", trackNumber)
                        put("disc_number", discNumber)
                        put("year", year)
                        put("album_art_available", albumArt != null)
                        put("available_actions", availableActions)
                    })
                }

                val json = JSONObject().apply {
                    put("playing", isPlaying)
                    put("active_sessions", sessions.size)
                    put("sessions", sessionList)
                }
                jsonResponse(json)

            } catch (e: SecurityException) {
                val json = JSONObject().apply {
                    put("playing", false)
                    put("error", "Notification listener permission required")
                    put("permission", "android.permission.MEDIA_CONTENT_CONTROL")
                }
                jsonResponse(json)
            }
        } catch (e: Exception) {
            errorResponse("Failed to get now playing info: ${e.message}")
        }
    }

    /** POST /media/play_file — Play a local audio file. Body: {"path": "/path/to/file.mp3", "loop": false} */
    private fun handleMediaPlayFile(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val path = data.optString("path", "")
        if (path.isEmpty()) {
            return errorResponse("Missing 'path' field", Response.Status.BAD_REQUEST)
        }

        val file = File(path)
        if (!file.exists()) {
            return errorResponse("File not found: $path", Response.Status.NOT_FOUND)
        }

        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.reset()
            it.release()
        }

        val looping = data.optBoolean("loop", false)

        return try {
            val latch = CountDownLatch(1)
            var prepareSuccess = false
            var duration = 0

            val player = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                isLooping = looping
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener { mp ->
                    mp.start()
                    duration = mp.duration
                    prepareSuccess = true
                    latch.countDown()
                }
                setOnErrorListener { _, _, _ ->
                    prepareSuccess = false
                    latch.countDown()
                    false
                }
                prepareAsync()
            }

            val completed = latch.await(10, TimeUnit.SECONDS)

            if (!completed || !prepareSuccess) {
                player.release()
                return errorResponse("Failed to prepare media player for: $path")
            }

            mediaPlayer = player

            val json = JSONObject().apply {
                put("success", true)
                put("path", path)
                put("duration_ms", duration)
                put("looping", looping)
                put("file_size_bytes", file.length())
            }
            jsonResponse(json)
        } catch (e: Exception) {
            errorResponse("Failed to play file: ${e.message}")
        }
    }

    // =========================================================================
    // LuxTTS Endpoints (PC-based TTS server)
    // =========================================================================

    /** POST /luxtts/speak - Speak text via LuxTTS PC server (REST, returns streaming audio) */
    private fun handleLuxTtsSpeak(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse("Missing 'text' field", Response.Status.BAD_REQUEST)
        }
        if (text.length < LUXTTS_MIN_TEXT_LENGTH) {
            return errorResponse(
                "Text too short (${text.length} chars). Minimum $LUXTTS_MIN_TEXT_LENGTH characters required.",
                Response.Status.BAD_REQUEST
            )
        }

        val voice = data.optString("voice", "af_heart")
        val speed = data.optDouble("speed", 1.0)

        return try {
            val url = URL("$LUXTTS_BASE_URL/tts")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 60000

            val payload = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("speed", speed)
            }

            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                connection.disconnect()
                return errorResponse("LuxTTS returned HTTP $responseCode: $errorBody")
            }

            val audioBytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()

            val contentType = connection.getHeaderField("Content-Type") ?: "audio/mpeg"
            val inputStream = java.io.ByteArrayInputStream(audioBytes)
            newFixedLengthResponse(
                Response.Status.OK,
                contentType,
                inputStream,
                audioBytes.size.toLong()
            )
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "LuxTTS server unreachable: ${e.message}", e)
            errorResponse("LuxTTS server unreachable at $LUXTTS_BASE_URL: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "LuxTTS request timed out: ${e.message}", e)
            errorResponse("LuxTTS request timed out: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "LuxTTS speak error: ${e.message}", e)
            errorResponse("LuxTTS speak failed: ${e.message}")
        }
    }

    /** GET /luxtts/voices - List available LuxTTS voices */
    private fun handleLuxTtsVoices(): Response {
        return try {
            val url = URL("$LUXTTS_BASE_URL/voices")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                connection.disconnect()
                return errorResponse("LuxTTS /voices returned HTTP $responseCode: $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseBody)
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "LuxTTS server unreachable: ${e.message}", e)
            errorResponse("LuxTTS server unreachable at $LUXTTS_BASE_URL: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "LuxTTS voices error: ${e.message}", e)
            errorResponse("LuxTTS voices failed: ${e.message}")
        }
    }

    /** GET /luxtts/health - Check LuxTTS server health */
    private fun handleLuxTtsHealth(): Response {
        return try {
            val url = URL("$LUXTTS_BASE_URL/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                connection.disconnect()
                return jsonResponse(JSONObject().apply {
                    put("reachable", false)
                    put("status_code", responseCode)
                    put("error", errorBody)
                })
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val upstream = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                JSONObject().put("raw", responseBody)
            }

            val json = JSONObject().apply {
                put("reachable", true)
                put("status_code", 200)
                put("luxtts", upstream)
            }
            jsonResponse(json)
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "LuxTTS health check failed: ${e.message}")
            jsonResponse(JSONObject().apply {
                put("reachable", false)
                put("error", "Server unreachable at $LUXTTS_BASE_URL: ${e.message}")
            })
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "LuxTTS health check timed out: ${e.message}")
            jsonResponse(JSONObject().apply {
                put("reachable", false)
                put("error", "Connection timed out: ${e.message}")
            })
        } catch (e: Exception) {
            Log.e(TAG, "LuxTTS health error: ${e.message}", e)
            errorResponse("LuxTTS health check failed: ${e.message}")
        }
    }

    /** POST /luxtts/speak_stream - Speak via LuxTTS with streaming support */
    private fun handleLuxTtsSpeakStream(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return errorResponse("Missing request body")
        val data = JSONObject(body)

        val text = data.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse("Missing 'text' field", Response.Status.BAD_REQUEST)
        }
        if (text.length < LUXTTS_MIN_TEXT_LENGTH) {
            return errorResponse(
                "Text too short (${text.length} chars). Minimum $LUXTTS_MIN_TEXT_LENGTH characters required.",
                Response.Status.BAD_REQUEST
            )
        }

        val voice = data.optString("voice", "af_heart")
        val speed = data.optDouble("speed", 1.0).toFloat()

        val latch = CountDownLatch(1)
        var resultJson = JSONObject()
        var speakError: String? = null

        try {
            scope.launch {
                try {
                    val url = URL("$LUXTTS_BASE_URL/tts")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 120000

                    val payload = JSONObject().apply {
                        put("text", text)
                        put("voice", voice)
                        put("speed", speed.toDouble())
                    }

                    connection.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val audioBytes = connection.inputStream.use { it.readBytes() }
                        connection.disconnect()

                        val tempFile = File(context.cacheDir, "luxtts_stream_${System.currentTimeMillis()}.mp3")
                        tempFile.writeBytes(audioBytes)

                        resultJson = JSONObject().apply {
                            put("success", true)
                            put("message", "Audio generated via LuxTTS streaming")
                            put("audio_file", tempFile.absolutePath)
                            put("audio_size_bytes", audioBytes.size)
                            put("voice", voice)
                            put("speed", speed.toDouble())
                            put("text_length", text.length)
                            put("wss_note", "For true WebSocket streaming with ExoPlayer, use KokoroTtsService directly")
                        }
                    } else {
                        val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                        connection.disconnect()
                        speakError = "LuxTTS returned HTTP $responseCode: $errorBody"
                    }
                } catch (e: Exception) {
                    speakError = "LuxTTS stream error: ${e.message}"
                    Log.e(TAG, "LuxTTS speak_stream error: ${e.message}", e)
                } finally {
                    latch.countDown()
                }
            }

            val completed = latch.await(120, TimeUnit.SECONDS)
            if (!completed) {
                return errorResponse("LuxTTS streaming request timed out after 120s")
            }

            if (speakError != null) {
                return errorResponse(speakError!!)
            }

            return jsonResponse(resultJson)
        } catch (e: Exception) {
            Log.e(TAG, "LuxTTS speak_stream error: ${e.message}", e)
            return errorResponse("LuxTTS speak_stream failed: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            // Unregister all network callbacks
            networkCallbacks.values.forEach { callback ->
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering network callback", e)
                }
            }
            networkCallbacks.clear()
            callbackEvents.clear()

            stop()
            tts?.shutdown()
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            Log.i(TAG, "DeviceApiServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DeviceApiServer", e)
        }
    }
}
