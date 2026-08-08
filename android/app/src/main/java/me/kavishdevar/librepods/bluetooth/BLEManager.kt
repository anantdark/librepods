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

package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.kavishdevar.librepods.utils.BluetoothCryptography
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.iterator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager for Bluetooth Low Energy scanning operations specifically for AirPods.
 *
 * Defaults to [ScanPowerMode.LOW_POWER] so standby (AirPods away / not connected) uses
 * the least radio duty cycle Android allows. Escalates only while pods are nearby or
 * the lid is open, then drops back automatically.
 */
@OptIn(ExperimentalEncodingApi::class)
class BLEManager(private val context: Context) {

    enum class ScanPowerMode {
        /** Standby: AirPods not nearby or AACP already connected. Minimal radio. */
        LOW_POWER,
        /** Nearby but not actively interacting (battery / takeover presence). */
        BALANCED,
        /** Short bursts: lid open / fresh discovery. Highest duty cycle. */
        LOW_LATENCY
    }

    data class AirPodsStatus(
        val address: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val paired: Boolean = false,
        val model: String = "Unknown",
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val color: String = "Unknown",
        val connectionState: String = "Unknown"
    )

    fun getMostRecentStatus(): AirPodsStatus? {
        return deviceStatusMap.values.maxByOrNull { it.lastSeen }
    }

    fun hasNearbyDevices(): Boolean = deviceStatusMap.isNotEmpty()

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private var mScanCallback: ScanCallback? = null
    private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val verifiedAddresses = mutableSetOf<String>()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var currentGlobalLidState: Boolean? = null
    private var lastBroadcastTime: Long = 0
    private val processedAddresses = mutableSetOf<String>()

    private val lastValidLeftBatteryMap = mutableMapOf<String, Int>()
    private val lastValidRightBatteryMap = mutableMapOf<String, Int>()
    private val lastValidCaseBatteryMap = mutableMapOf<String, Int>()
    @Volatile private var isScanning = false
    @Volatile private var currentPowerMode = ScanPowerMode.LOW_POWER
    /** When true, prefer LOW_POWER even if pods are nearby (AACP already connected). */
    @Volatile private var aacpConnectedHint = false
    /** Adapter off — refuse all scan starts until [onBluetoothEnabled]. */
    @Volatile private var bluetoothOffParked = false
    private var scanFilter: ScanFilter? = null

    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    val connStates = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging Up", 0xFF to "Unknown"
    )

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanupStaleDevices()
            checkLidStateTimeout()
            maybeAutoAdjustPowerMode()
            cleanupHandler.postDelayed(this, cleanupIntervalFor(currentPowerMode))
        }
    }
    private val lowLatencyExpiryRunnable = Runnable {
        if (currentPowerMode == ScanPowerMode.LOW_LATENCY) {
            Log.d(TAG, "LOW_LATENCY burst expired — dropping to adaptive mode")
            applyAdaptivePowerMode()
        }
    }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    /**
     * Hint from the service that AACP L2CAP is up.
     * While connected: stop LE scanning entirely — avoids radio contention with
     * L2CAP/A2DP (scan stop/start right after connect was causing disconnect loops).
     * On disconnect: resume adaptive scan after a short settle delay.
     */
    fun setAacpConnected(connected: Boolean) {
        if (aacpConnectedHint == connected) return
        aacpConnectedHint = connected
        Log.d(TAG, "AACP connected hint=$connected")
        cleanupHandler.removeCallbacks(resumeAfterAacpRunnable)
        if (connected) {
            if (isScanning) {
                Log.d(TAG, "AACP up — stopping BLE scan to avoid stack contention")
                stopScanInternal(keepCleanup = false)
                cleanupHandler.removeCallbacks(lowLatencyExpiryRunnable)
            }
        } else {
            // Let ACL/A2DP settle before restarting LE; immediate restart fights reconnects.
            cleanupHandler.postDelayed(resumeAfterAacpRunnable, RESUME_SCAN_AFTER_AACP_MS)
        }
    }

    private val resumeAfterAacpRunnable = Runnable {
        if (bluetoothOffParked || aacpConnectedHint) return@Runnable
        val mode = desiredPowerMode()
        Log.d(TAG, "Resuming BLE scan after AACP drop ($mode)")
        startScanning(mode)
    }

    fun getScanPowerMode(): ScanPowerMode = currentPowerMode

    @SuppressLint("MissingPermission")
    fun startScanning(mode: ScanPowerMode = ScanPowerMode.LOW_POWER) {
        try {
            if (bluetoothOffParked) {
                Log.d(TAG, "Skipping BLE start — Bluetooth off (parked)")
                return
            }
            if (aacpConnectedHint) {
                Log.d(TAG, "Skipping BLE start — AACP connected (scan parked)")
                return
            }
            Log.d(TAG, "Starting BLE scanner in $mode")

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                return
            }

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled")
                bluetoothOffParked = true
                return
            }

            if (isScanning && mScanCallback != null && currentPowerMode == mode) {
                Log.d(TAG, "BLE scanner already running in $mode")
                return
            }

            stopScanInternal(keepCleanup = false)

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner
            currentPowerMode = mode

            if (scanFilter == null) {
                scanFilter = buildAirPodsScanFilter()
            }

            mScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    processScanResult(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    processedAddresses.clear()
                    for (result in results) {
                        processScanResult(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                    isScanning = false
                }
            }

            mBluetoothLeScanner?.startScan(
                listOf(scanFilter),
                buildScanSettings(mode),
                mScanCallback
            )
            isScanning = true
            Log.d(TAG, "BLE scanner started successfully ($mode)")

            cleanupHandler.removeCallbacks(cleanupRunnable)
            cleanupHandler.postDelayed(cleanupRunnable, cleanupIntervalFor(mode))
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting BLE scanner", t)
            isScanning = false
        }
    }

    /**
     * Restart the scan with a new power mode if different from the current one.
     * No-op when not currently scanning.
     */
    fun setScanPowerMode(mode: ScanPowerMode) {
        if (bluetoothOffParked) return
        if (!isScanning) {
            currentPowerMode = mode
            return
        }
        if (currentPowerMode == mode) return
        Log.d(TAG, "Switching BLE scan power $currentPowerMode → $mode")
        startScanning(mode)
        if (mode == ScanPowerMode.LOW_LATENCY) {
            cleanupHandler.removeCallbacks(lowLatencyExpiryRunnable)
            cleanupHandler.postDelayed(lowLatencyExpiryRunnable, LOW_LATENCY_BURST_MS)
        } else {
            cleanupHandler.removeCallbacks(lowLatencyExpiryRunnable)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            Log.d(TAG, "Stopping BLE scanner")
            cleanupHandler.removeCallbacks(resumeAfterAacpRunnable)
            stopScanInternal(keepCleanup = false)
            cleanupHandler.removeCallbacks(lowLatencyExpiryRunnable)
            aacpConnectedHint = false
            currentPowerMode = ScanPowerMode.LOW_POWER
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
        }
    }

    /**
     * Bluetooth radio off — stop all LE work and drop tracked presence so the service
     * holds almost no background cost until the adapter is enabled again.
     */
    fun onBluetoothDisabled() {
        Log.d(TAG, "Bluetooth disabled — parking BLE manager")
        bluetoothOffParked = true
        cleanupHandler.removeCallbacks(resumeAfterAacpRunnable)
        cleanupHandler.removeCallbacks(lowLatencyExpiryRunnable)
        cleanupHandler.removeCallbacks(cleanupRunnable)
        stopScanning()
        deviceStatusMap.clear()
        processedAddresses.clear()
        verifiedAddresses.clear()
        currentGlobalLidState = null
        lastBroadcastTime = 0
        airPodsStatusListener?.onDeviceDisappeared()
    }

    /** Adapter on again — allow [startScanning] (caller starts the scan). */
    fun onBluetoothEnabled() {
        bluetoothOffParked = false
        Log.d(TAG, "Bluetooth enabled — BLE manager unparked")
    }

    fun isBluetoothOffParked(): Boolean = bluetoothOffParked

    fun isScanning(): Boolean = isScanning

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(keepCleanup: Boolean) {
        if (mBluetoothLeScanner != null && mScanCallback != null) {
            try {
                mBluetoothLeScanner?.stopScan(mScanCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "stopScan failed", t)
            }
            mScanCallback = null
        }
        isScanning = false
        if (!keepCleanup) {
            cleanupHandler.removeCallbacks(cleanupRunnable)
        }
    }

    private fun buildAirPodsScanFilter(): ScanFilter {
        val manufacturerData = ByteArray(27)
        val manufacturerDataMask = ByteArray(27)
        manufacturerData[0] = 7
        manufacturerData[1] = 25
        manufacturerDataMask[0] = -1
        manufacturerDataMask[1] = -1
        return ScanFilter.Builder()
            .setManufacturerData(76, manufacturerData, manufacturerDataMask)
            .build()
    }

    private fun buildScanSettings(mode: ScanPowerMode): ScanSettings {
        val builder = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        when (mode) {
            ScanPowerMode.LOW_POWER -> {
                builder
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
                    .setReportDelay(2000L)
            }
            ScanPowerMode.BALANCED -> {
                builder
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
                    .setReportDelay(1000L)
            }
            ScanPowerMode.LOW_LATENCY -> {
                builder
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(500L)
            }
        }
        return builder.build()
    }

    private fun cleanupIntervalFor(mode: ScanPowerMode): Long = when (mode) {
        ScanPowerMode.LOW_POWER -> CLEANUP_INTERVAL_LOW_POWER_MS
        ScanPowerMode.BALANCED -> CLEANUP_INTERVAL_BALANCED_MS
        ScanPowerMode.LOW_LATENCY -> CLEANUP_INTERVAL_LOW_LATENCY_MS
    }

    private fun desiredPowerMode(): ScanPowerMode {
        // AACP-connected scanning is handled by setAacpConnected (scan fully stopped).
        if (currentGlobalLidState == true) return ScanPowerMode.LOW_LATENCY
        if (deviceStatusMap.isNotEmpty()) return ScanPowerMode.BALANCED
        return ScanPowerMode.LOW_POWER
    }

    private fun applyAdaptivePowerMode() {
        // Never stop/restart the scanner from inside a ScanCallback — post to main.
        cleanupHandler.post {
            if (aacpConnectedHint || !isScanning) return@post
            val desired = desiredPowerMode()
            // Don't cut a LOW_LATENCY burst short unless devices vanished → LOW_POWER.
            if (currentPowerMode == ScanPowerMode.LOW_LATENCY &&
                desired != ScanPowerMode.LOW_POWER &&
                cleanupHandler.hasCallbacks(lowLatencyExpiryRunnable)
            ) {
                return@post
            }
            if (desired != currentPowerMode) {
                setScanPowerMode(desired)
            }
        }
    }

    private fun maybeAutoAdjustPowerMode() {
        if (aacpConnectedHint || !isScanning) return
        val desired = desiredPowerMode()
        if (desired != currentPowerMode) {
            Log.d(TAG, "Auto-adjust scan power $currentPowerMode → $desired")
            setScanPowerMode(desired)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getEncryptionKeyFromPreferences(): ByteArray? {
        val keyBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, null)
        return if (keyBase64 != null) {
            try {
                Base64.decode(keyBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode encryption key", e)
                null
            }
        } else {
            null
        }
    }

    @SuppressLint("GetInstance")
    private fun decryptLastBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (data.size < 16) {
                return null
            }

            val block = data.copyOfRange(data.size - 16, data.size)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            null
        }
    }

    /**
     * Decrypted proximity battery byte: bit7 = charging, bits0-6 = percent.
     * 0x7F (127) is Apple's unknown/unavailable sentinel — not a real charge level.
     */
    private fun formatBattery(byteVal: Int): Pair<Boolean, Int?> {
        val charging = (byteVal and 0x80) != 0
        val level = byteVal and 0x7F
        return Pair(charging, level.takeIf { it in 0..100 })
    }

    private fun resolveBattery(
        address: String,
        level: Int?,
        cache: MutableMap<String, Int>
    ): Int? {
        if (level != null) {
            cache[address] = level
            return level
        }
        return cache[address]
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            if (processedAddresses.contains(address)) {
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(76) ?: return
            if (manufacturerData.size <= 20) return

            if (!verifiedAddresses.contains(address)) {
                val irk = getIrkFromPreferences()
                if (irk == null || !BluetoothCryptography.verifyRPA(address, irk)) {
                    return
                }
                verifiedAddresses.add(address)
                Log.d(TAG, "RPA verified and added to trusted list: $address")
            }

            processedAddresses.add(address)
            lastBroadcastTime = System.currentTimeMillis()

            val encryptionKey = getEncryptionKeyFromPreferences()
            val decryptedData = if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey) else null
            val parsedStatus = if (decryptedData != null && decryptedData.size == 16) {
                parseProximityMessageWithDecryption(address, manufacturerData, decryptedData)
            } else {
                parseProximityMessage(address, manufacturerData)
            }

            val previousStatus = deviceStatusMap[address]
            val wasEmpty = deviceStatusMap.isEmpty()
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                if (previousStatus == null) {
                    listener.onBroadcastFromNewAddress(parsedStatus)
                    Log.d(TAG, "New AirPods device detected: $address")

                    if (currentGlobalLidState == null || currentGlobalLidState != parsedStatus.lidOpen) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus.lidOpen)
                        Log.d(TAG, "Lid state ${if (parsedStatus.lidOpen) "opened" else "closed"} (detected from new device)")
                    }
                } else {
                    if (parsedStatus != previousStatus) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus.lidOpen)
                            Log.d(TAG, "Lid state changed from $previousGlobalState to ${parsedStatus.lidOpen}")
                        }
                    }

                    if (parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                        parsedStatus.isRightInEar != previousStatus.isRightInEar) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar
                        )
                        Log.d(TAG, "Ear state changed - Left: ${parsedStatus.isLeftInEar}, Right: ${parsedStatus.isRightInEar}")
                    }

                    if (parsedStatus.leftBattery != previousStatus.leftBattery ||
                        parsedStatus.rightBattery != previousStatus.rightBattery ||
                        parsedStatus.caseBattery != previousStatus.caseBattery) {
                        listener.onBatteryChanged(parsedStatus)
                        Log.d(TAG, "Battery changed - Left: ${parsedStatus.leftBattery}, Right: ${parsedStatus.rightBattery}, Case: ${parsedStatus.caseBattery}")
                    }
                }
            }

            // Escalate once pods appear or lid opens; stay low-power while AACP is up.
            if (wasEmpty || currentGlobalLidState == true) {
                applyAdaptivePowerMode()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun parseProximityMessageWithDecryption(address: String, data: ByteArray, decrypted: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
//        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val (isLeftCharging, rawLeftBattery) = formatBattery(decrypted[leftByteIndex].toInt() and 0xFF)
        val (isRightCharging, rawRightBattery) = formatBattery(decrypted[rightByteIndex].toInt() and 0xFF)
        val (isCaseCharging, rawCaseBattery) = formatBattery(decrypted[3].toInt() and 0xFF)

        val leftBattery = resolveBattery(address, rawLeftBattery, lastValidLeftBatteryMap)
        val rightBattery = resolveBattery(address, rawRightBattery, lastValidRightBatteryMap)
        val caseBattery = resolveBattery(address, rawCaseBattery, lastValidCaseBatteryMap)

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceStatusMap.isNotEmpty()

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            deviceStatusMap.remove(device.key)
            lastValidLeftBatteryMap.remove(device.key)
            lastValidRightBatteryMap.remove(device.key)
            lastValidCaseBatteryMap.remove(device.key)
            Log.d(TAG, "Removed stale device from tracking: ${device.key}")
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
            applyAdaptivePowerMode()
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBroadcastTime > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true) {
            Log.d(TAG, "No broadcasts for ${LID_CLOSE_TIMEOUT_MS}ms, forcing lid state to closed")
            currentGlobalLidState = false
            airPodsStatusListener?.onLidStateChanged(false)
            // Lid-driven LOW_LATENCY no longer needed.
            applyAdaptivePowerMode()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getIrkFromPreferences(): ByteArray? {
        val irkBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)
        return if (irkBase64 != null) {
            try {
                Base64.decode(irkBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode IRK", e)
                null
            }
        } else {
            null
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_LOW_POWER_MS = 30000L
        private const val CLEANUP_INTERVAL_BALANCED_MS = 15000L
        private const val CLEANUP_INTERVAL_LOW_LATENCY_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        private const val LID_CLOSE_TIMEOUT_MS = 2500L
        /** Cap aggressive scanning after lid-open / discovery bursts. */
        private const val LOW_LATENCY_BURST_MS = 15_000L
        /** Delay LE resume after L2CAP drop so ACL/A2DP can settle. */
        private const val RESUME_SCAN_AFTER_AACP_MS = 2_500L
    }
}
