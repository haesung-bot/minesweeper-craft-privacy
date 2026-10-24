package com.soundcatch.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.soundcatch.recorder.RecorderState.Mode
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val REQ_PERMISSIONS = 1
        private val SCRIPT_URL = Regex("https://script\\.google\\.com/[^\\s\"'<>]+/exec")
    }

    private lateinit var settings: AppSettings
    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private var folderUrl: String? = null
    private var primaryColor = 0

    private lateinit var statusText: TextView
    private lateinit var meter: LevelMeterView
    private lateinit var levelText: TextView
    private lateinit var warningText: TextView
    private lateinit var toggleButton: Button
    private lateinit var uploadText: TextView
    private lateinit var urlEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var testText: TextView
    private lateinit var batteryText: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        storage = Storage(this)

        statusText = findViewById(R.id.statusText)
        meter = findViewById(R.id.meter)
        levelText = findViewById(R.id.levelText)
        warningText = findViewById(R.id.warningText)
        toggleButton = findViewById(R.id.toggleButton)
        uploadText = findViewById(R.id.uploadText)
        urlEdit = findViewById(R.id.urlEdit)
        secretEdit = findViewById(R.id.secretEdit)
        testText = findViewById(R.id.testText)
        batteryText = findViewById(R.id.batteryText)
        primaryColor = statusText.currentTextColor

        toggleButton.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.uploadNowButton).setOnClickListener { onUploadNow() }
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteUrl() }
        findViewById<Button>(R.id.testButton).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestBatteryExemption() }
        testText.setOnClickListener { folderUrl?.let { openUrl(it) } }

        urlEdit.setText(settings.scriptUrl)
        secretEdit.setText(settings.secret)
        urlEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveTextFields() }
        secretEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveTextFields() }

        setupSeek(R.id.sensSeek, R.id.sensLabel, 1, settings.sensitivity, { settings.sensitivity = it }) {
            "감도 $it / 10  (높을수록 작은 소리도 녹음)"
        }
        setupSeek(R.id.silenceSeek, R.id.silenceLabel, 2, settings.silenceSec, { settings.silenceSec = it }) {
            "소리가 멈춘 뒤 ${it}초 더 녹음하고 끝내기"
        }
        setupSeek(R.id.preRollSeek, R.id.preRollLabel, 0, settings.preRollSec, { settings.preRollSec = it }) {
            if (it == 0) "소리 나기 전 부분은 담지 않기" else "소리 나기 ${it}초 전부터 담기 (첫마디 안 잘리게)"
        }
        setupSeek(R.id.maxSeek, R.id.maxLabel, 1, settings.maxMinutes, { settings.maxMinutes = it }) {
            "길게 이어지면 ${it}분마다 파일 나누기"
        }

        setupCheck(R.id.wifiCheck, settings.wifiOnly) { settings.wifiOnly = it }
        setupCheck(R.id.deleteCheck, settings.deleteAfterUpload) { settings.deleteAfterUpload = it }
        setupCheck(R.id.autoStartCheck, settings.autoStart) { settings.autoStart = it }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            intent.removeExtra(EXTRA_AUTOSTART)
            Notifier.cancelRestartPrompt(this)
            startRecording()
        }
    }

    override fun onResume() {
        super.onResume()
        tick = 0
        handler.post(ticker)
        renderBattery()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        saveTextFields()
        super.onPause()
    }

    // ───────────── 버튼 ─────────────

    private fun onToggle() {
        saveTextFields()
        when (RecorderState.mode) {
            Mode.STARTING, Mode.LISTENING, Mode.RECORDING -> RecorderService.stop(this)
            Mode.STOPPING -> Unit
            Mode.OFF, Mode.UPLOAD_ONLY -> startRecording()
        }
    }

    private fun startRecording() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        if (!settings.driveConfigured) toast("드라이브 연결 전까지는 녹음이 폰에만 저장돼요")
        RecorderState.warning = ""
        RecorderService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!settings.driveConfigured) toast("드라이브 연결 전까지는 녹음이 폰에만 저장돼요")
            RecorderService.start(this)
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            toast("설정 > 권한에서 '마이크'를 허용해 주세요")
            openUrl("package:$packageName", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        } else {
            toast("소리를 감지하려면 마이크 권한이 필요해요")
        }
    }

    private fun onUploadNow() {
        saveTextFields()
        if (!settings.driveConfigured) {
            toast("먼저 드라이브 주소와 비밀번호를 넣어 주세요")
            return
        }
        RecorderService.uploadNow(this)
        toast("업로드를 시작해요")
    }

    private fun pasteUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        val url = SCRIPT_URL.find(text)?.value
        if (url == null) {
            toast("복사한 내용에 스크립트 주소(…/exec)가 없어요")
        } else {
            urlEdit.setText(url)
            saveTextFields()
            toast("주소를 넣었어요")
        }
    }

    private fun testConnection() {
        saveTextFields()
        val url = settings.scriptUrl
        val secret = settings.secret
        when {
            url.isEmpty() -> {
                testText.text = "스크립트 주소를 먼저 넣어 주세요"
                return
            }
            secret.isEmpty() -> {
                testText.text = "비밀번호를 넣어 주세요"
                return
            }
        }
        testText.text = "확인하는 중…"
        folderUrl = null
        Thread {
            val r = DriveUploader({ b, n -> Base64.encode(b, 0, n, Base64.NO_WRAP) }).ping(url, secret)
            runOnUiThread {
                if (r.ok) {
                    folderUrl = r.folderUrl
                    testText.text = "✅ 연결 성공! 녹음은 드라이브 '폰녹음' 폴더에 날짜별로 저장돼요." +
                        if (r.folderUrl != null) "\n(여기를 누르면 폴더 열기)" else ""
                } else {
                    testText.text = "❌ ${r.message}"
                }
            }
        }.start()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 배터리 최적화에서 빠져 있어요")
            return
        }
        if (!openUrl("package:$packageName", Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
            openUrl(null, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    // ───────────── 화면 갱신 ─────────────

    private fun render() {
        val s = RecorderState
        val mode = s.mode
        statusText.text = when (mode) {
            Mode.OFF -> "꺼짐"
            Mode.STARTING -> "준비하는 중…"
            Mode.LISTENING -> "○ 소리 기다리는 중"
            Mode.RECORDING -> "● 녹음 중  ${elapsed(s.recordingSinceMs)}"
            Mode.STOPPING -> "정지하는 중…"
            Mode.UPLOAD_ONLY -> "남은 녹음 올리는 중"
        }
        statusText.setTextColor(if (mode == Mode.RECORDING) getColor(R.color.recording) else primaryColor)

        val listening = mode == Mode.LISTENING || mode == Mode.RECORDING
        meter.update(if (listening) s.levelDb else null, s.thresholdDb, s.floorDb, mode == Mode.RECORDING)
        levelText.text = if (listening) {
            "지금 소리 ${s.levelDb.roundToInt()} dB · 녹음 기준 ${s.thresholdDb.roundToInt()} dB (빨간 선)"
        } else {
            "녹음 대기를 켜면 소리 크기가 보여요"
        }

        toggleButton.text = when (mode) {
            Mode.STARTING, Mode.LISTENING, Mode.RECORDING -> getString(R.string.stop)
            Mode.STOPPING -> getString(R.string.stopping)
            else -> getString(R.string.start)
        }
        toggleButton.isEnabled = mode != Mode.STOPPING

        val warning = s.warning
        warningText.visibility = if (warning.isEmpty()) View.GONE else View.VISIBLE
        warningText.text = warning

        // 파일 개수는 1.5초마다(서비스가 꺼져 있으면 직접 센다)
        if (tick++ % 10 == 0) {
            if (mode == Mode.OFF) {
                s.pendingCount = storage.pending().size
                s.failedCount = storage.failed().size
            }
            uploadText.text = buildString {
                append("업로드 대기 ${s.pendingCount}개")
                if (s.failedCount > 0) append(" · 보류 ${s.failedCount}개")
                if (s.lastUploadText.isNotEmpty()) append("\n").append(s.lastUploadText)
            }
        }
    }

    private fun renderBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryText.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "✅ 배터리 최적화에서 빠져 있어요"
        } else {
            "화면이 꺼진 뒤 앱이 멈추지 않게 눌러 주세요. (삼성: 설정 > 배터리 > 백그라운드 사용 제한 > '절전 예외 앱'에도 추가)"
        }
    }

    private fun elapsed(since: Long): String {
        val sec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    // ───────────── 설정 저장 도우미 ─────────────

    private fun saveTextFields() {
        val url = urlEdit.text.toString().trim()
        val secret = secretEdit.text.toString().trim()
        if (url != settings.scriptUrl) settings.scriptUrl = url
        if (secret != settings.secret) settings.secret = secret
    }

    private fun setupSeek(
        seekId: Int,
        labelId: Int,
        min: Int,
        value: Int,
        save: (Int) -> Unit,
        label: (Int) -> String,
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val text = findViewById<TextView>(labelId)
        seek.progress = value - min
        text.text = label(value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                text.text = label(progress + min)
                if (fromUser) save(progress + min)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun setupCheck(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        val box = findViewById<CheckBox>(id)
        box.isChecked = value
        box.setOnCheckedChangeListener { _, checked -> save(checked) }
    }

    private fun openUrl(uri: String?, action: String = Intent.ACTION_VIEW): Boolean {
        return try {
            val i = Intent(action)
            if (uri != null) i.data = Uri.parse(uri)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
