package com.dualcam.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualcam.app.camera.DualCameraSession
import com.dualcam.app.camera.DualLayout
import com.dualcam.app.camera.DualLayoutComposer
import com.dualcam.app.camera.FlashMode
import com.dualcam.app.camera.PhotoQuality
import com.dualcam.app.camera.DefaultRearZoomStops
import com.dualcam.app.camera.VideoQuality
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ControlScrim = Color(0x99000000)
private val AccentBlue = Color(0xFF8AB4F8)
private val AccentGreen = Color(0xFF81C995)
private val RecordRed = Color(0xFFE53935)

@Composable
fun DualCamScreen(viewModel: DualCamViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backSurface by viewModel.backSurface.collectAsStateWithLifecycle()
    val frontSurface by viewModel.frontSurface.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted || !audioGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    LaunchedEffect(cameraGranted, lifecycleOwner) {
        if (cameraGranted) viewModel.attach(lifecycleOwner)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !cameraGranted -> PermissionPane(
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
            state.ready && !state.supported -> UnsupportedPane(state.supportMessage)
            else -> {
                DualPreview(
                    layout = state.layout,
                    backPrimary = state.backPrimary,
                    backSurface = backSurface,
                    frontSurface = frontSurface,
                    rearZoom = state.rearZoom,
                    rearEquivalent = state.rearZoom,
                    pinchZoomMin = state.pinchZoomMin,
                    pinchZoomMax = state.pinchZoomMax,
                    onRearZoom = viewModel::setRearZoom,
                    onSwap = viewModel::swapPrimary,
                    recording = state.recording,
                )
                if (!state.settingsOpen) {
                    CameraChrome(
                        state = state,
                        onLayout = viewModel::setLayout,
                        onRearStop = viewModel::selectRearStop,
                        onFlash = viewModel::cycleFlash,
                        onTorch = viewModel::toggleTorch,
                        onOpenSettings = viewModel::toggleSettings,
                        onSwap = viewModel::swapPrimary,
                        onPhoto = viewModel::takePhoto,
                        onVideo = { viewModel.toggleRecording(audioGranted) },
                        onOpenLast = { openGallery(context, state.lastMediaUri) },
                    )
                }
                if (state.settingsOpen) {
                    SettingsSheet(
                        state = state,
                        onClose = viewModel::closeSettings,
                        onFlash = viewModel::setFlashMode,
                        onPhotoQuality = viewModel::setPhotoQuality,
                        onVideoQuality = viewModel::setVideoQuality,
                        onToggleSaveSeparate = viewModel::toggleSaveSeparate,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
        )
    }
}

@Composable
private fun DualPreview(
    layout: DualLayout,
    backPrimary: Boolean,
    backSurface: SurfaceRequest?,
    frontSurface: SurfaceRequest?,
    rearZoom: Float,
    rearEquivalent: Float,
    pinchZoomMin: Float,
    pinchZoomMax: Float,
    onRearZoom: (Float) -> Unit,
    onSwap: () -> Unit,
    recording: Boolean,
) {
    val currentBack by rememberUpdatedState(backSurface)
    val currentFront by rememberUpdatedState(frontSurface)
    val backFinder = remember {
        movableContentOf<Modifier> { modifier ->
            FinderOrPlaceholder(currentBack, modifier)
        }
    }
    val frontFinder = remember {
        movableContentOf<Modifier> { modifier ->
            FinderOrPlaceholder(currentFront, modifier)
        }
    }
    val primary = if (backPrimary) backFinder else frontFinder
    val secondary = if (backPrimary) frontFinder else backFinder
    val primaryLabel = if (backPrimary) rearZoomLabel(rearEquivalent) else "前置"
    val secondaryLabel = if (backPrimary) "前置" else rearZoomLabel(rearEquivalent)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (layout) {
            DualLayout.Pip -> {
                val pipW = maxWidth * DualLayoutComposer.PIP_WIDTH_FRACTION
                val pipH = pipW * DualLayoutComposer.PIP_ASPECT
                val insetX = maxWidth * DualLayoutComposer.PIP_INSET_X_FRACTION
                val insetY = maxHeight * DualLayoutComposer.PIP_INSET_Y_FRACTION
                CameraPane(
                    finder = primary,
                    label = primaryLabel,
                    onSwap = onSwap,
                    modifier = Modifier.fillMaxSize(),
                    showLabel = false,
                    zoomEnabled = backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                    zoom = rearZoom,
                    zoomMin = pinchZoomMin,
                    zoomMax = pinchZoomMax,
                    onZoom = onRearZoom,
                )
                CameraPane(
                    finder = secondary,
                    label = secondaryLabel,
                    onSwap = onSwap,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = insetY, end = insetX)
                        .size(pipW, pipH)
                        .clip(RoundedCornerShape(18.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(18.dp)),
                    showLabel = false,
                    zoomEnabled = !backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                    zoom = rearZoom,
                    zoomMin = pinchZoomMin,
                    zoomMax = pinchZoomMax,
                    onZoom = onRearZoom,
                )
            }
            DualLayout.SplitVertical -> {
                Column(Modifier.fillMaxSize()) {
                    CameraPane(
                        finder = primary,
                        label = primaryLabel,
                        onSwap = onSwap,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        zoomEnabled = backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                        zoom = rearZoom,
                        zoomMin = pinchZoomMin,
                        zoomMax = pinchZoomMax,
                        onZoom = onRearZoom,
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Black))
                    CameraPane(
                        finder = secondary,
                        label = secondaryLabel,
                        onSwap = onSwap,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        zoomEnabled = !backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                        zoom = rearZoom,
                        zoomMin = pinchZoomMin,
                        zoomMax = pinchZoomMax,
                        onZoom = onRearZoom,
                    )
                }
            }
            DualLayout.SplitHorizontal -> {
                Row(Modifier.fillMaxSize()) {
                    CameraPane(
                        finder = primary,
                        label = primaryLabel,
                        onSwap = onSwap,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        zoomEnabled = backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                        zoom = rearZoom,
                        zoomMin = pinchZoomMin,
                        zoomMax = pinchZoomMax,
                        onZoom = onRearZoom,
                    )
                    Box(Modifier.fillMaxHeight().width(2.dp).background(Color.Black))
                    CameraPane(
                        finder = secondary,
                        label = secondaryLabel,
                        onSwap = onSwap,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        zoomEnabled = !backPrimary && pinchZoomMax > pinchZoomMin + 0.05f,
                        zoom = rearZoom,
                        zoomMin = pinchZoomMin,
                        zoomMax = pinchZoomMax,
                        onZoom = onRearZoom,
                    )
                }
            }
        }
        RecordingPulse(visible = recording)
    }
}

@Composable
private fun CameraPane(
    finder: @Composable (Modifier) -> Unit,
    label: String,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    zoomEnabled: Boolean = false,
    zoom: Float = 1f,
    zoomMin: Float = 1f,
    zoomMax: Float = 1f,
    onZoom: (Float) -> Unit = {},
) {
    val latestZoom by rememberUpdatedState(zoom)
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomEnabled) {
            onZoom((latestZoom * zoomChange).coerceIn(zoomMin, zoomMax))
        }
    }
    Box(modifier) {
        finder(Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (zoomEnabled) Modifier.transformable(transformState) else Modifier)
                .clickable(onClick = onSwap),
        )
        if (showLabel) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ControlScrim)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FinderOrPlaceholder(request: SurfaceRequest?, modifier: Modifier) {
    if (request != null) {
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = modifier,
        )
    } else {
        PreviewPlaceholder("取景中", modifier)
    }
}

@Composable
private fun PreviewPlaceholder(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = modifier.background(Color(0xFF101418)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFFB0B8C1), fontSize = 13.sp)
    }
}

@Composable
private fun CameraChrome(
    state: DualCamUiState,
    onLayout: (DualLayout) -> Unit,
    onRearStop: (Float) -> Unit,
    onFlash: () -> Unit,
    onTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwap: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onOpenLast: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .zIndex(2f),
    ) {
        TopBar(
            state = state,
            onFlash = onFlash,
            onTorch = onTorch,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .blockPreviewGestures(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .blockPreviewGestures(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RearZoomRow(
                equivalent = state.rearZoom,
                stops = DefaultRearZoomStops.map { it.equivalent },
                onSelect = onRearStop,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(10.dp))
            LayoutRow(
                selected = state.layout,
                enabled = true,
                onSelect = onLayout,
            )
            Spacer(Modifier.height(16.dp))
            BottomControls(
                state = state,
                onSwap = onSwap,
                onPhoto = onPhoto,
                onVideo = onVideo,
                onOpenLast = onOpenLast,
                onOpenSettings = onOpenSettings,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBar(
    state: DualCamUiState,
    onFlash: () -> Unit,
    onTorch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(
            start = 12.dp,
            end = if (state.recording) 28.dp else 12.dp,
            top = if (state.recording) 16.dp else 8.dp,
            bottom = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "双摄",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Pixel 前后同时拍摄",
                color = Color(0xFFB0B8C1),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.recording) {
            GlassIconButton(
                icon = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "手电",
                size = 52.dp,
                tint = if (state.torchOn) Color(0xFFFFD54F) else Color.White,
                onClick = onTorch,
            )
            Spacer(Modifier.width(12.dp))
            RecordingBadge(state.recordingMs)
        }
    }
}

@Composable
private fun RecordingBadge(elapsedMs: Long) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RecordRed)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Text(
            text = formatTime(elapsedMs),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RearZoomRow(
    equivalent: Float,
    stops: List<Float>,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nearest = stops.minBy { abs(equivalent - it) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(ControlScrim)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stops.forEach { stop ->
            val active = abs(equivalent - stop) < 0.12f && stop == nearest
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelect(stop) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatZoom(stop),
                    color = if (active) Color.Black else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LayoutRow(
    selected: DualLayout,
    enabled: Boolean,
    onSelect: (DualLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(ControlScrim)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DualLayout.entries.forEach { layout ->
            val active = layout == selected
            Text(
                text = layout.label(),
                color = when {
                    !enabled -> Color(0x66FFFFFF)
                    active -> Color.Black
                    else -> Color.White
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active && enabled) Color.White else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(layout) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun BottomControls(
    state: DualCamUiState,
    onSwap: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onOpenLast: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GalleryButton(uri = state.lastMediaUri, onClick = onOpenLast)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "设置",
                size = 52.dp,
                onClick = onOpenSettings,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShutterButton(
                innerColor = Color.White,
                recording = false,
                capturing = state.capturing,
                enabled = !state.recording && !state.composing,
                buttonSize = 76.dp,
                onClick = onPhoto,
            )
            Spacer(Modifier.width(18.dp))
            ShutterButton(
                innerColor = RecordRed,
                recording = state.recording,
                capturing = false,
                enabled = !state.capturing && !state.composing,
                buttonSize = 68.dp,
                onClick = onVideo,
            )
        }
        GlassIconButton(
            icon = Icons.Filled.Cameraswitch,
            contentDescription = "互换主次画面",
            size = 52.dp,
            enabled = true,
            onClick = onSwap,
        )
    }
}

@Composable
private fun SaveModeChip(
    saveSeparate: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (saveSeparate) "分别保存" else "合拍",
        color = if (!enabled) Color(0x66FFFFFF) else if (saveSeparate) Color.White else Color.Black,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (saveSeparate) ControlScrim else Color.White)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun GalleryButton(uri: Uri?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ControlScrim)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = "相册",
            tint = Color.White,
        )
    }
}

@Composable
private fun ShutterButton(
    innerColor: Color,
    recording: Boolean,
    capturing: Boolean,
    enabled: Boolean,
    buttonSize: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed || capturing) 0.92f else 1f, label = "shutter")
    Box(
        modifier = Modifier
            .size(buttonSize)
            .scale(scale)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawCircle(
                color = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
                radius = this.size.minDimension / 2f - stroke / 2f,
                style = Stroke(width = stroke),
            )
            val inner = if (recording) this.size.minDimension * 0.22f else this.size.minDimension * 0.32f
            if (recording) {
                drawRoundRect(
                    color = RecordRed,
                    topLeft = Offset(size.width / 2f - inner, size.height / 2f - inner),
                    size = Size(inner * 2f, inner * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
            } else {
                drawCircle(
                    color = innerColor.copy(alpha = if (enabled) 1f else 0.35f),
                    radius = inner,
                )
            }
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(ControlScrim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun RecordingPulse(visible: Boolean) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        flash = visible
        if (visible) {
            delay(90)
            flash = false
        }
    }
    AnimatedVisibility(visible = flash, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f)))
    }
}

@Composable
private fun SettingsSheet(
    state: DualCamUiState,
    onClose: () -> Unit,
    onFlash: (FlashMode) -> Unit,
    onPhotoQuality: (PhotoQuality) -> Unit,
    onVideoQuality: (VideoQuality) -> Unit,
    onToggleSaveSeparate: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(8f)
            .background(Color(0x99000000))
            .clickable(onClick = onClose),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF12161C))
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                GlassIconButton(icon = Icons.Filled.Close, contentDescription = "关闭", onClick = onClose)
            }
            Spacer(Modifier.height(16.dp))
            SettingsLabel("闪光灯")
            SettingsChips(
                options = FlashMode.entries.map { it to it.settingsLabel() },
                selected = state.flashMode,
                enabled = !state.recording,
                onSelect = onFlash,
            )
            Spacer(Modifier.height(14.dp))
            SettingsLabel("照片画质")
            SettingsChips(
                options = PhotoQuality.entries.map { it to it.label },
                selected = state.photoQuality,
                enabled = !state.recording,
                onSelect = onPhotoQuality,
            )
            Spacer(Modifier.height(14.dp))
            SettingsLabel("视频画质")
            SettingsChips(
                options = VideoQuality.entries.map { it to it.label },
                selected = state.videoQuality,
                enabled = !state.recording,
                onSelect = onVideoQuality,
            )
            Spacer(Modifier.height(14.dp))
            SettingsLabel("保存方式")
            SettingsChips(
                options = listOf(false to "合拍", true to "分别保存"),
                selected = state.saveSeparate,
                enabled = !state.recording,
                onSelect = { if (it != state.saveSeparate) onToggleSaveSeparate() },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, color = Color(0xFFB0B8C1), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun <T> SettingsChips(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C222B))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                color = when {
                    !enabled -> Color(0x66FFFFFF)
                    active -> Color.Black
                    else -> Color.White
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active && enabled) Color.White else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun FlashMode.settingsLabel(): String = when (this) {
    FlashMode.Off -> "关闭"
    FlashMode.Auto -> "自动"
    FlashMode.On -> "强制"
}

@Composable
private fun PermissionPane(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("需要相机权限", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "同时使用前后摄像头拍摄，需要相机和麦克风权限。",
            color = Color(0xFFB0B8C1),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.Black),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text("授权并开始")
        }
    }
}

@Composable
private fun UnsupportedPane(message: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法双摄", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            message ?: "当前设备不支持前后摄像头同时工作。",
            color = Color(0xFFB0B8C1),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "请在 Pixel 10 Pro XL 上运行，并确认系统相机权限未被其他应用独占。",
            color = AccentGreen,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DualLayout.label(): String = when (this) {
    DualLayout.Pip -> "画中画"
    DualLayout.SplitVertical -> "上下"
    DualLayout.SplitHorizontal -> "左右"
}

private fun FlashMode.icon(): ImageVector = when (this) {
    FlashMode.Off -> Icons.Filled.FlashOff
    FlashMode.Auto -> Icons.Filled.FlashAuto
    FlashMode.On -> Icons.Filled.FlashOn
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatZoom(zoom: Float): String {
    val rounded = (zoom * 10f).roundToInt() / 10f
    return if (abs(rounded - rounded.toInt()) < 0.05f) {
        "${rounded.toInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", rounded)
    }
}

private fun rearZoomLabel(equivalent: Float): String {
    return if (abs(equivalent - 1f) < 0.05f) "后置" else "后置 ${formatZoom(equivalent)}"
}

private fun Modifier.blockPreviewGestures(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}

private fun openGallery(context: Context, lastUri: Uri?) {
    val target = lastUri ?: queryLatestDualCamUri(context)
    if (target == null) {
        Toast.makeText(context, "还没有保存的照片或视频", Toast.LENGTH_SHORT).show()
        return
    }
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    val review = Intent(MediaStore.ACTION_REVIEW).apply {
        data = target
        addFlags(flags)
    }
    if (review.resolveActivity(context.packageManager) != null &&
        runCatching { context.startActivity(review) }.isSuccess
    ) {
        return
    }
    val mime = context.contentResolver.getType(target) ?: "*/*"
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(target, mime)
                addFlags(flags)
            },
        )
    }
}

private fun queryLatestDualCamUri(context: Context): Uri? {
    val path = "${DualCameraSession.ALBUM_RELATIVE_PATH}%"
    fun latest(collection: Uri, idColumn: String, dateColumn: String, pathColumn: String): Pair<Uri, Long>? {
        return context.contentResolver.query(
            collection,
            arrayOf(idColumn, dateColumn),
            "$pathColumn LIKE ?",
            arrayOf(path),
            "$dateColumn DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(collection, cursor.getLong(0)) to cursor.getLong(1)
        }
    }
    val image = latest(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.RELATIVE_PATH,
    )
    val video = latest(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.RELATIVE_PATH,
    )
    return listOfNotNull(image, video).maxByOrNull { it.second }?.first
}
