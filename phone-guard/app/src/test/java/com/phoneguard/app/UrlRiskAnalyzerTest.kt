package com.phoneguard.app

import com.phoneguard.app.phishing.UrlRiskAnalyzer
import com.phoneguard.app.phishing.UrlRiskAnalyzer.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 피싱 링크 판별기의 자동화 테스트.
 * "이런 링크가 들어오면 이런 판정이 나와야 한다"를 코드로 고정해 둔다.
 */
class UrlRiskAnalyzerTest {

    private val analyzer = UrlRiskAnalyzer()

    @Test
    fun 정상적인_https_네이버는_안전하다() {
        val result = analyzer.analyze("https://www.naver.com/")
        assertEquals(RiskLevel.SAFE, result.level)
    }

    @Test
    fun 단축URL은_의심_이상으로_올라간다() {
        val result = analyzer.analyze("http://bit.ly/3xYz")
        // 단축(30) + http(15) = 45 → 최소 SUSPICIOUS, 사실상 위험 근처
        assertTrue(result.score >= 20)
        assertTrue(result.level != RiskLevel.SAFE)
    }

    @Test
    fun 골뱅이_트릭_주소는_위험하다() {
        // 겉보기엔 naver.com 같지만 실제 접속지는 evil.com
        val result = analyzer.analyze("http://naver.com@evil.com/login")
        assertEquals(RiskLevel.DANGEROUS, result.level)
    }

    @Test
    fun IP주소_직접_입력은_신호를_만든다() {
        val result = analyzer.analyze("http://192.168.0.5/verify")
        assertTrue(result.signals.any { it.reason.contains("IP 주소") })
    }

    @Test
    fun 유명브랜드_흉내_도메인을_잡는다() {
        // 진짜 등록 도메인은 security-login.ru 인데 naver를 앞세워 속인다
        val result = analyzer.analyze("http://naver.com.security-login.ru/")
        assertEquals(RiskLevel.DANGEROUS, result.level)
        assertTrue(result.signals.any { it.reason.contains("흉내") })
    }

    @Test
    fun 점수는_100을_넘지_않는다() {
        val result = analyzer.analyze("http://naver.com@bit.ly.security-login.ru/free/본인인증")
        assertTrue(result.score <= 100)
    }

    @Test
    fun 빈_문자열은_안전으로_떨어진다() {
        val result = analyzer.analyze("")
        assertEquals(RiskLevel.SAFE, result.level)
    }
}
