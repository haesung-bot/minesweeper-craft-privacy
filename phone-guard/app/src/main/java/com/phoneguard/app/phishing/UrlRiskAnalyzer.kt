package com.phoneguard.app.phishing

/**
 * 문자(스미싱)·메신저에 들어오는 링크가 얼마나 위험한지 점수로 판별한다.
 *
 * 이 클래스는 일부러 안드로이드 API를 전혀 쓰지 않는 "순수 Kotlin"이다.
 * 덕분에 PC/서버에서 JUnit 단위 테스트로 바로 검증할 수 있다(= 자동화 테스트).
 *
 * 주의: 이것은 휴리스틱(경험 규칙) 기반 점검기다. 100% 탐지가 아니라
 * "의심 신호가 몇 개나 겹치는지"를 보여주는 보조 도구로 설계했다.
 */
class UrlRiskAnalyzer {

    /** 하나의 의심 신호. 사용자에게 "왜 위험한지" 설명하기 위해 사유를 함께 담는다. */
    data class Signal(val reason: String, val weight: Int)

    /** 최종 판별 결과. */
    data class Result(
        val url: String,
        val score: Int,
        val level: RiskLevel,
        val signals: List<Signal>
    )

    enum class RiskLevel { SAFE, SUSPICIOUS, DANGEROUS }

    fun analyze(rawInput: String): Result {
        val url = rawInput.trim()
        val signals = mutableListOf<Signal>()

        val host = extractHost(url)

        // 1) URL 단축 서비스: 진짜 목적지를 숨긴다. 스미싱에서 매우 흔하다.
        if (host != null && SHORTENERS.any { host == it || host.endsWith(".$it") }) {
            signals += Signal("단축 URL이라 진짜 주소가 가려져 있어요 (예: bit.ly)", 30)
        }

        // 2) http (암호화 안 됨): 요즘 정상 사이트는 대부분 https다.
        if (url.startsWith("http://", ignoreCase = true)) {
            signals += Signal("암호화되지 않은 http 연결이에요", 15)
        }

        // 3) 주소에 IP가 그대로 박혀 있음: 정상 서비스는 보통 도메인 이름을 쓴다.
        if (host != null && IP_HOST.matches(host)) {
            signals += Signal("도메인 대신 IP 주소를 직접 써요", 25)
        }

        // 4) @ 기호 트릭: 'http://naver.com@evil.com' → 실제 접속지는 evil.com.
        val authority = extractAuthority(url)
        if (authority != null && authority.contains('@')) {
            signals += Signal("주소에 @ 기호가 있어 실제 접속지가 다를 수 있어요", 35)
        }

        // 5) 퓨니코드/유사 도메인(예: xn--): 한글·키릴 문자로 유명 브랜드를 흉내낸다.
        if (host != null && host.contains("xn--")) {
            signals += Signal("정상 브랜드를 흉내낸 유사 도메인일 수 있어요 (퓨니코드)", 25)
        }

        // 6) 유명 브랜드 키워드가 '서브도메인/경로'에만 있고 진짜 도메인은 다른 경우.
        //    예: 'naver.com.security-login.ru' → 진짜 등록 도메인은 security-login.ru
        if (host != null) {
            val baseDomain = baseDomainOf(host)
            val impersonated = BRAND_KEYWORDS.firstOrNull { brand ->
                host.contains(brand) && !baseDomain.contains(brand)
            }
            if (impersonated != null) {
                signals += Signal("유명 서비스($impersonated) 이름을 흉내낸 가짜 도메인 같아요", 35)
            }
        }

        // 7) 점(.)이 지나치게 많음: 서브도메인을 겹겹이 쌓아 속이는 전형적 수법.
        if (host != null && host.count { it == '.' } >= 4) {
            signals += Signal("서브도메인이 비정상적으로 많아요", 15)
        }

        // 8) 자주 쓰이는 미끼 단어 + 위험한 최상위 도메인 조합.
        val lower = url.lowercase()
        if (BAIT_WORDS.any { lower.contains(it) }) {
            signals += Signal("'무료/당첨/택배/본인인증' 같은 미끼 문구가 있어요", 10)
        }

        val score = signals.sumOf { it.weight }.coerceAtMost(100)
        val level = when {
            score >= 50 -> RiskLevel.DANGEROUS
            score >= 20 -> RiskLevel.SUSPICIOUS
            else -> RiskLevel.SAFE
        }
        return Result(url, score, level, signals)
    }

    /** scheme://authority/path 에서 authority(host[:port], userinfo 포함) 부분만 뽑는다. */
    private fun extractAuthority(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/')
    }

    /** 순수 호스트명만 뽑는다(userinfo·포트 제거, 소문자화). */
    private fun extractHost(url: String): String? {
        val authority = extractAuthority(url) ?: return null
        return authority
            .substringAfterLast('@')   // userinfo 제거
            .substringBefore(':')      // 포트 제거
            .lowercase()
            .ifEmpty { null }
    }

    /** 아주 단순한 등록도메인 추정: 뒤에서 두 조각(example.com). 교육용으로 충분하다. */
    private fun baseDomainOf(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    companion object {
        private val IP_HOST = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private val SHORTENERS = setOf(
            "bit.ly", "goo.gl", "t.co", "tinyurl.com", "ow.ly",
            "is.gd", "buff.ly", "han.gl", "me2.do", "url.kr"
        )

        private val BRAND_KEYWORDS = listOf(
            "naver", "kakao", "google", "apple", "samsung",
            "paypal", "toss", "kbstar", "shinhan", "coupang"
        )

        private val BAIT_WORDS = listOf(
            "free", "gift", "무료", "당첨", "택배", "배송", "본인인증",
            "login", "verify", "update", "확인", "긴급"
        )
    }
}
