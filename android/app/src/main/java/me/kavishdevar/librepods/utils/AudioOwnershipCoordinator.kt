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

package me.kavishdevar.librepods.utils

import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Continuity-style audio-ownership FSM (reuse-first).
 *
 * Prefs mapping (App Settings):
 * - Phone-state [takeover_when_media_start] / [takeover_when_ringing_call]
 * - AirPods-status [takeover_when_disconnected|idle|music|call] — e.g. music off ⇒
 *   do not hijack while Mac is Playing media
 * - Soft OWNS only when not Secondary and not idle-connect
 *
 * Packet I/O stays in AirPodsService; this only picks soft / hard / yield once per intent edge.
 */
class AudioOwnershipCoordinator(
    private val host: Host
) {
    enum class State {
        LinkedIdle,
        OwningMedia,
        OwningCall,
        Secondary,
        UserPinned
    }

    /**
     * Thin adapters over existing AirPodsService helpers
     * (takeOver / releaseAacpOwnershipToOtherDevice / prioritizeCallAudioNow / connectAudio).
     */
    interface Host {
        fun hardClaim(reason: String)
        fun prioritizeCallAudio()
        fun onCallTakeOverRequested()
        fun yieldToOtherDevice(keepHeadset: Boolean)
        fun softClaimForStatusSync()
        fun isRinging(): Boolean
        fun isInCall(): Boolean
        fun isLocalPlaying(): Boolean
        fun otherIsAudioSource(): Boolean
        fun otherSourceIsCall(): Boolean
        fun ownsConnection(): Boolean
        fun localOwnsAudioSource(): Boolean
        fun isAacpConnected(): Boolean
        fun showYieldIslandWithReverse()
        fun showTakingOverIsland()
        fun setOtherDeviceTookOver(value: Boolean)
        /** Phone-state + AirPods-status toggles for music hard claim. */
        fun isMusicTakeOverAllowedByPrefs(): Boolean
    }

    @Volatile
    var state: State = State.LinkedIdle
        private set

    @Volatile private var lastHardClaimMs = 0L
    @Volatile private var lastHardClaimReason: String? = null
    @Volatile private var confirmRetryUsed = false
    @Volatile private var lastAudioSourceKey: String = ""
    /** When we entered Secondary — Mac audio-source often flaps to NONE during handoff. */
    @Volatile private var secondarySinceMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var confirmRunnable: Runnable? = null

    companion object {
        private const val TAG = "AudioOwnership"
        /** One hard claim per intent edge; confirm retry uses the same window. */
        private const val HARD_CLAIM_DEBOUNCE_MS = 1_500L
        private const val CONFIRM_RETRY_DELAY_MS = 1_500L
        /**
         * Hold Secondary after Mac takes audio even if audio-source briefly reports NONE.
         * Leaving Secondary early lets soft-OWNS steal Mac playback.
         */
        private const val SECONDARY_HOLD_MS = 20_000L
    }

    /**
     * AACP (re)connect: handshake/notifications only — no soft/hard claim.
     *
     * Preserve Secondary across silent relink / proactive refresh. Resetting to LinkedIdle
     * let connect-path OWNS+media steal Mac audio while A2DP was still up.
     */
    fun onConnect() {
        when (state) {
            State.OwningCall -> {
                // Keep call priority across brief AACP flaps.
            }
            State.Secondary -> {
                // Relink while Mac owns — stay Secondary; do not clear secondarySinceMs.
                lastAudioSourceKey = ""
            }
            else -> {
                state = State.LinkedIdle
                lastAudioSourceKey = ""
                secondarySinceMs = 0L
            }
        }
        Log.d(TAG, "onConnect → $state")
    }

    /**
     * Android MUSIC/MOVIE play edge. Maps to existing takeOver("music") under prefs.
     * Blocked while OwningCall, Mac CALL, or when App Settings toggles deny hijack
     * (e.g. Mac Playing media + "Playing media" AirPods-status toggle off).
     */
    fun onLocalPlayStarted() {
        if (!allowMusicHardClaim()) {
            Log.d(TAG, "onLocalPlayStarted: blocked (call priority or Mac CALL)")
            return
        }
        if (!host.isMusicTakeOverAllowedByPrefs()) {
            Log.d(TAG, "onLocalPlayStarted: blocked by App Settings takeover toggles")
            return
        }
        if (!shouldHardClaim("music")) return

        val fromSecondary = state == State.Secondary || host.otherIsAudioSource()
        state = State.OwningMedia
        host.setOtherDeviceTookOver(false)
        if (fromSecondary) {
            host.showTakingOverIsland()
        }
        host.hardClaim("music")
        scheduleConfirmRetry("music")
    }

    /**
     * AirPods reported another device as audio source (Mac MEDIA/CALL).
     *
     * Yield audio ownership whenever Mac/other is the source (track switch, Mac play, CALL).
     * Stay AACP-linked via notification keep-alive + silent relink — never soft-OWNS fight.
     */
    fun onOtherAudioSource(key: String, otherIsSource: Boolean) {
        if (!otherIsSource) {
            if (key == lastAudioSourceKey) return
            lastAudioSourceKey = key
            if (state == State.Secondary) {
                val heldMs = System.currentTimeMillis() - secondarySinceMs
                // A2DP reconnect can briefly mark the phone as audio source and steal Mac
                // media even when takeover toggles are off. Only leave Secondary on local
                // ownership when App Settings allow music takeover; otherwise re-yield.
                if (host.localOwnsAudioSource()) {
                    if (host.isMusicTakeOverAllowedByPrefs()) {
                        state = State.LinkedIdle
                        Log.d(TAG, "onOtherAudioSource: local owns audio → LinkedIdle")
                    } else {
                        Log.d(
                            TAG,
                            "onOtherAudioSource: local owns but media takeover off — re-yield"
                        )
                        host.yieldToOtherDevice(keepHeadset = true)
                        host.setOtherDeviceTookOver(true)
                    }
                    return
                }
                // Time-only hold for NONE flaps during Mac handoff.
                // Do NOT pin on isLocalPlaying — residual/resume configs + keep-alive pauses
                // made Secondary sticky forever and blocked Android audio.
                if (heldMs < SECONDARY_HOLD_MS) {
                    Log.d(TAG, "onOtherAudioSource: hold Secondary (${heldMs}ms, NONE flap)")
                    return
                }
                state = State.LinkedIdle
                Log.d(TAG, "onOtherAudioSource: Secondary hold expired → LinkedIdle")
            }
            return
        }

        if (host.isRinging() || host.isInCall() || state == State.OwningCall) {
            Log.d(TAG, "onOtherAudioSource: ignore yield — OwningCall")
            lastAudioSourceKey = key
            return
        }

        // Already yielded for this source key — skip spam (~15Hz). Re-yield if we had
        // reclaimed (OwningMedia) so Mac play / track switch pauses Android again.
        if (key == lastAudioSourceKey && state == State.Secondary) return
        lastAudioSourceKey = key

        val reason = if (host.otherSourceIsCall()) "CALL" else "MEDIA"
        Log.d(TAG, "onOtherAudioSource → Secondary (Mac $reason — pause Android, release audio)")
        cancelConfirmRetry()
        enterSecondary()
        host.yieldToOtherDevice(keepHeadset = true)
        host.setOtherDeviceTookOver(true)
        host.showYieldIslandWithReverse()
    }

    /** Telephony RINGING / OFFHOOK / IDLE. Call > media. */
    fun onCallState(callState: Int) {
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                state = State.OwningCall
                // HFP/SCO first — existing fast path; takeOver second for Mac pause.
                host.prioritizeCallAudio()
                host.onCallTakeOverRequested()
                // Confirm ownership once after the first claim edge (retry loop may claim earlier).
                scheduleConfirmRetry("call")
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (state == State.OwningCall) {
                    state = if (host.otherIsAudioSource()) State.Secondary else State.LinkedIdle
                }
                cancelConfirmRetry()
                Log.d(TAG, "onCallState IDLE → $state")
            }
        }
    }

    /** Island reverse / user-initiated reclaim. */
    fun onUserReverse() {
        state = State.UserPinned
        host.setOtherDeviceTookOver(false)
        if (shouldHardClaim("reverse")) {
            host.hardClaim("reverse")
            scheduleConfirmRetry("reverse")
        } else {
            host.hardClaim("reverse")
        }
    }

    /**
     * OWNS flipped false from peer (not necessarily a new audio-source packet).
     *
     * Always yield — Mac MEDIA packets arrive ~0.5–1s after OWNS=0. Soft-reclaiming here
     * races the handoff and steals audio back before Mac can become the source.
     */
    fun onOwnershipLost() {
        if (host.isRinging() || host.isInCall() || state == State.OwningCall) return
        if (state == State.Secondary) return

        Log.d(TAG, "onOwnershipLost → Secondary (yield audio to peer)")
        enterSecondary()
        host.yieldToOtherDevice(keepHeadset = true)
        host.setOtherDeviceTookOver(true)
        host.showYieldIslandWithReverse()
    }

    /** Soft OWNS for status — never while Secondary / other device owns audio. */
    fun maybeSoftClaimForStatusSync() {
        if (state == State.Secondary) return
        if (host.otherIsAudioSource() && !host.isRinging() && !host.isInCall()) return
        host.softClaimForStatusSync()
    }

    fun allowMusicHardClaim(): Boolean {
        if (host.isRinging() || host.isInCall() || state == State.OwningCall) return false
        if (host.otherSourceIsCall()) return false
        // Idle phone must not steal Mac MEDIA. User play edges set localPlaying first so reclaim works.
        // Keep-alive / connect paths check Secondary separately and must not call this to steal.
        if (host.otherIsAudioSource() && !host.isLocalPlaying()) return false
        return true
    }

    /** Called after a successful hard claim path inside takeOver (optional bookkeeping). */
    fun onHardClaimIssued(reason: String) {
        when (reason) {
            "call" -> state = State.OwningCall
            "music" -> if (state != State.OwningCall) state = State.OwningMedia
            "reverse" -> state = State.UserPinned
        }
        host.setOtherDeviceTookOver(false)
    }

    /** Peer hijack / yield already applied — bookkeep without a second yield. */
    fun markYieldedToOther() {
        cancelConfirmRetry()
        enterSecondary()
        host.setOtherDeviceTookOver(true)
    }

    fun reset() {
        cancelConfirmRetry()
        state = State.LinkedIdle
        lastAudioSourceKey = ""
        secondarySinceMs = 0L
        lastHardClaimMs = 0L
        lastHardClaimReason = null
        confirmRetryUsed = false
    }

    private fun enterSecondary() {
        state = State.Secondary
        secondarySinceMs = System.currentTimeMillis()
    }

    private fun shouldHardClaim(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (reason == lastHardClaimReason && now - lastHardClaimMs < HARD_CLAIM_DEBOUNCE_MS) {
            Log.d(TAG, "hard claim $reason debounced (${now - lastHardClaimMs}ms)")
            return false
        }
        lastHardClaimMs = now
        lastHardClaimReason = reason
        confirmRetryUsed = false
        return true
    }

    private fun scheduleConfirmRetry(reason: String) {
        cancelConfirmRetry()
        val r = Runnable {
            if (confirmRetryUsed) return@Runnable
            if (!host.isAacpConnected()) return@Runnable
            if (reason == "music" && !allowMusicHardClaim()) return@Runnable
            if (host.localOwnsAudioSource() || (host.ownsConnection() && !host.otherIsAudioSource())) {
                Log.d(TAG, "ownership confirm ok for $reason")
                return@Runnable
            }
            confirmRetryUsed = true
            Log.d(TAG, "ownership confirm failed — one retry hardClaim($reason)")
            host.hardClaim(reason)
        }
        confirmRunnable = r
        handler.postDelayed(r, CONFIRM_RETRY_DELAY_MS)
    }

    fun cancelConfirmRetry() {
        confirmRunnable?.let { handler.removeCallbacks(it) }
        confirmRunnable = null
    }
}
