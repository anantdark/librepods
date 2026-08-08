/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.isHeadTrackingData
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.AudioOwnershipCoordinator
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"

/** Refresh secondary AACP before AirPods firmware drops it (~35–45s). */
private const val SECONDARY_PROACTIVE_REFRESH_MS = 28_000L
/**
 * Full Hijackv2+ShowUI while music plays must be rare — flooding it every few seconds
 * drops ACL/A2DP (~40–50s into playback). Light OWNS+media keep-alive is enough once owning.
 */
private const val PLAYING_HIJACK_KEEPALIVE_MIN_MS = 20_000L

private val AIRPODS_PNP_UUID: ParcelUuid =
    ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

/** Addresses that got ACL_CONNECTED and are waiting on SDP UUID results. */
private val pendingAirPodsAclAddresses: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

/** Classic BT devices only — name must contain "AirPods" (e.g. "Anant's AirPods"). */
@SuppressLint("MissingPermission")
private fun BluetoothDevice.isAirPodsByName(): Boolean {
    val n = name ?: return false
    return n.contains("AirPods", ignoreCase = true)
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.hasAirPodsUuid(): Boolean =
    uuids?.contains(AIRPODS_PNP_UUID) == true

object ServiceManager {
    private var service: AirPodsService? = null

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""
    var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false
    private var otherDeviceTookOver = false
    private val socketConnecting = AtomicBoolean(false)
    @Volatile private var lastSocketConnectAttemptMs = 0L
    /** True while adapter is off — BLE/media/telephony parked; FGS only waits for BT on. */
    @Volatile var bluetoothOffStandby: Boolean = false
    /** Set when we enabled BT for an incoming call — takeOver after radio is up. */
    @Volatile private var pendingCallTakeOverAfterBtEnable: Boolean = false
    @Volatile private var enablingBluetoothForCall: Boolean = false
        private set
    /** BT was off and we turned it on for this call — turn it back off when the call ends. */
    @Volatile private var disableBluetoothAfterCall: Boolean = false
    /**
     * Sticky for the whole RINGING→IDLE cycle. OFFHOOK used to re-enter takeOver while BT
     * was already on and clear [disableBluetoothAfterCall], so BT stayed on after hang-up.
     */
    @Volatile private var btWasOffBeforeThisCall: Boolean = false
    /** Keeps retrying L2CAP/takeOver while the phone is ringing until AirPods connect. */
    @Volatile private var incomingCallConnectJob: Job? = null
    /** Show TAKING_OVER island only once per RINGING→IDLE cycle (retry used to spam it). */
    @Volatile private var callTakeOverIslandShown: Boolean = false
    /** True once Hijackv2 was actually sent to a peer MAC this call (Mac should pause). */
    @Volatile private var callHijackSucceeded: Boolean = false
    /** Debounce staggered call-hijack launches (audio-source spam is ~15Hz). */
    @Volatile private var lastCallHijackLaunchMs: Long = 0L
    /** Periodic AACP notification refresh so Mac→app listening-mode changes land. */
    @Volatile private var listeningModeSyncJob: Job? = null
    /** Keeps AACP primary while linked (idle or playing). AirPods drop secondary links ~45s. */
    @Volatile private var aacpMediaKeepAliveJob: Job? = null
    /** Silent L2CAP re-open so idle/Mac-media peers stay linked like Continuity. */
    @Volatile private var aacpRelinkJob: Job? = null
    /** False only for intentional user/service teardown — unexpected drops should relink. */
    @Volatile private var aacpStayLinkedDesired: Boolean = true
    /**
     * When Mac owns audio we keep OWNS=0 (don't steal). Firmware then kills secondary AACP
     * ~35–45s — refresh the socket just before that so the user never sees a drop.
     */
    @Volatile private var aacpSecondarySinceMs: Long = 0L
    @Volatile private var lastNotificationRequestMs: Long = 0L
    @Volatile private var lastAudioSourceLogKey: String = ""
    @Volatile private var lastMediaKeepAliveMs: Long = 0L
    /** Last full Hijackv2 keep-alive while playing (debounced separately from soft OWNS). */
    @Volatile private var lastPlayingHijackKeepAliveMs: Long = 0L
    /** Battery/ear-detection used to call connectAudio every few seconds and kill L2CAP. */
    @Volatile private var lastConnectAudioAttemptMs: Long = 0L
    /** Head-gesture answer/reject deferred until AACP/ownership is ready after takeOver. */
    @Volatile private var pendingHeadGesturesForCall: Boolean = false
    /** Debounce hard call takeOver so Hijackv2 storms don't kill the HT motion stream. */
    @Volatile private var lastCallTakeOverMs: Long = 0L
    /** False until AirPods report an audio-source packet — avoid OWNS/A2DP before we know Mac owns media. */
    @Volatile private var audioSourcePacketSeen: Boolean = false
    /** Phone battery receiver is registered (unregistered in BT-off standby to avoid wakeups). */
    @Volatile private var phoneBatteryReceiverRegistered: Boolean = false
    /**
     * Continuity-style ownership FSM. Prefs stay on existing toggles
     * (takeover_when_media_start, takeover_when_ringing_call, …).
     */
    lateinit var audioOwnership: AudioOwnershipCoordinator
        private set

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        var takeoverWhenDisconnected: Boolean = true,
        var takeoverWhenIdle: Boolean = true,
        var takeoverWhenMusic: Boolean = false,
        var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        var takeoverWhenRingingCall: Boolean = true,
        var takeoverWhenMediaStart: Boolean = true,
        /** Root: turn Bluetooth on when an incoming call arrives while BT is off. */
        var enableBtOnIncomingCall: Boolean = false,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    private var handleIncomingCallOnceConnected = false

    lateinit var bleManager: BLEManager

    companion object {
        init {
            System.loadLibrary("bluetooth_socket")
        }
    }

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?
        ) {
            // BLE "Disconnected" is normal when pods sit in the case — do NOT auto L2CAP
            // connect (that caused connect/fail/disconnect storms). Only act if in use.
            if (device.connectionState == "Disconnected" &&
                BluetoothConnectionManager.aacpSocket?.isConnected != true &&
                !bluetoothOffStandby &&
                (device.isLeftInEar || device.isRightInEar || device.lidOpen)
            ) {
                Log.d(TAG, "BLE Disconnected while in-ear/lid-open — attempting L2CAP")
                CoroutineScope(Dispatchers.IO).launch {
                    val bluetoothManager = getSystemService(BluetoothManager::class.java)
                    val bluetoothAdapter = bluetoothManager.adapter
                    val bluetoothDevice = bluetoothAdapter.getRemoteDevice(
                        sharedPreferences.getString(
                            "mac_address", ""
                        ) ?: ""
                    )
                    connectToSocket(bluetoothAdapter, bluetoothDevice)
                }
            }
            Log.d(TAG, "Device status changed")
            applyBleBatteryToUi(notifyNearby = true)
        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
            applyBleBatteryToUi(notifyNearby = true)
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                showPopup(
                    this@AirPodsService,
                    getSharedPreferences("settings", MODE_PRIVATE).getString("name", "AirPods Pro")
                        ?: "AirPods"
                )
                applyBleBatteryToUi(notifyNearby = true)
            } else {
                Log.d(TAG, "Lid closed")
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            // Keep nearby UI subscribed; BATTERY_DATA alone is easy to miss after RPA churn.
            applyBleBatteryToUi(notifyNearby = true)
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            batteryNotification.clear()
            sendBatteryBroadcast()
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_GONE).apply {
                setPackage(packageName)
            })
            updateNotificationContent(false)
        }
    }

    /** Push BLE proximity battery into widgets/notification/UI (Mac-style, no L2CAP needed). */
    private fun applyBleBatteryToUi(notifyNearby: Boolean) {
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        val status = bleManager.getMostRecentStatus() ?: return
        batteryNotification.setBatteryDirect(
            leftLevel = status.leftBattery,
            leftCharging = status.isLeftCharging,
            rightLevel = status.rightBattery,
            rightCharging = status.isRightCharging,
            caseLevel = status.caseBattery,
            caseCharging = status.isCaseCharging
        )
        updateBattery()
        if (notifyNearby) {
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_NEARBY).apply {
                setPackage(packageName)
            })
        }
    }

    fun isBluetoothSocketExempted(): Boolean {
        return try {
            BluetoothSocket::class.java.declaredConstructors // will throw if still blocked
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        // Must run before any blocking work (e.g. su). startForegroundService() ANRs otherwise.
        startForegroundNotification()

        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(
            sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet()
        )
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()

        attManager = ATTManagerv2()

        // Defaults before the listener so first-run stem keys don't spam setupStemActions.
        ensureDefaultPreferences()
        initializeConfig()
        sharedPreferences.getInt("last_listening_mode", 0).takeIf { it in 1..4 }?.let { mode ->
            ancNotification.setStatus(byteArrayOf(mode.toByte()))
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        localMac = config.selfMacAddress
        if (localMac.isEmpty()) {
            resolveLocalMacAddressAsync()
        }

        ServiceManager.setService(this)
        initAudioOwnershipCoordinator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        externalBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                                mode
                            )
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val configByte = sharedPreferences.getInt("long_press_byte", 0b0111)
                        val allowOffModeValue =
                            aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
                        val allowOffMode =
                            allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
                        val nextMode = getNextMode(currentMode = currentMode, configByte = configByte, allowOffMode)

                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                            nextMode
                        )
                        Log.d(
                            TAG,
                            "Cycling ANC mode from $currentMode to $nextMode"
                        )
                    }
                } else  if (intent?.action == "me.kavishdevar.librepods.CONVO_DETECT") {
                    if (intent.hasExtra("enabled")) {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                            enabled
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalBroadcastReceiver, externalBroadcastFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                externalBroadcastReceiver, externalBroadcastFilter
            )
        }
        val audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager, this@AirPodsService.getSharedPreferences(
                "settings", MODE_PRIVATE
            )
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        isRinging = true
                        lastCallTakeOverMs = 0L
                        callHijackSucceeded = false
                        callTakeOverIslandShown = false
                        lastCallHijackLaunchMs = 0L
                        // Ownership coordinator: HFP first, then existing call takeOver path.
                        audioOwnership.onCallState(TelephonyManager.CALL_STATE_RINGING)
                        enableStemCaptureForIncomingCall()
                        // Starts now if AACP is up; otherwise deferred until takeOver / connect.
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        isRinging = false
                        isInCall = true
                        // If BT enable for RINGING is in flight, takeOver is already pending.
                        if (!pendingCallTakeOverAfterBtEnable && !enablingBluetoothForCall) {
                            audioOwnership.onCallState(TelephonyManager.CALL_STATE_OFFHOOK)
                        } else {
                            prioritizeCallAudioNow()
                        }
                        pendingHeadGesturesForCall = false
                        handleIncomingCallOnceConnected = false
                        // Drop ring-time stem customization only; keep ownership until call ends.
                        restoreStemConfigAfterCall(releaseOwnership = false)
                        // In-call: head gestures stay dormant (no HT packets / detector loop).
                        stopHeadGesturesForCall()
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        isRinging = false
                        isInCall = false
                        pendingCallTakeOverAfterBtEnable = false
                        enablingBluetoothForCall = false
                        pendingHeadGesturesForCall = false
                        handleIncomingCallOnceConnected = false
                        callTakeOverIslandShown = false
                        callHijackSucceeded = false
                        stopIncomingCallConnectRetry()
                        audioOwnership.onCallState(TelephonyManager.CALL_STATE_IDLE)
                        // Give stem / connection ownership back to Mac (or prior owner).
                        restoreStemConfigAfterCall(releaseOwnership = true)
                        stopHeadGesturesForCall()
                        maybeDisableBluetoothAfterCall()
                    }
                }
            }
        }
        // Telephony: registered while BT is on, or while BT-off + enable-BT-on-call (event-driven only).
        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            registerPhoneBatteryReceiverIfNeeded()
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction("android.bluetooth.device.action.UUID")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    val detectedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }
                    if (detectedDevice == null || !detectedDevice.isAirPodsByName()) {
                        Log.d(TAG, "Ignoring connection detect for non-AirPods: ${detectedDevice?.name}")
                        return
                    }
                    device = detectedDevice

                    if (config.deviceName == "AirPods" && device?.name != null) {
                        config.deviceName = device?.name ?: "AirPods"
                        sharedPreferences.edit { putString("name", config.deviceName) }
                    }

//                    Log.d("AirPodsCrossDevice", CrossDevice.isAvailable.toString())
//                    if (!CrossDevice.isAvailable) {
                    Log.d(TAG, "${config.deviceName} connected")
                    CoroutineScope(Dispatchers.IO).launch {
                        val bluetoothManager = getSystemService(BluetoothManager::class.java)
                        connectToSocket(bluetoothManager.adapter, device!!)
                    }
                    Log.d(TAG, "Setting metadata")
                    setMetadatas(device!!)
//                    isConnectedLocally = true
                    macAddress = device!!.address
                    sharedPreferences.edit {
                        putString("mac_address", macAddress)
                    }
//                    }

                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    // Transient AACP drops auto-relink and keep device — don't wipe state.
                    if (aacpStayLinkedDesired && aacpRelinkJob?.isActive == true) {
                        Log.d(TAG, "Ignoring DISCONNECTED broadcast — AACP silent relink in progress")
                        return
                    }
                    if (aacpStayLinkedDesired && !bluetoothOffStandby && macAddress.isNotEmpty()) {
                        Log.d(TAG, "AACP DISCONNECTED — scheduling silent relink (stay linked like Mac)")
                        scheduleAacpSilentRelink("broadcast")
                        return
                    }
                    device = null
//                    isConnectedLocally = false
                    popupShown = false
                    updateNotificationContent(false)
                    aacpManager.disconnected()
                    BluetoothConnectionManager.aacpSocket = null
                    BluetoothConnectionManager.attSocket = null
                    // Resume adaptive BLE: low-power until pods reappear nearby.
                    bleManager.setAacpConnected(false)
                }
            }
        }
        val showIslandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(
                        this@AirPodsService,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            )
                    )
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                showIslandReceiver, showIslandIntentFilter
            )
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                connectionReceiver, deviceIntentFilter
            )
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.bondedDevices.forEach { device ->
                if (!device.isAirPodsByName()) return@forEach
                if (!device.hasAirPodsUuid()) {
                    device.fetchUuidsWithSdp()
                    return@forEach
                }
                // Only if *this* AirPods device is on A2DP — not Sony/other headphones.
                bluetoothAdapter.getProfileProxy(
                    this, object : BluetoothProfile.ServiceListener {
                        @SuppressLint("NewApi")
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            if (profile == BluetoothProfile.A2DP) {
                                val airPodsA2dpConnected = proxy.connectedDevices.any {
                                    it.address.equals(device.address, ignoreCase = true)
                                }
                                if (airPodsA2dpConnected) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        connectToSocket(bluetoothAdapter, device)
                                    }
                                    setMetadatas(device)
                                    macAddress = device.address
                                    sharedPreferences.edit {
                                        putString("mac_address", macAddress)
                                    }
                                    // Do not broadcast AIRPODS_CONNECTED here — that alias is
                                    // treated as L2CAP-up by the UI. connectToSocket emits it.
                                } else {
                                    Log.d(
                                        TAG,
                                        "Bonded AirPods present but not on A2DP " +
                                            "(other audio device connected); skip L2CAP"
                                    )
                                }
                            }
                            bluetoothAdapter.closeProfileProxy(profile, proxy)
                        }

                        override fun onServiceDisconnected(profile: Int) {}
                    }, BluetoothProfile.A2DP
                )
            }

            MediaController.setMonitoringEnabled(true)
            if (checkSelfPermission("android.permission.READ_PHONE_STATE") ==
                PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
            }
            // Standby starts in LOW_POWER — escalates only when AirPods appear nearby / lid opens.
            CoroutineScope(Dispatchers.IO).launch {
                bleManager.startScanning(BLEManager.ScanPowerMode.LOW_POWER)
            }
        } else {
            Log.d(TAG, "Bluetooth off at service create — entering deep standby")
            enterBluetoothOffStandby()
            // Keep call listener alive when root enable-BT-on-call is on.
            syncTelephonyForBtOffStandby()
        }
    }

    /**
     * Bluetooth radio off: stop LE scan, drop presence, and unregister media/telephony
     * callbacks. Service stays alive only to catch the next adapter-on broadcast.
     * Telephony stays registered when [config.enableBtOnIncomingCall] so we can wake BT on ring
     * (system call-state callbacks only — no polling / BLE).
     */
    fun enterBluetoothOffStandby() {
        if (bluetoothOffStandby) {
            bleManager.onBluetoothDisabled()
            return
        }
        bluetoothOffStandby = true
        aacpStayLinkedDesired = false
        stopAacpSilentRelink()
        stopAacpMediaKeepAlive()
        stopListeningModeSyncLoop()
        Log.i(TAG, "Entering Bluetooth-off standby (near-zero background work)")

        try {
            bleManager.onBluetoothDisabled()
        } catch (e: Exception) {
            Log.w(TAG, "Failed parking BLE: ${e.message}")
        }

        MediaController.setMonitoringEnabled(false)
        // ACTION_BATTERY_CHANGED wakes often — drop it while BT is off.
        unregisterPhoneBatteryReceiverIfNeeded()
        stopHeadGesturesForCall()

        if (!shouldKeepTelephonyWhileBtOff()) {
            unregisterTelephonyCallbackSafe()
        }

        try {
            BluetoothConnectionManager.aacpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            BluetoothConnectionManager.attSocket?.close()
        } catch (_: Exception) {
        }
        BluetoothConnectionManager.aacpSocket = null
        BluetoothConnectionManager.attSocket = null
        device = null
        popupShown = false
        batteryNotification.clear()
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_GONE).apply {
            setPackage(packageName)
        })
        // Drop status/battery notification — keep only the silent FGS notif (id 1).
        try {
            getSystemService(NotificationManager::class.java).cancel(2)
        } catch (_: Exception) {
        }
        lastStatusNotificationText = null
    }

    /** Bluetooth radio on: resume low-power LE scan and event listeners. */
    fun exitBluetoothOffStandby() {
        bleManager.onBluetoothEnabled()
        if (!bluetoothOffStandby) {
            if (!bleManager.isScanning()) {
                CoroutineScope(Dispatchers.IO).launch {
                    bleManager.startScanning(BLEManager.ScanPowerMode.LOW_POWER)
                }
            }
            maybeTakeOverAfterBtEnabledForCall()
            return
        }
        bluetoothOffStandby = false
        aacpStayLinkedDesired = true
        Log.i(TAG, "Exiting Bluetooth-off standby — resuming low-power scan")

        MediaController.setMonitoringEnabled(true)
        registerTelephonyCallbackSafe()
        registerPhoneBatteryReceiverIfNeeded()

        CoroutineScope(Dispatchers.IO).launch {
            bleManager.startScanning(BLEManager.ScanPowerMode.LOW_POWER)
        }
        maybeTakeOverAfterBtEnabledForCall()
    }

    private fun shouldKeepTelephonyWhileBtOff(): Boolean {
        return config.enableBtOnIncomingCall && config.takeoverWhenRingingCall
    }

    private fun syncTelephonyForBtOffStandby() {
        if (!bluetoothOffStandby) return
        if (shouldKeepTelephonyWhileBtOff()) {
            registerTelephonyCallbackSafe()
        } else {
            unregisterTelephonyCallbackSafe()
        }
    }

    private fun registerTelephonyCallbackSafe() {
        if (!this::telephonyManager.isInitialized || !this::phoneStateListener.isInitialized) return
        try {
            if (checkSelfPermission("android.permission.READ_PHONE_STATE") ==
                PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering telephony: ${e.message}")
        }
    }

    private fun unregisterTelephonyCallbackSafe() {
        if (!this::telephonyManager.isInitialized || !this::phoneStateListener.isInitialized) return
        try {
            if (checkSelfPermission("android.permission.READ_PHONE_STATE") ==
                PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.unregisterTelephonyCallback(phoneStateListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed unregistering telephony: ${e.message}")
        }
    }

    private fun registerPhoneBatteryReceiverIfNeeded() {
        if (!config.showPhoneBatteryInWidget || phoneBatteryReceiverRegistered) return
        if (bluetoothOffStandby) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(BatteryChangedIntentReceiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(BatteryChangedIntentReceiver, filter)
            }
            phoneBatteryReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering battery receiver: ${e.message}")
        }
    }

    private fun unregisterPhoneBatteryReceiverIfNeeded() {
        if (!phoneBatteryReceiverRegistered) return
        try {
            unregisterReceiver(BatteryChangedIntentReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed unregistering battery receiver: ${e.message}")
        }
        phoneBatteryReceiverRegistered = false
    }

    private fun enableBluetoothViaRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "svc bluetooth enable"))
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "svc bluetooth enable timed out")
                false
            } else {
                val code = process.exitValue()
                Log.d(TAG, "svc bluetooth enable exited $code")
                code == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Bluetooth via root", e)
            false
        }
    }

    private fun disableBluetoothViaRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "svc bluetooth disable"))
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "svc bluetooth disable timed out")
                false
            } else {
                val code = process.exitValue()
                Log.d(TAG, "svc bluetooth disable exited $code")
                code == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Bluetooth via root", e)
            false
        }
    }

    /** If we enabled BT only for this call, turn it back off 5s after hang-up. */
    private fun maybeDisableBluetoothAfterCall() {
        val shouldDisable = disableBluetoothAfterCall || btWasOffBeforeThisCall
        disableBluetoothAfterCall = false
        btWasOffBeforeThisCall = false
        if (!shouldDisable) {
            Log.d(TAG, "Call ended — leaving Bluetooth on (was already on before the call)")
            return
        }
        Log.i(TAG, "Call ended — will disable Bluetooth in 5s (was off before this call)")
        CoroutineScope(Dispatchers.IO).launch {
            // Let ownership-release / stem-restore packets go out; give audio a moment to settle.
            delay(5_000)
            if (isRinging || isInCall) {
                Log.d(TAG, "Skipping BT disable — another call started")
                return@launch
            }
            val ok = disableBluetoothViaRoot()
            if (ok) {
                // Park immediately; STATE_OFF will also call enterBluetoothOffStandby.
                enterBluetoothOffStandby()
            } else {
                Log.w(TAG, "Failed to disable Bluetooth after call — will retry once")
                delay(2_000)
                if (!isRinging && !isInCall) {
                    if (disableBluetoothViaRoot()) {
                        enterBluetoothOffStandby()
                    }
                }
            }
        }
    }

    /**
     * Fast path for in-call audio: kick HFP/SCO (+ OWNS/hijack if AACP is up) immediately.
     * Waiting on the takeOver retry / gesture / debounce path was adding ~3–4s before
     * ringtone/call audio reached already-connected AirPods.
     */
    private fun prioritizeCallAudioNow() {
        val d = device ?: run {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (macAddress.isEmpty()) return
            if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            adapter.bondedDevices.find { it.address == macAddress }?.also { device = it }
        } ?: return

        Log.d(TAG, "Prioritizing call audio (HFP/SCO) for ${d.address}")
        // HEADSET first — call audio is SCO, not A2DP.
        connectAudio(this, d, preferHeadsetFirst = true)
        // HFP alone switches the route; Mac media keeps playing unless we Hijack.
        sendCallHijackPackets("prioritize")
    }

    /**
     * Full OWNS → media(PhoneCall) → ShowUI → Hijackv2 so MacBook pauses.
     * Packets are staggered — blasting them in one tick was ignored by macOS.
     * Returns true when a staggered claim was launched (or recently succeeded).
     */
    private fun sendCallHijackPackets(reason: String): Boolean {
        if (!isRinging && !isInCall) return false
        if (!isTakeOverAllowedByPrefs("call")) return false
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return false
        if (localMac.isEmpty()) return false

        val now = System.currentTimeMillis()
        // Allow one in-flight staggered sequence ~every 1.2s (audio-source is ~15Hz).
        if (now - lastCallHijackLaunchMs < 1_200L) {
            return callHijackSucceeded
        }
        lastCallHijackLaunchMs = now

        CoroutineScope(Dispatchers.IO).launch {
            if (!isRinging && !isInCall) return@launch
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return@launch

            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                1
            )
            delay(80)
            if (!isRinging && !isInCall) return@launch
            val mediaSent = aacpManager.sendMediaInformataion(
                localMac, streamingState = true, forCall = true
            )
            delay(80)
            if (!isRinging && !isInCall) return@launch
            val uiSent = aacpManager.sendSmartRoutingShowUI(localMac)
            delay(80)
            if (!isRinging && !isInCall) return@launch
            val hijackSent = aacpManager.sendHijackRequest(localMac, forCall = true)
            Log.d(
                TAG,
                "Call hijack ($reason): media=$mediaSent showUI=$uiSent hijack=$hijackSent " +
                    "peers=${aacpManager.connectedDevices.size} " +
                    "audioSrc=${aacpManager.audioSource?.mac}"
            )
            if (hijackSent) {
                callHijackSucceeded = true
                otherDeviceTookOver = false
                lastCallTakeOverMs = System.currentTimeMillis()
                if (::audioOwnership.isInitialized) {
                    audioOwnership.onHardClaimIssued("call")
                }
                // Second nudge — MacBook often needs a follow-up after A2DP/SCO settles.
                delay(500)
                if ((isRinging || isInCall) &&
                    BluetoothConnectionManager.aacpSocket?.isConnected == true
                ) {
                    aacpManager.sendSmartRoutingShowUI(localMac)
                    delay(50)
                    aacpManager.sendHijackRequest(localMac, forCall = true)
                    Log.d(TAG, "Call hijack ($reason): follow-up ShowUI+Hijackv2 sent")
                }
            } else {
                lastCallTakeOverMs = 0L
                lastCallHijackLaunchMs = 0L
            }
        }
        return true
    }

    /** When peer MAC / Mac MEDIA appears mid-ring, claim immediately (don't wait for retry tick). */
    private fun maybeHijackForActiveCall(reason: String) {
        if (!isRinging && !isInCall) return
        if (callHijackSucceeded && !otherDeviceIsAudioSource()) return
        sendCallHijackPackets(reason)
    }

    /**
     * While ringing (and briefly after answer if still not linked), keep attempting takeOver
     * until AACP is up. Stops on IDLE or successful connect.
     *
     * Must not require head-tracking while OFFHOOK — gestures are intentionally stopped
     * in-call, which used to make this loop run forever and spam TAKING_OVER islands.
     */
    private fun startIncomingCallConnectRetry() {
        // Need retries for call audio even when the "take over when ringing" toggle is off —
        // head-gesture ownership and HFP reconnect still depend on this loop.
        if (!config.takeoverWhenRingingCall && !config.headGestures && device == null) return
        if (incomingCallConnectJob?.isActive == true) {
            Log.d(TAG, "Incoming call connect retry already running")
            return
        }
        incomingCallConnectJob = CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            while (isActive && (isRinging || isInCall)) {
                if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) {
                    delay(800)
                    continue
                }
                attempt++
                Log.d(TAG, "Incoming call: connect/takeOver retry #$attempt")
                // Keep nudging HFP every attempt — SCO setup is the long pole.
                prioritizeCallAudioNow()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        takeOver("call")
                    } catch (e: Exception) {
                        Log.w(TAG, "takeOver retry failed: ${e.message}")
                    }
                }
                if (isRinging && config.headGestures &&
                    BluetoothConnectionManager.aacpSocket?.isConnected == true
                ) {
                    maybeStartHeadGesturesAfterCallTakeOver()
                }
                val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
                val gesturesArmed = gestureDetector?.isDetecting() == true
                val htLive = validHeadTrackingSamples > 0
                val macStillMedia = otherDeviceIsAudioSource()

                val macPeerLinked = aacpManager.connectedDevices.any { it.mac != localMac }
                // While MacBook is still linked, give Hijack a few staggered rounds to pause it.
                if (aacpUp && callHijackSucceeded && !macStillMedia &&
                    (!macPeerLinked || attempt >= 3)
                ) {
                    if (isInCall) {
                        Log.d(TAG, "In-call hijack done — stop connect/takeOver retry")
                        break
                    }
                    if (isRinging && (!config.headGestures || (gesturesArmed && htLive))) {
                        Log.d(TAG, "Ringing hijack done — stop aggressive retry")
                        break
                    }
                }
                if (isInCall && attempt >= 8) {
                    Log.d(TAG, "In-call after $attempt nudges — stop retry")
                    break
                }
                // Faster than 2s — SCO often needs a second nudge ~1s after first connect.
                delay(1_000)
            }
        }
    }

    private fun stopIncomingCallConnectRetry() {
        incomingCallConnectJob?.cancel()
        incomingCallConnectJob = null
    }

    private fun maybeTakeOverAfterBtEnabledForCall() {
        if (!pendingCallTakeOverAfterBtEnable) return
        pendingCallTakeOverAfterBtEnable = false
        enablingBluetoothForCall = false
        // Keep trying for the whole ring; one-shot BLE wait alone often misses the pods.
        startIncomingCallConnectRetry()
    }

    private fun handleIncomingCallTakeOver() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val btWasOff = bluetoothOffStandby || adapter?.isEnabled != true
        if (btWasOff && config.enableBtOnIncomingCall && config.takeoverWhenRingingCall) {
            if (enablingBluetoothForCall) {
                Log.d(TAG, "Already enabling Bluetooth for incoming call")
                startIncomingCallConnectRetry()
                return
            }
            Log.i(TAG, "Incoming call with BT off — enabling via root")
            enablingBluetoothForCall = true
            pendingCallTakeOverAfterBtEnable = true
            // Sticky for RINGING→IDLE — OFFHOOK must not clear this after BT is on.
            btWasOffBeforeThisCall = true
            disableBluetoothAfterCall = true
            CoroutineScope(Dispatchers.IO).launch {
                val ok = enableBluetoothViaRoot()
                if (!ok) {
                    enablingBluetoothForCall = false
                    pendingCallTakeOverAfterBtEnable = false
                    disableBluetoothAfterCall = false
                    btWasOffBeforeThisCall = false
                    Log.w(TAG, "Could not enable Bluetooth for incoming call")
                }
                // takeOver retries start from exitBluetoothOffStandby when STATE_ON arrives
            }
            startIncomingCallConnectRetry()
            return
        }
        // BT already on at this entry — only clear the post-call disable if we did not
        // enable BT earlier in this same call (OFFHOOK re-entry after RINGING enable).
        if (!btWasOffBeforeThisCall) {
            disableBluetoothAfterCall = false
        } else {
            disableBluetoothAfterCall = true
            Log.d(TAG, "Keeping post-call BT disable — radio was off before this call")
        }
        startIncomingCallConnectRetry()
    }

    @Suppress("unused")
    fun cameraOpened() {
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) return
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if visible")
        cameraActive = true
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) {
            cameraActive = false
            return
        }
        cameraActive = false
        setupStemActions()
    }

    private fun injectCameraShutter() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // KEYCODE_CAMERA (27). MIUI Camera also accepts it while in the foreground.
                val camera = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 27"))
                val finished = camera.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    camera.destroyForcibly()
                    Log.w(TAG, "Camera keyevent timed out")
                } else if (camera.exitValue() != 0) {
                    Log.w(TAG, "Camera keyevent exited ${camera.exitValue()}, trying volume shutter")
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 25")).waitFor(1, TimeUnit.SECONDS)
                } else {
                    Log.d(TAG, "Camera shutter keyevent sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject camera shutter", e)
            }
        }
    }

    fun isCustomAction(
        action: StemAction?, default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) || isCustomAction(
                config.rightSinglePressAction, singlePressDefault
            ) || (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) || isCustomAction(
                config.rightDoublePressAction, doublePressDefault
            )
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) || isCustomAction(
                config.rightTriplePressAction, triplePressDefault
            )
        val longPressCustomized = isCustomAction(
            config.leftLongPressAction, longPressDefault
        ) || isCustomAction(
            config.rightLongPressAction, longPressDefault
        ) || (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        Log.d(
            TAG,
            "Setting up stem actions: Single Press Customized: $singlePressCustomized, Double Press Customized: $doublePressCustomized, Triple Press Customized: $triplePressCustomized, Long Press Customized: $longPressCustomized"
        )
        aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                batteryNotification.setBattery(batteryInfo)
                sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                    Battery.putIntoIntent(this, batteryNotification.getBattery())
                    setPackage(packageName)
                })
                updateBattery()
                updateNotificationContent(
                    true,
                    this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE)
                        .getString("name", device?.name),
                    batteryNotification.getBattery()
                )
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                val bothCharging =
                    batteryNotification.getBattery()[0].status == BatteryStatus.CHARGING &&
                        batteryNotification.getBattery()[1].status == BatteryStatus.CHARGING
                if (bothCharging) {
                    disconnectAudio(this@AirPodsService, device)
                }
                // Do NOT connectAudio on every battery packet — notification keep-alive
                // refreshes battery ~every few seconds; HEADSET.connect storms kill L2CAP
                // while music is playing (socket closed / ACL_DISCONNECTED).
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    val list = earDetectionNotification.status
                    val bytes = ByteArray(2)
                    bytes[0] = list[0]
                    bytes[1] = list[1]
                    putExtra("data", bytes)
                }.apply {
                    setPackage(packageName)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                conversationAwarenessNotification.setData(conversationAwareness)
                sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                    putExtra("data", conversationAwarenessNotification.status)
                }.apply {
                    setPackage(packageName)
                })

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    MediaController.startSpeaking()
                } else if (conversationAwarenessNotification.status == 6.toByte() ||conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    MediaController.stopSpeaking()
                }

                Log.d(
                    "AirPodsParser",
                    "Conversation Awareness: ${conversationAwarenessNotification.status}"
                )
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    val mode = command.value.takeIf { it.isNotEmpty() }?.get(0) ?: 0x00.toByte()
                    ancNotification.setStatus(byteArrayOf(mode))
                    if (mode.toInt() in 1..4) {
                        sharedPreferences.edit { putInt("last_listening_mode", mode.toInt()) }
                        Log.d(TAG, "Listening mode synced from AirPods: ${mode.toInt()}")
                    }
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                if (!owns) {
                    Log.d(TAG, "ownership lost → coordinator yield (keep HFP)")
                    audioOwnership.onOwnershipLost()
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(
                    TAG,
                    "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped"
                )
                // Mac play / reverse — pause Android and stop hard-claim storms.
                yieldAacpOwnershipKeepingHeadset(keepHeadset = !reasonReverseTapped)
                if (::audioOwnership.isInitialized) {
                    audioOwnership.markYieldedToOther()
                }
                if (reasonReverseTapped) {
                    Log.d(TAG, "reverse tapped, disconnecting audio")
                    disconnectedBecauseReversed = true
                    disconnectAudio(this@AirPodsService, device, disconnectHeadset = true)
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = true,
                        otherDeviceName = senderName
                    )
                }
                if (!aacpManager.owns) {
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                }
                MediaController.sendPause()
            }

            override fun onShowNearbyUI(sender: String) {
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    ),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                Log.d(
                    "AirPodsParser",
                    "Device Information: name: ${deviceInformation.name}, modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                // Store in SharedPreferences
                sharedPreferences.edit {
                    putString("name", deviceInformation.name)
                    putString("airpods_model_number", deviceInformation.modelNumber)
                    putString("airpods_manufacturer", deviceInformation.manufacturer)
                    putString("airpods_serial_number", deviceInformation.serialNumber)
                    putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                    putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                    putString("airpods_version1", deviceInformation.version1)
                    putString("airpods_version2", deviceInformation.version2)
                    putString("airpods_version3", deviceInformation.version3)
                    putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                    putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                }
                // Update config
                config.airpodsName = deviceInformation.name
                config.airpodsModelNumber = deviceInformation.modelNumber
                config.airpodsManufacturer = deviceInformation.manufacturer
                config.airpodsSerialNumber = deviceInformation.serialNumber
                config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                config.airpodsVersion1 = deviceInformation.version1
                config.airpodsVersion2 = deviceInformation.version2
                config.airpodsVersion3 = deviceInformation.version3
                config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier

                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                if (model != null) {
                    airpodsInstance = AirPodsInstance(
                        name = config.airpodsName,
                        model = model,
                        actualModelNumber = config.airpodsModelNumber,
                        serialNumber = config.airpodsSerialNumber,
                        leftSerialNumber = config.airpodsLeftSerialNumber,
                        rightSerialNumber = config.airpodsRightSerialNumber,
                        version1 = config.airpodsVersion1,
                        version2 = config.airpodsVersion2,
                        version3 = config.airpodsVersion3,
                    )
                    if (device != null) setMetadatas(device!!)
                }
                sendBroadcast(
                    Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(
                        packageName
                    )
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                // Dormant unless HT explicitly started (ringing gestures or Head Tracking screen).
                if (!isHeadTrackingActive) return
                validHeadTrackingSamples++
                HeadTracking.processPacket(headTracking)
                val horizontal =
                    ByteBuffer.wrap(headTracking, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val vertical =
                    ByteBuffer.wrap(headTracking, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val now = SystemClock.elapsedRealtime()
                if (now - lastHtSampleLogMs >= 200L) {
                    lastHtSampleLogMs = now
                    Log.d(
                        TAG,
                        "HT sample #$validHeadTrackingSamples h=$horizontal v=$vertical"
                    )
                }
                // Call nod/shake only while ringing — never while in-call or idle.
                if (isRinging && !isInCall) {
                    processHeadTrackingData(headTracking)
                } else {
                    // Head Tracking screen / test: still feed detector if it's armed.
                    gestureDetector?.processHeadOrientation(horizontal, vertical)
                }
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                Log.d("AirPodsParser", "Proximity keys: $keys")
                sharedPreferences.edit {
                    for (key in keys) {
                        Log.d("AirPodsParser", "Proximity key: ${key.key.name} = ${key.value}")
                        putString(key.key.name, Base64.encode(key.value))
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {

                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d(
                    "AirPodsParser",
                    "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}, ringing=$isRinging"
                )
                // While the phone is ringing, stem taps must answer here — otherwise AirPods
                // may deliver the gesture to Mac (connection owner) even when call audio is local.
                if (isRinging && stemPressType == StemPressType.SINGLE_PRESS) {
                    Log.d(TAG, "Single stem press while ringing — answering on phone")
                    answerCall()
                    return
                }
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                    Log.d(TAG, "Camera stem action matched, injecting shutter")
                    injectCameraShutter()
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }

            override fun onAudioSourceReceived(audioSource: ByteArray) {
                audioSourcePacketSeen = true
                val src = aacpManager.audioSource
                val otherIsSource = localMac != "" &&
                    src?.type != AACPManager.Companion.AudioSourceType.NONE &&
                    src?.mac != null &&
                    src.mac != localMac
                // Deduplicate — AirPods spam audio-source packets ~15Hz while Mac plays.
                val key = "${src?.mac}|${src?.type}|$otherIsSource"
                if (key != lastAudioSourceLogKey) {
                    lastAudioSourceLogKey = key
                    Log.d(
                        "AirPodsParser",
                        "Audio source changed mac: ${src?.mac}, type: ${src?.type?.name}, otherOwns=$otherIsSource"
                    )
                }
                audioOwnership.onOtherAudioSource(key, otherIsSource)
                // Peer MAC / Mac MEDIA just became known — hijack now (don't wait for retry tick).
                if (otherIsSource || (isRinging || isInCall)) {
                    maybeHijackForActiveCall("audio-source")
                }
                // Refresh listening-mode subscription only when safe (see maybeRefresh…).
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    maybeRefreshNotificationsForListeningMode(force = false)
                }
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d(
                        "AirPodsParser",
                        "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                    Log.d(
                        TAG,
                        "Sending new Tipi packet for device ${device.mac}, and sending media info to the device"
                    )
                    aacpManager.sendMediaInformationNewDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                }
                // Peer list just populated — this is when Hijackv2 can finally target Mac.
                if (newDevices.isNotEmpty() || connectedDevices.any { it.mac != localMac }) {
                    maybeHijackForActiveCall("connected-devices")
                }
            }

            override fun onHeadphoneAccommodationReceived(eqData: FloatArray) {
                sendBroadcast(
                    Intent(AirPodsNotifications.EQ_DATA).putExtra("eqData", eqData).apply {
                        setPackage(packageName)
                    })
            }

            override fun onCustomEqReceived(customEq: CustomEq) {
                // TODO
            }

            override fun onCapabilitiesReceived(capabilities: List<Capability>) {
                // TODO
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d(
                    "AACPManager",
                    "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}"
                )
            }
        })
    }

    private fun getActionFor(
        bud: AACPManager.Companion.StemPressBudType, type: StemPressType
    ): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d(
                    "AirPodsParser", "Default single press action: Play/Pause, not taking action."
                )
            }

            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w(
                        "AirPodsParser",
                        "Digital Assistant action is not supported on this Android version."
                    )
                }
            }

            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE").apply {
                    setPackage(packageName)
                })
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )

            if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(
                    false, false
                ) && islandWindow?.isVisible != true
            ) {
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    )
                )
            }

            if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                islandWindow?.close()
            }

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                // main: connect A2DP on in-ear. If Mac is actively the source, skip so we
                // don't fight multipoint (OWNS=0 path already yielded control).
                if (!shouldYieldAudioToOtherDevice()) {
                    connectAudio(this@AirPodsService, device)
                    justEnabledA2dp = true
                    registerA2dpConnectionReceiver()
                } else {
                    Log.d(TAG, "In-ear while Mac owns audio — skip connectAudio")
                }
                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
            } else if (newInEarData == listOf(false, false)) {
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device)
                }
            }
            val wasNone = inEarData == listOf(false, false)
            val nowSingle = newInEarData.count { it } == 1

            if (wasNone && nowSingle) {
                if (shouldYieldAudioToOtherDevice()) {
                    Log.d(TAG, "In-ear auto-play skipped — Mac/other owns audio")
                    return
                }
                MediaController.sendPlay()
                MediaController.iPausedTheMedia = false
                return
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp && !shouldYieldAudioToOtherDevice()) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    }
                } else {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    Log.d(
                        "MediaController",
                        "A2DP state changed: $previousState -> $state for device: ${device?.address}"
                    )

                    if (state == BluetoothProfile.STATE_CONNECTED && previousState != BluetoothProfile.STATE_CONNECTED && device?.address == this@AirPodsService.device?.address) {
                        if (shouldYieldAudioToOtherDevice()) {
                            Log.d(
                                TAG,
                                "A2DP connected while yielding to Mac — disconnect, do not auto-play"
                            )
                            disconnectAudio(
                                this@AirPodsService,
                                this@AirPodsService.device,
                                disconnectHeadset = false
                            )
                            context.unregisterReceiver(this)
                            return
                        }
                        Log.d("MediaController", "A2DP connected, sending play command")
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false

                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val a2dpIntentFilter =
            IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
        }
    }

    private fun ensureDefaultPreferences() {
        with(sharedPreferences) {
            edit {
                if (!contains("conversational_awareness_pause_music")) putBoolean(
                    "conversational_awareness_pause_music", false
                )
                if (!contains("personalized_volume")) putBoolean("personalized_volume", false)
                if (!contains("automatic_ear_detection")) putBoolean(
                    "automatic_ear_detection", true
                )
                if (!contains("long_press_nc")) putBoolean("long_press_nc", true)
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget", true
                )
                if (!contains("single_anc")) putBoolean("single_anc", true)
                if (!contains("long_press_transparency")) putBoolean(
                    "long_press_transparency", true
                )
                if (!contains("conversational_awareness")) putBoolean(
                    "conversational_awareness", true
                )
                if (!contains("relative_conversational_awareness_volume")) putBoolean(
                    "relative_conversational_awareness_volume", true
                )
                if (!contains("long_press_adaptive")) putBoolean("long_press_adaptive", true)
                if (!contains("loud_sound_reduction")) putBoolean("loud_sound_reduction", true)
                if (!contains("long_press_off")) putBoolean("long_press_off", false)
                if (!contains("volume_control")) putBoolean("volume_control", true)
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing", false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected", false
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", false)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", false)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call", false
                )
                if (!contains("takeover_when_media_start")) putBoolean(
                    "takeover_when_media_start", false
                )
                if (!contains("enable_bt_on_incoming_call")) putBoolean(
                    "enable_bt_on_incoming_call", false
                )

                if (!contains("adaptive_strength")) putInt("adaptive_strength", 51)
                if (!contains("tone_volume")) putInt("tone_volume", 75)
                if (!contains("conversational_awareness_volume")) putInt(
                    "conversational_awareness_volume", 43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")
                if (!contains("name")) putString("name", "AirPods")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("camera_action")) putString("camera_action", "SINGLE_PRESS")
            }
        }
    }

    private fun resolveLocalMacAddressAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            val mac = resolveLocalMacAddress()
            if (mac.isEmpty()) return@launch
            localMac = mac
            config.selfMacAddress = mac
            sharedPreferences.edit {
                putString("self_mac_address", mac)
            }
            Log.d(TAG, "Resolved local MAC address")
        }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun resolveLocalMacAddress(): String {
        if (checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
            val address = getSystemService(BluetoothManager::class.java).adapter?.address
            if (!address.isNullOrBlank() && !address.startsWith("02:00:00:00:00:00")) {
                return address
            }
        }

        try {
            val fromSettings = Settings.Secure.getString(contentResolver, "bluetooth_address")
            if (!fromSettings.isNullOrBlank() && fromSettings != "null") {
                return fromSettings.trim()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Settings.Secure bluetooth_address unavailable: ${e.message}")
        }

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "settings get secure bluetooth_address")
            )
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "Timed out waiting for su to read bluetooth_address")
                return ""
            }
            if (process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                    .takeUnless { it.isEmpty() || it == "null" }.orEmpty()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error retrieving local MAC address: ${e.message}. We probably aren't rooted."
            )
            ""
        }
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversational_awareness_pause_music", false
            ),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean(
                "show_phone_battery_in_widget", true
            ),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean(
                "relative_conversational_awareness_volume", true
            ),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean(
                "disconnect_when_not_wearing", false
            ),
            conversationalAwarenessVolume = sharedPreferences.getInt(
                "conversational_awareness_volume", 43
            ),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean(
                "takeover_when_disconnected", false
            ),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean(
                "takeover_when_ringing_call", false
            ),
            takeoverWhenMediaStart = sharedPreferences.getBoolean(
                "takeover_when_media_start", false
            ),
            enableBtOnIncomingCall = sharedPreferences.getBoolean(
                "enable_bt_on_incoming_call", false
            ),

            // Stem actions
            leftSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,
            rightSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,

            leftDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_double_press_action", "PREVIOUS_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,
            rightDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,

            leftTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,
            rightTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,

            leftLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            )!!,
            rightLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_long_press_action", "DIGITAL_ASSISTANT"
                ) ?: "DIGITAL_ASSISTANT"
            )!!,

            cameraAction = sharedPreferences.getString("camera_action", null)
                ?.let { StemPressType.valueOf(it) },

            // AirPods device information
            airpodsName = sharedPreferences.getString("airpods_name", "") ?: "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "")
                ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString(
                "airpods_right_serial_number", ""
            ) ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "")
                ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "")
                ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "name" -> config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
            "mac_address" -> macAddress = preferences.getString(key, "") ?: ""
            "automatic_ear_detection" -> config.earDetectionEnabled =
                preferences.getBoolean(key, true)

            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                if (widgetMobileBatteryEnabled) {
                    registerPhoneBatteryReceiverIfNeeded()
                } else {
                    unregisterPhoneBatteryReceiverIfNeeded()
                }
                updateBattery()
            }

            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume =
                preferences.getBoolean(key, true)

            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing =
                preferences.getBoolean(key, false)

            "conversational_awareness_volume" -> config.conversationalAwarenessVolume =
                preferences.getInt(key, 43)

            "qs_click_behavior" -> config.qsClickBehavior =
                preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> config.takeoverWhenDisconnected =
                preferences.getBoolean(key, true)

            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> {
                config.takeoverWhenRingingCall = preferences.getBoolean(key, true)
                syncTelephonyForBtOffStandby()
            }

            "takeover_when_media_start" -> config.takeoverWhenMediaStart =
                preferences.getBoolean(key, true)

            "enable_bt_on_incoming_call" -> {
                config.enableBtOnIncomingCall = preferences.getBoolean(key, false)
                syncTelephonyForBtOffStandby()
            }

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                )!!
                setupStemActions()
            }

            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES")
                        ?: "CYCLE_NOISE_CONTROL_MODES"
                )!!
                setupStemActions()
            }

            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT"
                )!!
                setupStemActions()
            }

            "camera_action" -> config.cameraAction =
                preferences.getString(key, null)?.let { StemPressType.valueOf(it) }

            // AirPods device information
            "airpods_name" -> config.airpodsName = preferences.getString(key, "") ?: ""
            "airpods_model_number" -> config.airpodsModelNumber =
                preferences.getString(key, "") ?: ""

            "airpods_manufacturer" -> config.airpodsManufacturer =
                preferences.getString(key, "") ?: ""

            "airpods_serial_number" -> config.airpodsSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_right_serial_number" -> config.airpodsRightSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision =
                preferences.getString(key, "") ?: ""

            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier =
                preferences.getString(key, "") ?: ""

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next().let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs =
                sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                    ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    @Volatile private var isRinging = false
    /** Stem config temporarily customized so single-press reaches the phone while ringing. */
    @Volatile private var stemCustomizedForCall = false
    /**
     * True when we claimed OWNS_CONNECTION for an incoming call while Mac/other previously owned.
     * Cleared by releasing ownership after the call ends.
     */
    @Volatile private var releaseOwnershipAfterCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    fun showPopup(service: Service, name: String) {
        if (!sharedPreferences.getBoolean("show_bottom_sheet_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupWindow.open(name, batteryNotification)
        popupShown = true
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null

    @SuppressLint("MissingPermission")
    fun showIsland(
        service: Service,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")
        if (!sharedPreferences.getBoolean("show_island_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(
                sharedPreferences.getString("name", "AirPods Pro").toString(),
                batteryPercentage,
                this@AirPodsService,
                type,
                reversed,
                otherDeviceName
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    //    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    var widgetMobileBatteryEnabled = false
    @Volatile private var lastBatteryUiUpdateMs = 0L
    @Volatile private var lastStatusNotificationMs = 0L
    @Volatile private var lastStatusNotificationText: String? = null

    object BatteryChangedIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                // Phone battery ticks often; skip widget work when AirPods aren't relevant.
                val service = ServiceManager.getService() ?: return
                if (service.bluetoothOffStandby) return
                val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
                if (!aacpUp && !service.bleManager.hasNearbyDevices()) return
                service.updateBattery()
            } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                try {
                    context?.unregisterReceiver(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_NONE
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods BluetoothConnectionManager.aacpSocket? Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
            }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("KotlinUnreachableCode")
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        return // something causes too many notifications. turning off for now
        if (BuildConfig.FLAVOR != "xposed") {
            Log.w(
                TAG,
                "Not showing BluetoothConnectionManager.aacpSocket? error notification to user, the service shouldn't be running if it isn't supported."
            )
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods).setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP").setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. Error: $errorMessage"
                )
            ).setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
            setPackage(packageName)
        })
    }

    /**
     * Listening-mode / stay-linked policy:
     * - Mac/other is audio source: OWNS=0 (release audio) + notifications only — never steal on sync.
     * - Android playing: light OWNS+media keep-alive (Hijackv2 only when not yet owning).
     * - Idle, no other source: soft OWNS for status; silent relink if socket drops.
     */
    private fun maybeRefreshNotificationsForListeningMode(force: Boolean = false) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        val now = System.currentTimeMillis()
        val otherSource = otherDeviceIsAudioSource()
        val secondary = isOwnershipSecondary()
        val phonePlaying = try {
            MediaController.getLocalPlaybackActive()
        } catch (_: Exception) {
            false
        }
        // While music is playing and we already own, refresh slowly — frequent Hijackv2
        // (via force sync + media keep-alive) was dropping ACL mid-playback.
        val minInterval = when {
            otherSource || secondary -> 15_000L
            phonePlaying && aacpManager.owns -> 10_000L
            force -> 1_000L
            else -> 3_000L
        }
        if (now - lastNotificationRequestMs < minInterval) return
        lastNotificationRequestMs = now

        // Mac play / Secondary hold — notifications only, never soft/hard OWNS reclaim.
        if ((otherSource || secondary) && !isRinging && !isInCall) {
            if (aacpManager.owns) {
                releaseAacpOwnershipToOtherDevice()
            }
            aacpManager.sendNotificationRequest()
            return
        }

        if (phonePlaying && localMac.isNotEmpty()) {
            sendPlayingHardClaimKeepAlive()
        } else if (!aacpManager.owns) {
            claimAacpOwnershipForStatusSync()
        }
        Log.d(
            TAG,
            "Refreshing AACP notifications for listening-mode sync (owns=${aacpManager.owns}, phonePlaying=$phonePlaying)"
        )
        aacpManager.sendNotificationRequest()
    }

    /**
     * Soft OWNS for status sync when we are not stealing from Mac.
     * Wait for the first audio-source packet — claiming earlier briefly steals Mac audio.
     * Never claim while another device is the AACP audio source.
     */
    private fun claimAacpOwnershipForStatusSync() {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        if (!audioSourcePacketSeen) {
            Log.d(TAG, "Skip soft OWNS — waiting for audio-source packet")
            return
        }
        if (isOwnershipSecondary()) {
            Log.d(TAG, "Skip soft OWNS — ownership Secondary")
            return
        }
        if (otherDeviceIsAudioSource() && !isRinging && !isInCall) {
            Log.d(TAG, "Skip soft OWNS — Mac/other is audio source")
            return
        }
        aacpSecondarySinceMs = 0L
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            1
        )
        Log.d(TAG, "Soft OWNS=1 for status sync (no Hijackv2)")
    }

    private fun isOwnershipSecondary(): Boolean =
        ::audioOwnership.isInitialized &&
            audioOwnership.state == AudioOwnershipCoordinator.State.Secondary

    /** True while Mac/other should keep AirPods audio (do not auto-play / connectAudio). */
    private fun shouldYieldAudioToOtherDevice(): Boolean {
        if (isRinging || isInCall) return false
        if (otherDeviceIsAudioSource()) return true
        if (isOwnershipSecondary()) return true
        // Do not pin on pausedForOtherDevice / recentlyLostOwnership alone — those flags
        // outlive Mac ownership (and used to be refreshed every keep-alive tick), which
        // kept disconnecting A2DP and pausing media after Mac had already stopped.
        return false
    }

    /**
     * MediaController yield hold — true while Secondary or Mac is still the audio source.
     * Used so residual playback configs cannot clear [MediaController.pausedForOtherDevice] early.
     * Does not pin on [otherDeviceTookOver] alone — that would block Android reclaim after Mac stops.
     */
    fun shouldHoldYieldToOtherDevice(): Boolean {
        if (isRinging || isInCall) return false
        if (isOwnershipSecondary()) return true
        if (otherDeviceIsAudioSource()) return true
        return false
    }

    /**
     * While Android is actively playing and Mac is not the audio source, refresh ownership.
     *
     * Once we already own the link, only send OWNS+media — repeating ShowUI+Hijackv2 every
     * few seconds from listening-mode sync + media keep-alive drops ACL/A2DP mid-song.
     * Full Hijackv2 is reserved for the first claim edge (and rare re-claim if ownership lost).
     */
    private fun sendPlayingHardClaimKeepAlive() {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        if (localMac.isEmpty()) return
        if (MediaController.pausedForOtherDevice || MediaController.recentlyLostOwnership) {
            Log.d(TAG, "Skip hard-claim keep-alive — yielded to other device")
            return
        }
        if (::audioOwnership.isInitialized &&
            audioOwnership.state == AudioOwnershipCoordinator.State.Secondary
        ) {
            Log.d(TAG, "Skip hard-claim keep-alive — ownership Secondary")
            return
        }
        if (otherDeviceIsAudioSource() && !isRinging && !isInCall) {
            // Respect AirPods-status toggles: Mac Playing media + toggle off → never steal.
            if (!isAirPodsStatusTakeOverAllowed()) {
                Log.d(TAG, "Skip hard-claim keep-alive — AirPods-status toggle denies peer state")
                releaseAacpOwnershipToOtherDevice()
                return
            }
            Log.d(TAG, "Skip hard-claim keep-alive — Mac/other is audio source")
            releaseAacpOwnershipToOtherDevice()
            return
        }
        // Always honor toggles — even if Soft OWNS already set owns=true, do not reinforce
        // with media/Hijack when "Starting media playback" / AirPods-status deny takeover.
        if (!isTakeOverAllowedByPrefs("music")) {
            Log.d(TAG, "Skip hard-claim keep-alive — prefs deny music takeOver")
            return
        }
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            1
        )
        aacpManager.sendMediaInformataion(localMac, streamingState = true)

        val alreadyOwning = aacpManager.owns
        val now = System.currentTimeMillis()
        val hijackRecentlySent = now - lastPlayingHijackKeepAliveMs < PLAYING_HIJACK_KEEPALIVE_MIN_MS
        if (alreadyOwning || hijackRecentlySent) {
            Log.d(
                TAG,
                "Playing light keep-alive (OWNS+media — " +
                    if (alreadyOwning) "already owning, skip Hijackv2)"
                    else "Hijackv2 debounced)"
            )
            return
        }
        lastPlayingHijackKeepAliveMs = now
        aacpManager.sendSmartRoutingShowUI(localMac)
        aacpManager.sendHijackRequest(localMac)
        Log.d(TAG, "Playing hard-claim keep-alive (OWNS+media+ShowUI+Hijackv2)")
    }

    private fun initAudioOwnershipCoordinator() {
        audioOwnership = AudioOwnershipCoordinator(object : AudioOwnershipCoordinator.Host {
            override fun hardClaim(reason: String) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeOver(reason)
                }
            }

            override fun isMusicTakeOverAllowedByPrefs(): Boolean =
                isTakeOverAllowedByPrefs("music")

            override fun prioritizeCallAudio() {
                prioritizeCallAudioNow()
            }

            override fun onCallTakeOverRequested() {
                handleIncomingCallTakeOver()
            }

            override fun yieldToOtherDevice(keepHeadset: Boolean) {
                yieldAacpOwnershipKeepingHeadset(keepHeadset)
            }

            override fun softClaimForStatusSync() {
                claimAacpOwnershipForStatusSync()
            }

            override fun isRinging(): Boolean = isRinging

            override fun isInCall(): Boolean = isInCall

            override fun isLocalPlaying(): Boolean = isPhoneActivelyPlayingMedia()

            override fun otherIsAudioSource(): Boolean = otherDeviceIsAudioSource()

            override fun otherSourceIsCall(): Boolean = otherDeviceIsCallAudioSource()

            override fun ownsConnection(): Boolean = aacpManager.owns

            override fun localOwnsAudioSource(): Boolean {
                val src = aacpManager.audioSource ?: return false
                if (localMac.isEmpty()) return false
                return src.type != AACPManager.Companion.AudioSourceType.NONE &&
                    src.mac == localMac
            }

            override fun isAacpConnected(): Boolean =
                BluetoothConnectionManager.aacpSocket?.isConnected == true

            override fun showYieldIslandWithReverse() {
                showOwnershipMovedIsland(takingOver = false)
            }

            override fun showTakingOverIsland() {
                showOwnershipMovedIsland(takingOver = true)
            }

            override fun setOtherDeviceTookOver(value: Boolean) {
                otherDeviceTookOver = value
            }
        })
    }

    /**
     * Yield path for Mac/other audio source: OWNS=0 + pause local media + stop hard-claim.
     * Prefer keeping HFP so [prioritizeCallAudioNow] stays fast on the next ring.
     */
    private fun yieldAacpOwnershipKeepingHeadset(keepHeadset: Boolean) {
        if (::audioOwnership.isInitialized) {
            audioOwnership.cancelConfirmRetry()
        }
        releaseAacpOwnershipToOtherDevice()
        // Force pause even for WhatsApp status / video (not MUSIC stream).
        MediaController.sendPause(force = true)
        MediaController.clearLocalPlaybackForYield()
        otherDeviceTookOver = true
        disconnectAudio(this, device, disconnectHeadset = !keepHeadset)
        Log.d(TAG, "Yielded audio to other device (pause+OWNS=0, keepHeadset=$keepHeadset)")
    }

    private fun islandBatteryLevel(): Int {
        val left = batteryNotification.getBattery()
            .find { it.component == BatteryComponent.LEFT }?.level ?: 0
        val right = batteryNotification.getBattery()
            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
        return if (left > 0 && right > 0) left.coerceAtMost(right)
        else left.coerceAtLeast(right)
    }

    private fun showOwnershipMovedIsland(takingOver: Boolean) {
        val srcMac = aacpManager.audioSource?.mac
        val otherName =
            aacpManager.connectedDevices.find { it.mac == srcMac }?.type ?: "Other device"
        if (takingOver) {
            showIsland(
                this,
                islandBatteryLevel(),
                IslandType.TAKING_OVER,
                reversed = false,
                otherDeviceName = otherName
            )
        } else {
            // reverse=false → Island shows the reverse-takeover action button.
            showIsland(
                this,
                islandBatteryLevel(),
                IslandType.MOVED_TO_OTHER_DEVICE,
                reversed = false,
                otherDeviceName = otherName
            )
        }
    }

    /** Island reverse button — routes through ownership coordinator. */
    fun requestUserReverseTakeOver() {
        if (!::audioOwnership.isInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeOver("reverse")
            }
            return
        }
        audioOwnership.onUserReverse()
    }

    /** MediaController play-edge entry — coordinator when ready, else legacy takeOver. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestMusicOwnershipFromPlayEdge() {
        if (::audioOwnership.isInitialized) {
            audioOwnership.onLocalPlayStarted()
        } else {
            takeOver("music")
        }
    }

    /** main: give up AACP ownership when another device is the audio source. */
    private fun releaseAacpOwnershipToOtherDevice() {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        if (aacpSecondarySinceMs == 0L) {
            aacpSecondarySinceMs = System.currentTimeMillis()
        }
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            byteArrayOf(0x00)
        )
        Log.d(TAG, "Released AACP OWNS=0 to other device")
    }

    /**
     * True when Android is playing claim-worthy media (music, video, WhatsApp status, …).
     * Uses [MediaController.getLocalPlaybackActive] — not only [AudioManager.isMusicActive].
     */
    private fun isPhoneActivelyPlayingMedia(): Boolean {
        return try {
            MediaController.getLocalPlaybackActive()
        } catch (_: Exception) {
            false
        }
    }

    /** True when another device owns AACP audio as a phone call (outdranks Android media). */
    private fun otherDeviceIsCallAudioSource(): Boolean {
        val src = aacpManager.audioSource ?: return false
        if (localMac.isEmpty()) return false
        return src.type == AACPManager.Companion.AudioSourceType.CALL &&
            src.mac != null &&
            src.mac != localMac
    }

    /**
     * Stay AACP-linked without stealing Mac audio:
     * - Mac/other audio source → OWNS=0 + light notifications (release on track switch).
     * - Android playing → hard claim.
     * - Idle, no other source → soft OWNS + notifications.
     * Socket drops → [scheduleAacpSilentRelink].
     */
    private fun startAacpMediaKeepAlive() {
        if (aacpMediaKeepAliveJob?.isActive == true) return
        aacpMediaKeepAliveJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting AACP stay-linked keep-alive loop")
            var first = true
            while (isActive && BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                delay(if (first) 2_000L else 10_000L)
                first = false
                if (BluetoothConnectionManager.aacpSocket?.isConnected != true) break

                val playing = try {
                    MediaController.getLocalPlaybackActive()
                } catch (_: Exception) {
                    false
                }
                val secondary = isOwnershipSecondary()

                // Mac play / Secondary hold — OWNS=0 so we don't steal; refresh before ~45s kill.
                // Stay on this path even when audio-source briefly reports NONE during handoff.
                if ((otherDeviceIsAudioSource() || secondary) && !isRinging && !isInCall) {
                    val macOwnsAudio = otherDeviceIsAudioSource()
                    if (aacpManager.owns) {
                        releaseAacpOwnershipToOtherDevice()
                    } else if (aacpSecondarySinceMs == 0L) {
                        aacpSecondarySinceMs = System.currentTimeMillis()
                    }
                    // Only pause/disconnect while Mac/other is actually the audio source.
                    // Secondary-only (NONE flap) used to re-pause every 10s and call
                    // clearLocalPlaybackForYield(), which reset the 20s/45s reclaim timers
                    // forever — media kept pausing and A2DP never stayed up.
                    if (macOwnsAudio) {
                        if (playing && !MediaController.userPlayedTheMedia) {
                            Log.d(TAG, "Secondary keep-alive — re-pause local media for Mac")
                            MediaController.sendPause(force = true)
                            // Do not clearLocalPlaybackForYield() here — that resets yield
                            // timers every tick and blocks user reclaim indefinitely.
                        }
                        try {
                            disconnectAudio(this@AirPodsService, device, disconnectHeadset = false)
                        } catch (_: Exception) {
                        }
                    }
                    aacpManager.sendNotificationRequest()
                    val secondaryFor = System.currentTimeMillis() - aacpSecondarySinceMs
                    if (secondaryFor >= SECONDARY_PROACTIVE_REFRESH_MS) {
                        Log.d(
                            TAG,
                            "Secondary for ${secondaryFor}ms — proactive AACP refresh before ~45s drop"
                        )
                        aacpSecondarySinceMs = System.currentTimeMillis()
                        try {
                            BluetoothConnectionManager.aacpSocket?.close()
                        } catch (_: Exception) {
                        }
                        // Read loop → silent relink; exit this keep-alive iteration.
                        break
                    }
                    continue
                }

                aacpSecondarySinceMs = 0L
                lastMediaKeepAliveMs = System.currentTimeMillis()
                if (playing) {
                    sendPlayingHardClaimKeepAlive()
                } else {
                    claimAacpOwnershipForStatusSync()
                    aacpManager.sendNotificationRequest()
                }
            }
            Log.d(TAG, "AACP keep-alive loop ended")
        }
    }

    private fun stopAacpMediaKeepAlive() {
        aacpMediaKeepAliveJob?.cancel()
        aacpMediaKeepAliveJob = null
        audioSourcePacketSeen = false
        lastAudioSourceLogKey = ""
        lastPlayingHijackKeepAliveMs = 0L
    }

    /**
     * Re-open AACP after an unexpected drop without tearing down A2DP/audio.
     * Gives Mac-like "always linked" UX when firmware closes idle secondary sockets.
     */
    private fun scheduleAacpSilentRelink(reason: String) {
        if (!aacpStayLinkedDesired || bluetoothOffStandby) return
        if (macAddress.isEmpty() && device == null) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        if (aacpRelinkJob?.isActive == true) {
            Log.d(TAG, "AACP silent relink already running ($reason)")
            return
        }
        aacpRelinkJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "AACP silent relink started ($reason)")
            var attempt = 0
            while (isActive && aacpStayLinkedDesired && !bluetoothOffStandby) {
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    Log.d(TAG, "AACP silent relink: already connected")
                    return@launch
                }
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    delay(3_000)
                    continue
                }
                if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    delay(5_000)
                    continue
                }
                val d = device ?: adapter.bondedDevices.find { it.address == macAddress }
                if (d == null) {
                    delay(3_000)
                    continue
                }
                device = d
                if (macAddress.isEmpty()) macAddress = d.address
                attempt++
                Log.d(TAG, "AACP silent relink attempt #$attempt for ${d.address}")
                try {
                    // AACP only — do not storm A2DP/HFP on idle relink.
                    connectToSocket(adapter, d, manual = false)
                } catch (e: Exception) {
                    Log.w(TAG, "AACP silent relink connect failed: ${e.message}")
                }
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    Log.d(TAG, "AACP silent relink succeeded after $attempt attempt(s)")
                    return@launch
                }
                delay(if (attempt < 8) 2_000L else 8_000L)
            }
            Log.d(TAG, "AACP silent relink ended (desired=$aacpStayLinkedDesired)")
        }
    }

    private fun stopAacpSilentRelink() {
        aacpRelinkJob?.cancel()
        aacpRelinkJob = null
    }

    /** Unexpected AACP socket loss — clean manager state and re-open without full disconnect UX. */
    private fun handleUnexpectedAacpSocketLoss(reason: String) {
        Log.d(TAG, "Unexpected AACP loss ($reason)")
        stopListeningModeSyncLoop()
        stopAacpMediaKeepAlive()
        try {
            aacpManager.disconnected()
        } catch (_: Exception) {
        }
        BluetoothConnectionManager.aacpSocket = null
        if (aacpStayLinkedDesired && !bluetoothOffStandby) {
            // Keep UI/BLE in "linked" posture during the brief silent relink.
            updateNotificationContent(
                true,
                sharedPreferences.getString("name", device?.name ?: config.deviceName),
                batteryNotification.getBattery()
            )
            scheduleAacpSilentRelink(reason)
        } else {
            bleManager.setAacpConnected(false)
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                setPackage(packageName)
            })
        }
    }

    /** Runs only while L2CAP/AACP is up. Stopped on disconnect — BLE path is battery-only. */
    private fun startListeningModeSyncLoop() {
        if (listeningModeSyncJob?.isActive == true) return
        listeningModeSyncJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting listening-mode sync loop (AACP connected)")
            for (attempt in 1..3) {
                if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                    Log.d(TAG, "Stopping listening-mode sync — AACP down")
                    return@launch
                }
                maybeRefreshNotificationsForListeningMode(force = true)
                delay(1_200)
                if (aacpManager.hasListeningModeStatus()) {
                    Log.d(TAG, "LISTENING_MODE present after attempt $attempt — steady sync")
                    break
                }
            }
            while (isActive && BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                delay(8_000L)
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    maybeRefreshNotificationsForListeningMode(force = true)
                }
            }
            Log.d(TAG, "Listening-mode sync loop ended")
        }
    }

    private fun stopListeningModeSyncLoop() {
        if (listeningModeSyncJob != null) {
            Log.d(TAG, "Stopping listening-mode sync loop")
        }
        listeningModeSyncJob?.cancel()
        listeningModeSyncJob = null
        lastAudioSourceLogKey = ""
    }

    fun sendBatteryBroadcast() {
        broadcastBatteryInformation()
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            Battery.putIntoIntent(this, batteryNotification.getBattery())
            setPackage(packageName)
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            return
        }
        device?.let { it ->
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_BATTERY,
                batteryNotification.getBattery()
                    .find { it.component == BatteryComponent.CASE }?.level.toString()
                    .toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_CHARGING,
                (if (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                ) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_BATTERY,
                batteryNotification.getBattery()
                    .find { it.component == BatteryComponent.LEFT }?.level.toString()
                    .toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_CHARGING,
                (if (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                ) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_BATTERY,
                batteryNotification.getBattery()
                    .find { it.component == BatteryComponent.RIGHT }?.level.toString()
                    .toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_CHARGING,
                (if (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                ) "1".toByteArray() else "0".toByteArray())
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) return
        val now = SystemClock.elapsedRealtime()
        val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
        val nearby = bleManager.hasNearbyDevices()
        // Longer throttle on standby; keep snappy only while actively connected/nearby.
        val minInterval = if (aacpUp || nearby) 750L else 5_000L
        if (now - lastBatteryUiUpdateMs < minInterval) return
        lastBatteryUiUpdateMs = now
        if (!aacpUp && !nearby) {
            // Nothing to show — avoid metadata/widget/notification churn.
            return
        }
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        // Status notification only while AACP-connected; nearby BLE updates app + widget only.
        if (aacpUp) {
            sendBatteryNotification()
        } else {
            try {
                getSystemService(NotificationManager::class.java).cancel(2)
            } catch (_: Exception) {
            }
        }
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.noise_control_widget).also { it ->
            val ancStatus = ancNotification.status
            val allowOffModeValue =
                aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
            val allowOffMode =
                allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (allowOffMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (allowOffMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button, if (allowOffMode) View.VISIBLE else View.GONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (allowOffMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (allowOffMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (bluetoothOffStandby || bleManager.isBluetoothOffParked()) {
            notificationManager.cancel(2)
            return
        }

        val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
        // Nearby-only: app + widget get battery via broadcast; no status notification.
        if (!aacpUp) {
            notificationManager.cancel(2)
            if (connected && !config.bleOnlyMode && BluetoothConnectionManager.aacpSocket != null) {
                showSocketConnectionFailureNotification(
                    "BluetoothConnectionManager.aacpSocket? created, but not connected. Check logs"
                )
            }
            return
        }

        val batteries = batteryList ?: batteryNotification.getBattery()

        fun batLine(component: Int, label: String): String {
            val b = batteries.find { it.component == component } ?: return ""
            if (b.status == BatteryStatus.DISCONNECTED) return ""
            val bolt = if (b.status == BatteryStatus.CHARGING) "⚡" else ""
            return "$label: $bolt ${b.level}%"
        }
        val contentText = listOf(
            batLine(BatteryComponent.LEFT, "L"),
            batLine(BatteryComponent.RIGHT, "R"),
            batLine(BatteryComponent.CASE, "Case")
        ).filter { it.isNotBlank() }.joinToString(" ")

        val title = airpodsName ?: config.deviceName
        val fingerprint = "$title|$contentText|$disconnectedBecauseReversed"
        val now = SystemClock.elapsedRealtime()
        // System sheds notifications when enqueue rate > ~5/s; BLE battery spam was ANR-adjacent.
        if (fingerprint == lastStatusNotificationText && now - lastStatusNotificationMs < 1000L) {
            return
        }
        lastStatusNotificationText = fingerprint
        lastStatusNotificationMs = now

        val updatedNotificationBuilder =
            NotificationCompat.Builder(this, "airpods_connection_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle(title)
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)

        if (disconnectedBecauseReversed) {
            updatedNotificationBuilder.addAction(
                R.drawable.ic_bluetooth, "Reconnect", PendingIntent.getService(
                    this, 0, Intent(this, AirPodsService::class.java).apply {
                        action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        notificationManager.notify(2, updatedNotificationBuilder.build())
        notificationManager.cancel(1)
    }

    /**
     * Arm nod=accept / shake=reject only while the phone is ringing.
     * Head tracking + detector stay dormant on idle and during an active call.
     *
     * Idempotent while already armed — takeOver / connect callbacks must not
     * stop+restart HT (that killed the stream when AirPods were already linked).
     */
    fun handleIncomingCall() {
        if (isInCall || !isRinging) return
        if (!config.headGestures) return
        if (bluetoothOffStandby || bleManager.isBluetoothOffParked() ||
            BluetoothConnectionManager.aacpSocket?.isConnected != true
        ) {
            pendingHeadGesturesForCall = true
            Log.d(TAG, "Deferring head gestures for incoming call until AACP is ready")
            return
        }
        pendingHeadGesturesForCall = false
        initGestureDetector()
        // Already detecting: never stop/restart (that drops the HT stream mid-ring).
        if (gestureDetector?.isDetecting() == true) {
            Log.d(TAG, "Head gestures already armed for this ring — leave HT running")
            if (!isHeadTrackingActive) {
                startHeadTracking(allowOwnershipClaim = true)
            } else if (validHeadTrackingSamples == 0) {
                nudgeHeadTrackingStartPackets()
            }
            return
        }
        Log.d(TAG, "Starting head gestures for incoming call (nod=accept, shake=reject)")
        // Stale HT from the Head Tracking screen — stop only when detector isn't armed yet.
        if (isHeadTrackingActive) {
            stopHeadTracking()
        }
        gestureDetector?.startDetection { accepted ->
            if (!isRinging || isInCall) return@startDetection
            if (accepted) {
                Log.d(TAG, "Head gesture accept — answering call")
                answerCall()
                gestureDetector?.playActionChime(accepted = true)
            } else {
                Log.d(TAG, "Head gesture reject — ending call")
                rejectCall()
                gestureDetector?.playActionChime(accepted = false)
            }
            handleIncomingCallOnceConnected = false
            stopHeadGesturesForCall()
        }
    }

    /** Fully stop gesture detector + HT stream (used when leaving RINGING). */
    private fun stopHeadGesturesForCall() {
        pendingHeadGesturesForCall = false
        if (isHeadTrackingActive || gestureDetector?.isDetecting() == true) {
            Log.d(TAG, "Stopping head gestures (dormant until next ring)")
        }
        // stopHeadTracking also stops the detector without re-entrancy.
        stopHeadTracking()
    }

    /** After call takeOver / AACP up — arm nod/shake if still ringing (no-op if already armed). */
    private fun maybeStartHeadGesturesAfterCallTakeOver() {
        if (!config.headGestures) return
        if (!isRinging || isInCall) return
        Handler(Looper.getMainLooper()).post {
            if (!isRinging || isInCall) return@post
            handleIncomingCall()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        initGestureDetector()
        val detector = gestureDetector
            ?: throw IllegalStateException("Gesture detector unavailable")
        return suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "testHeadGestures: arming detector (nod=Yes, shake=No)")
            detector.startDetection(doNotStop = true) { accepted ->
                // Test UI: chime after the gesture is recognized (no telephony action).
                detector.playActionChime(accepted)
                if (continuation.isActive) {
                    continuation.resume(accepted) { _, _, _ ->
                        detector.stopDetection()
                    }
                }
            }
            continuation.invokeOnCancellation {
                detector.stopDetection()
            }
        }
    }

    private fun answerCall() {
        try {
            // Ensure Mac is hijacked before/as we answer — gesture path may have only soft-OWNS'd.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        takeOver("call", startHeadTrackingAgain = false)
                    } catch (e: Exception) {
                        Log.w(TAG, "takeOver on answer failed: ${e.message}")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod =
                    telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        try {
            gestureDetector?.processHeadOrientation(horizontal, vertical)
        } catch (e: Exception) {
            Log.w(TAG, "gesture detector on ${data.toHexString()}: ${e.message}")
        }
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId)).build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1

    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C

    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"

    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @SuppressLint("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null || checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100, rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
                VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV
            )
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                BluetoothHeadset.AT_CMD_TYPE_SET
            )
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "no permission BLUETOOTH_PRIVILEGED, returning")
            return
        }
        Log.d(TAG, "has permission BLUETOOTH_PRIVILEGED, proceeding")
        d.let { device ->
            val instance = airpodsInstance
            if (instance != null) {
                val metadataSet = SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MAIN_ICON,
                    resToUri(instance.model.budCaseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_MODEL_NAME, instance.model.name.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(instance.model.caseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(instance.model.rightBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(instance.model.leftBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    instance.model.manufacturer.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
                Log.d(TAG, "Metadata set: $metadataSet")
            } else {
                Log.w(
                    TAG,
                    "AirPods demoInstance is not of type AirPodsInstance, skipping metadata setting"
                )
            }
        }
    }

    @Suppress("ClassName")
    private object bluetoothReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            val appContext = context?.applicationContext
            val service = ServiceManager.getService()

            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.d(TAG, "Bluetooth adapter state changed: $state")
                when (state) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        service?.enterBluetoothOffStandby()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        service?.exitBluetoothOffStandby()
                    }
                }
                return
            }

            // Ignore device events while radio is off / parking.
            if (service?.bluetoothOffStandby == true) return

            val bluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java
                )
            } else {
                intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
            }
            val name = appContext?.getSharedPreferences("settings", MODE_PRIVATE)
                ?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && !action.isNullOrEmpty()) {
                Log.d(TAG, "Received bluetooth connection broadcast: action=$action")

                // System/media often re-attaches A2DP while Mac owns audio. Without PRIVILEGED
                // disconnect is sticky only until the next play — catch every reconnect here
                // (the one-shot connectAudio receiver is not enough).
                if (action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" ||
                    action == "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
                ) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val ourDevice = service?.device
                    val isOurs = ourDevice != null &&
                        bluetoothDevice.address.equals(ourDevice.address, ignoreCase = true)
                    val a2dpUp = state == BluetoothProfile.STATE_CONNECTED ||
                        state == 10 /* BluetoothA2dp.STATE_PLAYING */
                    if (isOurs && a2dpUp && service != null &&
                        !service.isRinging && !service.isInCall &&
                        service.shouldYieldAudioToOtherDevice()
                    ) {
                        Log.d(
                            TAG,
                            "A2DP up while yielding to Mac — disconnect (media takeover off / Secondary)"
                        )
                        try {
                            MediaController.sendPause(force = true)
                        } catch (_: Exception) {
                        }
                        service.disconnectAudio(
                            service, ourDevice, disconnectHeadset = false
                        )
                    }
                    return
                }

                if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    if (!bluetoothDevice.isAirPodsByName()) {
                        Log.d(TAG, "Ignoring ACL_CONNECTED for non-AirPods: ${bluetoothDevice.name}")
                    } else if (bluetoothDevice.hasAirPodsUuid()) {
                        val detected = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        detected.putExtra("name", name)
                        detected.putExtra("device", bluetoothDevice)
                        appContext?.sendBroadcast(detected)
                    } else {
                        // Wait for SDP — do not treat later UUID events for idle bonded pods
                        // as a connection (that falsely connects while Sony/etc. is active).
                        pendingAirPodsAclAddresses.add(bluetoothDevice.address)
                        bluetoothDevice.fetchUuidsWithSdp()
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
                    pendingAirPodsAclAddresses.remove(bluetoothDevice.address)
                } else if ("android.bluetooth.device.action.UUID" == action) {
                    if (!bluetoothDevice.isAirPodsByName()) {
                        Log.d(TAG, "Ignoring UUID event for non-AirPods: ${bluetoothDevice.name}")
                    } else if (!pendingAirPodsAclAddresses.remove(bluetoothDevice.address)) {
                        Log.d(
                            TAG,
                            "Ignoring UUID for AirPods without pending ACL: ${bluetoothDevice.name}"
                        )
                    } else if (bluetoothDevice.hasAirPodsUuid()) {
                        val detected = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        detected.putExtra("name", name)
                        detected.putExtra("device", bluetoothDevice)
                        appContext?.sendBroadcast(detected)
                    }
                }
            }
        }
    }

    val externalBroadcastFilter = IntentFilter().apply {
        addAction("me.kavishdevar.librepods.SET_ANC_MODE")
        addAction("me.kavishdevar.librepods.CONVO_DETECT")
    }
    var externalBroadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        if (intent?.action == "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE") {
            Log.d(TAG, "reconnect after reversed received, taking over")
            disconnectedBecauseReversed = false
            otherDeviceTookOver = false
            CoroutineScope(Dispatchers.IO).launch {
                takeOver("music", manualTakeOverAfterReversed = true)
            }
        }

        return START_STICKY
    }

    /**
     * Takeover gates from App Settings:
     * 1) Phone-state: "Starting media playback" / "Receiving a call"
     * 2) AirPods-status (music only): Disconnected / Idle / Playing media / On call
     *
     * Calls: phone-state "Receiving a call" alone is enough to Hijack and pause Mac media.
     * Gating calls on AirPods-status "Playing media" left HFP switching the route while
     * Mac kept playing (no Hijackv2).
     * Yield when Mac plays is separate and always allowed.
     * Head-gesture answer still needs call ownership for HT even when ringing toggle is off.
     */
    private fun isTakeOverAllowedByPrefs(takingOverFor: String): Boolean {
        if (takingOverFor == "reverse") return true

        val phoneStateOk = when (takingOverFor) {
            "music" -> config.takeoverWhenMediaStart
            "call" -> config.takeoverWhenRingingCall ||
                (config.headGestures && isRinging && !isInCall)
            else -> true
        }
        if ((takingOverFor == "music" || takingOverFor == "call") && !phoneStateOk) {
            Log.d(TAG, "Not taking over: phone-state toggle off for $takingOverFor")
            return false
        }

        // Incoming/active call — do not require AirPods-status "Playing media".
        if (takingOverFor == "call") {
            return true
        }

        if (!isAirPodsStatusTakeOverAllowed()) {
            return false
        }
        return true
    }

    /**
     * "Connect to your AirPods when its status is" — Disconnected / Idle / Playing media / On call.
     * When Mac (or any peer) is Playing media, requires [Config.takeoverWhenMusic], etc.
     */
    fun isAirPodsStatusTakeOverAllowed(): Boolean {
        val airPodsState = resolveAirPodsTakeOverState()
        val allowed = when (airPodsState) {
            "Disconnected", "Unknown" -> config.takeoverWhenDisconnected
            "Idle" -> config.takeoverWhenIdle
            "Music" -> config.takeoverWhenMusic
            "Call", "Ringing", "Hanging Up" -> config.takeoverWhenCall
            else -> false
        }
        if (!allowed) {
            Log.d(TAG, "Not taking over: AirPods-status toggle off for state=$airPodsState")
        }
        return allowed
    }

    /**
     * Prefer BLE proximity state; when AACP is up BLE scan is stopped so fall back to
     * AACP audio-source type (Mac playing media / on call).
     */
    private fun resolveAirPodsTakeOverState(): String {
        val bleState = bleManager.getMostRecentStatus()?.connectionState
        if (bleState != null && bleState != "Unknown") return bleState

        val src = aacpManager.audioSource
        if (src != null && src.mac != null && src.mac != localMac) {
            return when (src.type) {
                AACPManager.Companion.AudioSourceType.MEDIA -> "Music"
                AACPManager.Companion.AudioSourceType.CALL -> "Call"
                AACPManager.Companion.AudioSourceType.NONE -> "Idle"
                else -> "Idle"
            }
        }
        return bleState ?: "Disconnected"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun takeOver(
        takingOverFor: String,
        manualTakeOverAfterReversed: Boolean = false,
        startHeadTrackingAgain: Boolean = false
    ) {
        if (bluetoothOffStandby) {
            Log.d(TAG, "Skipping takeOver — Bluetooth off standby")
            return
        }
        if (takingOverFor == "reverse") {
            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
            )
            aacpManager.sendMediaInformataion(
                localMac
            )
            aacpManager.sendHijackReversed(
                localMac
            )
            connectAudio(
                this@AirPodsService, device
            )
            otherDeviceTookOver = false
            if (::audioOwnership.isInitialized) {
                audioOwnership.onHardClaimIssued("reverse")
            }
        }
        // Idle: no-op. Hard Hijackv2 only when Android is actually playing (or call/reverse).
        if (takingOverFor == "music" && !manualTakeOverAfterReversed &&
            !isPhoneActivelyPlayingMedia()
        ) {
            Log.d(TAG, "takeOver(music): Android idle — no-op (ownership follows audio source)")
            return
        }
        // Call > media; Mac CALL source must not be stolen for Android music.
        if (takingOverFor == "music" &&
            ::audioOwnership.isInitialized &&
            !audioOwnership.allowMusicHardClaim()
        ) {
            Log.d(TAG, "takeOver(music): blocked by ownership coordinator (call priority)")
            return
        }
        // Debounce successful call hijacks only — a failed "no peer MAC" claim must retry
        // immediately when CONNECTED_DEVICES / audio-source arrives.
        if (takingOverFor == "call" && !manualTakeOverAfterReversed) {
            val now = System.currentTimeMillis()
            if (callHijackSucceeded &&
                now - lastCallTakeOverMs < 4_000L &&
                BluetoothConnectionManager.aacpSocket?.isConnected == true
            ) {
                Log.d(TAG, "takeOver(call): debounced — arm gestures only")
                maybeStartHeadGesturesAfterCallTakeOver()
                return
            }
        }
        val ownsConnection = aacpManager.getControlCommandStatus(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
        )?.value?.getOrNull(0)?.toInt()
        Log.d(
            TAG, "owns connection: $ownsConnection"
        )
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            val vendorHook =
                XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)
            // ownsConnection==0 used to abort here, which blocked claiming ownership while Mac
            // still owned — stem taps kept going to Mac even when call audio was on the phone.
            // Call + (playing) music need Hijackv2 so Mac pauses; reverse is user-initiated.
            if (!vendorHook && takingOverFor != "call" && takingOverFor != "music" &&
                takingOverFor != "reverse"
            ) {
                Log.d(TAG, "not taking over, vendorid is probably not set to apple")
                return
            }
            val otherDeviceIsSource =
                aacpManager.audioSource?.mac != null &&
                    aacpManager.audioSource?.mac != localMac &&
                    aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE
            val hardMusicClaim = takingOverFor == "music" && isPhoneActivelyPlayingMedia()
            // null ownership is unknown — hijacking then causes A2DP/ACL reconnect storms.
            val needsHijack =
                (ownsConnection != null && ownsConnection != 1) || otherDeviceIsSource ||
                    takingOverFor == "call" || hardMusicClaim
            if (needsHijack) {
                if (!isTakeOverAllowedByPrefs(takingOverFor)) {
                    // Audio hijack blocked — head gestures still work on an existing AACP link.
                    if (takingOverFor == "call") {
                        Log.d(TAG, "Call audio takeOver blocked by prefs — arming head gestures only")
                        maybeStartHeadGesturesAfterCallTakeOver()
                    }
                    return
                }
                if (disconnectedBecauseReversed) {
                    if (manualTakeOverAfterReversed) {
                        Log.d(TAG, "forcefully taking over despite reverse as user requested")
                        disconnectedBecauseReversed = false
                    } else {
                        Log.d(
                            TAG,
                            "connected locally, but can not hijack as other device had reversed"
                        )
                        if (takingOverFor == "call") {
                            maybeStartHeadGesturesAfterCallTakeOver()
                        }
                        return
                    }
                }

                Log.d(TAG, "already connected locally, hijacking connection by asking AirPods")
                if (takingOverFor == "call") {
                    markReleaseOwnershipAfterCallIfNeeded(ownsConnection)
                }
                // Hard claim only for call, or music while Android is actually playing.
                val doHardClaim = takingOverFor == "call" || hardMusicClaim
                if (!doHardClaim) {
                    Log.d(TAG, "Skipping Hijackv2 — soft OWNS only (not playing / not call)")
                    if (!otherDeviceIsSource) {
                        claimAacpOwnershipForStatusSync()
                    }
                    return
                }
                val hijackSent = if (takingOverFor == "call") {
                    sendCallHijackPackets("takeOver")
                } else {
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
                    )
                    val mediaSent = aacpManager.sendMediaInformataion(
                        localMac, streamingState = true
                    )
                    val uiSent = aacpManager.sendSmartRoutingShowUI(localMac)
                    val sent = aacpManager.sendHijackRequest(localMac)
                    Log.d(
                        TAG,
                        "Hard claim ($takingOverFor): media=$mediaSent showUI=$uiSent hijack=$sent " +
                            "peers=${aacpManager.connectedDevices.size} " +
                            "audioSrc=${aacpManager.audioSource?.mac}"
                    )
                    if (sent) {
                        otherDeviceTookOver = false
                        if (::audioOwnership.isInitialized) {
                            audioOwnership.onHardClaimIssued(takingOverFor)
                        }
                    }
                    sent
                }
                if (takingOverFor == "call" && hijackSent) {
                    lastCallTakeOverMs = System.currentTimeMillis()
                }
                connectAudio(this, device, preferHeadsetFirst = takingOverFor == "call")
                if (takingOverFor == "call") {
                    enableStemCaptureForIncomingCall()
                    maybeStartHeadGesturesAfterCallTakeOver()
                }
                // Prefer TAKING_OVER when reclaiming from Mac (island may already be open).
                // Call retries must not re-popup every debounce window.
                val islandType =
                    if (takingOverFor == "music" || takingOverFor == "call") IslandType.TAKING_OVER
                    else IslandType.CONNECTED
                val showCallIsland = takingOverFor != "call" || !callTakeOverIslandShown
                if (takingOverFor == "call") callTakeOverIslandShown = true
                if (showCallIsland) {
                    showIsland(
                        this,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            ),
                        islandType
                    )
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(500) // a2dp takes time, and so does taking control + AirPods pause it for no reason after connecting
                    if (takingOverFor == "music" && isPhoneActivelyPlayingMedia()) {
                        Log.d(TAG, "Resuming music after taking control")
                        MediaController.sendPlay(replayWhenPaused = true)
                    } else if (startHeadTrackingAgain) {
                        Log.d(TAG, "Starting head tracking again after taking control")
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Only while still ringing — stay dormant in-call / idle.
                            if (isRinging && config.headGestures) {
                                maybeStartHeadGesturesAfterCallTakeOver()
                            }
                        }, 500)
                    }
                    delay(1000) // should ideally have a callback when it's taken over because for some reason android doesn't dispatch when it's paused
                    if (takingOverFor == "music" && isPhoneActivelyPlayingMedia()) {
                        Log.d(TAG, "resuming again just in case")
                        MediaController.sendPlay(force = true)
                    }
                }
            } else {
                Log.d(
                    TAG,
                    "Already connected locally; skipping hijack (owns=$ownsConnection, otherSource=$otherDeviceIsSource)"
                )
                if (takingOverFor == "call" || hardMusicClaim) {
                    // Even when we already "own", force Hijackv2 so Mac media pauses.
                    if (takingOverFor == "call") {
                        markReleaseOwnershipAfterCallIfNeeded(ownsConnection)
                        if (sendCallHijackPackets("takeOver-already-owns")) {
                            lastCallTakeOverMs = System.currentTimeMillis()
                        }
                    } else {
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
                        )
                        aacpManager.sendMediaInformataion(localMac, streamingState = true)
                        aacpManager.sendSmartRoutingShowUI(localMac)
                        aacpManager.sendHijackRequest(localMac)
                        if (::audioOwnership.isInitialized) {
                            audioOwnership.onHardClaimIssued(takingOverFor)
                        }
                    }
                    connectAudio(this, device, preferHeadsetFirst = takingOverFor == "call")
                    if (takingOverFor == "call") {
                        enableStemCaptureForIncomingCall()
                        maybeStartHeadGesturesAfterCallTakeOver()
                    }
                } else if (takingOverFor == "music") {
                    if (!otherDeviceIsSource) {
                        claimAacpOwnershipForStatusSync()
                    }
                }
                if (startHeadTrackingAgain) {
                    Handler(Looper.getMainLooper()).post {
                        // Only while still ringing — stay dormant in-call / idle.
                        if (isRinging && config.headGestures) {
                            maybeStartHeadGesturesAfterCallTakeOver()
                        }
                    }
                }
            }
            return
        }

        // During an incoming/active call, keep trying even if BLE hasn't reported in-ear yet
        // (common right after we wake BT). Other takeovers still require in-ear.
        if (takingOverFor != "call") {
            if (bleManager.getMostRecentStatus()?.isLeftInEar == false &&
                bleManager.getMostRecentStatus()?.isRightInEar == false
            ) {
                Log.d(TAG, "Both AirPods are out of ear, not taking over audio")
                return
            }
        }

        if (!isTakeOverAllowedByPrefs(takingOverFor)) {
            if (takingOverFor == "call") {
                // If something else brings AACP up during this ring, still arm gestures.
                pendingHeadGesturesForCall = true
                handleIncomingCallOnceConnected = true
                maybeStartHeadGesturesAfterCallTakeOver()
            }
            return
        }

        if (takingOverFor == "music") {
            Log.d(TAG, "Pausing music so that it doesn't play through speakers")
            MediaController.pausedWhileTakingOver = true
            MediaController.sendPause(true)
        } else {
            handleIncomingCallOnceConnected = true
        }

        Log.d(TAG, "Taking over audio")
//        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d(TAG, macAddress)

//        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }

        if (device != null) {
            if (config.bleOnlyMode) {
                // In BLE-only mode, just show connecting status without actual L2CAP connection
                Log.d(TAG, "BLE-only mode: showing connecting status without L2CAP connection")
                updateNotificationContent(
                    true, config.deviceName, batteryNotification.getBattery()
                )
                // Set a temporary connecting state
//                isConnectedLocally = false // Keep as false since we're not actually connecting to L2CAP
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    // Call: bring HFP up in parallel with L2CAP — don't serialize behind socket connect.
                    if (takingOverFor == "call") {
                        connectAudio(this@AirPodsService, device, preferHeadsetFirst = true)
                    }
                    connectToSocket(bluetoothAdapter, device!!)
                    connectAudio(
                        this@AirPodsService,
                        device,
                        preferHeadsetFirst = takingOverFor == "call"
                    )
                    if (takingOverFor == "call") {
                        // After AACP is up: full Hijackv2 so Mac pauses, then stem/gestures.
                        // 200ms is enough for the first control packets; 800ms was adding lag.
                        delay(200)
                        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                            val owns = aacpManager.getControlCommandStatus(
                                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
                            )?.value?.getOrNull(0)?.toInt()
                            markReleaseOwnershipAfterCallIfNeeded(owns)
                            sendCallHijackPackets("socket-connected")
                            enableStemCaptureForIncomingCall()
                            maybeStartHeadGesturesAfterCallTakeOver()
                        }
                    }
                }
//                isConnectedLocally = true
            }
        }
        val showCallIsland = takingOverFor != "call" || !callTakeOverIslandShown
        if (takingOverFor == "call") callTakeOverIslandShown = true
        if (showCallIsland) {
            showIsland(
                this,
                batteryNotification.getBattery()
                    .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level!!
                    ),
                IslandType.TAKING_OVER
            )
        }

//        CrossDevice.isAvailable = false
    }

    /** Remember to hand the link back after the call if we weren't already the owner. */
    private fun markReleaseOwnershipAfterCallIfNeeded(ownsBefore: Int?) {
        if (ownsBefore != 1 || otherDeviceIsAudioSource()) {
            releaseOwnershipAfterCall = true
        }
    }

    /** Route stem single-press to this phone while ringing (otherwise Mac may get the gesture). */
    private fun enableStemCaptureForIncomingCall() {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        val ownsBefore = aacpManager.getControlCommandStatus(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
        )?.value?.getOrNull(0)?.toInt()
        // takeOver may have already claimed OWNS=1; mark only if still not our link.
        markReleaseOwnershipAfterCallIfNeeded(ownsBefore)
        stemCustomizedForCall = true
        Log.d(
            TAG,
            "Enabling stem capture for incoming call (customize single press, ownsBefore=$ownsBefore, releaseAfter=$releaseOwnershipAfterCall)"
        )
        aacpManager.sendStemConfigPacket(
            singlePressCustomized = true,
            doublePressCustomized = false,
            triplePressCustomized = false,
            longPressCustomized = false,
        )
        // Ensure we own the link so AirPods deliver the press here.
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
        )
    }

    /**
     * @param releaseOwnership When true (call ended), restore stem config and give the link
     * back to Mac/previous owner if we had claimed it for the call.
     */
    private fun restoreStemConfigAfterCall(releaseOwnership: Boolean) {
        if (!stemCustomizedForCall && !(releaseOwnership && releaseOwnershipAfterCall)) return
        if (stemCustomizedForCall) {
            stemCustomizedForCall = false
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                Log.d(TAG, "Restoring normal stem config after call")
                setupStemActions()
            }
        }
        if (!releaseOwnership || !releaseOwnershipAfterCall) return
        releaseOwnershipAfterCall = false
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        Log.d(TAG, "Releasing call-time claim — restore stem; keep AACP alive unless Mac CALL")
        otherDeviceTookOver = false
        // A2DP-only drop; keep HFP. Soft OWNS reclaim below prevents ~45s secondary teardown.
        disconnectAudio(this, device, disconnectHeadset = false)
        CoroutineScope(Dispatchers.IO).launch {
            delay(1_200)
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return@launch
            if (isRinging || isInCall) return@launch
            if (otherDeviceIsCallAudioSource()) {
                Log.d(TAG, "Post-call: Mac still on CALL — keep OWNS=0")
                releaseAacpOwnershipToOtherDevice()
                aacpManager.sendNotificationRequest()
                return@launch
            }
            // Stay primary after call so the L2CAP link does not die at ~45s.
            claimAacpOwnershipForStatusSync()
            aacpManager.sendNotificationRequest()
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean = false
    ) {
        if (bluetoothOffStandby || !adapter.isEnabled) {
            Log.d(TAG, "Skipping L2CAP connect — Bluetooth unavailable")
            return
        }
        // socket.connect() + runBlocking must never run on the main thread (ANR).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CoroutineScope(Dispatchers.IO).launch {
                connectToSocket(adapter, device, manual)
            }
            return
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return

        val now = SystemClock.elapsedRealtime()
        if (!manual && now - lastSocketConnectAttemptMs < 2500L) {
            Log.d(TAG, "Skipping L2CAP connect; attempted ${now - lastSocketConnectAttemptMs}ms ago")
            return
        }
        if (!socketConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping L2CAP connect; another attempt is in progress")
            return
        }
        lastSocketConnectAttemptMs = now

        try {
            connectToSocketLocked(adapter, device, manual)
        } finally {
            socketConnecting.set(false)
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun connectToSocketLocked(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean
    ) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")

        // Drop half-open sockets so the stack can free PSM 0x1001 (avoids "no RCB available").
        try {
            BluetoothConnectionManager.attSocket?.close()
        } catch (_: Exception) {
        }
        try {
            BluetoothConnectionManager.aacpSocket?.close()
        } catch (_: Exception) {
        }
        BluetoothConnectionManager.attSocket = null
        BluetoothConnectionManager.aacpSocket = null
        try {
            attManager.stopReader()
        } catch (_: Exception) {
        }

        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
//        if (!isConnectedLocally) {
        val socket = try {
            createBluetoothSocket(adapter, device, uuid, 4097)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
            showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
            return
        }

        try {
            runBlocking {
                withTimeout(5000.milliseconds) {
                    try {
                        // Brief settle so Capod/system Obex can release a conflicting PSM registration.
                        delay(300.milliseconds)
                        socket.connect()
                        this@AirPodsService.device = device
                        val xposedRemotePref = XposedRemotePrefProvider.create()
                        val attSocket = if (xposedRemotePref.getBoolean("vendor_id_hook", false)) {
                            createBluetoothSocket(
                                adapter,
                                device,
                                ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"),
                                31
                            )
                        } else null
                        attSocket?.connect()

                        if (attSocket != null) {
                            attManager.startReader()
                            attManager.readCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION)
                            attManager.readCharacteristic(ATTHandles.TRANSPARENCY)
                            attManager.readCharacteristic(ATTHandles.HEARING_AID)
                        }

                        BluetoothConnectionManager.aacpSocket = socket
                        BluetoothConnectionManager.attSocket = attSocket

                        // Create AirPodsInstance from stored config if available
                        if (airpodsInstance == null && config.airpodsModelNumber.isNotEmpty()) {
                            val model =
                                AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                            if (model != null) {
                                airpodsInstance = AirPodsInstance(
                                    name = config.airpodsName,
                                    model = model,
                                    actualModelNumber = config.airpodsModelNumber,
                                    serialNumber = config.airpodsSerialNumber,
                                    leftSerialNumber = config.airpodsLeftSerialNumber,
                                    rightSerialNumber = config.airpodsRightSerialNumber,
                                    version1 = config.airpodsVersion1,
                                    version2 = config.airpodsVersion2,
                                    version3 = config.airpodsVersion3,
                                )
                                setMetadatas(device)
                            }
                        }

                        updateNotificationContent(
                            true, config.deviceName, batteryNotification.getBattery()
                        )
                        // AACP owns battery/status — drop BLE to LOW_POWER while connected.
                        bleManager.setAacpConnected(true)
                        Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
                        // Allow one Hijackv2 on this link if music is already playing.
                        lastPlayingHijackKeepAliveMs = 0L
                        if (::audioOwnership.isInitialized) {
                            audioOwnership.onConnect()
                        }
                        sharedPreferences.edit { putBoolean("connection_successful", true) }
                        if (!sharedPreferences.contains("first_connection_successful_time")) {
                            sharedPreferences.edit {
                                putLong(
                                    "first_connection_successful_time",
                                    System.currentTimeMillis()
                                )
                            }
                        }
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED))
                    } catch (e: Exception) {
//                        sharedPreferences.edit { putBoolean("connection_successful", false) }
                        Log.d(
                            TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}"
                        )
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                        val hint = when {
                            e.message?.contains("closed", ignoreCase = true) == true ||
                                e.message?.contains("read failed", ignoreCase = true) == true ||
                                e.message?.contains("RCB", ignoreCase = true) == true ->
                                "L2CAP busy — force-stop Capod/other AirPods apps, then reconnect"
                            else -> e.localizedMessage ?: e.message ?: "unknown error"
                        }
                        if (manual) {
                            sendToast("Couldn't connect to socket: $hint")
                        } else {
                            showSocketConnectionFailureNotification("Couldn't connect to socket: $hint")
                        }
                        return@withTimeout
//                            throw e // lol how did i not catch this before... gonna comment this line instead of removing to preserve history
                    }
                }
            }
            if (!socket.isConnected) {
                Log.d(TAG, "<LogCollector:Complete:Failed> socket not connected")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                if (manual) {
                    sendToast(
                        "Couldn't connect to socket: timeout. Stop Capod if it's running."
                    )
                } else {
                    showSocketConnectionFailureNotification(
                        "Couldn't connect to socket: Timeout. Another app may be using L2CAP PSM 0x1001."
                    )
                }
                return
            }
            this@AirPodsService.device = device
            BluetoothConnectionManager.aacpSocket?.let {
                // Idle connect: handshake/notifications only — do NOT OWNS/Hijack (Mac keeps audio).
                // Hard claim only if Android is already playing and we are not yielding to Mac.
                audioSourcePacketSeen = false
                // Keep Secondary timer across silent relink so proactive refresh cadence stays sane.
                if (!isOwnershipSecondary() && !otherDeviceTookOver) {
                    aacpSecondarySinceMs = 0L
                }
                aacpStayLinkedDesired = true
                stopAacpSilentRelink()
                aacpManager.sendPacket(aacpManager.createHandshakePacket())
                aacpManager.sendSetFeatureFlagsPacket()
                aacpManager.sendNotificationRequest()
                val yielding = shouldYieldAudioToOtherDevice() ||
                    otherDeviceTookOver ||
                    isOwnershipSecondary()
                if (yielding) {
                    releaseAacpOwnershipToOtherDevice()
                    Log.d(TAG, "Connect: handshake only — yielding to Mac/other (no OWNS claim)")
                } else if (isPhoneActivelyPlayingMedia() && isTakeOverAllowedByPrefs("music")) {
                    // Hard-claim only when playing AND App Settings toggles allow (phone + AirPods status).
                    sendPlayingHardClaimKeepAlive()
                    Log.d(TAG, "Connect: handshake + playing hard-claim")
                } else if (isPhoneActivelyPlayingMedia()) {
                    Log.d(TAG, "Connect: handshake only — prefs deny music takeOver")
                } else {
                    Log.d(TAG, "Connect: handshake only — no OWNS until audio-source known")
                }
                Log.d(TAG, "Requesting proximity keys")
                aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)
                    aacpManager.sendPacket(aacpManager.createHandshakePacket())
                    delay(150)
                    aacpManager.sendSetFeatureFlagsPacket()
                    delay(150)
                    aacpManager.sendNotificationRequest()
                    delay(200)
                    aacpManager.sendSomePacketIDontKnowWhatItIs()
                    delay(200)
                    aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                    // Head tracking stays dormant on connect — only arm for ringing gestures.
                    if (handleIncomingCallOnceConnected || (isRinging && config.headGestures) || pendingHeadGesturesForCall) {
                        Handler(Looper.getMainLooper()).post {
                            handleIncomingCall()
                        }
                    } else if (isPhoneActivelyPlayingMedia() &&
                        !shouldYieldAudioToOtherDevice() &&
                        !otherDeviceTookOver &&
                        !isOwnershipSecondary() &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        // Already playing on connect — claim so Mac pauses (MediaController path).
                        takeOver("music")
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return@postDelayed
                        aacpManager.sendPacket(aacpManager.createHandshakePacket())
                        aacpManager.sendSetFeatureFlagsPacket()
                        aacpManager.sendNotificationRequest()
                        aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                    }, 5000)

                    sendBroadcast(
                        Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device)
                            .apply {
                                setPackage(packageName)
                            })

                    setupStemActions()

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1500)
                        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return@launch
                        if (!aacpManager.hasListeningModeStatus()) {
                            Log.d(TAG, "Listening mode missing after connect — re-request notifications")
                            maybeRefreshNotificationsForListeningMode(force = true)
                        }
                    }
                    startListeningModeSyncLoop()
                    startAacpMediaKeepAlive()

                    while (socket.isConnected) {
                        try {
                            val buffer = ByteArray(1024)
                            val bytesRead = it.inputStream.read(buffer)
                            var data: ByteArray
                            if (bytesRead > 0) {
                                data = buffer.copyOfRange(0, bytesRead)
                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                    putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    setPackage(packageName)
                                })
                                val bytes = buffer.copyOfRange(0, bytesRead)
                                val formattedHex = bytes.joinToString(" ") { "%02X".format(it) }
//                                    CrossDevice.sendReceivedPacket(bytes)
                                updateNotificationContent(
                                    true,
                                    sharedPreferences.getString("name", device.name),
                                    batteryNotification.getBattery()
                                )

                                aacpManager.receivePacket(data)

                                if (!isHeadTrackingData(data)) {
                                    Log.d("AirPodsData", "Data received: $formattedHex")
                                    logPacket(data, "AirPods")
                                }

                            } else if (bytesRead == -1) {
                                Log.d("AirPodsService", "socket closed (bytesRead = -1)")
                                handleUnexpectedAacpSocketLoss("bytesRead=-1")
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading data, we have probably disconnected.")
                            e.printStackTrace()
                            handleUnexpectedAacpSocketLoss("read-error")
                            return@launch
                        }

                    }
                    Log.d("AirPods Service", "socket closed")
//                        isConnectedLocally = false
                    handleUnexpectedAacpSocketLoss("socket-loop-exit")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Failed to connect to BluetoothConnectionManager.aacpSocket?: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
            showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
//                isConnectedLocally = false
            this@AirPodsService.device = device
            updateNotificationContent(false)
        }
//        } else {
//            Log.d(TAG, "Already connected locally, skipping BluetoothConnectionManager.aacpSocket? connection (isConnectedLocally = $isConnectedLocally, BluetoothConnectionManager.aacpSocket?.isConnected = ${this::BluetoothConnectionManager.aacpSocket?.isInitialized && BluetoothConnectionManager.aacpSocket?.isConnected})")
//        }
    }

    fun disconnectForCD() {
        aacpStayLinkedDesired = false
        stopAacpSilentRelink()
        BluetoothConnectionManager.aacpSocket?.close()
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.MOVED_TO_REMOTE
        )
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        if (BluetoothConnectionManager.aacpSocket == null) return
        try {
            BluetoothConnectionManager.aacpSocket?.close()
        } catch(e: Exception) {
            Log.e(TAG, "error closing aacp socket ${e.message}")
        }
//        isConnectedLocally = false
        aacpManager.disconnected()

        BluetoothConnectionManager.aacpSocket = null
        BluetoothConnectionManager.attSocket = null

        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            setPackage(packageName)
        })

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
            try {
                device?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "device.disconnect() failed, $e")
            }
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        }
        Log.d(TAG, "Disconnected AirPods upon user request")
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            ancNotification.setStatus(CrossDevice.ancBytes)
//        }
        // Live listening mode only exists over AACP. BLE-nearby is battery-only.
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            val last = sharedPreferences.getInt("last_listening_mode", 0)
            return if (last in 1..4) last else ancNotification.status
        }
        if (!aacpManager.hasListeningModeStatus()) {
            val last = sharedPreferences.getInt("last_listening_mode", 0)
            if (last in 1..4) return last
        }
        return ancNotification.status
    }

    /** True when AACP reports media/call audio owned by another paired device (e.g. Mac). */
    private fun otherDeviceIsAudioSource(): Boolean {
        val src = aacpManager.audioSource ?: return false
        if (localMac.isEmpty()) return false
        return src.type != AACPManager.Companion.AudioSourceType.NONE &&
            src.mac != null &&
            src.mac != localMac
    }

    /**
     * Drop A2DP (and optionally HFP) policy for AirPods.
     * @param disconnectHeadset When false, only A2DP is torn down so HFP stays ready for calls
     * (Mac-source yield / Continuity soft handoff).
     */
    fun disconnectAudio(
        context: Context,
        device: BluetoothDevice?,
        disconnectHeadset: Boolean = true
    ) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        val privileged =
            checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
                PackageManager.PERMISSION_GRANTED
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                try {
                    if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Already disconnected from A2DP")
                        return
                    }
                    if (privileged) {
                        val method = proxy.javaClass.getMethod(
                            "setConnectionPolicy", BluetoothDevice::class.java, Int::class.java
                        )
                        Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device?.address} to 0")
                        method.invoke(proxy, device, 0)
                    } else {
                        // FOSS / non-system: policy needs PRIVILEGED; disconnect() mirrors connect().
                        val disconnectMethod =
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        Log.d(TAG, "calling A2DP.disconnect for ${device?.address} (no PRIVILEGED)")
                        disconnectMethod.invoke(proxy, device)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "A2DP yield disconnect failed: ${e.message}")
                } finally {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        if (!disconnectHeadset) {
            Log.d(TAG, "Keeping HEADSET/HFP connected after A2DP yield")
            return
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            val method =
                                proxy.javaClass.getMethod(
                                    "setConnectionPolicy",
                                    BluetoothDevice::class.java,
                                    Int::class.java
                                )
                            Log.d(TAG, "calling HEADSET.setConnectionPolicy for ${device?.address} to 0")
                            method.invoke(proxy, device, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } else {
            Log.d(TAG, "not disconnecting HEADSET, no MODIFIY_PHONE_STATE permission")
        }
    }

    fun connectAudio(
        context: Context,
        device: BluetoothDevice?,
        preferHeadsetFirst: Boolean = false
    ) {
        if (device == null) return
        if (otherDeviceIsCallAudioSource() && !isRinging && !isInCall) {
            Log.d(TAG, "Skipping connectAudio — Mac/other is on CALL")
            return
        }
        // Media toggle off / Secondary / Mac MEDIA — A2DP connect steals Mac audio even
        // without Hijackv2 (classic BT exclusive route).
        if (!isRinging && !isInCall && shouldYieldAudioToOtherDevice()) {
            Log.d(TAG, "Skipping connectAudio — yielding to Mac/other (prefs or Secondary)")
            return
        }
        if (!isRinging && !isInCall && otherDeviceIsAudioSource() &&
            !isTakeOverAllowedByPrefs("music")
        ) {
            Log.d(TAG, "Skipping connectAudio — Mac MEDIA and media takeover prefs deny")
            return
        }
        // Call path may nudge often; media/idle must not storm HEADSET.connect (kills L2CAP).
        val now = System.currentTimeMillis()
        val minInterval = if (preferHeadsetFirst || isRinging || isInCall) 800L else 8_000L
        if (now - lastConnectAudioAttemptMs < minInterval) {
            Log.d(TAG, "Skipping connectAudio — debounced (${now - lastConnectAudioAttemptMs}ms)")
            return
        }
        lastConnectAudioAttemptMs = now
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        fun bindA2dp() {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                                Log.d(TAG, "A2DP already connected for ${device.address}, skip connect")
                                return
                            }
                            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    val policyMethod = proxy.javaClass.getMethod(
                                        "setConnectionPolicy",
                                        BluetoothDevice::class.java,
                                        Int::class.java
                                    )
                                    Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device.address} to 100")
                                    policyMethod.invoke(proxy, device, 100)

                                    val connectMethod =
                                        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                    connectMethod.invoke(proxy, device)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                if (MediaController.pausedWhileTakingOver) {
                                    MediaController.sendPlay()
                                }
                            } else {
                                val connectMethod =
                                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                connectMethod.invoke(proxy, device)
                                Log.d(
                                    TAG,
                                    "not setting connection policy for A2DP, no BLUETOOTH_PRIVILEGED permission. just called connect"
                                )
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Could not read A2DP connection state: ${e.message}")
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        }

        fun bindHeadset() {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            val state = proxy.getConnectionState(device)
                            if (state == BluetoothProfile.STATE_CONNECTED ||
                                state == BluetoothProfile.STATE_CONNECTING
                            ) {
                                Log.d(
                                    TAG,
                                    "HEADSET already connected/connecting for ${device.address}, skip"
                                )
                                return
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Could not read HEADSET state: ${e.message}")
                        }
                        // Media playback is A2DP — don't poke HFP unless call path needs SCO.
                        if (!preferHeadsetFirst && !isRinging && !isInCall) {
                            Log.d(TAG, "Skipping HEADSET.connect — not a call path")
                            return
                        }
                        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            try {
                                val policyMethod = proxy.javaClass.getMethod(
                                    "setConnectionPolicy",
                                    BluetoothDevice::class.java,
                                    Int::class.java
                                )
                                Log.d(
                                    TAG,
                                    "calling HEADSET.setConnectionPolicy for ${device.address} to 100"
                                )
                                policyMethod.invoke(proxy, device, 100)
                                val connectMethod =
                                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                connectMethod.invoke(proxy, device)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                            }
                        } else {
                            // Still try connect() — some ROMs allow it without privileged policy.
                            try {
                                val connectMethod =
                                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                connectMethod.invoke(proxy, device)
                                Log.d(
                                    TAG,
                                    "HEADSET.connect without MODIFY_PHONE_STATE for ${device.address}"
                                )
                            } catch (e: Exception) {
                                Log.d(
                                    TAG,
                                    "not connecting HEADSET, no MODIFY_PHONE_STATE: ${e.message}"
                                )
                            } finally {
                                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                            }
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        }

        if (preferHeadsetFirst || isRinging || isInCall) {
            bindHeadset()
            bindA2dp()
        } else {
            // Music path: A2DP only. Repeated HEADSET.connect was closing ACL/L2CAP.
            bindA2dp()
        }
    }

    fun setName(name: String) {
        aacpManager.sendRename(name)

        if (config.deviceName != name) {
            config.deviceName = name
            device?.alias = name
            sharedPreferences.edit { putString("name", name) }
        }

        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d(TAG, "setName: $name")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")
        aacpStayLinkedDesired = false
        stopAacpSilentRelink()
        stopAacpMediaKeepAlive()
        stopListeningModeSyncLoop()

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(externalBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        unregisterPhoneBatteryReceiverIfNeeded()
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.unregisterTelephonyCallback(phoneStateListener)
        }
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    var isHeadTrackingActive = false
    /** Count of validated motion samples (0x44/0x45) since last startHeadTracking. */
    @Volatile private var validHeadTrackingSamples = 0
    private var lastHtSampleLogMs = 0L

    /**
     * @param allowOwnershipClaim If true, may claim OWNS_CONNECTION / takeOver so HT
     * works for ringing gestures / Head Tracking screen. Must stay false for any
     * automatic background probe — soft-claim kicks Mac/other devices onto speakers.
     */
    fun startHeadTracking(allowOwnershipClaim: Boolean = false) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            Log.d(TAG, "Skipping startHeadTracking — AACP not connected")
            return
        }
        isHeadTrackingActive = true
        validHeadTrackingSamples = 0
        val preferAlternate =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        val ownsConnection = aacpManager.getControlCommandStatus(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
        )?.value?.getOrNull(0)?.toInt()

        // Always start HT packets immediately while AACP is up. Waiting on takeOver
        // meant gestures stayed dead for the whole ring when AirPods were already
        // connected but Mac still owned the link.
        if (allowOwnershipClaim) {
            if (ownsConnection != 1) {
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
                )
                Log.d(TAG, "Soft-claimed ownership for head tracking (owns was $ownsConnection)")
            }
            // Soft OWNS ≠ full claim. Stem capture often sets OWNS=1 first, which used to
            // skip Hijackv2 — Mac kept playing. While ringing (or Mac is audio source),
            // always run takeOver so Mac gets the pause/hijack packets.
            val needsFullHijack = isRinging ||
                ownsConnection == 0 ||
                otherDeviceIsAudioSource()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && needsFullHijack) {
                CoroutineScope(Dispatchers.IO).launch {
                    takeOver("call", startHeadTrackingAgain = false)
                }
            }
        } else if (ownsConnection != 1) {
            Log.d(
                TAG,
                "Head-tracking without ownership claim (owns=$ownsConnection) — leave Mac/other device alone"
            )
        }

        armHeadTrackingStartWithRetries(preferAlternate)
        HeadTracking.reset()
    }

    /** Send one HT start (used by the retry loop and mid-ring nudges). */
    private fun nudgeHeadTrackingStartPackets(useAlternate: Boolean? = null) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        if (!isHeadTrackingActive) return
        val alt = useAlternate
            ?: sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        Log.d(TAG, "Sending HT start (alternate=$alt)")
        if (alt) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
        } else {
            aacpManager.sendStartHeadTracking()
        }
    }

    /**
     * Keep requesting the motion stream until 0x44/0x45 samples arrive.
     * Call rings often start HT before AACP/ownership has settled — a single
     * fallback after 1.5s was giving up while the detector stayed armed with no data.
     */
    private fun armHeadTrackingStartWithRetries(preferAlternate: Boolean) {
        nudgeHeadTrackingStartPackets(preferAlternate)
        CoroutineScope(Dispatchers.IO).launch {
            var useAlt = preferAlternate
            // Longer while ringing — connect/headset churn often delays the motion stream.
            val maxAttempts = if (isRinging) 12 else 2
            for (attempt in 1..maxAttempts) {
                delay(if (attempt == 1) 1_200L else 2_000L)
                if (!isHeadTrackingActive) return@launch
                if (validHeadTrackingSamples > 0) {
                    Log.d(TAG, "HT stream OK ($validHeadTrackingSamples samples)")
                    return@launch
                }
                useAlt = !useAlt
                Log.w(
                    TAG,
                    "No HT motion samples — retry #$attempt alternate=$useAlt ringing=$isRinging"
                )
                if (isRinging) {
                    // Re-assert ownership; without it AirPods often only send HT setup blobs.
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        1
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        localMac.isNotEmpty()
                    ) {
                        aacpManager.sendHijackRequest(localMac)
                    }
                }
                nudgeHeadTrackingStartPackets(useAlt)
            }
            if (validHeadTrackingSamples == 0) {
                Log.w(TAG, "Still no HT motion samples after retries — gestures stay dormant")
            }
        }
    }

    fun stopHeadTracking() {
        // UI (Head Tracking screen dispose) must not kill an active call-gesture session.
        if (isRinging && !isInCall && gestureDetector?.isDetecting() == true) {
            Log.d(TAG, "Ignoring stopHeadTracking — call gesture session active")
            return
        }
        val wasActive = isHeadTrackingActive
        isHeadTrackingActive = false
        // doNotStop=true: detector stops its loop without calling back into stopHeadTracking.
        gestureDetector?.stopDetection(doNotStop = true)
        if (!wasActive) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
        } else {
            aacpManager.sendStopHeadTracking()
        }
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "connecting to $macAddress (AACP only; A2DP only if phone needs audio)")
                connectToSocket(bluetoothAdapter, device!!, manual = true)
                // connectAudio is gated — idle reconnect must not yank Mac media.
                connectAudio(this@AirPodsService, device!!)
            }
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}

fun getNextMode(currentMode: Int, configByte: Int, offmodeEnabled: Boolean): Int {
    val enabledModes = buildList {
        if ((configByte and 0x01) != 0 && offmodeEnabled) add(1)
        if ((configByte and 0x04) != 0) add(3)
        if ((configByte and 0x08) != 0) add(4)
        if ((configByte and 0x02) != 0) add(2)
    }
    Log.d(TAG, "currentMode: $currentMode, config: ${configByte.toString(2)}")

    if (enabledModes.isEmpty()) return currentMode

    val currentIndex = enabledModes.indexOf(currentMode)
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % enabledModes.size

    return enabledModes[nextIndex]
}
