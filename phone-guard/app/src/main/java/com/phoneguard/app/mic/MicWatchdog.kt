package com.phoneguard.app.mic

import android.app.AppOpsManager
import android.content.Context
import android.os.Build

/**
 * PhoneGuard 화면이 열려 있는 동안, "지금 이 순간 마이크를 쓰기 시작/멈춘 앱"을 감시한다.
 *
 * 중요: PhoneGuard는 이 기능을 위해 RECORD_AUDIO 권한을 요청하지 않는다.
 * 이건 "내가 녹음하는" 기능이 아니라 "남이 녹음을 시작/멈추는 순간을 감시만 하는" 기능이다
 * (OS가 마이크/카메라 사용중 표시를 위해 열어둔 공개 API를 그대로 쓴다).
 *
 * 한계(정직하게, 실제 기기에서 검증 필요):
 *  - Android 11(API 30) 이상에서만 동작한다. 그 이하 버전은 지원하지 않는다.
 *  - 제조사/OS 버전에 따라 다른 앱의 패키지명이 가려질 수 있다.
 *  - 상시 백그라운드 감시가 아니라, PhoneGuard를 열어 둔 동안만 동작한다.
 *    (상시 감시는 포그라운드 서비스 + 알림 권한이 필요해 다음 단계로 남겨둔다.)
 *  - 기기에서 이 API 자체를 막아 두면(SecurityException) start()가 false를 돌려준다.
 */
class MicWatchdog(private val context: Context) {

    private val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private var listener: AppOpsManager.OnOpActiveChangedListener? = null

    @get:androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 감시를 시작한다.
     * @param onChange (패키지명, 지금 켜졌는지[true]/꺼졌는지[false])
     * @return 시작에 성공했는지. 기기가 지원하지 않거나 막아두면 false.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun start(onChange: (packageName: String, active: Boolean) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val l = AppOpsManager.OnOpActiveChangedListener { _, _, packageName, active ->
                onChange(packageName, active)
            }
            appOps.startWatchingActive(
                arrayOf(AppOpsManager.OPSTR_RECORD_AUDIO),
                context.mainExecutor,
                l
            )
            listener = l
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun stop() {
        listener?.let { l -> runCatching { appOps.stopWatchingActive(l) } }
        listener = null
    }
}
