package com.phoneguard.app

import com.phoneguard.app.mic.MicRiskAnalyzer
import com.phoneguard.app.mic.MicRiskAnalyzer.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicRiskAnalyzerTest {

    private fun analyze(perms: Set<String>, hasMicFgService: Boolean = false) =
        MicRiskAnalyzer.analyze(MicRiskAnalyzer.Input(perms, hasMicFgService))

    @Test
    fun 마이크_권한이_없으면_NONE() {
        val r = analyze(setOf("android.permission.INTERNET"))
        assertEquals(Level.NONE, r.level)
        assertEquals(0, r.score)
    }

    @Test
    fun 마이크_권한만_있으면_LOW() {
        val r = analyze(setOf("android.permission.RECORD_AUDIO"))
        assertEquals(Level.LOW, r.level)
        assertEquals(10, r.score)
    }

    @Test
    fun 마이크와_인터넷은_MEDIUM이고_사유에_유출위험이_적힌다() {
        val r = analyze(
            setOf("android.permission.RECORD_AUDIO", "android.permission.INTERNET")
        )
        assertEquals(Level.MEDIUM, r.level)
        assertEquals(35, r.score)
        assertTrue(r.reasons.any { it.contains("밖으로") })
    }

    @Test
    fun 통화권한_없이_백그라운드_마이크서비스면_가중된다() {
        val r = analyze(
            perms = setOf("android.permission.RECORD_AUDIO"),
            hasMicFgService = true
        )
        assertEquals(Level.MEDIUM, r.level)
        assertEquals(30, r.score)
        assertTrue(r.reasons.any { it.contains("백그라운드") })
    }

    @Test
    fun 통화권한이_있으면_백그라운드_보너스가_면제된다() {
        val r = analyze(
            perms = setOf("android.permission.RECORD_AUDIO", "android.permission.READ_PHONE_STATE"),
            hasMicFgService = true
        )
        // 전화앱처럼 보이므로 백그라운드 보너스(+20)가 빠지고 기본 10점만
        assertEquals(Level.LOW, r.level)
        assertEquals(10, r.score)
    }

    @Test
    fun 오버레이와_함께면_MEDIUM으로_올라간다() {
        val r = analyze(
            setOf("android.permission.RECORD_AUDIO", "android.permission.SYSTEM_ALERT_WINDOW")
        )
        assertEquals(Level.MEDIUM, r.level)
        assertEquals(30, r.score)
    }

    @Test
    fun 모든_위험신호가_겹치면_HIGH다() {
        val r = analyze(
            perms = setOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET",
                "android.permission.SYSTEM_ALERT_WINDOW"
            ),
            hasMicFgService = true
        )
        // 10 + 25(인터넷) + 20(백그라운드, 통화권한 없음) + 20(오버레이) = 75
        assertEquals(75, r.score)
        assertEquals(Level.HIGH, r.level)
    }
}
