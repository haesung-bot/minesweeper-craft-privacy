package com.phoneguard.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.phoneguard.app.databinding.ActivityMainBinding
import com.phoneguard.app.device.DeviceSecurityChecker
import com.phoneguard.app.mic.MicPermissionScanner
import com.phoneguard.app.mic.MicWatchdog
import com.phoneguard.app.permissions.InstalledAppScanner
import com.phoneguard.app.phishing.UrlRiskAnalyzer

/**
 * 다섯 가지 기능을 한 화면에서 시연하는 간단한 진입점.
 * UI는 일부러 최소로 두고, 실제 "두뇌"는 각 기능 클래스에 있다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val urlAnalyzer = UrlRiskAnalyzer()
    private val micWatchdog by lazy { MicWatchdog(this) }
    private var isWatchingMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnalyze.setOnClickListener { analyzeUrl() }
        binding.btnDeviceScan.setOnClickListener { scanDevice() }
        binding.btnPermScan.setOnClickListener { scanPermissions() }
        binding.btnMicScan.setOnClickListener { scanMic() }
        binding.btnMicWatch.setOnClickListener { toggleMicWatch() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isWatchingMic && micWatchdog.isSupported) micWatchdog.stop()
    }

    private fun analyzeUrl() {
        val result = urlAnalyzer.analyze(binding.inputUrl.text.toString())
        val sb = StringBuilder()
        sb.appendLine("[링크 검사]")
        sb.appendLine("판정: ${result.level}  (위험점수 ${result.score}/100)")
        if (result.signals.isEmpty()) {
            sb.appendLine("- 특별한 위험 신호가 없어요")
        } else {
            result.signals.forEach { sb.appendLine("- ${it.reason}") }
        }
        binding.output.text = sb.toString()
    }

    private fun scanDevice() {
        val report = DeviceSecurityChecker(this).runChecks()
        val sb = StringBuilder()
        sb.appendLine("[기기 보안 점검]  안전도 ${report.score}/100")
        report.checks.forEach {
            val mark = if (it.passed) "✅" else "⚠️"
            sb.appendLine("$mark ${it.title}: ${it.detail}")
        }
        binding.output.text = sb.toString()
    }

    private fun scanPermissions() {
        val apps = InstalledAppScanner(this).scan(includeSystemApps = false)
        val sb = StringBuilder()
        sb.appendLine("[설치 앱 권한 점검]  위험도 높은 순 상위 10개")
        apps.take(10).forEach {
            sb.appendLine("${it.result.level}  ${it.label}  (${it.result.score}점)")
            it.result.reasons.take(3).forEach { r -> sb.appendLine("   - $r") }
        }
        if (apps.isEmpty()) sb.appendLine("- 점검할 사용자 앱이 없어요")
        binding.output.text = sb.toString()
    }

    private fun scanMic() {
        val apps = MicPermissionScanner(this).scan(includeSystemApps = false)
        val sb = StringBuilder()
        sb.appendLine("[마이크 권한 점검]  마이크 권한을 가진 사용자 앱 ${apps.size}개")
        if (apps.isEmpty()) {
            sb.appendLine("- 마이크 권한을 가진 사용자 앱이 없어요")
        }
        apps.forEach {
            sb.appendLine("${it.result.level}  ${it.label}  (${it.result.score}점)")
            it.result.reasons.forEach { r -> sb.appendLine("   - $r") }
        }
        binding.output.text = sb.toString()
    }

    /**
     * PhoneGuard는 이 기능을 위해 RECORD_AUDIO 권한을 요청하지 않는다.
     * "내가 녹음"하는 게 아니라 "남이 녹음을 시작/멈추는 순간을 감시만" 한다.
     */
    private fun toggleMicWatch() {
        if (!micWatchdog.isSupported) {
            binding.output.text = "이 기기/버전에서는 실시간 감시가 지원되지 않아요.\n" +
                "대신 설정 > 보안 및 개인정보 보호 > 권한 관리자 > 마이크 에서 확인해 보세요."
            return
        }

        if (isWatchingMic) {
            micWatchdog.stop()
            isWatchingMic = false
            binding.btnMicWatch.text = getString(R.string.btn_mic_watch_start)
            return
        }

        val started = micWatchdog.start { pkg, active ->
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: Exception) {
                pkg
            }
            val state = if (active) "🔴 시작" else "⚪ 종료"
            binding.output.append("\n[실시간] $label ($pkg) 마이크 사용 $state")
        }

        if (started) {
            isWatchingMic = true
            binding.btnMicWatch.text = getString(R.string.btn_mic_watch_stop)
            binding.output.text = "[실시간 마이크 감시 시작]\n다른 앱이 마이크를 켜거나 끄면 여기 표시돼요.\n"
        } else {
            binding.output.text = "실시간 감시를 시작할 수 없었어요(기기 제한).\n" +
                "대신 설정 > 보안 및 개인정보 보호 > 권한 관리자 > 마이크 에서 확인해 보세요."
        }
    }
}
