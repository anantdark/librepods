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

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null
    /** WhatsApp status / ExoPlayer often play without AudioManager.isMusicActive. */
    @Volatile private var lastClaimWorthyPlaybackAt: Long = 0L
    private const val PLAYBACK_ACTIVE_HOLD_MS = 5_000L

    /**
     * After Mac takes audio — hold long enough for Secondary AACP refresh (~28s) + pause settle.
     * Short holds let residual NewPipe configs clear the flag and hard-claim Mac's stream.
     */
    private const val YIELD_TO_OTHER_DEVICE_HOLD_MS = 45_000L
    private const val RECENTLY_LOST_OWNERSHIP_MS = 20_000L
    private val clearPausedForOtherDeviceRunnable: Runnable = object : Runnable {
        override fun run() {
            // Stay yielded while coordinator still says Mac owns / Secondary.
            val service = ServiceManager.getService()
            if (service != null && service.shouldHoldYieldToOtherDevice()) {
                handler.postDelayed(this, YIELD_TO_OTHER_DEVICE_HOLD_MS)
                Log.d("MediaController", "Keeping pausedForOtherDevice — Mac/other still owns audio")
                return
            }
            pausedForOtherDevice = false
            Log.d(
                "MediaController",
                "Cleared pausedForOtherDevice after timeout, resuming normal playback monitoring"
            )
        }
    }
    private val clearRecentlyLostOwnershipRunnable = Runnable {
        // Fixed window only — do not extend while Mac owns, or user play can never reclaim.
        recentlyLostOwnership = false
        Log.d("MediaController", "Cleared recentlyLostOwnership after yield hold")
    }

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences) {
        if (this::audioManager.isInitialized) {
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        // Do not register playback callback here — AirPodsService enables it only when
        // Bluetooth is on (exitBluetoothOffStandby / create-with-BT-on).
    }

    private var monitoringEnabled = false

    /** Unregister playback callback when Bluetooth is off — no AirPods work possible. */
    fun setMonitoringEnabled(enabled: Boolean) {
        if (!this::audioManager.isInitialized) return
        if (monitoringEnabled == enabled) return
        monitoringEnabled = enabled
        try {
            if (enabled) {
                audioManager.registerAudioPlaybackCallback(cb, null)
                Log.d("MediaController", "Playback monitoring enabled")
            } else {
                audioManager.unregisterAudioPlaybackCallback(cb)
                Log.d("MediaController", "Playback monitoring disabled (Bluetooth off standby)")
            }
        } catch (e: Exception) {
            Log.w("MediaController", "Failed to toggle playback monitoring: ${e.message}")
        }
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val musicStreamActive = audioManager.isMusicActive

            Log.d("MediaController", "Configs received: ${configs?.size ?: 0} configurations")
            val startedClaimWorthy = configs?.any { config ->
                // WhatsApp status / many video players: started player, often not MUSIC stream.
                // PLAYER_STATE_STARTED = 2 (API 26+); older: presence in callback ≈ active.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val state = try {
                        config.javaClass.getMethod("getPlayerState").invoke(config) as Int
                    } catch (_: Exception) {
                        2
                    }
                    if (state != 2) return@any false
                }
                val attrs = config.audioAttributes
                if (attrs == null) {
                    Log.d("MediaController", "Started player with no audioAttributes — treat as media")
                    return@any true
                }
                Log.d(
                    "MediaController",
                    "Config content=${attrs.contentType} usage=${attrs.usage}"
                )
                isClaimWorthyPlayback(attrs)
            } == true

            val isActive = musicStreamActive || startedClaimWorthy
            if (isActive) {
                lastClaimWorthyPlaybackAt = now
            }

            // Standby: BT off, or no L2CAP and no BLE presence → skip takeover / AACP chatter.
            val service = ServiceManager.getService()
            if (service?.bluetoothOffStandby == true) {
                lastKnownIsMusicActive = isActive
                return
            }
            val aacpUp = BluetoothConnectionManager.aacpSocket?.isConnected == true
            if (service != null && !aacpUp && !service.bleManager.hasNearbyDevices()) {
                lastKnownIsMusicActive = isActive
                return
            }

            Log.d(
                "MediaController",
                "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive " +
                    "(musicStream=$musicStreamActive claimWorthy=$startedClaimWorthy), " +
                    "pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive"
            )

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = true
                return
            }

            // Never drop inactive→active play edges — debounce previously swallowed YouTube
            // (USAGE_MEDIA + CONTENT_TYPE_UNKNOWN) and skipped hard claim → AACP died ~40s later.
            val playEdgeCandidate = isActive && lastKnownIsMusicActive != true
            if (!playEdgeCandidate && now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                lastPlaybackCallbackAt = now
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = isActive
                return
            }

            // Claim on any started claim-worthy player, or MUSIC stream with empty configs.
            val hasNewMusicOrMovie =
                startedClaimWorthy || (musicStreamActive && (configs.isNullOrEmpty() || isActive))

            Log.d("MediaController", "Has media play signal: $hasNewMusicOrMovie")

            if (pausedForOtherDevice) {
                // Do NOT reschedule with a short timeout — residual pause configs used to
                // shrink the yield hold to 500ms and let keep-alive steal Mac audio.
                val macStillOwns = service?.shouldHoldYieldToOtherDevice() == true
                val truePlayEdge = isActive && lastKnownIsMusicActive != true && hasNewMusicOrMovie

                // After the post-yield settle window, a real play edge reclaims (steals from Mac).
                // recentlyLostOwnership blocks residual NewPipe configs right after our pause.
                if (truePlayEdge && !recentlyLostOwnership) {
                    Log.d(
                        "MediaController",
                        "User play while yielded — reclaiming (macStillOwns=$macStillOwns)"
                    )
                    pausedForOtherDevice = false
                    userPlayedTheMedia = true
                    if (!pausedWhileTakingOver) {
                        requestMusicOwnershipClaim()
                    }
                } else if (isActive) {
                    Log.d(
                        "MediaController",
                        "Ignoring playback while yielded " +
                            "(macStillOwns=$macStillOwns recentlyLost=$recentlyLostOwnership playEdge=$truePlayEdge)"
                    )
                }

                lastKnownIsMusicActive = isActive && hasNewMusicOrMovie
                return
            }

            if (configs != null && !iPausedTheMedia) {
                val localMac = ServiceManager.getService()?.localMac ?: return
                if (localMac == "") return
                ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
                    localMac,
                    isActive
                )
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                handler.postDelayed({
                    userPlayedTheMedia = getLocalPlaybackActive()
                    if (getLocalPlaybackActive()) {
                        pausedForOtherDevice = false
                    }
                }, 7)
            }

            Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
            if (!pausedWhileTakingOver && isActive && hasNewMusicOrMovie) {
                if (lastKnownIsMusicActive != true) {
                    if (!recentlyLostOwnership) {
                        Log.d("MediaController", "Media active — requesting ownership hard claim")
                        requestMusicOwnershipClaim()
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss")
                    }
                }
            }

            lastKnownIsMusicActive = hasNewMusicOrMovie && isActive
        }
    }

    /**
     * True for playback that should steal AirPods from Mac (music, video, WhatsApp status, …).
     * Excludes alarms, notification sounds, UI ticks, and in-call voice.
     */
    private fun isClaimWorthyPlayback(attrs: android.media.AudioAttributes): Boolean {
        when (attrs.usage) {
            android.media.AudioAttributes.USAGE_ALARM,
            android.media.AudioAttributes.USAGE_NOTIFICATION,
            android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT,
            android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> return false
        }
        when (attrs.contentType) {
            android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION -> return false
        }
        return true
    }

    /** Play-edge → ownership coordinator when present; else legacy takeOver("music"). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestMusicOwnershipClaim() {
        ServiceManager.getService()?.requestMusicOwnershipFromPlayEdge()
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    /**
     * Local media/video playback that should hard-claim AACP — includes players that do not
     * set [AudioManager.isMusicActive] (WhatsApp status, many ExoPlayer video surfaces).
     */
    @Synchronized
    fun getLocalPlaybackActive(): Boolean {
        if (!this::audioManager.isInitialized) return false
        if (audioManager.isMusicActive) return true
        return SystemClock.uptimeMillis() - lastClaimWorthyPlaybackAt < PLAYBACK_ACTIVE_HOLD_MS
    }

    @Synchronized
    fun sendPlayPause() {
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Sending pause because music is active")
            sendPause()
        } else {
            Log.d("MediaController", "Sending play because music is not active")
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        val streamActive = audioManager.isMusicActive
        val localActive = getLocalPlaybackActive()
        Log.d(
            "MediaController",
            "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, " +
                "isMusicActive: $streamActive, localPlayback: $localActive, force: $force"
        )
        // WhatsApp status / video often have localActive but not isMusicActive — still pause.
        if (force || ((streamActive || localActive) && !userPlayedTheMedia)) {
            iPausedTheMedia = true
            userPlayedTheMedia = false
            lastClaimWorthyPlaybackAt = 0L
            lastKnownIsMusicActive = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
    }

    /** After Mac takes audio — block stale playback holds from re-triggering hard claim. */
    @Synchronized
    fun clearLocalPlaybackForYield() {
        lastClaimWorthyPlaybackAt = 0L
        lastKnownIsMusicActive = false
        userPlayedTheMedia = false
        pausedForOtherDevice = true
        recentlyLostOwnership = true
        handler.removeCallbacks(clearPausedForOtherDeviceRunnable)
        handler.postDelayed(clearPausedForOtherDeviceRunnable, YIELD_TO_OTHER_DEVICE_HOLD_MS)
        handler.removeCallbacks(clearRecentlyLostOwnershipRunnable)
        handler.postDelayed(clearRecentlyLostOwnershipRunnable, RECENTLY_LOST_OWNERSHIP_MS)
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationalAwarenessVolume: $conversationalAwarenessVolume, relativeVolume: $relativeVolume")

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                sendPause(force = true)
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                sendPlay()
            }
            initialVolume = null
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
