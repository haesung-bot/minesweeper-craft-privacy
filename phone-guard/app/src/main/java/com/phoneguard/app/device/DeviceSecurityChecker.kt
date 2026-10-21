package com.phoneguard.app.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * 내 기기의 보안 상태를 점검한다.
 *
 * 설계 원칙:
 *  - 점검 "항목 하나하나"는 순수 로직(RootIndicators)으로 분리해서 PC에서 테스트 가능하게 한다.
 *  - 안드로이드 API가 필요한 수집 부분만 이 클래스가 담당한다.
 *
 * 한계(정직하게): 루팅 탐지는 결정적이지 않다. 숨기려는 루팅은 이 신호들을 피할 수 있다.
 * 이 점검은 "기본적인 위생 상태"를 보여주는 용도다.
 */
class DeviceSecurityChecker(private val context: Context) {

    data class Check(
        val id: String,
        val title: String,
        val passed: Boolean,
        val detail: String
    )

    data class Report(val checks: List<Check>) {
        /** 통과한 항목 비율(0~100). 사용자에게 보여줄 한 줄 점수. */
        val score: Int
            get() = if (checks.isEmpty()) 100
            else (checks.count { it.passed } * 100) / checks.size
    }

    fun runChecks(): Report {
        val checks = mutableListOf<Check>()

        // 1) 화면 잠금(PIN/패턴/지문) 설정 여부
        val secureLock = isDeviceSecured()
        checks += Check(
            id = "screen_lock",
            title = "화면 잠금 설정",
            passed = secureLock,
            detail = if (secureLock) "화면 잠금이 설정돼 있어요"
            else "화면 잠금이 없어요. 분실 시 바로 열려요 ⚠️"
        )

        // 2) USB 디버깅(ADB) 켜짐 여부 — 평소엔 꺼두는 게 안전
        val adb = isAdbEnabled()
        checks += Check(
            id = "usb_debugging",
            title = "USB 디버깅",
            passed = !adb,
            detail = if (adb) "USB 디버깅이 켜져 있어요. 안 쓸 땐 끄세요 ⚠️"
            else "USB 디버깅이 꺼져 있어요"
        )

        // 3) 출처를 알 수 없는 앱 설치 허용 여부 (API 레벨에 따라 의미가 다름)
        val unknownSources = isUnknownSourcesAllowed()
        checks += Check(
            id = "unknown_sources",
            title = "알 수 없는 출처 앱 설치",
            passed = !unknownSources,
            detail = if (unknownSources) "출처 불명 앱 설치가 허용돼 있어요 ⚠️"
            else "허용돼 있지 않아요(또는 앱별 제어)"
        )

        // 4) 루팅 흔적 — 순수 로직으로 판별(테스트 가능)
        val rootSignals = RootIndicators.scan(
            suPaths = RootIndicators.DEFAULT_SU_PATHS.filter { File(it).exists() },
            dangerousPackagesInstalled = detectRootApps(),
            buildTags = Build.TAGS
        )
        val rooted = rootSignals.isNotEmpty()
        checks += Check(
            id = "root",
            title = "루팅 흔적",
            passed = !rooted,
            detail = if (rooted) "루팅 흔적이 보여요: ${rootSignals.joinToString()} ⚠️"
            else "뚜렷한 루팅 흔적은 없어요"
        )

        return Report(checks)
    }

    private fun isDeviceSecured(): Boolean = try {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE)
                as android.app.KeyguardManager
        km.isDeviceSecure
    } catch (_: Exception) { false }

    private fun isAdbEnabled(): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) == 1
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun isUnknownSourcesAllowed(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // O 이상에서는 앱별 권한이라 전역값이 항상 신뢰되진 않음 → 보수적으로 false
            context.packageManager.canRequestPackageInstalls()
        } else {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.INSTALL_NON_MARKET_APPS, 0
            ) == 1
        }
    } catch (_: Exception) { false }

    /** 루팅 관리 앱이 설치돼 있는지 확인. 설치 사실만 보고 실행 여부는 보지 않는다. */
    private fun detectRootApps(): List<String> {
        val pm = context.packageManager
        return RootIndicators.KNOWN_ROOT_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (_: Exception) { false }
        }
    }
}
