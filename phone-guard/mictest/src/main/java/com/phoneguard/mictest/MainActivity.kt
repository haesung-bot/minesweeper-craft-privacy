package com.phoneguard.mictest

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PhoneGuard의 "실시간 마이크 감시" 기능을 테스트하기 위한 표적(target) 앱.
 *
 * 백신 테스트의 EICAR 파일과 같은 역할이다: 진짜로 마이크 하드웨어를 열어서
 * 안드로이드 OS가 "지금 마이크 사용 중"으로 인식하게 만들지만, 읽은 오디오는
 * 화면에 보여주지도, 파일로 저장하지도, 분석하지도, 어디로 전송하지도 않는다.
 * 읽는 즉시 버퍼를 버린다. 목적은 오직 "OS 신호를 켜는 것" 하나뿐이다.
 *
 * 그래서 이 앱은 INTERNET 권한이 아예 없다(매니페스트에 선언조차 안 함) —
 * 설령 코드에 실수가 있어도 밖으로 보낼 방법 자체가 없다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startTestRecording()
        } else {
            statusText.text = "마이크 권한이 거부됐어요. 테스트를 진행할 수 없어요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (isRecording.get()) stopTestRecording() else ensurePermissionThenStart()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) stopTestRecording()
    }

    private fun ensurePermissionThenStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startTestRecording()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startTestRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "마이크 권한이 없어요."
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            statusText.text = "이 기기에서 마이크를 열 수 없어요."
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, minBufferSize
            )
        } catch (_: Exception) {
            statusText.text = "마이크를 여는 데 실패했어요(다른 앱이 쓰고 있을 수도 있어요)."
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            statusText.text = "마이크 초기화에 실패했어요."
            recorder.release()
            return
        }

        isRecording.set(true)
        toggleButton.text = getString(R.string.btn_stop)
        statusText.text = "🔴 마이크 테스트 중... (저장·전송 없음)"

        recordThread = Thread {
            try {
                recorder.startRecording()
                val buffer = ByteArray(minBufferSize)
                // 읽은 내용은 아무 데도 쓰지 않고 바로 버린다. 목적은 OS 레벨
                // "마이크 사용 중" 신호를 켜는 것뿐이지, 소리 자체가 아니다.
                while (isRecording.get()) {
                    recorder.read(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
                // 표적 앱이라 실패해도 크래시 없이 조용히 끝낸다.
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
        recordThread?.start()
    }

    private fun stopTestRecording() {
        isRecording.set(false)
        recordThread?.join(500)
        recordThread = null
        toggleButton.text = getString(R.string.btn_start)
        statusText.text = "⚪ 중지됨"
    }
}
