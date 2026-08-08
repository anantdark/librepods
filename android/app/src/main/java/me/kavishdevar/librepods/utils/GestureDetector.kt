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

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Nod (vertical) = accept, shake (horizontal) = reject.
 *
 * Tuned from on-device natural gesture logs (2026-08-08):
 * - Idle: max(|h|,|v|) typically < 200
 * - Nod (accept): TWO nods — live logs show 3 alternating V extremes
 *   (e.g. -444 → +381 → -449), peaks ~380–625, pair ~0.4–1.4s
 * - Shake (reject): longer (~0.45–0.7s), H peaks ~500–870, 2–3 sign flips
 * Axes are coupled on AirPods — classification uses dominant-axis energy, not purity.
 */
class GestureDetector(
    private val airPodsService: AirPodsService
) {
    companion object {
        private const val TAG = "GestureDetector"

        private const val WARMUP_MS = 1000L
        private const val BASELINE_SAMPLES = 8
        private const val MAX_VALID_ORIENTATION_VALUE = 6000

        /** Count an extreme above this (idle stays <200; nod starts ~360). */
        private const val PEAK_AMPLITUDE_MIN = 300.0

        /** Direction reversal delta. */
        private const val DIRECTION_FLIP_MIN = 200.0

        /** Audio / significantMotion cue. */
        private const val IMMEDIATE_FEEDBACK_THRESHOLD = 350

        /**
         * Accept = two nods. Live logs show a double-nod as 3 alternating V extremes
         * (e.g. -444 → +381 → -449 over ~0.4–1.4s), not 4. One nod is only 2 extremes.
         * Shake needs clearer left/right alternation (3+ H extremes).
         */
        private const val MIN_EXTREMES_NOD = 3
        private const val MIN_EXTREMES_SHAKE = 3

        private const val EXTREME_TTL_MS = 2000L

        /**
         * Live double-nod: strong V peaks ~380–625, cycles ~0.2–0.5s each,
         * pair completes in ~0.4–1.4s. Extremes within a cycle can be ~100–250ms apart.
         */
        private const val MIN_GESTURE_SPAN_NOD_MS = 180L
        private const val MAX_GESTURE_SPAN_NOD_MS = 1600L
        private const val MIN_GESTURE_SPAN_SHAKE_MS = 250L
        private const val MAX_GESTURE_SPAN_SHAKE_MS = 2000L

        private const val MIN_CONFIDENCE_NOD = 0.58
        private const val MIN_CONFIDENCE_SHAKE = 0.72

        /**
         * Nod is coupled with H on AirPods — requiring V >> H buffer avg rejected
         * real nods (primary=502 secondary=769). Soft check vs other-axis extremes.
         */
        private const val NOD_DOMINANCE_RATIO = 0.85
        private const val SHAKE_DOMINANCE_RATIO = 1.15

        private const val MAX_BUFFER = 80
        /** Natural deliberate peaks ~400–850. */
        private const val AMPLITUDE_FULL_SCALE = 850.0
    }

    val audio = GestureFeedback(ServiceManager.getService()?.baseContext!!)

    private val horizontalBuffer = Collections.synchronizedList(ArrayList<Double>())
    private val verticalBuffer = Collections.synchronizedList(ArrayList<Double>())

    private val horizontalAvgBuffer = Collections.synchronizedList(ArrayList<Double>())
    private val verticalAvgBuffer = Collections.synchronizedList(ArrayList<Double>())

    private var prevHorizontal: Double = 0.0
    private var prevVertical: Double = 0.0
    private var hasBaseline = false
    private var samplesSeen = 0

    private val horizontalPeaks = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val horizontalTroughs = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val verticalPeaks = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val verticalTroughs = CopyOnWriteArrayList<Triple<Int, Double, Long>>()

    private var lastPeakTime: Long = 0
    private val peakIntervals = Collections.synchronizedList(ArrayList<Double>())

    private var horizontalIncreasing: Boolean? = null
    private var verticalIncreasing: Boolean? = null

    private var isRunning = false
    private var detectionJob: Job? = null
    private var gestureDetectedCallback: ((Boolean) -> Unit)? = null
    private var detectionStartedAt = 0L

    private var significantMotion = false
    private var lastSignificantMotionTime = 0L

    init {
        while (horizontalAvgBuffer.size < 3) horizontalAvgBuffer.add(0.0)
        while (verticalAvgBuffer.size < 3) verticalAvgBuffer.add(0.0)
    }

    fun startDetection(doNotStop: Boolean = false, onGestureDetected: (Boolean) -> Unit) {
        if (isRunning) return

        Log.d(TAG, "Starting gesture detection (warmup ${WARMUP_MS}ms)...")
        isRunning = true
        gestureDetectedCallback = onGestureDetected
        detectionStartedAt = System.currentTimeMillis()

        airPodsService.startHeadTracking(allowOwnershipClaim = true)

        clearData()

        detectionJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRunning) {
                delay(40)
                pruneStaleExtremes()
                val gesture = detectGestures()
                if (gesture != null) {
                    withContext(Dispatchers.Main) {
                        // Chime is the caller's job (after accept/reject), so audio
                        // follows the action instead of racing ahead of it.
                        gestureDetectedCallback?.invoke(gesture)
                        stopDetection(doNotStop)
                    }
                    break
                }
            }
        }
    }

    /** True while the detector loop is armed (call ring or test screen). */
    fun isDetecting(): Boolean = isRunning

    fun stopDetection(doNotStop: Boolean = false) {
        if (!isRunning) return

        Log.d(TAG, "Stopping gesture detection")
        isRunning = false

        detectionJob?.cancel()
        detectionJob = null
        gestureDetectedCallback = null
        clearData()

        if (!doNotStop) airPodsService.stopHeadTracking()
    }

    /** Distinct chime after a gesture action (accept vs decline). */
    fun playActionChime(accepted: Boolean) {
        audio.playConfirmation(accepted)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadOrientation(horizontal: Int, vertical: Int) {
        if (!isRunning) return

        if (abs(horizontal) > MAX_VALID_ORIENTATION_VALUE || abs(vertical) > MAX_VALID_ORIENTATION_VALUE) {
            return
        }

        samplesSeen++
        val now = System.currentTimeMillis()

        if (!hasBaseline || samplesSeen <= BASELINE_SAMPLES) {
            prevHorizontal = horizontal.toDouble()
            prevVertical = vertical.toDouble()
            hasBaseline = true
            pushSample(horizontal.toDouble(), vertical.toDouble())
            return
        }

        if (now - detectionStartedAt < WARMUP_MS || samplesSeen < BASELINE_SAMPLES + 12) {
            prevHorizontal = horizontal.toDouble()
            prevVertical = vertical.toDouble()
            pushSample(horizontal.toDouble(), vertical.toDouble())
            return
        }

        val horizontalDelta = horizontal - prevHorizontal
        val verticalDelta = vertical - prevVertical

        // Track motion for detection only — audio plays once on gesture complete
        // (playConfirmation), not on each peak.
        val significantHorizontal = abs(horizontalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD
        val significantVertical = abs(verticalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD

        if (significantHorizontal || significantVertical) {
            significantMotion = true
            lastSignificantMotionTime = now
        } else if (significantMotion && (now - lastSignificantMotionTime) > 500) {
            significantMotion = false
        }

        prevHorizontal = horizontal.toDouble()
        prevVertical = vertical.toDouble()
        pushSample(horizontal.toDouble(), vertical.toDouble())
        detectPeaksAndTroughs()
    }

    private fun pushSample(h: Double, v: Double) {
        val smoothH = applySmoothing(h, horizontalAvgBuffer)
        val smoothV = applySmoothing(v, verticalAvgBuffer)
        synchronized(horizontalBuffer) {
            horizontalBuffer.add(smoothH)
            if (horizontalBuffer.size > MAX_BUFFER) horizontalBuffer.removeAt(0)
        }
        synchronized(verticalBuffer) {
            verticalBuffer.add(smoothV)
            if (verticalBuffer.size > MAX_BUFFER) verticalBuffer.removeAt(0)
        }
    }

    private fun applySmoothing(newValue: Double, buffer: MutableList<Double>): Double {
        synchronized(buffer) {
            buffer.add(newValue)
            // Light smoothing — heavy averaging was wiping horizontal shake peaks.
            if (buffer.size > 2) buffer.removeAt(0)
            return buffer.average()
        }
    }

    private fun detectPeaksAndTroughs() {
        if (horizontalBuffer.size < 3 || verticalBuffer.size < 3) return

        processDirectionChanges(
            horizontalBuffer, horizontalIncreasing, horizontalPeaks, horizontalTroughs, "H"
        )?.let { horizontalIncreasing = it }

        processDirectionChanges(
            verticalBuffer, verticalIncreasing, verticalPeaks, verticalTroughs, "V"
        )?.let { verticalIncreasing = it }
    }

    private fun processDirectionChanges(
        buffer: List<Double>,
        isIncreasing: Boolean?,
        peaks: MutableList<Triple<Int, Double, Long>>,
        troughs: MutableList<Triple<Int, Double, Long>>,
        axis: String
    ): Boolean? {
        if (buffer.size < 2) return isIncreasing

        val current = buffer.last()
        val prev = buffer[buffer.size - 2]
        var increasing = isIncreasing ?: (current > prev)
        val now = System.currentTimeMillis()
        // Shake (H) flips can be slightly softer than nod in the smoothed stream.
        val flipMin = if (axis == "H") DIRECTION_FLIP_MIN * 0.85 else DIRECTION_FLIP_MIN
        val peakMin = if (axis == "H") PEAK_AMPLITUDE_MIN * 0.9 else PEAK_AMPLITUDE_MIN

        if (increasing && current < prev - flipMin) {
            if (abs(prev) > peakMin) {
                peaks.add(Triple(buffer.size - 1, prev, now))
                Log.d(TAG, "Peak ${prev.toInt()} on $axis")
                recordInterval(now)
            }
            increasing = false
        } else if (!increasing && current > prev + flipMin) {
            if (abs(prev) > peakMin) {
                troughs.add(Triple(buffer.size - 1, prev, now))
                Log.d(TAG, "Trough ${prev.toInt()} on $axis")
                recordInterval(now)
            }
            increasing = true
        }

        return increasing
    }

    private fun recordInterval(now: Long) {
        if (lastPeakTime > 0) {
            val interval = (now - lastPeakTime) / 1000.0
            synchronized(peakIntervals) {
                peakIntervals.add(interval)
                if (peakIntervals.size > 6) peakIntervals.removeAt(0)
            }
        }
        lastPeakTime = now
    }

    private fun pruneStaleExtremes() {
        val cutoff = System.currentTimeMillis() - EXTREME_TTL_MS
        fun prune(list: MutableList<Triple<Int, Double, Long>>) {
            list.removeAll { it.third < cutoff }
        }
        prune(horizontalPeaks)
        prune(horizontalTroughs)
        prune(verticalPeaks)
        prune(verticalTroughs)
    }

    private fun calculateRhythmConsistency(): Double {
        if (peakIntervals.size < 2) return 0.55 // nod often has too few intervals — don't zero out
        val meanInterval = peakIntervals.average()
        if (meanInterval == 0.0) return 0.55
        val variances = peakIntervals.map { (it / meanInterval - 1.0).pow(2) }
        return max(0.0, 1.0 - min(1.0, variances.average() / 0.5))
    }

    /**
     * Pick the newest alternating window of [minExtremes] whose time span is in range.
     * Avoids takeLast() pairing a fresh extreme with a stale one (span > max).
     */
    private fun selectRecentWindow(
        extremes: List<Triple<Int, Double, Long>>,
        minExtremes: Int,
        minSpan: Long,
        maxSpan: Long
    ): List<Triple<Int, Double, Long>>? {
        val sorted = extremes.sortedBy { it.third }
        if (sorted.size < minExtremes) return null
        for (end in sorted.size downTo minExtremes) {
            val window = sorted.subList(end - minExtremes, end)
            val span = window.last().third - window.first().third
            if (span < minSpan || span > maxSpan) continue
            val signs = window.map { if (it.second > 0) 1 else -1 }
            val alternating = (1 until signs.size).all { signs[it] != signs[it - 1] }
            if (alternating) return window.toList()
        }
        return null
    }

    /**
     * @param isVertical true = nod candidate, false = shake candidate
     */
    private fun scoreGesture(
        extremes: List<Triple<Int, Double, Long>>,
        isVertical: Boolean,
        minExtremes: Int
    ): Double {
        if (extremes.size < minExtremes) return 0.0

        val minSpan = if (isVertical) MIN_GESTURE_SPAN_NOD_MS else MIN_GESTURE_SPAN_SHAKE_MS
        val maxSpan = if (isVertical) MAX_GESTURE_SPAN_NOD_MS else MAX_GESTURE_SPAN_SHAKE_MS
        val dominanceRatio = if (isVertical) NOD_DOMINANCE_RATIO else SHAKE_DOMINANCE_RATIO

        val recent = selectRecentWindow(extremes, minExtremes, minSpan, maxSpan)
        if (recent == null) {
            val fallback = extremes.sortedBy { it.third }.takeLast(minExtremes)
            if (fallback.size >= minExtremes) {
                val span = fallback.last().third - fallback.first().third
                Log.d(
                    TAG,
                    "Reject span ${span}ms (${if (isVertical) "nod" else "shake"} needs $minSpan..$maxSpan)"
                )
            }
            return 0.0
        }

        val spanMs = recent.last().third - recent.first().third
        val avgAmplitude = recent.map { abs(it.second) }.average()
        if (avgAmplitude < PEAK_AMPLITUDE_MIN) return 0.0
        val amplitudeFactor = min(1.0, avgAmplitude / AMPLITUDE_FULL_SCALE)

        val rhythmFactor = calculateRhythmConsistency()

        val primaryAmp = avgAmplitude
        // Compare against the other axis's recent extremes (not the whole buffer avg),
        // so coupled H motion during a nod doesn't kill the score.
        val secondaryAmp = if (isVertical) {
            val hExt = (horizontalPeaks + horizontalTroughs)
                .sortedBy { it.third }
                .takeLast(4)
                .map { abs(it.second) }
            if (hExt.isNotEmpty()) hExt.average()
            else synchronized(horizontalBuffer) {
                horizontalBuffer.takeLast(8).map { abs(it) }.average()
            }.takeIf { !it.isNaN() } ?: 0.0
        } else {
            val vExt = (verticalPeaks + verticalTroughs)
                .sortedBy { it.third }
                .takeLast(4)
                .map { abs(it.second) }
            if (vExt.isNotEmpty()) vExt.average()
            else synchronized(verticalBuffer) {
                verticalBuffer.takeLast(8).map { abs(it) }.average()
            }.takeIf { !it.isNaN() } ?: 0.0
        }

        if (primaryAmp < secondaryAmp * dominanceRatio) {
            Log.d(
                TAG,
                "Reject ${if (isVertical) "nod" else "shake"}: primary=$primaryAmp secondary=$secondaryAmp (need ×$dominanceRatio)"
            )
            return 0.0
        }
        val isolationFactor = min(1.0, max(0.55, primaryAmp / (secondaryAmp + 1.0) / 2.0))

        val now = System.currentTimeMillis()
        if (!significantMotion || now - lastSignificantMotionTime > 700) {
            Log.d(TAG, "Reject: no recent significant motion")
            return 0.0
        }

        val score = (
            amplitudeFactor * 0.50 +
                rhythmFactor * 0.15 +
                isolationFactor * 0.35
            )
        Log.d(
            TAG,
            "${if (isVertical) "Nod" else "Shake"} window span=${spanMs}ms amp=$avgAmplitude → $score"
        )
        return score
    }

    private fun detectGestures(): Boolean? {
        if (samplesSeen < BASELINE_SAMPLES + 12) return null
        if (System.currentTimeMillis() - detectionStartedAt < WARMUP_MS) return null

        val nodExtremes = (verticalPeaks + verticalTroughs).sortedBy { it.third }
        val shakeExtremes = (horizontalPeaks + horizontalTroughs).sortedBy { it.third }

        val nodConfidence =
            if (nodExtremes.size >= MIN_EXTREMES_NOD)
                scoreGesture(nodExtremes, isVertical = true, MIN_EXTREMES_NOD)
            else 0.0
        val shakeConfidence =
            if (shakeExtremes.size >= MIN_EXTREMES_SHAKE)
                scoreGesture(shakeExtremes, isVertical = false, MIN_EXTREMES_SHAKE)
            else 0.0

        if (nodConfidence > 0 || shakeConfidence > 0) {
            Log.d(
                TAG,
                "Scores nod=$nodConfidence (need $MIN_CONFIDENCE_NOD) shake=$shakeConfidence (need $MIN_CONFIDENCE_SHAKE) " +
                    "vExt=${nodExtremes.size} hExt=${shakeExtremes.size}"
            )
        }

        val nodOk = nodConfidence >= MIN_CONFIDENCE_NOD
        val shakeOk = shakeConfidence >= MIN_CONFIDENCE_SHAKE

        // Pick the stronger signature when both fire (axes are coupled).
        return when {
            nodOk && shakeOk -> {
                if (nodConfidence >= shakeConfidence) {
                    Log.d(TAG, "Yes (nod) detected confidence=$nodConfidence (beat shake=$shakeConfidence)")
                    true
                } else {
                    Log.d(TAG, "No (shake) detected confidence=$shakeConfidence (beat nod=$nodConfidence)")
                    false
                }
            }
            nodOk -> {
                Log.d(TAG, "Yes (nod) detected confidence=$nodConfidence")
                true
            }
            shakeOk -> {
                Log.d(TAG, "No (shake) detected confidence=$shakeConfidence")
                false
            }
            else -> null
        }
    }

    private fun clearData() {
        horizontalBuffer.clear()
        verticalBuffer.clear()
        horizontalAvgBuffer.clear()
        verticalAvgBuffer.clear()
        while (horizontalAvgBuffer.size < 3) horizontalAvgBuffer.add(0.0)
        while (verticalAvgBuffer.size < 3) verticalAvgBuffer.add(0.0)
        horizontalPeaks.clear()
        horizontalTroughs.clear()
        verticalPeaks.clear()
        verticalTroughs.clear()
        peakIntervals.clear()
        horizontalIncreasing = null
        verticalIncreasing = null
        lastPeakTime = 0
        significantMotion = false
        lastSignificantMotionTime = 0L
        hasBaseline = false
        samplesSeen = 0
        prevHorizontal = 0.0
        prevVertical = 0.0
    }

    private fun Double.pow(exponent: Int): Double = this.pow(exponent.toDouble())
}
