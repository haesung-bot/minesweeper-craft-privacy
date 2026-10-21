package com.phoneguard.app.permissions

/**
 * 앱이 요청한 권한 목록을 받아 "위험 점수"와 사유를 계산하는 순수 로직.
 * 안드로이드 API에 의존하지 않으므로 PC에서 단위 테스트할 수 있다.
 *
 * 핵심 아이디어: 권한 하나하나보다 "위험 권한의 조합"이 더 위험하다.
 * 예) 문자 읽기 + 인터넷 = 인증번호 가로채기 가능성.
 */
object PermissionRiskScorer {

    /** 권한별 민감도 가중치. */
    private val WEIGHTS = mapOf(
        "android.permission.READ_SMS" to 25,
        "android.permission.RECEIVE_SMS" to 25,
        "android.permission.SEND_SMS" to 20,
        "android.permission.READ_CONTACTS" to 15,
        "android.permission.ACCESS_FINE_LOCATION" to 15,
        "android.permission.ACCESS_BACKGROUND_LOCATION" to 20,
        "android.permission.CAMERA" to 15,
        "android.permission.RECORD_AUDIO" to 20,
        "android.permission.READ_CALL_LOG" to 15,
        "android.permission.REQUEST_INSTALL_PACKAGES" to 20,
        "android.permission.SYSTEM_ALERT_WINDOW" to 20, // 다른 앱 위에 그리기(오버레이 피싱)
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to 30 // 접근성 악용
    )

    data class Result(val score: Int, val level: Level, val reasons: List<String>)
    enum class Level { LOW, MEDIUM, HIGH }

    fun score(requestedPermissions: List<String>): Result {
        val perms = requestedPermissions.toSet()
        val reasons = mutableListOf<String>()
        var score = 0

        for (p in perms) {
            WEIGHTS[p]?.let { w ->
                score += w
                reasons += "${short(p)} 권한 (+$w)"
            }
        }

        // 위험 조합 보너스
        val readsSms = "android.permission.READ_SMS" in perms ||
                "android.permission.RECEIVE_SMS" in perms
        val hasInternet = "android.permission.INTERNET" in perms
        if (readsSms && hasInternet) {
            score += 20
            reasons += "문자 읽기 + 인터넷 = 인증번호 유출 위험 (+20)"
        }
        val overlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms
        val accessibility = "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms
        if (overlay && accessibility) {
            score += 20
            reasons += "오버레이 + 접근성 = 화면 가로채기 위험 (+20)"
        }

        score = score.coerceAtMost(100)
        val level = when {
            score >= 50 -> Level.HIGH
            score >= 20 -> Level.MEDIUM
            else -> Level.LOW
        }
        return Result(score, level, reasons)
    }

    private fun short(perm: String) = perm.substringAfterLast('.')
}
