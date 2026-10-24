package com.soundcatch.recorder

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 소리 감지 로직. 안드로이드 의존성이 없어서 PC(JVM)에서도 테스트할 수 있다.
 *
 * 동작 방식
 * 1. 주변 소음 크기(바닥 소음, floor)를 계속 추적한다.
 *    - 조용해지면 빠르게 내려가고, 시끄러워지면 아주 천천히(약 2분) 따라 올라간다.
 * 2. 바닥 소음보다 [Config.marginDb] 이상 큰 소리가 [ATTACK_MS] 이상 이어지면 녹음을 시작한다.
 *    이때 직전 [Config.preRollSec]초 분량도 함께 넘겨서 첫마디가 잘리지 않게 한다.
 * 3. 녹음 중 [Config.silenceSec]초 동안 기준 이상의 소리가 없으면 녹음을 끝낸다.
 * 4. 한 파일이 [Config.maxSegmentSec]초를 넘으면 파일만 나누고 이어서 녹음한다.
 */
class SoundDetector(
    private val sampleRate: Int,
    initialConfig: Config,
) {
    data class Config(
        /** 1(둔감) ~ 10(민감) */
        val sensitivity: Int = 6,
        val silenceSec: Int = 10,
        val preRollSec: Int = 2,
        val maxSegmentSec: Int = 600,
    ) {
        /** 바닥 소음보다 몇 dB 커야 '소리'로 볼지. 감도 6 → 17.5dB */
        val marginDb: Double get() = 30.0 - (sensitivity.coerceIn(1, 10) - 1) * 2.5
    }

    interface Listener {
        fun onRecordStart()
        fun onRecordData(samples: ShortArray, count: Int)

        /** split=true 이면 파일만 나누는 것이고, 곧바로 onRecordStart()가 다시 불린다. */
        fun onRecordStop(split: Boolean)
    }

    @Volatile
    var config: Config = initialConfig

    var levelDb = SILENCE_DB
        private set
    var floorDb = SILENCE_DB
        private set
    var thresholdDb = SILENCE_DB
        private set
    var isRecording = false
        private set

    /** 지금까지 받은 프레임이 전부 '완전 무음(0)'이었는지 (마이크가 막혔는지 판단용) */
    var onlyDigitalSilence = true
        private set

    private var samplesSeen = 0L
    private var floorReady = false
    private var voicedRun = 0L
    private var silenceRun = 0L
    private var segmentSamples = 0L

    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollSamples = 0L
    private val pool = ArrayDeque<ShortArray>()

    fun process(frame: ShortArray, count: Int, listener: Listener) {
        if (count <= 0) return
        val db = measureDb(frame, count)
        levelDb = db
        samplesSeen += count
        if (db > SILENCE_DB) onlyDigitalSilence = false
        updateFloor(db, count)

        val cfg = config
        thresholdDb = max(floorDb + cfg.marginDb, MIN_THRESHOLD_DB)
        val warmedUp = samplesSeen > sampleRate.toLong() * WARMUP_MS / 1000
        val voiced = warmedUp && db >= thresholdDb

        if (!isRecording) {
            keepPreRoll(frame, count, cfg)
            voicedRun = if (voiced) voicedRun + count else 0L
            if (voicedRun >= attackSamples()) {
                isRecording = true
                silenceRun = 0L
                segmentSamples = 0L
                listener.onRecordStart()
                while (preRoll.isNotEmpty()) {
                    val buf = preRoll.removeFirst()
                    listener.onRecordData(buf, buf.size)
                    segmentSamples += buf.size
                    recycle(buf)
                }
                preRollSamples = 0L
            }
            return
        }

        listener.onRecordData(frame, count)
        segmentSamples += count
        silenceRun = if (voiced) 0L else silenceRun + count
        if (silenceRun >= cfg.silenceSec.toLong() * sampleRate) {
            stopRecording(listener)
        } else if (segmentSamples >= cfg.maxSegmentSec.toLong() * sampleRate) {
            listener.onRecordStop(split = true)
            segmentSamples = 0L
            listener.onRecordStart()
        }
    }

    /** 서비스를 끌 때: 녹음 중이면 파일을 마무리한다. */
    fun finish(listener: Listener) {
        if (isRecording) stopRecording(listener)
        while (preRoll.isNotEmpty()) recycle(preRoll.removeFirst())
        preRollSamples = 0L
        voicedRun = 0L
    }

    private fun stopRecording(listener: Listener) {
        isRecording = false
        voicedRun = 0L
        silenceRun = 0L
        segmentSamples = 0L
        listener.onRecordStop(split = false)
    }

    private fun attackSamples(): Long = sampleRate.toLong() * ATTACK_MS / 1000

    private fun keepPreRoll(frame: ShortArray, count: Int, cfg: Config) {
        val copy = obtain(count)
        System.arraycopy(frame, 0, copy, 0, count)
        preRoll.addLast(copy)
        preRollSamples += count
        val keep = cfg.preRollSec.coerceIn(0, MAX_PREROLL_SEC).toLong() * sampleRate + attackSamples() + count
        while (preRoll.size > 1 && preRollSamples - preRoll.first().size >= keep) {
            val old = preRoll.removeFirst()
            preRollSamples -= old.size
            recycle(old)
        }
    }

    private fun obtain(size: Int): ShortArray {
        val reused = pool.removeLastOrNull()
        return if (reused != null && reused.size == size) reused else ShortArray(size)
    }

    private fun recycle(buf: ShortArray) {
        if (pool.size < MAX_POOL) pool.addLast(buf)
    }

    private fun updateFloor(db: Double, count: Int) {
        // 완전 무음(0) 프레임은 마이크 시작 직후·다른 앱 사용 중에 생긴다 → 바닥 소음 계산에서 제외
        if (db <= SILENCE_DB) return
        if (!floorReady) {
            floorDb = db
            floorReady = true
            return
        }
        val dt = count.toDouble() / sampleRate
        val warming = samplesSeen <= sampleRate.toLong() * WARMUP_MS / 1000
        val tau = when {
            warming -> WARMUP_TAU_SEC
            db < floorDb -> FLOOR_FALL_TAU_SEC
            else -> FLOOR_RISE_TAU_SEC
        }
        floorDb += (db - floorDb) * min(1.0, dt / tau)
        floorDb = floorDb.coerceIn(SILENCE_DB, MAX_FLOOR_DB)
    }

    companion object {
        const val SILENCE_DB = -100.0
        const val MIN_THRESHOLD_DB = -70.0
        const val MAX_FLOOR_DB = -15.0
        const val WARMUP_MS = 1500L
        const val ATTACK_MS = 150L
        const val MAX_PREROLL_SEC = 5
        private const val WARMUP_TAU_SEC = 0.2
        private const val FLOOR_FALL_TAU_SEC = 0.15
        private const val FLOOR_RISE_TAU_SEC = 120.0
        private const val MAX_POOL = 160

        /** 프레임의 소리 크기(dBFS). 직류 성분을 빼고 RMS로 계산. 완전 무음이면 [SILENCE_DB]. */
        fun measureDb(frame: ShortArray, count: Int): Double {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            var sumSq = 0.0
            for (i in 0 until count) {
                val v = frame[i].toDouble()
                sum += v
                sumSq += v * v
            }
            val mean = sum / count
            val variance = sumSq / count - mean * mean
            val rms = sqrt(max(variance, 0.0))
            if (rms < 1.0) return SILENCE_DB
            return max(SILENCE_DB + 0.1, 20.0 * log10(rms / 32768.0))
        }
    }
}
