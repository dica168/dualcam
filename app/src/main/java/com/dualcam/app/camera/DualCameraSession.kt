@file:OptIn(ExperimentalCamera2Interop::class)

package com.dualcam.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dualcam.app.camera.DualLayoutComposer.oriented
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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

    private val _composedSurface = MutableStateFlow<SurfaceRequest?>(null)
    val composedSurface: StateFlow<SurfaceRequest?> = _composedSurface.asStateFlow()

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
    private var videoCaptureComposed: VideoCapture<Recorder>? = null
    private var recordingBack: Recording? = null
    private var recordingFront: Recording? = null
    private var recordingComposed: Recording? = null
    private var liveRecorder: LiveDualRecorder? = null
    private var analysisBack: ImageAnalysis? = null
    private var analysisFront: ImageAnalysis? = null
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
    private var recordLayout: DualLayout = DualLayout.Pip
    private var recordBackPrimary: Boolean = true
    private var recordSaveSeparate: Boolean = false
    private var recordBackFile: File? = null
    private var recordFrontFile: File? = null

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
        if (_isRecording.value) return
        if (!recordingSession && boundConfig == config && concurrent != null) return

        clearSurfaces()
        cameraProvider.unbindAll()
        concurrent = null
        imageCaptureBack = null
        imageCaptureFront = null
        videoCaptureBack = null
        videoCaptureFront = null
        videoCaptureComposed = null
        backCamera = null
        frontCamera = null

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
        val executor = ContextCompat.getMainExecutor(context)
        val stamp = timestamp()
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
            if (saveSeparate) {
                val backUri = backBmp?.let { saveJpeg(it, "DUAL_BACK_$stamp.jpg", photoQuality.jpeg) }
                val frontUri = frontBmp?.let { saveJpeg(it, "DUAL_FRONT_$stamp.jpg", photoQuality.jpeg) }
                _events.emit(CaptureEvent.PhotosSaved(composed = null, back = backUri, front = frontUri))
            } else if (backBmp != null && frontBmp != null) {
                val composed = withContext(Dispatchers.Default) {
                    DualLayoutComposer.compose(
                        backBmp,
                        frontBmp,
                        layout,
                        backPrimary,
                        photoQuality.width,
                        photoQuality.height,
                    )
                }
                val uri = saveJpeg(composed, "DUAL_$stamp.jpg", photoQuality.jpeg)
                _events.emit(CaptureEvent.PhotosSaved(composed = uri, back = null, front = null))
            } else {
                val uri = saveJpeg((backBmp ?: frontBmp)!!, "DUAL_$stamp.jpg", photoQuality.jpeg)
                _events.emit(CaptureEvent.PhotosSaved(composed = uri, back = null, front = null))
            }
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
        if (_isRecording.value) return
        recordLayout = layout
        recordBackPrimary = backPrimary
        recordSaveSeparate = saveSeparate
        val recordAudio = audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)
        val executor = ContextCompat.getMainExecutor(context)

        if (saveSeparate) {
            val backCapture = videoCaptureBack
            val frontCapture = videoCaptureFront
            if (backCapture == null || frontCapture == null) {
                _events.tryEmit(CaptureEvent.Failed("分别保存需要两路录像，当前会话不支持。"))
                return
            }
            val stamp = timestamp()
            val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val backFile = File(dir, "DUAL_BACK_$stamp.mp4")
            val frontFile = File(dir, "DUAL_FRONT_$stamp.mp4")
            recordBackFile = backFile
            recordFrontFile = frontFile
            var pending = 2
            var backOk = false
            var frontOk = false
            var error: String? = null
            fun onEvent(event: VideoRecordEvent, isBack: Boolean) {
                when (event) {
                    is VideoRecordEvent.Start -> _isRecording.value = true
                    is VideoRecordEvent.Finalize -> {
                        if (isBack) recordingBack = null else recordingFront = null
                        if (event.hasError()) {
                            error = event.cause?.message ?: "录像失败（${event.error}）"
                        } else if (isBack) {
                            backOk = true
                        } else {
                            frontOk = true
                        }
                        pending--
                        if (pending == 0) {
                            _isRecording.value = false
                            finishRecording(backOk, frontOk, error)
                        }
                    }
                }
            }
            recordingBack = startFile(backCapture, backFile, recordAudio, executor) {
                onEvent(it, isBack = true)
            }
            recordingFront = startFile(frontCapture, frontFile, audioEnabled = false, executor) {
                onEvent(it, isBack = false)
            }
            return
        }

        lastZoom = zoom
        try {
            bindPreviewAndAnalysis(owner)
            applyEquivalentZoom(zoom)
            val rec = LiveDualRecorder(
                quality = videoQuality,
                audioEnabled = recordAudio,
                cacheDir = File(context.cacheDir, "recordings").apply { mkdirs() },
            )
            rec.layout = layout
            rec.backPrimary = backPrimary
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
    }

    private fun bindPreviewAndAnalysis(owner: LifecycleOwner) {
        val cameraProvider = provider ?: throw IllegalStateException("相机未初始化")
        val backSelector = lastBackSelector ?: throw IllegalStateException("后置镜头不可用")
        val frontSelector = lastFrontSelector ?: throw IllegalStateException("前置镜头不可用")
        val rotation = displayRotation(owner)
        clearSurfaces()
        cameraProvider.unbindAll()
        concurrent = null
        imageCaptureBack = null
        imageCaptureFront = null
        videoCaptureBack = null
        videoCaptureFront = null
        videoCaptureComposed = null
        backCamera = null
        frontCamera = null

        val previewSelector = previewSelector()
        val previewBack = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setSurfaceProvider { request -> _backSurface.value = request }
            }
        val previewFront = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setSurfaceProvider { request -> _frontSurface.value = request }
            }
        val backAnalysis = imageAnalysisOf(rotation) { image ->
            liveRecorder?.submitBack(image.toBitmap().oriented(image.imageInfo.rotationDegrees))
        }
        val frontAnalysis = imageAnalysisOf(rotation) { image ->
            liveRecorder?.submitFront(image.toBitmap().oriented(image.imageInfo.rotationDegrees))
        }
        analysisBack = backAnalysis
        analysisFront = frontAnalysis
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
        recordingBack?.stop()
        recordingFront?.stop()
        recordingBack = null
        recordingFront = null
        recordingComposed = null
        val rec = liveRecorder
        liveRecorder = null
        if (rec != null) {
            _isRecording.value = false
            scope.launch(Dispatchers.Default) {
                try {
                    val file = rec.stop()
                    val uri = copyVideoToGallery(file, "DUAL_${timestamp()}.mp4")
                    file.delete()
                    if (uri != null) {
                        _events.emit(CaptureEvent.VideoSaved(composed = uri, back = null, front = null))
                    } else {
                        _events.emit(CaptureEvent.Failed("录像未写入相册"))
                    }
                } catch (error: Exception) {
                    _events.emit(CaptureEvent.Failed(error.message ?: "录像保存失败"))
                } finally {
                    val owner = boundOwner
                    val config = boundConfig
                    if (owner != null && config != null) {
                        withContext(Dispatchers.Main) {
                            runCatching { bind(owner, config) }
                            applyEquivalentZoom(lastZoom)
                        }
                    }
                }
            }
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        val camera = backCamera ?: return false
        if (!camera.cameraInfo.hasFlashUnit()) return false
        camera.cameraControl.enableTorch(enabled)
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
            lastBackSelector = target.backSelector
            lastFrontSelector = target.frontSelector
            rearIntrinsic = target.intrinsic
            boundConfig = config
            bindPreviewAndAnalysis(owner)
            applyEquivalentZoom(equivalent)
        } else {
            bind(owner, config)
            applyEquivalentZoom(equivalent)
        }
    }

    fun release() {
        stopRecording()
        clearSurfaces()
        provider?.unbindAll()
        concurrent = null
        boundConfig = null
        backCamera = null
        frontCamera = null
        analysisExecutor.shutdown()
        scope.cancel()
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
        val previewSelector = previewSelector()
        val stillSelector = stillSelector()
        val previewBack = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setSurfaceProvider { request -> _backSurface.value = request }
            }
        val previewFront = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setSurfaceProvider { request -> _frontSurface.value = request }
            }
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
        val backAnalysis = imageAnalysisOf(rotation) { image ->
            liveRecorder?.submitBack(image.toBitmap().oriented(image.imageInfo.rotationDegrees))
        }
        val frontAnalysis = imageAnalysisOf(rotation) { image ->
            liveRecorder?.submitFront(image.toBitmap().oriented(image.imageInfo.rotationDegrees))
        }
        analysisBack = backAnalysis
        analysisFront = frontAnalysis
        val backVideo = videoCaptureOf(rotation)
        val frontVideo = videoCaptureOf(rotation)
        videoCaptureBack = backVideo
        videoCaptureFront = frontVideo

        fun groups(extra: UseCaseExtra): List<ConcurrentCamera.SingleCameraConfig> {
            val backGroup = UseCaseGroup.Builder()
                .addUseCase(previewBack)
                .addUseCase(backStill)
                .apply {
                    when (extra) {
                        UseCaseExtra.Analysis -> addUseCase(backAnalysis)
                        UseCaseExtra.Video -> addUseCase(backVideo)
                        UseCaseExtra.None -> Unit
                    }
                }
                .build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(previewFront)
                .addUseCase(frontStill)
                .apply {
                    when (extra) {
                        UseCaseExtra.Analysis -> addUseCase(frontAnalysis)
                        UseCaseExtra.Video -> addUseCase(frontVideo)
                        UseCaseExtra.None -> Unit
                    }
                }
                .build()
            return listOf(
                ConcurrentCamera.SingleCameraConfig(backSelector, backGroup, owner),
                ConcurrentCamera.SingleCameraConfig(frontSelector, frontGroup, owner),
            )
        }

        val extra = if (config.saveSeparate) UseCaseExtra.Video else UseCaseExtra.None
        val bound = try {
            cameraProvider.bindToLifecycle(groups(extra))
        } catch (_: RuntimeException) {
            if (extra == UseCaseExtra.Analysis) {
                analysisBack = null
                analysisFront = null
            } else {
                videoCaptureBack = null
                videoCaptureFront = null
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(groups(UseCaseExtra.None))
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
            .setResolutionSelector(previewSelector())
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

    private enum class UseCaseExtra { None, Analysis, Video }

    private fun finishRecording(backOk: Boolean, frontOk: Boolean, error: String?) {
        val backFile = recordBackFile
        val frontFile = recordFrontFile
        scope.launch {
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
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        return uri
    }

    private fun copyVideoToGallery(file: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        return uri
    }

    private fun assignCameras(bound: ConcurrentCamera) {
        backCamera = bound.cameras.firstOrNull {
            it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        frontCamera = bound.cameras.firstOrNull {
            it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
    }

    private fun videoCaptureOf(rotation: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                ),
            )
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startGallery(
        capture: VideoCapture<Recorder>,
        fileName: String,
        audioEnabled: Boolean,
        executor: Executor,
        onEvent: (VideoRecordEvent) -> Unit,
    ): Recording {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
        val pending = capture.output.prepareRecording(context, output)
        val prepared = if (audioEnabled) pending.withAudioEnabled() else pending
        return prepared.start(executor, onEvent)
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
        _composedSurface.value = null
    }

    private fun displayRotation(owner: LifecycleOwner): Int {
        val fromActivity = (owner as? Activity)?.display?.rotation
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

    private fun stillSelector(): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1440),
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
