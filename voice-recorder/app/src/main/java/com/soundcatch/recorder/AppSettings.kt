package com.soundcatch.recorder

import android.content.Context
import android.content.SharedPreferences

/** 앱 설정 (폰 안에만 저장) */
class AppSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var scriptUrl: String
        get() = prefs.getString(K_URL, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_URL, v.trim()).apply()

    var secret: String
        get() = prefs.getString(K_SECRET, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_SECRET, v.trim()).apply()

    var sensitivity: Int
        get() = prefs.getInt(K_SENS, 6).coerceIn(1, 10)
        set(v) = prefs.edit().putInt(K_SENS, v.coerceIn(1, 10)).apply()

    var silenceSec: Int
        get() = prefs.getInt(K_SILENCE, 10).coerceIn(2, 60)
        set(v) = prefs.edit().putInt(K_SILENCE, v.coerceIn(2, 60)).apply()

    var preRollSec: Int
        get() = prefs.getInt(K_PREROLL, 2).coerceIn(0, SoundDetector.MAX_PREROLL_SEC)
        set(v) = prefs.edit().putInt(K_PREROLL, v.coerceIn(0, SoundDetector.MAX_PREROLL_SEC)).apply()

    var maxMinutes: Int
        get() = prefs.getInt(K_MAX_MIN, 10).coerceIn(1, 30)
        set(v) = prefs.edit().putInt(K_MAX_MIN, v.coerceIn(1, 30)).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(K_WIFI, true)
        set(v) = prefs.edit().putBoolean(K_WIFI, v).apply()

    var deleteAfterUpload: Boolean
        get() = prefs.getBoolean(K_DELETE, true)
        set(v) = prefs.edit().putBoolean(K_DELETE, v).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(K_AUTOSTART, true)
        set(v) = prefs.edit().putBoolean(K_AUTOSTART, v).apply()

    /** 사용자가 녹음을 켜 둔 상태인지 (재부팅 후 다시 켤지 판단) */
    var wasRunning: Boolean
        get() = prefs.getBoolean(K_RUNNING, false)
        set(v) = prefs.edit().putBoolean(K_RUNNING, v).apply()

    val driveConfigured: Boolean
        get() = scriptUrl.startsWith("http") && secret.isNotEmpty()

    fun detectorConfig() = SoundDetector.Config(
        sensitivity = sensitivity,
        silenceSec = silenceSec,
        preRollSec = preRollSec,
        maxSegmentSec = maxMinutes * 60,
    )

    companion object {
        const val K_URL = "script_url"
        const val K_SECRET = "secret"
        const val K_SENS = "sensitivity"
        const val K_SILENCE = "silence_sec"
        const val K_PREROLL = "preroll_sec"
        const val K_MAX_MIN = "max_minutes"
        const val K_WIFI = "wifi_only"
        const val K_DELETE = "delete_after_upload"
        const val K_AUTOSTART = "auto_start"
        const val K_RUNNING = "was_running"

        val DETECTOR_KEYS = setOf(K_SENS, K_SILENCE, K_PREROLL, K_MAX_MIN)
        val UPLOAD_KEYS = setOf(K_URL, K_SECRET, K_WIFI, K_DELETE)
    }
}
