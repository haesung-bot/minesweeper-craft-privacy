package com.phoneguard.app.permissions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 기기에 설치된 (사용자) 앱들을 훑어 권한 위험도를 매긴다.
 * 수집은 안드로이드 API로 하고, 점수 계산은 PermissionRiskScorer(순수 로직)에 위임한다.
 */
class InstalledAppScanner(private val context: Context) {

    data class AppRisk(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        val result: PermissionRiskScorer.Result
    )

    /** 위험도 높은 순으로 정렬해 돌려준다. 시스템 앱은 기본 제외. */
    fun scan(includeSystemApps: Boolean = false): List<AppRisk> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystemApps) return@mapNotNull null

            val requested = pkg.requestedPermissions?.toList() ?: emptyList()
            val result = PermissionRiskScorer.score(requested)

            AppRisk(
                packageName = pkg.packageName,
                label = pm.getApplicationLabel(appInfo).toString(),
                isSystemApp = isSystem,
                result = result
            )
        }.sortedByDescending { it.result.score }
    }
}
