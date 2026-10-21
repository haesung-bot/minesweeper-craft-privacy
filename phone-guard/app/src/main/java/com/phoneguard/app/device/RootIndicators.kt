package com.phoneguard.app.device

/**
 * 루팅 흔적을 판별하는 "순수 로직"만 모아 둔 곳.
 * 안드로이드 API에 의존하지 않으므로 PC/서버에서 단위 테스트로 검증할 수 있다.
 *
 * DeviceSecurityChecker가 실제 기기에서 수집한 값(존재하는 su 경로, 설치된 루팅 앱,
 * 빌드 태그)을 넘겨주면, 여기서 "어떤 신호가 잡혔는지" 목록을 돌려준다.
 */
object RootIndicators {

    /** 루팅 기기에서 흔히 보이는 su 바이너리 경로들. */
    val DEFAULT_SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/su/bin/su"
    )

    /** 루팅 관리/은폐 앱 패키지명. */
    val KNOWN_ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.thirdparty.superuser"
    )

    /**
     * 수집된 원시 신호들을 받아 "사람이 읽을 수 있는 사유" 목록으로 바꾼다.
     * 비어 있으면 뚜렷한 루팅 흔적이 없다는 뜻.
     */
    fun scan(
        suPaths: List<String>,
        dangerousPackagesInstalled: List<String>,
        buildTags: String?
    ): List<String> {
        val reasons = mutableListOf<String>()

        if (suPaths.isNotEmpty()) {
            reasons += "su 바이너리(${suPaths.size}개)"
        }
        if (dangerousPackagesInstalled.isNotEmpty()) {
            reasons += "루팅앱 설치(${dangerousPackagesInstalled.joinToString()})"
        }
        // test-keys로 서명된 커스텀 ROM은 루팅/비공식 빌드의 흔한 신호
        if (buildTags != null && buildTags.contains("test-keys")) {
            reasons += "test-keys 빌드"
        }
        return reasons
    }
}
