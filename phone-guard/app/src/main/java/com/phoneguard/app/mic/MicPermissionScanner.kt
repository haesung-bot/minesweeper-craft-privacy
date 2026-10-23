package com.phoneguard.app.mic

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * 설치된 앱 중 "마이크 권한을 선언한 앱"만 걸러내어 MicRiskAnalyzer로 점수를 매긴다.
 * 수집은 안드로이드 API로, 점수 계산은 순수 로직(MicRiskAnalyzer)에 맡긴다.
 */
class MicPermissionScanner(private val context: Context) {

    data class MicApp(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        val result: MicRiskAnalyzer.Result
    )

    /** 위험도 높은 순으로 정렬. 시스템 앱은 기본 제외. */
    fun scan(includeSystemApps: Boolean = false): List<MicApp> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        } else {
            PackageManager.GET_PERMISSIONS
        }
        val packages = pm.getInstalledPackages(flags)

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystemApps) return@mapNotNull null

            val requested = pkg.requestedPermissions?.toSet() ?: emptySet()
            if ("android.permission.RECORD_AUDIO" !in requested) return@mapNotNull null

            val result = MicRiskAnalyzer.analyze(
                MicRiskAnalyzer.Input(
                    permissions = requested,
                    hasMicForegroundService = hasMicForegroundService(pkg)
                )
            )

            MicApp(
                packageName = pkg.packageName,
                label = pm.getApplicationLabel(appInfo).toString(),
                isSystemApp = isSystem,
                result = result
            )
        }.sortedByDescending { it.result.score }
    }

    /** API 31+ 에서만 서비스의 foregroundServiceType 정보를 볼 수 있다. */
    private fun hasMicForegroundService(pkg: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val services = pkg.services ?: return false
        return services.any { svc ->
            (svc.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        }
    }
}
