package com.phoneguard.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.phoneguard.app.databinding.ActivityMainBinding
import com.phoneguard.app.device.DeviceSecurityChecker
import com.phoneguard.app.permissions.InstalledAppScanner
import com.phoneguard.app.phishing.UrlRiskAnalyzer

/**
 * 네 가지 기능을 한 화면에서 시연하는 간단한 진입점.
 * UI는 일부러 최소로 두고, 실제 "두뇌"는 각 기능 클래스에 있다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val urlAnalyzer = UrlRiskAnalyzer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnalyze.setOnClickListener { analyzeUrl() }
        binding.btnDeviceScan.setOnClickListener { scanDevice() }
        binding.btnPermScan.setOnClickListener { scanPermissions() }
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
}
