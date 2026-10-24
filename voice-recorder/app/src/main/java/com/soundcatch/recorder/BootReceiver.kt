package com.soundcatch.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 폰을 다시 켜거나 앱을 업데이트했을 때, 녹음을 켜 두었던 상태면 다시 켠다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> Unit
            else -> return
        }
        val settings = AppSettings(context)
        if (!settings.autoStart || !settings.wasRunning) return
        if (Build.VERSION.SDK_INT >= 34) {
            // 안드로이드 14부터는 재부팅 직후 마이크 서비스를 자동으로 켤 수 없다 → 알림 한 번 탭
            Notifier.postRestartPrompt(context)
            return
        }
        try {
            RecorderService.start(context)
        } catch (e: Exception) {
            Notifier.postRestartPrompt(context)
        }
    }
}
