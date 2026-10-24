package com.soundcatch.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SoundDetectorTest {

    private val rate = 44_100
    private val frame = rate / 20 // 50ms

    /** 녹음 이벤트를 기록하는 가짜 리스너 */
    private class Recorder : SoundDetector.Listener {
        val events = mutableListOf<String>()
        val segments = mutableListOf<Long>()
        var current = 0L
        override fun onRecordStart() {
            events += "start"
            current = 0L
        }

        override fun onRecordData(samples: ShortArray, count: Int) {
            current += count
        }

        override fun onRecordStop(split: Boolean) {
            events += if (split) "split" else "stop"
            segments += current
        }
    }

    private val random = Random(42)
    private var phase = 0.0

    private fun noise(db: Double): ShortArray {
        // 균등분포 잡음: RMS = A / sqrt(3)
        val a = 32768.0 * 10.0.pow(db / 20) * sqrt(3.0)
        return ShortArray(frame) { ((random.nextDouble() * 2 - 1) * a).toInt().toShort() }
    }

    private fun tone(db: Double, noiseDb: Double = -60.0): ShortArray {
        val a = 32768.0 * 10.0.pow(db / 20) * sqrt(2.0)
        val n = noise(noiseDb)
        return ShortArray(frame) {
            phase += 2 * PI * 440 / rate
            (a * sin(phase) + n[it]).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun feed(d: SoundDetector, r: Recorder, seconds: Double, gen: () -> ShortArray) {
        repeat((seconds * 20).toInt()) {
            val f = gen()
            d.process(f, f.size, r)
        }
    }

    private fun detector(cfg: SoundDetector.Config = SoundDetector.Config()) = SoundDetector(rate, cfg)

    @Test
    fun measuresLevelOfSine() {
        val db = SoundDetector.measureDb(tone(-30.0, noiseDb = -120.0), frame)
        assertTrue("level=$db", db > -30.5 && db < -29.5)
        assertEquals(SoundDetector.SILENCE_DB, SoundDetector.measureDb(ShortArray(frame), frame), 0.001)
    }

    @Test
    fun speechBurstMakesOneRecordingWithPreRollAndHangover() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 2.0) { tone(-30.0) }
        feed(d, r, 15.0) { noise(-60.0) }
        assertEquals(listOf("start", "stop"), r.events)
        val sec = r.segments[0].toDouble() / rate
        // 미리 담기 2초 + 시작 판정 0.15초 + 나머지 소리 1.85초 + 조용한 10초 ≈ 14초
        assertTrue("length=$sec", sec in 13.8..14.4)
        assertFalse(d.isRecording)
    }

    @Test
    fun shortClickDoesNotTrigger() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 0.05) { tone(-20.0) }
        feed(d, r, 5.0) { noise(-60.0) }
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun noTriggerDuringWarmUp() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 1.0) { tone(-30.0) }
        feed(d, r, 5.0) { noise(-60.0) }
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun digitalSilenceIsIgnoredForNoiseFloor() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 0.5) { ShortArray(frame) }
        feed(d, r, 4.0) { noise(-60.0) }
        feed(d, r, 5.0) { ShortArray(frame) }
        feed(d, r, 6.0) { noise(-60.0) }
        assertTrue(r.events.toString(), r.events.isEmpty())
        assertTrue("floor=${d.floorDb}", d.floorDb > -63 && d.floorDb < -57)
        assertFalse(d.onlyDigitalSilence)
    }

    @Test
    fun reportsOnlyDigitalSilence() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 2.0) { ShortArray(frame) }
        assertTrue(d.onlyDigitalSilence)
    }

    @Test
    fun longSoundIsSplitIntoSegments() {
        val d = detector(SoundDetector.Config(maxSegmentSec = 10))
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 15.0) { tone(-30.0) }
        feed(d, r, 15.0) { noise(-60.0) }
        // 전체 = 미리 담기 2.2초 + 소리 14.85초 + 조용한 10초 = 27.05초 → 10 + 10 + 7.05
        assertEquals(listOf("start", "split", "start", "split", "start", "stop"), r.events)
        assertEquals(10.0, r.segments[0].toDouble() / rate, 0.06)
        assertEquals(10.0, r.segments[1].toDouble() / rate, 0.06)
        assertEquals(7.05, r.segments[2].toDouble() / rate, 0.06)
    }

    @Test
    fun sensitivityChangesWhatCounts() {
        val quiet = { tone(-48.0) } // 바닥 소음보다 12dB 큼
        val low = detector(SoundDetector.Config(sensitivity = 6)) // 17.5dB 필요
        val r1 = Recorder()
        feed(low, r1, 3.0) { noise(-60.0) }
        feed(low, r1, 2.0, quiet)
        assertTrue(r1.events.isEmpty())

        val high = detector(SoundDetector.Config(sensitivity = 10)) // 7.5dB 필요
        val r2 = Recorder()
        feed(high, r2, 3.0) { noise(-60.0) }
        feed(high, r2, 2.0, quiet)
        assertEquals(listOf("start"), r2.events)
    }

    @Test
    fun preRollZeroKeepsOnlyTheTriggerPart() {
        val d = detector(SoundDetector.Config(preRollSec = 0, silenceSec = 2))
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 1.0) { tone(-30.0) }
        feed(d, r, 3.0) { noise(-60.0) }
        assertEquals(listOf("start", "stop"), r.events)
        val sec = r.segments[0].toDouble() / rate
        // 시작 판정분(0.15~0.2초) + 나머지 소리 0.85초 + 조용한 2초 ≈ 3.0~3.05초
        assertTrue("length=$sec", sec in 2.95..3.15)
    }

    @Test
    fun finishClosesOpenRecording() {
        val d = detector()
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 1.0) { tone(-30.0) }
        assertTrue(d.isRecording)
        d.finish(r)
        assertEquals(listOf("start", "stop"), r.events)
        assertFalse(d.isRecording)
        d.finish(r)
        assertEquals(2, r.events.size)
    }

    @Test
    fun steadyNewNoiseEventuallyStopsRecording() {
        // 에어컨처럼 계속되는 큰 소음(+25dB)은 처음엔 녹음되지만 결국 멈춘다
        val d = detector()
        val r = Recorder()
        feed(d, r, 3.0) { noise(-60.0) }
        feed(d, r, 120.0) { noise(-35.0) }
        assertEquals(listOf("start", "stop"), r.events)
        val sec = r.segments[0].toDouble() / rate
        assertTrue("length=$sec", sec in 30.0..90.0)
    }
}
