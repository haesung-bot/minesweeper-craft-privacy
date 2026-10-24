package com.soundcatch.recorder

import android.content.Context
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 녹음 파일을 오래된 것부터 하나씩 드라이브로 올리는 백그라운드 스레드.
 * - 와이파이/인터넷이 없으면 기다렸다가 다시 시도
 * - 구글 서버 오류는 간격을 늘려 가며 재시도, 같은 파일이 5번 실패하면 failed/ 로 옮겨 다음 파일 진행
 * - 업로드 성공하면 폰에서 지우거나(uploaded/로 옮기거나)
 */
class Uploader(
    private val context: Context,
    private val settings: AppSettings,
    private val storage: Storage,
    /** 대기 개수·상태 문구가 바뀌었을 때 */
    private val onChanged: () -> Unit,
    /** oneShot 모드에서 더 할 일이 없을 때 */
    private val onIdle: () -> Unit,
) : Thread("uploader") {

    private val signal = LinkedBlockingQueue<Boolean>()

    @Volatile private var running = true

    /** true: 남은 파일만 한 번 올리고 끝내는 모드 (녹음을 끈 뒤) */
    @Volatile var oneShot = false

    private val drive = DriveUploader({ bytes, len -> Base64.encode(bytes, 0, len, Base64.NO_WRAP) })
    private val attempts = HashMap<String, Int>()
    private var backoffMs = 0L
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

    /** 새 파일이 생겼거나 설정이 바뀌었을 때 기다리지 말고 바로 시도 */
    fun kick() {
        signal.offer(true)
    }

    fun shutdown() {
        running = false
        kick()
    }

    override fun run() {
        while (running) {
            val waitMs = try {
                step()
            } catch (t: Throwable) {
                setText("업로드 오류: ${t.message ?: t.javaClass.simpleName}")
                60_000L
            }
            if (!running) break
            if (waitMs > 0) waitForKick(waitMs)
        }
    }

    private fun waitForKick(ms: Long) {
        try {
            signal.poll(ms, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        signal.clear()
    }

    /** 한 번 시도하고, 다음 시도까지 기다릴 시간(ms)을 돌려준다. 0이면 바로 다음 파일. */
    private fun step(): Long {
        val files = storage.pending()
        updateCounts(files.size)
        if (files.isEmpty()) {
            if (oneShot) onIdle()
            return IDLE_WAIT_MS
        }
        if (!settings.driveConfigured) {
            setText("드라이브 설정 전이라 폰에 보관 중")
            if (oneShot) onIdle()
            return IDLE_WAIT_MS
        }
        if (!Net.available(context, settings.wifiOnly)) {
            setText(if (settings.wifiOnly) "와이파이 연결을 기다리는 중" else "인터넷 연결을 기다리는 중")
            if (oneShot) onIdle()
            return 30_000L
        }

        val file = files.first()
        if (!file.exists()) return 0L
        val result = drive.upload(settings.scriptUrl, settings.secret, file, file.name, folderFor(file))
        return when (result.kind) {
            DriveUploader.Kind.OK -> {
                attempts.remove(file.name)
                backoffMs = 0L
                afterSuccess(file)
                RecorderState.uploadedCount++
                setText("${timeFormat.format(Date())} 업로드 성공 (${file.name})")
                updateCounts(storage.pending().size)
                0L
            }
            DriveUploader.Kind.CONFIG -> {
                setText("설정 확인 필요: ${result.message}")
                if (oneShot) onIdle()
                CONFIG_WAIT_MS
            }
            DriveUploader.Kind.NETWORK -> {
                setText("다시 시도 예정 · ${result.message}")
                if (oneShot) onIdle()
                nextBackoff()
            }
            DriveUploader.Kind.SERVER -> {
                val n = (attempts[file.name] ?: 0) + 1
                attempts[file.name] = n
                if (n >= MAX_ATTEMPTS) {
                    attempts.remove(file.name)
                    storage.moveTo(file, storage.failedDir)
                    setText("${file.name} 업로드 ${n}번 실패 → 보류 (${result.message})")
                    updateCounts(storage.pending().size)
                    0L
                } else {
                    setText("다시 시도 예정($n/$MAX_ATTEMPTS) · ${result.message}")
                    if (oneShot) onIdle()
                    nextBackoff()
                }
            }
        }
    }

    private fun afterSuccess(file: File) {
        if (settings.deleteAfterUpload) {
            file.delete()
        } else {
            storage.moveTo(file, storage.uploadedDir)
        }
    }

    private fun nextBackoff(): Long {
        backoffMs = (if (backoffMs == 0L) 15_000L else backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        return backoffMs
    }

    private fun updateCounts(pending: Int) {
        val failed = storage.failed().size
        if (pending != RecorderState.pendingCount || failed != RecorderState.failedCount) {
            RecorderState.pendingCount = pending
            RecorderState.failedCount = failed
            onChanged()
        }
    }

    private fun setText(text: String) {
        if (RecorderState.lastUploadText != text) {
            RecorderState.lastUploadText = text
            onChanged()
        }
    }

    companion object {
        private const val IDLE_WAIT_MS = 60_000L
        private const val CONFIG_WAIT_MS = 10 * 60_000L
        private const val MAX_BACKOFF_MS = 10 * 60_000L
        private const val MAX_ATTEMPTS = 5

        /** 파일 이름 앞의 날짜(2026-10-24_…)로 드라이브 하위 폴더를 정한다. */
        fun folderFor(file: File): String {
            val name = file.name
            return if (name.length >= 10 && name[4] == '-' && name[7] == '-') name.substring(0, 10) else "기타"
        }
    }
}
