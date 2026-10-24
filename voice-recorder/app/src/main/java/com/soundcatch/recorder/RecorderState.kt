package com.soundcatch.recorder

/** 서비스 ↔ 화면이 공유하는 현재 상태 (같은 프로세스 안에서만 사용) */
object RecorderState {
    enum class Mode { OFF, STARTING, LISTENING, RECORDING, STOPPING, UPLOAD_ONLY }

    @Volatile var mode = Mode.OFF
    @Volatile var levelDb = SoundDetector.SILENCE_DB
    @Volatile var thresholdDb = SoundDetector.SILENCE_DB
    @Volatile var floorDb = SoundDetector.SILENCE_DB
    @Volatile var recordingSinceMs = 0L
    @Volatile var pendingCount = 0
    @Volatile var failedCount = 0
    @Volatile var uploadedCount = 0
    @Volatile var lastUploadText = ""
    @Volatile var warning = ""

    val isCapturing: Boolean
        get() = mode == Mode.STARTING || mode == Mode.LISTENING || mode == Mode.RECORDING
}
