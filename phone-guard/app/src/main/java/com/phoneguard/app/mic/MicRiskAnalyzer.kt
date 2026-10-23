package com.phoneguard.app.mic

/**
 * "마이크 권한을 가진 앱 하나"가 얼마나 의심스러운지 점수화하는 순수 로직.
 * 안드로이드 API에 의존하지 않으므로 PC/서버에서 단위 테스트로 검증할 수 있다.
 *
 * 설계 의도: 마이크 권한 자체는 흔하다(전화, 음성메모, 통화앱 등). 그래서
 * "권한이 있다 = 위험하다"가 아니라, "권한 조합이 설명되지 않는다"를 본다.
 *  - 마이크 + 인터넷: 들은 걸 밖으로 보낼 수 있다
 *  - 마이크 + 백그라운드 녹음 서비스인데 통화 관련 권한이 전혀 없다: 전화앱이 아닌데 왜?
 *  - 마이크 + 오버레이/접근성: 화면 위에 뭔가 그리면서 동시에 들을 수 있다
 */
object MicRiskAnalyzer {

    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val INTERNET = "android.permission.INTERNET"

    /** 마이크 접근을 "정당해 보이게" 설명해 주는 동반 권한들(전화/통화 관련). */
    private val TELEPHONY_LIKE = setOf(
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.CAMERA" // 영상통화 등
    )
    private val OVERLAY_OR_ACCESSIBILITY = setOf(
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE"
    )

    private const val BASE_MIC = 10
    private const val BONUS_INTERNET = 25
    private const val BONUS_BG_SERVICE_NO_TELEPHONY = 20
    private const val BONUS_OVERLAY_OR_A11Y = 20

    enum class Level { NONE, LOW, MEDIUM, HIGH }

    data class Result(val score: Int, val level: Level, val reasons: List<String>)

    /**
     * @param permissions 앱이 선언한 권한 전체
     * @param hasMicForegroundService 백그라운드(화면이 꺼져도)에서 마이크를 쓰는
     *   포그라운드 서비스를 선언했는지 (API 31+ 매니페스트 정보로 판단)
     */
    data class Input(
        val permissions: Set<String>,
        val hasMicForegroundService: Boolean = false
    )

    fun analyze(input: Input): Result {
        val perms = input.permissions

        if (RECORD_AUDIO !in perms) {
            return Result(0, Level.NONE, listOf("마이크 권한이 없어요"))
        }

        var score = BASE_MIC
        val reasons = mutableListOf("마이크 권한을 갖고 있어요 (+$BASE_MIC)")

        val hasInternet = INTERNET in perms
        if (hasInternet) {
            score += BONUS_INTERNET
            reasons += "마이크 + 인터넷 = 들은 내용을 밖으로 보낼 수 있어요 (+$BONUS_INTERNET)"
        }

        val telephonyLike = perms.any { it in TELEPHONY_LIKE }
        if (input.hasMicForegroundService && !telephonyLike) {
            score += BONUS_BG_SERVICE_NO_TELEPHONY
            reasons += "전화·통화 관련 권한 없이 백그라운드에서 마이크를 쓸 수 있어요 (+$BONUS_BG_SERVICE_NO_TELEPHONY)"
        }

        if (perms.any { it in OVERLAY_OR_ACCESSIBILITY }) {
            score += BONUS_OVERLAY_OR_A11Y
            reasons += "오버레이/접근성과 마이크를 함께 가져 화면·소리를 동시에 다룰 수 있어요 (+$BONUS_OVERLAY_OR_A11Y)"
        }

        score = score.coerceAtMost(100)
        val level = when {
            score >= 50 -> Level.HIGH
            score >= 25 -> Level.MEDIUM
            else -> Level.LOW
        }
        return Result(score, level, reasons)
    }
}
