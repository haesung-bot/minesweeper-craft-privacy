package com.soundcatch.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.soundcatch.recorder.RecorderState.Mode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면이 꺼져도 계속 마이크를 듣다가 소리가 나면 녹음하고, 녹음 파일을 드라이브로 올리는 서비스.
 * 켜져 있는 동안 상단 알림이 항상 표시된다(안드로이드 규칙).
 */
class RecorderService : Service(), SoundDetector.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val ACTION_START = "com.soundcatch.recorder.START"
        private const val ACTION_STOP = "com.soundcatch.recorder.STOP"
        private const val ACTION_UPLOAD = "com.soundcatch.recorder.UPLOAD"

        const val SAMPLE_RATE = 44_100
        private const val FRAME_SAMPLES = SAMPLE_RATE / 20 // 50ms
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val MIC_CHECK_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun uploadNow(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_UPLOAD))
        }

        fun stop(context: Context) {
            context.startService(intent(context, ACTION_STOP))
        }

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            1,
            intent(context, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun intent(context: Context, action: String) =
            Intent(context, RecorderService::class.java).setAction(action)
    }

    private lateinit var settings: AppSettings
    private lateinit var storage: Storage
    private lateinit var uploader: Uploader
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var capturing = false
    @Volatile private var uploadAfterStop = false
    @Volatile private var destroyed = false
    @Volatile private var detector: SoundDetector? = null
    private var captureThread: Thread? = null

    // 녹음 스레드에서만 사용
    private var writer: AacFileWriter? = null
    private var writerFile: File? = null
    private val fileTime = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        storage = Storage(this)
        Notifier.ensureChannels(this)
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        uploader = Uploader(
            this,
            settings,
            storage,
            onChanged = { main.post { Notifier.update(this) } },
            onIdle = { main.post { if (RecorderState.mode == Mode.UPLOAD_ONLY) shutdown() } },
        )
        uploader.start()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.wasRunning = false
                if (capturing) {
                    stopCapture()
                } else if (RecorderState.mode != Mode.UPLOAD_ONLY) {
                    shutdown()
                }
                return START_NOT_STICKY
            }
            ACTION_UPLOAD -> {
                storage.requeueFailed()
                if (capturing) {
                    uploader.kick()
                    return START_STICKY
                }
                enterUploadOnly()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START, 또는 시스템이 서비스를 되살린 경우(intent == null)
                if (intent == null && !settings.wasRunning) {
                    shutdown()
                    return START_NOT_STICKY
                }
                if (!startCapture()) return START_NOT_STICKY
                settings.wasRunning = true
                return START_STICKY
            }
        }
    }

    // ───────────── 녹음 시작/정지 ─────────────

    private fun startCapture(): Boolean {
        if (capturing) {
            Notifier.update(this)
            return true
        }
        // 직전 녹음 스레드가 파일을 마무리하는 중이면 끝날 때까지 잠깐 기다린다
        captureThread?.let { if (it.isAlive) it.join(3_000) }
        uploadAfterStop = false
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            RecorderState.warning = "마이크 권한이 없어요. 앱을 열어 '녹음 시작'을 눌러 주세요"
            foregroundThenStop()
            return false
        }
        RecorderState.mode = Mode.STARTING
        RecorderState.warning = ""
        try {
            goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // 안드로이드 14+: 화면 밖(재부팅 등)에서는 마이크 서비스를 시작할 수 없다 → 탭해서 다시 시작
            Notifier.postRestartPrompt(this)
            foregroundThenStop()
            return false
        }
        Notifier.cancelRestartPrompt(this)
        storage.deleteParts()
        capturing = true
        uploader.oneShot = false
        uploader.kick()
        captureThread = Thread({ captureLoop() }, "capture").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    /** 녹음을 끝내고, 남은 파일이 있으면 올린 다음 서비스를 끈다. */
    private fun stopCapture() {
        uploadAfterStop = true
        RecorderState.mode = Mode.STOPPING
        Notifier.update(this)
        capturing = false // 녹음 스레드가 파일을 마무리하고 onCaptureEnded()를 부른다
    }

    private fun onCaptureEnded(ended: Thread) {
        if (captureThread === ended) captureThread = null
        if (destroyed) return
        if (capturing) return // 그 사이 다시 켜짐
        val upload = uploadAfterStop
        uploadAfterStop = false
        if (upload && storage.pending().isNotEmpty() && settings.driveConfigured &&
            Net.available(this, settings.wifiOnly)
        ) {
            enterUploadOnly()
        } else {
            shutdown()
        }
    }

    private fun enterUploadOnly() {
        try {
            goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            shutdown()
            return
        }
        RecorderState.mode = Mode.UPLOAD_ONLY
        Notifier.update(this)
        uploader.oneShot = true
        uploader.kick()
    }

    /** startForegroundService()로 불렸으면 반드시 startForeground()를 한 번 불러야 한다. */
    private fun foregroundThenStop() {
        try {
            goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
        }
        shutdown()
    }

    private fun goForeground(type: Int) {
        val notification = Notifier.status(this)
        if (Build.VERSION.SDK_INT >= 30) {
            ServiceCompat.startForeground(this, Notifier.ID_STATUS, notification, type)
        } else {
            startForeground(Notifier.ID_STATUS, notification)
        }
    }

    private fun shutdown() {
        capturing = false
        RecorderState.mode = Mode.OFF
        RecorderState.levelDb = SoundDetector.SILENCE_DB
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        capturing = false
        captureThread?.join(3_000)
        uploader.shutdown()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        RecorderState.mode = Mode.OFF
        RecorderState.levelDb = SoundDetector.SILENCE_DB
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key in AppSettings.DETECTOR_KEYS) detector?.config = settings.detectorConfig()
        if (key in AppSettings.UPLOAD_KEYS) uploader.kick()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundCatch:recorder").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ───────────── 녹음 스레드 ─────────────

    private fun captureLoop() {
        val d = SoundDetector(SAMPLE_RATE, settings.detectorConfig())
        detector = d
        val frame = ShortArray(FRAME_SAMPLES)
        var record: AudioRecord? = null
        val startedAt = SystemClock.elapsedRealtime()
        var lastHousekeeping = 0L
        var lastNotifiedMode = Mode.STARTING
        try {
            while (capturing) {
                if (record == null) {
                    record = openAudio()
                    if (record == null) {
                        RecorderState.warning = "마이크를 열 수 없어요 (다른 앱이 사용 중일 수 있어요)"
                        main.post { Notifier.update(this) }
                        SystemClock.sleep(3_000)
                        continue
                    }
                    if (RecorderState.mode == Mode.STARTING) RecorderState.mode = Mode.LISTENING
                }
                val n = readFrame(record, frame)
                if (n < 0) {
                    // 마이크 오류 → 다시 연다
                    finishRecording(d)
                    closeAudio(record)
                    record = null
                    SystemClock.sleep(1_000)
                    continue
                }
                if (n == 0) continue
                d.process(frame, n, this)
                RecorderState.levelDb = d.levelDb
                RecorderState.thresholdDb = d.thresholdDb
                RecorderState.floorDb = d.floorDb

                val mode = RecorderState.mode
                if (mode != lastNotifiedMode) {
                    lastNotifiedMode = mode
                    main.post { Notifier.update(this) }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - startedAt > MIC_CHECK_MS && d.onlyDigitalSilence) {
                    // 30초 동안 완전 무음 = 시스템이 마이크를 막은 상태 (백그라운드에서 켜진 경우 등)
                    RecorderState.warning = "마이크 소리가 들어오지 않아요"
                    Notifier.postRestartPrompt(this, "마이크 소리가 들어오지 않아요. 탭해서 녹음 대기를 다시 시작해 주세요")
                    capturing = false
                    break
                }
                if (now - lastHousekeeping > 10_000) {
                    lastHousekeeping = now
                    checkStorage(d)
                }
            }
        } catch (t: Throwable) {
            RecorderState.warning = "녹음 오류: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            finishRecording(d)
            closeAudio(record)
            if (detector === d) detector = null
            val ended = Thread.currentThread()
            main.post { onCaptureEnded(ended) }
        }
    }

    private fun finishRecording(d: SoundDetector) {
        try {
            d.finish(this)
        } catch (t: Throwable) {
            abortWriter()
        }
    }

    @SuppressLint("MissingPermission") // 권한은 startCapture()에서 확인
    private fun openAudio(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val size = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            var r: AudioRecord? = null
            try {
                r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording()
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return r
                }
            } catch (_: Exception) {
            }
            try {
                r?.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 50ms 분량을 채워서 읽는다. 오류면 음수. */
    private fun readFrame(record: AudioRecord, frame: ShortArray): Int {
        var filled = 0
        while (filled < frame.size && capturing) {
            val r = record.read(frame, filled, frame.size - filled)
            if (r < 0) return r
            if (r == 0) return filled
            filled += r
        }
        return filled
    }

    private fun closeAudio(record: AudioRecord?) {
        if (record == null) return
        try {
            record.stop()
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    private fun checkStorage(d: SoundDetector) {
        if (storage.freeBytes() < MIN_FREE_BYTES) storage.pruneUploaded(MIN_FREE_BYTES)
        if (storage.freeBytes() < MIN_FREE_BYTES / 2 && d.isRecording) {
            RecorderState.warning = "저장 공간이 부족해서 녹음을 멈췄어요"
            finishRecording(d)
        }
    }

    // ───────────── SoundDetector.Listener (녹음 스레드에서 호출) ─────────────

    override fun onRecordStart() {
        if (storage.freeBytes() < MIN_FREE_BYTES) storage.pruneUploaded(MIN_FREE_BYTES)
        if (storage.freeBytes() < MIN_FREE_BYTES / 2) {
            RecorderState.warning = "저장 공간이 부족해서 녹음을 쉬고 있어요"
            return
        }
        val now = System.currentTimeMillis()
        val part = File(storage.pendingDir, fileTime.format(Date(now)) + ".m4a.part")
        try {
            writer = AacFileWriter.open(part, SAMPLE_RATE)
            writerFile = part
            RecorderState.recordingSinceMs = now
            RecorderState.mode = Mode.RECORDING
            if (RecorderState.warning.startsWith("저장")) RecorderState.warning = ""
        } catch (e: Exception) {
            writer = null
            writerFile = null
            part.delete()
            RecorderState.warning = "녹음 파일을 만들 수 없어요: ${e.message}"
        }
    }

    override fun onRecordData(samples: ShortArray, count: Int) {
        val w = writer ?: return
        try {
            w.write(samples, count)
        } catch (e: Exception) {
            RecorderState.warning = "저장 오류: ${e.message}"
            abortWriter()
        }
    }

    override fun onRecordStop(split: Boolean) {
        val w = writer
        val part = writerFile
        writer = null
        writerFile = null
        if (w != null && part != null) {
            val seconds = w.durationSec
            if (w.close()) {
                val name = part.name.removeSuffix(".m4a.part") + "_" + formatDuration(seconds) + ".m4a"
                if (!part.renameTo(Storage.uniqueFile(storage.pendingDir, name))) part.delete()
                uploader.kick()
            } else {
                part.delete()
            }
        }
        if (!split && RecorderState.mode == Mode.RECORDING) RecorderState.mode = Mode.LISTENING
    }

    private fun abortWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writerFile?.delete()
        writer = null
        writerFile = null
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m${s.toString().padStart(2, '0')}s" else "${s}s"
    }
}
