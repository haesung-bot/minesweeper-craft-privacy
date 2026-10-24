package com.soundcatch.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.soundcatch.recorder.RecorderState.Mode

object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_ALERT = "alert"
    const val ID_STATUS = 1
    private const val ID_RESTART = 2

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "녹음 상태", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "중요 알림", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** 녹음 서비스가 켜져 있는 동안 항상 보이는 알림 */
    fun status(context: Context): Notification {
        val s = RecorderState
        val uploadLine = when {
            s.pendingCount > 0 -> "업로드 대기 ${s.pendingCount}개"
            s.lastUploadText.isNotEmpty() -> s.lastUploadText
            else -> "업로드할 파일 없음"
        }
        val (title, text) = when (s.mode) {
            Mode.RECORDING -> "● 녹음 중" to uploadLine
            Mode.UPLOAD_ONLY -> "남은 녹음 업로드 중" to "${s.pendingCount}개 남음"
            Mode.STOPPING -> "정지하는 중…" to uploadLine
            Mode.STARTING -> "준비 중…" to uploadLine
            else -> "소리 기다리는 중" to (s.warning.ifEmpty { uploadLine })
        }
        val b = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, autoStart = false))
        if (s.mode == Mode.RECORDING) {
            b.setShowWhen(true).setWhen(s.recordingSinceMs).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        if (s.mode == Mode.LISTENING || s.mode == Mode.RECORDING) {
            b.addAction(0, "정지", RecorderService.stopIntent(context))
        }
        return b.build()
    }

    fun update(context: Context) {
        if (RecorderState.mode == Mode.OFF) return
        try {
            NotificationManagerCompat.from(context).notify(ID_STATUS, status(context))
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 표시만 안 될 뿐 녹음은 계속된다
        }
    }

    /** 재부팅 뒤 자동으로 못 켤 때(안드로이드 14 이상): 탭 한 번으로 다시 시작 */
    fun postRestartPrompt(context: Context, text: String = "폰이 다시 켜졌어요. 탭하면 녹음 대기를 다시 시작해요") {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("소리 감지 녹음기가 꺼져 있어요")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, autoStart = true))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_RESTART, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelRestartPrompt(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RESTART)
    }

    private fun openApp(context: Context, autoStart: Boolean): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOSTART, autoStart)
        return PendingIntent.getActivity(
            context,
            if (autoStart) 11 else 10,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
