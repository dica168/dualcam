@file:OptIn(ExperimentalCamera2Interop::class)

package com.dualcam.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.dualcam.app.camera.DualLayoutComposer.oriented
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class DualCameraSession(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analysisExecutor = Executors.newFixedThreadPool(2)

    private val _backSurface = MutableStateFlow<SurfaceRequest?>(null)
    val backSurface: StateFlow<SurfaceRequest?> = _backSurface.asStateFlow()

    private val _frontSurface = MutableStateFlow<SurfaceRequest?>(null)
    val frontSurface: StateFlow<SurfaceRequest?> = _frontSurface.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    private var provider: ProcessCameraProvider? = null
    private var concurrent: ConcurrentCamera? = null
    private var imageCaptureBack: ImageCapture? = null
    private var imageCaptureFront: ImageCapture? = null
    private var videoCaptureBack: VideoCapture<Recorder>? = null
    private var videoCaptureFront: VideoCapture<Recorder>? = null
    private var recordingBack: Recording? = null
    private var recordingFront: Recording? = null
    // Written on the main thread, read by the ImageAnalysis executor threads.
    @Volatile private var liveRecorder: LiveDualRecorder? = null
    @Volatile private var startingRecording: Boolean = false
    @Volatile private var stoppingSeparate: Boolean = false
    private var videoQuality: VideoQuality = VideoQuality.HD
    private var photoQuality: PhotoQuality = PhotoQuality.Standard
    private var boundOwner: LifecycleOwner? = null
    private var lastBackSelector: CameraSelector? = null
    private var lastFrontSelector: CameraSelector? = null
    private var boundConfig: BindConfig? = null
    private var compositionMode: Boolean = false
    private var recordingSession: Boolean = false
    private var lastZoom: Float = 1f
    private var rearIntrinsic: Float = 1f
    private var backCamera: Camera? = null
    private var frontCamera: Camera? = null
    private var torchEnabled: Boolean = false
    private var recordLayout: DualLayout = DualLayout.Pip
    private var recordBackPrimary: Boolean = true
    private var recordBackFile: File? = null
    private var recordFrontFile: File? = null

    // --- Camera health monitoring (main-thread state unless noted) ---
    // CameraX gives up on a camera after some errors (ERROR_CAMERA_DISABLED, service or fatal
    // errors, or 10 s of failed reopen attempts), and in concurrent mode one dead camera also
    // keeps its partner from streaming. Nothing reopens it afterwards, so the session watches the
    // preview streams itself and rebinds both cameras when either one stops delivering frames.
    /** elapsedRealtime of the last preview frame; written from the camera threads. */
    private val backFrameAt = AtomicLong(0L)
    private val frontFrameAt = AtomicLong(0L)
    /** The config the UI last asked for, kept even when binding it failed. */
    private var wantedConfig: BindConfig? = null
    /** Frames older than this don't count: set on every (re)bind and while not resumed. */
    private var watchSince = 0L
    /** When a camera last reported an error; frames older than this don't prove it is alive. */
    private var lastErrorAt = 0L
    /** Set on an error CameraX will not recover from, to rebind without waiting out the stall. */
    private var criticalErrorAt = 0L
    private var recoverAttempts = 0
    private var everStreamed = false
    private var interruptionNotified = false
    private var lastCameraError: Int? = null
    private var capturesInFlight = 0
    private var watchdogJob: Job? = null
    private val stateObservers = mutableListOf<Pair<LiveData<CameraState>, Observer<CameraState>>>()

    suspend fun prepare(): PrepareResult {
        val feature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        val cameraProvider = obtainProvider()
        val pairs = cameraProvider.availableConcurrentCameraInfos
        val lenses = runCatching { detectLenses(pairs) }.getOrDefault(listOf(RearLens.Wide))
        if (pairs.isEmpty()) {
            val message = if (feature) {
                "系统声明支持并发相机，但当前没有可用的前后摄像头组合。"
            } else {
                "这台设备没有开放前后摄像头同时工作。Pixel 10 Pro XL 应支持此功能。"
            }
            return PrepareResult(supported = false, message = message, lenses = lenses)
        }
        return PrepareResult(supported = true, message = null, lenses = lenses)
    }

    suspend fun bind(owner: LifecycleOwner, config: BindConfig) {
        val cameraProvider = obtainProvider()
        wantedConfig = config
        startWatchdog()
        if (_isRecording.value) {
            if (boundOwner !== owner && liveRecorder != null) {
                boundOwner = owner
                boundConfig = config
                bindPreviewAndAnalysis(owner)
                applyEquivalentZoom(lastZoom)
                if (torchEnabled) {
                    torchEnabled = setTorch(true)
                }
            } else if (boundOwner !== owner) {
                // Separate-file recording is tied to the old (now destroyed) owner and cannot be
                // moved. Remember the new owner and drop the dead binding so the watchdog rebinds
                // as soon as the recordings have finalized.
                boundOwner = owner
                concurrent = null
                watchSince = SystemClock.elapsedRealtime()
            }
            return
        }
        // A new LifecycleOwner (activity recreated) means the old binding is already dead,
        // so the early-return must also require the same owner.
        if (!recordingSession && boundConfig == config && concurrent != null && boundOwner === owner) return

        resetBindings(cameraProvider)

        val target = resolveRearTarget(cameraProvider, config.rearLens)
        if (target == null) {
            _events.tryEmit(CaptureEvent.Failed("找不到可同时开启的前后摄像头组合。"))
            return
        }

        lastBackSelector = target.backSelector
        lastFrontSelector = target.frontSelector
        rearIntrinsic = target.intrinsic
        boundOwner = owner
        val rotation = displayRotation(owner)
        try {
            bindCameras(owner, cameraProvider, target.backSelector, target.frontSelector, rotation, config)
        } catch (error: Exception) {
            lastBackSelector = CameraSelector.DEFAULT_BACK_CAMERA
            lastFrontSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            rearIntrinsic = 1f
            cameraProvider.unbindAll()
            bindCameras(
                owner,
                cameraProvider,
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                rotation,
                config,
            )
        }
        boundConfig = config
        compositionMode = false
        recordingSession = false
        if (torchEnabled) {
            torchEnabled = setTorch(true)
        }
    }

    fun updateLiveLayout(layout: DualLayout, backPrimary: Boolean) {
        liveRecorder?.layout = layout
        liveRecorder?.backPrimary = backPrimary
        recordLayout = layout
        recordBackPrimary = backPrimary
    }

    fun applyCaptureQuality(photo: PhotoQuality, video: VideoQuality) {
        photoQuality = photo
        videoQuality = video
    }

    suspend fun takePhotos(
        layout: DualLayout,
        backPrimary: Boolean,
        saveSeparate: Boolean,
    ) {
        val backCapture = imageCaptureBack
        val frontCapture = imageCaptureFront
        if (backCapture == null || frontCapture == null) {
            _events.emit(CaptureEvent.Failed("拍照尚未就绪。"))
            return
        }
        // JPEG decode + rotation in the capture callback and the MediaStore write are heavy;
        // keep them off the main thread.
        val executor = Dispatchers.IO.asExecutor()
        val stamp = timestamp()
        capturesInFlight++
        try {
            coroutineScope {
                val backJob = async { runCatching { backCapture.awaitBitmap(executor) } }
                val frontJob = async { runCatching { frontCapture.awaitBitmap(executor) } }
                val backBmp = backJob.await().getOrNull()
                val frontBmp = frontJob.await().getOrNull()
                if (backBmp == null && frontBmp == null) {
                    val reason = backJob.await().exceptionOrNull()?.message
                        ?: frontJob.await().exceptionOrNull()?.message
                        ?: "拍照失败"
                    _events.emit(CaptureEvent.Failed(reason))
                    return@coroutineScope
                }
                val event = withContext(Dispatchers.IO) {
                    if (saveSeparate) {
                        val backUri = backBmp?.let { saveJpeg(it, "DUAL_BACK_$stamp.jpg", photoQuality.jpeg) }
                        val frontUri = frontBmp?.let { saveJpeg(it, "DUAL_FRONT_$stamp.jpg", photoQuality.jpeg) }
                        CaptureEvent.PhotosSaved(composed = null, back = backUri, front = frontUri)
                    } else if (backBmp != null && frontBmp != null) {
                        val composed = DualLayoutComposer.compose(
                            backBmp,
                            frontBmp,
                            layout,
                            backPrimary,
                            photoQuality.width,
                            photoQuality.height,
                        )
                        val uri = saveJpeg(composed, "DUAL_$stamp.jpg", photoQuality.jpeg)
                        composed.recycle()
                        CaptureEvent.PhotosSaved(composed = uri, back = null, front = null)
                    } else {
                        val uri = saveJpeg((backBmp ?: frontBmp)!!, "DUAL_$stamp.jpg", photoQuality.jpeg)
                        CaptureEvent.PhotosSaved(
                            composed = uri,
                            back = null,
                            front = null,
                            degradedSingle = true,
                        )
                    }
                }
                backBmp?.recycle()
                frontBmp?.recycle()
                _events.emit(event)
            }
        } catch (error: Exception) {
            _events.emit(CaptureEvent.Failed(error.message ?: "照片保存失败"))
        } finally {
            capturesInFlight--
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        owner: LifecycleOwner,
        audioEnabled: Boolean,
        layout: DualLayout,
        backPrimary: Boolean,
        saveSeparate: Boolean,
        zoom: Float,
    ) {
        if (_isRecording.value || startingRecording || liveRecorder != null || recordingBack != null || recordingFront != null) return
        startingRecording = true
        try {
            recordLayout = layout
            recordBackPrimary = backPrimary
            val recordAudio = audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)
            val executor = ContextCompat.getMainExecutor(context)
            val dir = recordingCacheDir()

            if (saveSeparate) {
                val backCapture = videoCaptureBack
                val frontCapture = videoCaptureFront
                if (backCapture == null || frontCapture == null) {
                    _events.tryEmit(CaptureEvent.Failed("分别保存需要两路录像，当前会话不支持。"))
                    return
                }
                val stamp = timestamp()
                val backFile = File(dir, "DUAL_BACK_$stamp.mp4")
                val frontFile = File(dir, "DUAL_FRONT_$stamp.mp4")
                recordBackFile = backFile
                recordFrontFile = frontFile
                stoppingSeparate = false
                var aborted = false
                var pending = 2
                var backOk = false
                var frontOk = false
                var error: String? = null
                fun onEvent(event: VideoRecordEvent, isBack: Boolean) {
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            if (!aborted && !stoppingSeparate) {
                                _isRecording.value = true
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (isBack) recordingBack = null else recordingFront = null
                            if (aborted) {
                                backFile.delete()
                                frontFile.delete()
                                return
                            }
                            if (event.hasError()) {
                                error = event.cause?.message ?: "录像失败（${event.error}）"
                            } else if (isBack) {
                                backOk = true
                            } else {
                                frontOk = true
                            }
                            pending--
                            if (pending == 0) {
                                stoppingSeparate = false
                                _isRecording.value = false
                                finishRecording(backOk, frontOk, error)
                            }
                        }
                    }
                }
                try {
                    recordingBack = startFile(backCapture, backFile, recordAudio, executor) {
                        onEvent(it, isBack = true)
                    }
                    recordingFront = startFile(frontCapture, frontFile, audioEnabled = false, executor) {
                        onEvent(it, isBack = false)
                    }
                } catch (error: Exception) {
                    aborted = true
                    stoppingSeparate = false
                    // If the second recording fails to start, don't leave the first one running.
                    runCatching { recordingBack?.stop() }
                    runCatching { recordingFront?.stop() }
                    recordingBack = null
                    recordingFront = null
                    recordBackFile = null
                    recordFrontFile = null
                    backFile.delete()
                    frontFile.delete()
                    _isRecording.value = false
                    _events.tryEmit(CaptureEvent.Failed(error.message ?: "无法启动录像。"))
                }
                return
            }

            lastZoom = zoom
            try {
                bindPreviewAndAnalysis(owner)
                applyEquivalentZoom(zoom)
                if (torchEnabled) {
                    torchEnabled = setTorch(true)
                }
                val rec = LiveDualRecorder(
                    quality = videoQuality,
                    audioEnabled = recordAudio,
                    cacheDir = dir,
                )
                rec.layout = layout
                rec.backPrimary = backPrimary
                // Runs on the encoder thread; hop to main so the stop path (which joins that
                // thread) runs where the rest of the session state is touched.
                rec.onFailure = { error -> scope.launch { onLiveRecorderFailed(rec, error) } }
                liveRecorder = rec
                rec.start()
                _isRecording.value = true
            } catch (error: Throwable) {
                liveRecorder = null
                _isRecording.value = false
                _events.tryEmit(CaptureEvent.Failed(error.message ?: "无法启动录像。"))
                runCatching {
                    bind(owner, boundConfig ?: BindConfig(RearLens.Wide, FlashMode.Off, saveSeparate))
                }
            }
        } finally {
            startingRecording = false
        }
    }

    private fun bindPreviewAndAnalysis(owner: LifecycleOwner) {
        val cameraProvider = provider ?: throw IllegalStateException("相机未初始化")
        val backSelector = lastBackSelector ?: throw IllegalStateException("后置镜头不可用")
        val frontSelector = lastFrontSelector ?: throw IllegalStateException("前置镜头不可用")
        val rotation = displayRotation(owner)
        resetBindings(cameraProvider)
        boundOwner = owner

        val previewBack = previewOf(rotation, backFrameAt, _backSurface)
        val previewFront = previewOf(rotation, frontFrameAt, _frontSurface)
        // Frames go to the GPU composer as raw RGBA; rotation is handled there.
        val backAnalysis = imageAnalysisOf(rotation) { image -> liveRecorder?.submitBack(image) }
        val frontAnalysis = imageAnalysisOf(rotation) { image -> liveRecorder?.submitFront(image) }
        val bound = cameraProvider.bindToLifecycle(
            listOf(
                ConcurrentCamera.SingleCameraConfig(
                    backSelector,
                    UseCaseGroup.Builder().addUseCase(previewBack).addUseCase(backAnalysis).build(),
                    owner,
                ),
                ConcurrentCamera.SingleCameraConfig(
                    frontSelector,
                    UseCaseGroup.Builder().addUseCase(previewFront).addUseCase(frontAnalysis).build(),
                    owner,
                ),
            ),
        )
        concurrent = bound
        assignCameras(bound)
        recordingSession = true
    }

    fun applyFlash(mode: FlashMode) {
        imageCaptureBack?.flashMode = mode.toImageFlash()
    }

    fun stopRecording() {
        val backRec = recordingBack
        val frontRec = recordingFront
        val hasSeparate = backRec != null || frontRec != null
        if (hasSeparate) {
            stoppingSeparate = true
            backRec?.stop()
            frontRec?.stop()
        }
        val rec = liveRecorder
        liveRecorder = null
        if (rec != null) {
            finishLiveRecording(rec, interrupted = false)
        } else if (!hasSeparate) {
            _isRecording.value = false
            torchEnabled = false
            _events.tryEmit(CaptureEvent.Failed("录像未就绪"))
        }
    }

    /** The encoder thread died mid-recording: stop immediately instead of waiting for the user. */
    private fun onLiveRecorderFailed(rec: LiveDualRecorder, error: Throwable) {
        // Already stopped (or replaced) by the time the failure reached the main thread.
        if (liveRecorder !== rec) return
        liveRecorder = null
        finishLiveRecording(rec, interrupted = true)
    }

    private fun finishLiveRecording(rec: LiveDualRecorder, interrupted: Boolean) {
        _isRecording.value = false
        torchEnabled = false
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = finalizeLiveRecording(rec, interrupted)
            // Rebind the photo session before reporting, so the UI re-enables its
            // buttons only once ImageCapture is ready again.
            withContext(Dispatchers.Main) {
                val owner = boundOwner
                val config = boundConfig
                if (owner != null && config != null) {
                    runCatching { bind(owner, config) }
                    applyEquivalentZoom(lastZoom)
                }
                _events.emit(result)
            }
        }
    }

    private fun finalizeLiveRecording(rec: LiveDualRecorder, interrupted: Boolean): CaptureEvent {
        var file: File? = null
        return try {
            file = rec.stop()
            val uri = copyVideoToGallery(file, "DUAL_${timestamp()}.mp4")
            if (uri != null) {
                CaptureEvent.VideoSaved(composed = uri, back = null, front = null, audioDropped = rec.audioDropped)
            } else {
                CaptureEvent.Failed("录像未写入相册")
            }
        } catch (error: Exception) {
            val reason = error.message ?: "录像保存失败"
            CaptureEvent.Failed(if (interrupted) "录像中断：$reason" else reason)
        } finally {
            file?.delete()
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        val camera = backCamera ?: run {
            torchEnabled = false
            return false
        }
        if (!camera.cameraInfo.hasFlashUnit()) {
            torchEnabled = false
            return false
        }
        camera.cameraControl.enableTorch(enabled)
        torchEnabled = enabled
        return true
    }

    fun rearZoomRange(): ZoomRange {
        val zoom = backCamera?.cameraInfo?.zoomState?.value
        return ZoomRange(
            min = zoom?.minZoomRatio ?: 1f,
            max = zoom?.maxZoomRatio ?: 1f,
            current = zoom?.zoomRatio ?: 1f,
        )
    }

    fun setRearZoom(ratio: Float): Float = applyEquivalentZoom(ratio)

    fun applyEquivalentZoom(equivalent: Float): Float {
        lastZoom = equivalent
        val camera = backCamera ?: return equivalent
        val info = camera.cameraInfo
        val zoom = info.zoomState.value ?: return equivalent
        val intrinsic = info.intrinsicZoomRatio.coerceAtLeast(0.01f)
        rearIntrinsic = intrinsic
        val logical = zoom.minZoomRatio <= 0.6f || zoom.maxZoomRatio >= 4.5f
        val ratio = if (logical) {
            equivalent.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        } else {
            (equivalent / intrinsic).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        }
        camera.cameraControl.setZoomRatio(ratio)
        return if (logical) ratio else ratio * intrinsic
    }

    suspend fun switchRearLens(
        owner: LifecycleOwner,
        config: BindConfig,
        equivalent: Float,
    ) {
        lastZoom = equivalent
        val cameraProvider = provider ?: run {
            applyEquivalentZoom(equivalent)
            return
        }
        val target = resolveRearTarget(cameraProvider, config.rearLens)
        val sameSelector = target != null &&
            target.backSelector == lastBackSelector &&
            target.frontSelector == lastFrontSelector
        if (target == null || sameSelector) {
            if (target != null) {
                rearIntrinsic = target.intrinsic
                boundConfig = config
            }
            applyEquivalentZoom(equivalent)
            return
        }
        if (_isRecording.value) {
            if (liveRecorder == null) {
                // Separate-file recording runs on the bound VideoCapture use cases; rebinding
                // would finalize both recordings mid-way. Only digital zoom is possible here.
                applyEquivalentZoom(equivalent)
                return
            }
            lastBackSelector = target.backSelector
            lastFrontSelector = target.frontSelector
            rearIntrinsic = target.intrinsic
            boundConfig = config
            bindPreviewAndAnalysis(owner)
            applyEquivalentZoom(equivalent)
            if (torchEnabled) {
                torchEnabled = setTorch(true)
            }
        } else {
            bind(owner, config)
            applyEquivalentZoom(equivalent)
        }
    }

    fun release() {
        stoppingSeparate = true
        recordingBack?.stop()
        recordingFront?.stop()
        recordingBack = null
        recordingFront = null
        val rec = liveRecorder
        liveRecorder = null
        _isRecording.value = false
        torchEnabled = false
        if (rec != null) {
            // `scope` is cancelled below, so a coroutine would never run; use a plain thread to
            // make sure the encoder/muxer are released and the file still reaches the gallery.
            thread(name = "dual-release") {
                var file: File? = null
                try {
                    file = rec.stop()
                    copyVideoToGallery(file, "DUAL_${timestamp()}.mp4")
                } catch (_: Throwable) {
                } finally {
                    file?.delete()
                }
            }
        }
        clearSurfaces()
        clearStateObservers()
        provider?.unbindAll()
        concurrent = null
        boundConfig = null
        wantedConfig = null
        boundOwner = null
        backCamera = null
        frontCamera = null
        analysisExecutor.shutdown()
        scope.cancel()
    }

    /** Unbinds everything and clears all use-case references. */
    private fun resetBindings(cameraProvider: ProcessCameraProvider) {
        clearSurfaces()
        clearStateObservers()
        cameraProvider.unbindAll()
        watchSince = SystemClock.elapsedRealtime()
        concurrent = null
        imageCaptureBack = null
        imageCaptureFront = null
        videoCaptureBack = null
        videoCaptureFront = null
        backCamera = null
        frontCamera = null
    }

    private fun recordingCacheDir(): File {
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        // Nothing is recording at this point, so any leftover file is from a crash.
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    private suspend fun obtainProvider(): ProcessCameraProvider {
        provider?.let { return it }
        val instance = ProcessCameraProvider.getInstance(context).await()
        provider = instance
        return instance
    }

    private fun bindCameras(
        owner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        backSelector: CameraSelector,
        frontSelector: CameraSelector,
        rotation: Int,
        config: BindConfig,
    ) {
        val stillSelector = stillSelector(config.photoQuality)
        val previewBack = previewOf(rotation, backFrameAt, _backSurface)
        val previewFront = previewOf(rotation, frontFrameAt, _frontSurface)
        val backStill = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(stillSelector)
            .setTargetRotation(rotation)
            .setFlashMode(config.flashMode.toImageFlash())
            .build()
        val frontStill = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(stillSelector)
            .setTargetRotation(rotation)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCaptureBack = backStill
        imageCaptureFront = frontStill

        fun groups(withVideo: Boolean): List<ConcurrentCamera.SingleCameraConfig> {
            val backGroup = UseCaseGroup.Builder()
                .addUseCase(previewBack)
                .addUseCase(backStill)
                .apply {
                    if (withVideo) {
                        addUseCase(videoCaptureOf(rotation, config.videoQuality).also { videoCaptureBack = it })
                    }
                }
                .build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(previewFront)
                .addUseCase(frontStill)
                .apply {
                    if (withVideo) {
                        addUseCase(videoCaptureOf(rotation, config.videoQuality).also { videoCaptureFront = it })
                    }
                }
                .build()
            return listOf(
                ConcurrentCamera.SingleCameraConfig(backSelector, backGroup, owner),
                ConcurrentCamera.SingleCameraConfig(frontSelector, frontGroup, owner),
            )
        }

        val bound = if (config.saveSeparate) {
            try {
                cameraProvider.bindToLifecycle(groups(withVideo = true))
            } catch (_: RuntimeException) {
                // Preview + ImageCapture + VideoCapture exceeds this device's concurrent stream
                // budget; fall back to photo-only and let startRecording report the limitation.
                videoCaptureBack = null
                videoCaptureFront = null
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(groups(withVideo = false))
            }
        } else {
            cameraProvider.bindToLifecycle(groups(withVideo = false))
        }
        concurrent = bound
        assignCameras(bound)
    }

    private fun imageAnalysisOf(
        rotation: Int,
        onFrame: (ImageProxy) -> Unit,
    ): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // RGBA output lets CameraX do the YUV conversion natively instead of toBitmap().
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(analysisSelector())
            .setTargetRotation(rotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        onFrame(image)
                    } catch (_: Throwable) {
                    } finally {
                        image.close()
                    }
                }
            }
    }

    private fun finishRecording(backOk: Boolean, frontOk: Boolean, error: String?) {
        val backFile = recordBackFile
        val frontFile = recordFrontFile
        recordBackFile = null
        recordFrontFile = null
        torchEnabled = false
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val backUri = if (backOk) backFile?.let { copyVideoToGallery(it, it.name) } else null
                val frontUri = if (frontOk) frontFile?.let { copyVideoToGallery(it, it.name) } else null
                if (backUri == null && frontUri == null) {
                    _events.emit(CaptureEvent.Failed(error ?: "录像失败"))
                } else {
                    _events.emit(CaptureEvent.VideoSaved(composed = null, back = backUri, front = frontUri))
                }
            } catch (e: Exception) {
                _events.emit(CaptureEvent.Failed(e.message ?: "合成视频失败"))
            } finally {
                backFile?.delete()
                frontFile?.delete()
            }
        }
    }

    private fun saveJpeg(bitmap: Bitmap, name: String, quality: Int = 92): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val ok = resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            } == true
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun copyVideoToGallery(file: File, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val copied = resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            if (copied == null || copied <= 0L) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun assignCameras(bound: ConcurrentCamera) {
        backCamera = bound.cameras.firstOrNull {
            it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        frontCamera = bound.cameras.firstOrNull {
            it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        watchSince = SystemClock.elapsedRealtime()
        observeCameraStates(bound)
    }

    /**
     * A Preview whose repeating request reports every completed frame into [heartbeat], so the
     * watchdog can tell a streaming camera from one that is open but stuck (or not open at all).
     */
    private fun previewOf(
        rotation: Int,
        heartbeat: AtomicLong,
        target: MutableStateFlow<SurfaceRequest?>,
    ): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(previewSelector())
            .setTargetRotation(rotation)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    heartbeat.set(SystemClock.elapsedRealtime())
                }
            },
        )
        return builder.build().also { useCase ->
            useCase.setSurfaceProvider { request -> target.value = request }
        }
    }

    private fun observeCameraStates(bound: ConcurrentCamera) {
        clearStateObservers()
        for (camera in bound.cameras) {
            val live = camera.cameraInfo.cameraState
            val facing = if (camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
            // observeForever replays the current (possibly stale, pre-rebind) state synchronously;
            // only states published after this binding are of interest.
            var armed = false
            val observer = Observer<CameraState> { state -> if (armed) onCameraState(facing, state) }
            live.observeForever(observer)
            armed = true
            stateObservers += live to observer
        }
    }

    private fun clearStateObservers() {
        stateObservers.forEach { (live, observer) -> live.removeObserver(observer) }
        stateObservers.clear()
    }

    private fun onCameraState(facing: String, state: CameraState) {
        val error = state.error ?: return
        Log.w(TAG, "$facing camera ${state.type} with error ${error.code} (${error.type})", error.cause)
        lastCameraError = error.code
        lastErrorAt = SystemClock.elapsedRealtime()
        if (error.type == CameraState.ErrorType.CRITICAL) {
            // CameraX will not reopen after a critical error; don't wait out the full stall.
            criticalErrorAt = lastErrorAt
        }
    }

    private fun startWatchdog() {
        if (watchdogJob != null) return
        watchdogJob = scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                try {
                    checkHealth()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "camera recovery failed", error)
                }
            }
        }
    }

    private suspend fun checkHealth() {
        val owner = boundOwner ?: return
        val config = wantedConfig ?: return
        val now = SystemClock.elapsedRealtime()
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || capturesInFlight > 0) {
            // The cameras are closed on purpose (background) or busy with a still capture.
            // Restart the clock, and give a fresh set of quick retries when we come back.
            watchSince = now
            criticalErrorAt = 0L
            if (recoverAttempts > MAX_FAST_ATTEMPTS) recoverAttempts = 0
            return
        }
        // Both cameras must be streaming, so the staler of the two decides.
        val lastFrame = minOf(backFrameAt.get(), frontFrameAt.get())
        val since = maxOf(watchSince, lastErrorAt)
        if (lastFrame > since && now - lastFrame < HEALTHY_MS) {
            if (interruptionNotified) {
                Log.i(TAG, "camera streams recovered")
                _events.tryEmit(CaptureEvent.Notice("相机已恢复"))
            }
            everStreamed = true
            interruptionNotified = false
            recoverAttempts = 0
            criticalErrorAt = 0L
            lastCameraError = null
            return
        }
        val idle = now - maxOf(lastFrame, watchSince)
        val due = when {
            // The quick attempts are used up: whatever is wrong is not transient, so back off.
            recoverAttempts >= MAX_FAST_ATTEMPTS -> idle >= SLOW_RETRY_MS
            criticalErrorAt != 0L -> now - criticalErrorAt >= FAST_RECOVER_MS
            else -> idle >= STALL_MS + recoverAttempts * STALL_STEP_MS
        }
        if (due) recoverCameras(owner, config)
    }

    /** Tears both cameras down and binds them again; called when the streams have stalled. */
    private suspend fun recoverCameras(owner: LifecycleOwner, config: BindConfig) {
        val attempt = ++recoverAttempts
        criticalErrorAt = 0L
        Log.w(TAG, "camera streams stalled (attempt $attempt, lastError=$lastCameraError); rebinding")
        when (attempt) {
            1 -> notifyInterruption(
                if (everStreamed) "相机画面中断，正在重新连接…" else "相机没有响应，正在重试…",
            )
            MAX_FAST_ATTEMPTS + 1 -> notifyInterruption(describeCameraFailure())
        }
        val cameraProvider = obtainProvider()
        if (recordingBack != null || recordingFront != null) {
            // VideoCapture recordings cannot survive a rebind; finalize what was captured so far.
            stopRecording()
        }
        resetBindings(cameraProvider)
        // Give the camera service a moment to finish closing both devices before reopening them.
        delay(REBIND_SETTLE_MS)
        // Released, moved to another activity, or already rebound by a user action meanwhile.
        if (boundOwner !== owner || concurrent != null) return
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        if (liveRecorder != null) {
            bindPreviewAndAnalysis(owner)
            applyEquivalentZoom(lastZoom)
            if (torchEnabled) {
                torchEnabled = setTorch(true)
            }
        } else {
            bind(owner, config)
            applyEquivalentZoom(lastZoom)
        }
    }

    private fun notifyInterruption(message: String) {
        interruptionNotified = true
        _events.tryEmit(CaptureEvent.Notice(message))
    }

    private fun describeCameraFailure(): String = when (lastCameraError) {
        CameraState.ERROR_CAMERA_IN_USE,
        CameraState.ERROR_MAX_CAMERAS_IN_USE,
        -> "相机被其他应用占用，请关闭它们；这里会继续自动重试。"
        CameraState.ERROR_CAMERA_DISABLED -> "系统禁止了相机访问（隐私开关或设备策略），恢复后会自动重连。"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "相机硬件出错，若一直无法恢复请重启手机。"
        CameraState.ERROR_STREAM_CONFIG -> "当前画质组合无法同时开启两个摄像头，请在设置里调低画质。"
        else -> "相机暂时无法启动，会继续自动重试。"
    }

    private fun videoCaptureOf(
        rotation: Int,
        quality: VideoQuality = videoQuality,
    ): VideoCapture<Recorder> {
        val qualities = when (quality) {
            VideoQuality.HD -> listOf(Quality.HD, Quality.SD, Quality.FHD)
            VideoQuality.FHD -> listOf(Quality.FHD, Quality.HD, Quality.SD)
        }
        val preferred = when (quality) {
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    qualities,
                    FallbackStrategy.lowerQualityOrHigherThan(preferred),
                ),
            )
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startFile(
        capture: VideoCapture<Recorder>,
        file: File,
        audioEnabled: Boolean,
        executor: Executor,
        onEvent: (VideoRecordEvent) -> Unit,
    ): Recording {
        val output = FileOutputOptions.Builder(file).build()
        val pending = capture.output.prepareRecording(context, output)
        val prepared = if (audioEnabled) pending.withAudioEnabled() else pending
        return prepared.start(executor, onEvent)
    }

    private fun clearSurfaces() {
        _backSurface.value = null
        _frontSurface.value = null
    }

    private fun displayRotation(owner: LifecycleOwner): Int {
        // Activity.getDisplay() throws if the activity is no longer attached to a display.
        val fromActivity = runCatching { (owner as? Activity)?.display?.rotation }.getOrNull()
        if (fromActivity != null) return fromActivity

        val displayManager = context.getSystemService(DisplayManager::class.java)
        return displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun previewSelector(): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
    }

    /** Live-composition frames only need to match the output video size, not the preview. */
    private fun analysisSelector(): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(videoQuality.height, videoQuality.width),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
    }

    /**
     * Requests the capture size for [quality]; the concurrent-camera HAL picks the closest size
     * it can actually deliver (often capped around 1440p), so "High" is best-effort.
     */
    private fun stillSelector(quality: PhotoQuality): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(quality.captureWidth, quality.captureHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
    }

    private fun resolveRearTarget(
        cameraProvider: ProcessCameraProvider,
        desired: RearLens,
    ): RearTarget? {
        val targets = listRearTargets(cameraProvider)
        if (targets.isEmpty()) return null
        val matching = targets.filter { it.lens == desired }
        return matching.minByOrNull { abs(it.intrinsic - desired.zoom) }
            ?: targets.maxByOrNull { it.span }
            ?: targets.firstOrNull()
    }

    private fun listRearTargets(cameraProvider: ProcessCameraProvider): List<RearTarget> {
        val targets = mutableListOf<RearTarget>()
        for (group in cameraProvider.availableConcurrentCameraInfos) {
            val front = group.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                ?: continue
            val backs = group.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            for (back in backs) {
                runCatching {
                    targets += RearTarget(
                        lens = classifyCamera(back),
                        backSelector = back.cameraSelector,
                        frontSelector = front.cameraSelector,
                        intrinsic = physicalZoom(back),
                        span = zoomSpan(back),
                    )
                }
            }
        }
        return targets
    }

    private fun detectLenses(pairs: List<List<CameraInfo>>): List<RearLens> {
        val found = linkedSetOf<RearLens>()
        fun addFrom(info: CameraInfo) {
            runCatching { found += classifyCamera(info) }
            runCatching {
                info.physicalCameraInfos.forEach { physical ->
                    found += classifyCamera(physical)
                }
            }
        }
        for (group in pairs) {
            group.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.forEach(::addFrom)
        }
        provider?.availableCameraInfos
            ?.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            ?.forEach(::addFrom)
        return found.toList().ifEmpty { listOf(RearLens.Wide) }
    }

    private fun classifyCamera(info: CameraInfo): RearLens {
        val intrinsic = info.intrinsicZoomRatio
        if (intrinsic < 0.75f || intrinsic > 2.5f) return classifyRearLens(intrinsic)
        val focal = runCatching {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull()
        }.getOrNull()
        return when {
            focal != null && focal < 4f -> RearLens.UltraWide
            focal != null && focal > 12f -> RearLens.Tele
            else -> classifyRearLens(intrinsic)
        }
    }

    private fun physicalZoom(info: CameraInfo): Float {
        val intrinsic = info.intrinsicZoomRatio
        if (intrinsic < 0.75f || intrinsic > 2.5f) return intrinsic
        return when (classifyCamera(info)) {
            RearLens.UltraWide -> 0.5f
            RearLens.Wide -> 1f
            RearLens.Tele -> 5f
        }
    }

    private fun zoomSpan(info: CameraInfo): Float {
        val zoom = info.zoomState.value ?: return 1f
        return zoom.maxZoomRatio / zoom.minZoomRatio.coerceAtLeast(0.01f)
    }

    data class PrepareResult(
        val supported: Boolean,
        val message: String?,
        val lenses: List<RearLens>,
    )

    companion object {
        const val ALBUM_RELATIVE_PATH = "DCIM/DualCam"
        private const val TAG = "DualCam"
        private const val WATCHDOG_TICK_MS = 1_000L
        /** Both previews delivered a frame this recently: everything is fine. */
        private const val HEALTHY_MS = 1_500L
        /** No frames for this long (plus a step per failed attempt) triggers a rebind. */
        private const val STALL_MS = 4_000L
        private const val STALL_STEP_MS = 2_000L
        /** Shorter wait once a camera has reported an error CameraX will not recover from. */
        private const val FAST_RECOVER_MS = 1_200L
        private const val MAX_FAST_ATTEMPTS = 3
        /** After the quick attempts, keep retrying quietly at this interval. */
        private const val SLOW_RETRY_MS = 10_000L
        private const val REBIND_SETTLE_MS = 400L
    }

    private data class RearTarget(
        val lens: RearLens,
        val backSelector: CameraSelector,
        val frontSelector: CameraSelector,
        val intrinsic: Float,
        val span: Float,
    )
}

private fun FlashMode.toImageFlash(): Int = when (this) {
    FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
    FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.On -> ImageCapture.FLASH_MODE_ON
}

private fun timestamp(): String {
    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

private suspend fun ImageCapture.awaitBitmap(executor: Executor): Bitmap =
    suspendCancellableCoroutine { continuation ->
        takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap().oriented(image.imageInfo.rotationDegrees)
                        if (continuation.isActive) continuation.resume(bitmap)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            },
        )
    }
