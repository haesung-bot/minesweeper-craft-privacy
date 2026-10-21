package com.phoneguard.app

import com.phoneguard.app.permissions.PermissionRiskScorer
import com.phoneguard.app.permissions.PermissionRiskScorer.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRiskScorerTest {

    @Test
    fun 권한이_없으면_LOW() {
        val r = PermissionRiskScorer.score(emptyList())
        assertEquals(Level.LOW, r.level)
        assertEquals(0, r.score)
    }

    @Test
    fun 문자읽기와_인터넷_조합은_보너스를_받는다() {
        val r = PermissionRiskScorer.score(
            listOf(
                "android.permission.READ_SMS",
                "android.permission.INTERNET"
            )
        )
        // READ_SMS(25) + 조합 보너스(20) = 45
        assertEquals(Level.MEDIUM, r.level)
        assertTrue(r.reasons.any { it.contains("인증번호 유출") })
    }

    @Test
    fun 오버레이와_접근성_조합은_HIGH로_간다() {
        val r = PermissionRiskScorer.score(
            listOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.BIND_ACCESSIBILITY_SERVICE"
            )
        )
        // 20 + 30 + 조합 20 = 70
        assertEquals(Level.HIGH, r.level)
        assertTrue(r.reasons.any { it.contains("화면 가로채기") })
    }

    @Test
    fun 점수는_100을_넘지_않는다() {
        val r = PermissionRiskScorer.score(
            listOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS",
                "android.permission.INTERNET",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO"
            )
        )
        assertTrue(r.score <= 100)
        assertEquals(Level.HIGH, r.level)
    }
}
