package com.dualcam.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.dualcam.app.camera.BindConfig
import com.dualcam.app.camera.CaptureEvent
import com.dualcam.app.camera.DualCameraSession
import com.dualcam.app.camera.DualLayout
import com.dualcam.app.camera.FlashMode
import com.dualcam.app.camera.PhotoQuality
import com.dualcam.app.camera.RearLens
import com.dualcam.app.camera.VideoQuality
import com.dualcam.app.camera.ZoomRange
import com.dualcam.app.camera.equivalentZoomBounds
import com.dualcam.app.camera.lensForEquivalent
import com.dualcam.app.camera.nearestRearZoomStop
import com.dualcam.app.camera.rearStopTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DualCamUiState(
    val ready: Boolean = false,
    val supported: Boolean = true,
    val supportMessage: String? = null,
    val layout: DualLayout = DualLayout.Pip,
    val backPrimary: Boolean = true,
    val rearLens: RearLens = RearLens.Wide,
    val availableLenses: List<RearLens> = listOf(RearLens.Wide),
    val rearZoom: Float = 1f,
    val rearZoomRange: ZoomRange = ZoomRange(),
    val pinchZoomMin: Float = 0.5f,
    val pinchZoomMax: Float = 10f,
    val flashMode: FlashMode = FlashMode.Off,
    val torchOn: Boolean = false,
    val capturing: Boolean = false,
    val recording: Boolean = false,
    val composing: Boolean = false,
    val saveSeparate: Boolean = false,
    val photoQuality: PhotoQuality = PhotoQuality.Standard,
    val videoQuality: VideoQuality = VideoQuality.HD,
    val settingsOpen: Boolean = false,
    val recordingMs: Long = 0L,
    val lastMediaUri: Uri? = null,
    val lastMediaIsVideo: Boolean = false,
    val message: String? = null,
)

class DualCamViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("dualcam", Context.MODE_PRIVATE)
    private val session = DualCameraSession(application)
    private var lifecycleOwner: LifecycleOwner? = null
    private var timerJob: Job? = null

    private val _uiState = MutableStateFlow(
        DualCamUiState(
            saveSeparate = prefs.getBoolean(KEY_SAVE_SEPARATE, false),
            photoQuality = PhotoQuality.entries.getOrElse(prefs.getInt(KEY_PHOTO_QUALITY, 0)) { PhotoQuality.Standard },
            videoQuality = VideoQuality.entries.getOrElse(prefs.getInt(KEY_VIDEO_QUALITY, 0)) { VideoQuality.HD },
        ),
    )
    val uiState: StateFlow<DualCamUiState> = _uiState.asStateFlow()

    val backSurface = session.backSurface
    val frontSurface = session.frontSurface

    init {
        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is CaptureEvent.PhotosSaved -> {
                        val composed = event.composed
                        val separate = listOfNotNull(event.back, event.front)
                        _uiState.update {
                            it.copy(
                                capturing = false,
                                lastMediaUri = composed ?: event.back ?: event.front,
                                lastMediaIsVideo = false,
                                message = when {
                                    composed != null -> "已保存合拍照片"
                                    separate.size == 2 -> "已分别保存前后两张照片"
                                    separate.size == 1 -> "已保存 1 张照片"
                                    else -> "拍照未写入相册"
                                },
                            )
                        }
                    }
                    is CaptureEvent.VideoSaved -> {
                        val composed = event.composed
                        val separate = listOfNotNull(event.back, event.front)
                        _uiState.update {
                            it.copy(
                                composing = false,
                                lastMediaUri = composed ?: event.back ?: event.front,
                                lastMediaIsVideo = true,
                                message = when {
                                    composed != null -> "已保存合拍视频"
                                    separate.size == 2 -> "已分别保存前后两段视频"
                                    separate.size == 1 -> "已保存 1 段视频"
                                    else -> "录像未写入相册"
                                },
                            )
                        }
                    }
                    is CaptureEvent.Failed -> {
                        _uiState.update {
                            it.copy(
                                capturing = false,
                                composing = false,
                                message = event.message,
                            )
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            session.isRecording.collect { recording ->
                _uiState.update { state ->
                    state.copy(recording = recording, composing = false)
                }
                timerJob?.cancel()
                if (recording) {
                    val started = SystemClock.elapsedRealtime()
                    timerJob = viewModelScope.launch {
                        while (true) {
                            _uiState.update {
                                it.copy(recordingMs = SystemClock.elapsedRealtime() - started)
                            }
                            delay(200)
                        }
                    }
                } else {
                    _uiState.update { it.copy(recordingMs = 0L, torchOn = false) }
                }
            }
        }
    }

    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        viewModelScope.launch {
            runCatching {
                val prepared = session.prepare()
                val (pinchMin, pinchMax) = equivalentZoomBounds(prepared.lenses)
                _uiState.update {
                    it.copy(
                        ready = true,
                        supported = prepared.supported,
                        supportMessage = prepared.message,
                        availableLenses = prepared.lenses,
                        pinchZoomMin = pinchMin,
                        pinchZoomMax = pinchMax,
                        rearLens = when {
                            RearLens.Wide in prepared.lenses -> RearLens.Wide
                            else -> prepared.lenses.firstOrNull() ?: RearLens.Wide
                        },
                    )
                }
                if (prepared.supported) {
                    session.applyCaptureQuality(_uiState.value.photoQuality, _uiState.value.videoQuality)
                    rebind()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        ready = true,
                        supported = false,
                        supportMessage = error.message ?: "相机启动失败",
                    )
                }
            }
        }
    }

    fun setLayout(layout: DualLayout) {
        _uiState.update { it.copy(layout = layout) }
        session.updateLiveLayout(layout, _uiState.value.backPrimary)
    }

    fun swapPrimary() {
        _uiState.update { it.copy(backPrimary = !it.backPrimary) }
        val state = _uiState.value
        session.updateLiveLayout(state.layout, state.backPrimary)
    }

    fun toggleSettings() {
        _uiState.update { it.copy(settingsOpen = !it.settingsOpen) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(settingsOpen = false) }
    }

    fun toggleSaveSeparate() {
        val next = !_uiState.value.saveSeparate
        prefs.edit().putBoolean(KEY_SAVE_SEPARATE, next).apply()
        _uiState.update { it.copy(saveSeparate = next) }
        rebind()
    }

    fun setPhotoQuality(quality: PhotoQuality) {
        prefs.edit().putInt(KEY_PHOTO_QUALITY, quality.ordinal).apply()
        _uiState.update { it.copy(photoQuality = quality) }
        session.applyCaptureQuality(quality, _uiState.value.videoQuality)
    }

    fun setVideoQuality(quality: VideoQuality) {
        prefs.edit().putInt(KEY_VIDEO_QUALITY, quality.ordinal).apply()
        _uiState.update { it.copy(videoQuality = quality) }
        session.applyCaptureQuality(_uiState.value.photoQuality, quality)
    }

    fun setFlashMode(mode: FlashMode) {
        if (_uiState.value.recording) return
        _uiState.update { it.copy(flashMode = mode) }
        session.applyFlash(mode)
    }

    fun setRearZoom(equivalent: Float) {
        val state = _uiState.value
        val clamped = equivalent.coerceIn(state.pinchZoomMin, state.pinchZoomMax)
        val lens = lensForEquivalent(clamped, state.rearLens, state.availableLenses)
        applyRearZoom(clamped, lens)
    }

    fun selectRearStop(equivalent: Float) {
        val stop = nearestRearZoomStop(equivalent)
        val (lens, _) = rearStopTarget(stop.equivalent, _uiState.value.availableLenses)
        applyRearZoom(stop.equivalent, lens)
    }

    private fun applyRearZoom(equivalent: Float, lens: RearLens) {
        val state = _uiState.value
        val owner = lifecycleOwner ?: return
        val lensChanged = lens != state.rearLens
        _uiState.update { it.copy(rearZoom = equivalent, rearLens = lens) }
        if (lensChanged) {
            viewModelScope.launch {
                runCatching {
                    session.switchRearLens(owner, currentBindConfig(), equivalent)
                    _uiState.update { it.copy(rearZoomRange = session.rearZoomRange()) }
                }.onFailure { error ->
                    _uiState.update { it.copy(message = error.message ?: "切换镜头失败") }
                }
            }
        } else {
            session.applyEquivalentZoom(equivalent)
            _uiState.update { it.copy(rearZoomRange = session.rearZoomRange()) }
        }
    }

    fun cycleFlash() {
        if (_uiState.value.recording) return
        val next = when (_uiState.value.flashMode) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        _uiState.update { it.copy(flashMode = next) }
        session.applyFlash(next)
    }

    fun toggleTorch() {
        val enable = !_uiState.value.torchOn
        if (session.setTorch(enable)) {
            _uiState.update { it.copy(torchOn = enable) }
        }
    }

    fun takePhoto() {
        val state = _uiState.value
        if (!state.supported || state.capturing || state.recording) return
        _uiState.update { it.copy(capturing = true, message = null) }
        viewModelScope.launch {
            session.takePhotos(state.layout, state.backPrimary, state.saveSeparate)
        }
    }

    fun toggleRecording(audioEnabled: Boolean) {
        val state = _uiState.value
        val owner = lifecycleOwner ?: return
        if (!state.supported || state.capturing) return
        if (state.recording) {
            session.stopRecording()
        } else {
            viewModelScope.launch {
                session.startRecording(
                    owner = owner,
                    audioEnabled = audioEnabled,
                    layout = state.layout,
                    backPrimary = state.backPrimary,
                    saveSeparate = state.saveSeparate,
                    zoom = state.rearZoom,
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        timerJob?.cancel()
        session.release()
        super.onCleared()
    }

    private fun currentBindConfig(): BindConfig {
        val state = _uiState.value
        return BindConfig(
            rearLens = state.rearLens,
            flashMode = state.flashMode,
            saveSeparate = state.saveSeparate,
        )
    }

    private fun rebind() {
        val owner = lifecycleOwner ?: return
        val state = _uiState.value
        if (!state.supported) return
        viewModelScope.launch {
            runCatching {
                session.bind(owner, currentBindConfig())
                session.applyEquivalentZoom(state.rearZoom)
                _uiState.update { it.copy(rearZoomRange = session.rearZoomRange()) }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "相机启动失败") }
            }
        }
    }

    private companion object {
        const val KEY_SAVE_SEPARATE = "save_separate"
        const val KEY_PHOTO_QUALITY = "photo_quality"
        const val KEY_VIDEO_QUALITY = "video_quality"
    }
}
