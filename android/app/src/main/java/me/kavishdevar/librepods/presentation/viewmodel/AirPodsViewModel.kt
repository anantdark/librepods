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

package me.kavishdevar.librepods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTCCCDHandles
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.services.AirPodsService

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String = "AirPods",

    val isLocallyConnected: Boolean = false,
    /** BLE proximity — battery available without AACP (like macOS menu-bar battery). */
    val isNearby: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    /** 1=Off, 2=ANC, 3=Transparency, 4=Adaptive. 0 = not synced yet. */
    val ancMode: Int = 0,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = true,
    val cameraAction: AACPManager.Companion.StemPressType? = AACPManager.Companion.StemPressType.SINGLE_PRESS,

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false,

    val dynamicEndOfCharge: Boolean = false,

    val connectionSuccessful: Boolean = false,
    val timeUntilFOSSPremiumExpiry: Long = 0L,

    val customEq: CustomEq = CustomEq(1, 50, 50, 50) // disabled
)

val demoInstance = AirPodsInstance(
    name = "AirPods Pro",
    model = AirPodsModels.getModelByModelNumber("A3064")!!,
    actualModelNumber = "A3064",
    serialNumber = "JXF9Q94A40",
    leftSerialNumber = "L-DEMO",
    rightSerialNumber = "R-DEMO",
    version1 = "90.3388000000000000.1786",
    version2 = "90.3388000000000000.1786",
    version3 = "9441861",
)

val demoState = AirPodsUiState(
    deviceName = demoInstance.name,

    isLocallyConnected = true,

    capabilities = demoInstance.model.capabilities,

    battery = listOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.RIGHT, 18, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 76, BatteryStatus.NOT_CHARGING)
    ),

    ancMode = 4,
    offListeningMode = false,

    modelName = demoInstance.model.displayName,
    actualModel = demoInstance.actualModelNumber,
    serialNumbers =  listOf(
        demoInstance.serialNumber?: "",
        demoInstance.leftSerialNumber?: "",
        demoInstance.rightSerialNumber?: ""
    ),

    version1 = demoInstance.version1?: "",
    version2 = demoInstance.version2?: "",
    version3 = demoInstance.version3?: "",

    headTrackingActive = true,
    headGesturesEnabled = true,

    automaticEarDetectionEnabled = true,
    automaticConnectionEnabled = true,

    leftAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    rightAction = StemAction.DIGITAL_ASSISTANT,

    loudSoundReductionEnabled = true,

    isPremium = true,
    vendorIdHook = true,

    dynamicEndOfCharge = true,

    connectionSuccessful = true,

    customEq = CustomEq(state = 2, low = 65, mid = 50, high = 70),

    controlStates = mapOf(
        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.STEM_CONFIG to byteArrayOf(0x00),
        ControlCommandIdentifiers.CLICK_HOLD_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG to byteArrayOf(0x00, 0x03),
        ControlCommandIdentifiers.CHIME_VOLUME to byteArrayOf(0x46, 0x50),
        ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_AID to byteArrayOf(0x01, 0x02),
        ControlCommandIdentifiers.HPS_GAIN_SWIPE to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_ASSIST_CONFIG to byteArrayOf(0x02),
        ControlCommandIdentifiers.HRM_STATE to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTO_ANC_STRENGTH to byteArrayOf(0x45),
        ControlCommandIdentifiers.ONE_BUD_ANC_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_TOGGLE_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_CAP_LEVEL_CONFIG to byteArrayOf(0x52),
        ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE to byteArrayOf(0x01),
        ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(0x04)
    )
)

class AirPodsViewModel(

) : ViewModel() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var service: AirPodsService
    private lateinit var controlRepo: ControlCommandRepository

    var isReady by mutableStateOf(false)
        private set

    fun init(service: AirPodsService, controlRepo: ControlCommandRepository, sharedPreferences: SharedPreferences, appContext: Context) {
        this.service = service
        this.controlRepo = controlRepo
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext

        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        observeAACP()
        loadCurrentStatus()
        loadEq()
        // ATT reads block on LinkedBlockingQueue.poll — never on main (ANR in onServiceConnected).
        viewModelScope.launch(Dispatchers.IO) {
            loadATT()
        }
        observeATT()
        observeSharedPreferences()
        observeBilling()
        if (isDemoMode) activateDemoMode()
        isReady = true
    }

    private val _uiState = MutableStateFlow(AirPodsUiState())

    val uiState: StateFlow<AirPodsUiState> = _uiState

    private var isDemoMode = false

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private lateinit var broadcastReceiver: BroadcastReceiver

    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
        sharedPreferences.edit {
            if (action == null) remove("camera_action")
            else putString("camera_action", action.name)
        }
        _uiState.update { it.copy(cameraAction = action) }
        if (::service.isInitialized) {
            service.setupStemActions()
        }
    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        val updatedEq = _uiState.value.customEq.copy(low = low, mid = mid, high = high)
        service.aacpManager.sendCustomEqPacket(updatedEq)
        _uiState.update {
            it.copy(
                customEq = updatedEq
            )
        }
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        service.aacpManager.sendCustomEqPacket(_uiState.value.customEq.copy(state = if (enabled) 2 else 1))
        _uiState.update {
            it.copy(
                customEq = it.customEq.copy(state = if (enabled) 2 else 1)
            )
        }
    }

    override fun onCleared() {
        listeners.forEach { (id, listener) ->
            controlRepo.remove(id, listener)
        }
        service.aacpManager.customEqCallback = null
        appContext.unregisterReceiver(broadcastReceiver)
    }

    private fun loadName() {
        val name = sharedPreferences.getString("name", "AirPods Pro")!!
        _uiState.update { it.copy(deviceName = name) }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        setControlCommandBoolean(
                            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            false
                        )
                        setHeadGesturesEnabled(false)
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun observeSharedPreferences() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "name" -> loadName()
                "off_listening_mode", "automatic_ear_detection", "automatic_connection_ctrl_cmd",
                "head_gestures", "camera_action", "left_long_press_action", "right_long_press_action",
                "dynamic_end_of_charge", "foss_upgraded", "premium_expiry_time" -> loadSharedPreferences()
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = true, isNearby = true)
                        }
                        // Status packets arrive shortly after handshake — refresh once connected
                        // and again after a short delay so LISTENING_MODE can sync.
                        loadCurrentStatus()
                        viewModelScope.launch {
                            delay(1500)
                            loadCurrentStatus()
                        }
                    }

                    AirPodsNotifications.ANC_DATA -> {
                        val mode = intent.getIntExtra("data", 0)
                        if (mode in 1..4) {
                            applyListeningMode(mode)
                        }
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        _uiState.update {
                            it.copy(
                                isLocallyConnected = false,
                                isNearby = service.bleManager.hasNearbyDevices(),
                                battery = service.getBattery(),
                                // AACP gone — noise control is not live; nearby is battery-only.
                                controlStates = it.controlStates - ControlCommandIdentifiers.LISTENING_MODE
                            )
                        }
                    }

                    AirPodsNotifications.AIRPODS_NEARBY -> {
                        _uiState.update {
                            it.copy(
                                isNearby = true,
                                battery = service.getBattery()
                            )
                        }
                    }

                    AirPodsNotifications.AIRPODS_GONE -> {
                        _uiState.update {
                            it.copy(
                                isNearby = false,
                                battery = if (it.isLocallyConnected) it.battery else emptyList()
                            )
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(
                                battery = service.getBattery(),
                                isNearby = it.isLocallyConnected || service.bleManager.hasNearbyDevices()
                            )
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadInstance()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.AIRPODS_NEARBY)
            addAction(AirPodsNotifications.AIRPODS_GONE)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.ANC_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        if (!isDemoMode) controlRepo.setValue(identifier, value)
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to value)
            )
        }
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
        if (identifier == ControlCommandIdentifiers.LISTENING_MODE && value in 1..4) {
            applyListeningMode(value)
        }
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        val listener = controlRepo.observe(identifier) { value ->
            if (identifier == ControlCommandIdentifiers.LISTENING_MODE) {
                val mode = value.getOrNull(0)?.toInt() ?: return@observe
                if (mode in 1..4) applyListeningMode(mode)
                return@observe
            }
            _uiState.update { state ->
                val current = state.controlStates[identifier]
                if (current?.contentEquals(value) == true) return@update state

                if (identifier == ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE) {
                    state.copy(
                        dynamicEndOfCharge = value[0] == 0x01.toByte(),
                        controlStates = state.controlStates + (identifier to value)
                    )
                } else {
                    state.copy(
                        controlStates = state.controlStates + (identifier to value)
                    )
                }
            }
        }

        listeners[identifier] = listener
    }

    private fun applyListeningMode(mode: Int) {
        if (mode !in 1..4) return
        sharedPreferences.edit { putInt("last_listening_mode", mode) }
        val modeBytes = byteArrayOf(mode.toByte())
        _uiState.update { state ->
            if (state.ancMode == mode &&
                state.controlStates[ControlCommandIdentifiers.LISTENING_MODE]
                    ?.contentEquals(modeBytes) == true
            ) {
                return@update state
            }
            state.copy(
                ancMode = mode,
                controlStates = state.controlStates + (ControlCommandIdentifiers.LISTENING_MODE to modeBytes)
            )
        }
    }

    // I'm lazy, sorry.
    fun observeAACP() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.IN_CASE_TONE_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
        service.aacpManager.customEqCallback = { customEq ->
            _uiState.update { it.copy(customEq = customEq) }
        }
    }

    fun loadCurrentStatus() {
        if (isDemoMode) return
        service.let { service ->
            val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
            // Listening mode is live only while AACP is up. Nearby/BLE is battery-only.
            val liveMode = if (aacpUp) {
                controlRepo.getValue(ControlCommandIdentifiers.LISTENING_MODE)
                    ?.getOrNull(0)?.toInt()?.takeIf { it in 1..4 }
                    ?: service.getANC().takeIf { it in 1..4 }
            } else {
                null
            }
            val persistedMode = sharedPreferences.getInt("last_listening_mode", 0)
                .takeIf { it in 1..4 }
            val mode = liveMode ?: persistedMode ?: 0
            val repoMap = if (aacpUp) controlRepo.getMap() else emptyMap()
            _uiState.update { state ->
                val mergedControls = if (aacpUp) {
                    state.controlStates + repoMap
                } else {
                    // Drop stale live control map when only BLE-nearby (battery path).
                    state.controlStates.filterKeys { it != ControlCommandIdentifiers.LISTENING_MODE }
                }
                val withMode = if (mode in 1..4 && aacpUp) {
                    mergedControls + (ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(mode.toByte()))
                } else if (aacpUp) {
                    mergedControls
                } else {
                    mergedControls
                }
                state.copy(
                    isLocallyConnected = aacpUp,
                    isNearby = aacpUp || service.bleManager.hasNearbyDevices(),
                    battery = service.getBattery(),
                    ancMode = if (aacpUp) mode else (persistedMode ?: 0),
                    controlStates = withMode
                )
            }
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = sharedPreferences.getBoolean("off_listening_mode", true)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val cameraAction = sharedPreferences.getString("camera_action", "SINGLE_PRESS")
            ?.let { name -> AACPManager.Companion.StemPressType.entries.find { it.name == name } }
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false)
        val dynamicEndOfCharge = sharedPreferences.getBoolean("dynamic_end_of_charge", false)

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                cameraAction = cameraAction,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook,
                dynamicEndOfCharge = dynamicEndOfCharge,
                connectionSuccessful = connectionSuccessful,
            )
        }

        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase
        if (BuildConfig.PLAY_BUILD) {
            val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
            val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
            val now = System.currentTimeMillis()

            when {
                // existing temporary premium
                expiryTime > 0L -> {
                    if (expiryTime <= now) {
                        sharedPreferences.edit {
                            remove("premium_expiry_time")
                            remove("foss_upgraded")
                        }

                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = 0L,
                                isPremium = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = expiryTime - now,
                                isPremium = true
                            )
                        }
                    }
                }

                // First migration from accidental FOSS Play build
                fossUpgraded && !_uiState.value.isPremium -> {
                    val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                    sharedPreferences.edit {
                        putLong("premium_expiry_time", newExpiry)
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = newExpiry - now,
                            isPremium = true
                        )
                    }
                }
            }
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        service.aacpManager.sendControlCommand(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE.value, enabled)
        sharedPreferences.edit { putBoolean("dynamic_end_of_charge", enabled) }
        _uiState.update {
            it.copy(dynamicEndOfCharge = enabled)
        }
    }

    private fun loadEq() {
        _uiState.update {
            it.copy(
                customEq = service.aacpManager.customEq
            )
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        service.setName(name)
    }

    fun startHeadTracking() {
        // UI / explicit user action — claiming ownership is OK here.
        service.startHeadTracking(allowOwnershipClaim = true)
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        when (handle) {
            // ideally should be using a different viewmodel for ATT based things because there are a lot of values, and I am not going to add all to this state, but there's loudsoundreduction.
            ATTHandles.LOUD_SOUND_REDUCTION -> {
                _uiState.value = _uiState.value.copy(loudSoundReductionEnabled = value[0].toInt() == 0x01)
            }
            ATTHandles.HEARING_AID -> {
                _uiState.value = _uiState.value.copy(hearingAidData = value)
            }
            ATTHandles.TRANSPARENCY -> {
                _uiState.value = _uiState.value.copy(transparencyData = value)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service.attManager.writeCharacteristic(handle, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadATT() {
        val loudSoundReduction = service.attManager.getCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION) ?: byteArrayOf()
        val loudSoundReductionEnabled = if (loudSoundReduction.isNotEmpty()) {
            loudSoundReduction[0].toInt() == 1
        } else false
        val hearingAidData = service.attManager.getCharacteristic(ATTHandles.HEARING_AID) ?: byteArrayOf()
        val transparencyData = service.attManager.getCharacteristic(ATTHandles.TRANSPARENCY) ?: byteArrayOf()
        _uiState.update {
            it.copy(
                loudSoundReductionEnabled = loudSoundReductionEnabled,
                transparencyData = transparencyData,
                hearingAidData = hearingAidData
            )
        }
    }

    fun observeATT() {
        viewModelScope.launch(Dispatchers.IO) {
            service.attManager.enableNotification(ATTCCCDHandles.HEARING_AID)
            service.attManager.enableNotification(ATTCCCDHandles.TRANSPARENCY)
        }
        service.attManager.setOnNotificationReceived { handle, value ->
            when (handle) {
                ATTHandles.LOUD_SOUND_REDUCTION.value.toByte() -> {
                    val loudSoundReductionEnabled = if (value.isNotEmpty()) {
                        value[0].toInt() == 1
                    } else false
                    _uiState.update {
                        it.copy(loudSoundReductionEnabled = loudSoundReductionEnabled)
                    }
                }
                ATTHandles.HEARING_AID.value.toByte() -> {
                    _uiState.update {
                        it.copy(hearingAidData = value)
                    }
                }
                ATTHandles.TRANSPARENCY.value.toByte() -> {
                    _uiState.update {
                        it.copy(transparencyData = value)
                    }
                }
            }
        }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun activateDemoMode() {
        isDemoMode = true
        _uiState.update {demoState}
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        if (isDemoMode) {
            isDemoMode = false
            _uiState.update {
                it.copy(isLocallyConnected = false)
            }
        } else {
            service.disconnectAirPods()
            if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    appContext, "App has disconnected, disconnect from Android Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
