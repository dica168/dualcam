package com.dualcam.app.camera

import android.net.Uri

enum class CaptureMode { Photo, Video }

enum class DualLayout {
    Pip,
    SplitVertical,
    SplitHorizontal,
}

enum class RearLens(val label: String, val zoom: Float) {
    UltraWide("0.5", 0.5f),
    Wide("1", 1.0f),
    Tele("5", 5.0f),
}

data class RearZoomStop(
    val equivalent: Float,
    val lens: RearLens,
    val ratio: Float,
)

val DefaultRearZoomStops = listOf(
    RearZoomStop(0.5f, RearLens.UltraWide, 1.0f),
    RearZoomStop(0.8f, RearLens.UltraWide, 1.6f),
    RearZoomStop(1.0f, RearLens.Wide, 1.0f),
    RearZoomStop(1.5f, RearLens.Wide, 1.5f),
    RearZoomStop(2.0f, RearLens.Wide, 2.0f),
    RearZoomStop(5.0f, RearLens.Tele, 1.0f),
    RearZoomStop(10.0f, RearLens.Tele, 2.0f),
)

fun nearestRearZoomStop(equivalent: Float): RearZoomStop {
    return DefaultRearZoomStops.minBy { kotlin.math.abs(it.equivalent - equivalent) }
}

fun rearStopTarget(equivalent: Float, lenses: List<RearLens>): Pair<RearLens, Float> {
    val preferred = nearestRearZoomStop(equivalent)
    if (preferred.lens in lenses) return preferred.lens to preferred.ratio
    val fallback = DefaultRearZoomStops
        .filter { it.lens in lenses }
        .minByOrNull { kotlin.math.abs(it.equivalent - equivalent) }
    return if (fallback != null) fallback.lens to fallback.ratio else RearLens.Wide to 1f
}

fun classifyRearLens(intrinsicZoom: Float): RearLens = when {
    intrinsicZoom < 0.75f -> RearLens.UltraWide
    intrinsicZoom > 2.5f -> RearLens.Tele
    else -> RearLens.Wide
}

fun lensForEquivalent(
    equivalent: Float,
    current: RearLens,
    lenses: List<RearLens>,
): RearLens {
    val hasUw = RearLens.UltraWide in lenses
    val hasWide = RearLens.Wide in lenses
    val hasTele = RearLens.Tele in lenses
    return when (current) {
        RearLens.UltraWide -> when {
            equivalent >= 3.6f && hasTele -> RearLens.Tele
            equivalent >= 0.95f && hasWide -> RearLens.Wide
            hasUw -> RearLens.UltraWide
            hasWide -> RearLens.Wide
            else -> current
        }
        RearLens.Wide -> when {
            equivalent < 0.85f && hasUw -> RearLens.UltraWide
            equivalent >= 3.6f && hasTele -> RearLens.Tele
            hasWide -> RearLens.Wide
            hasTele && equivalent >= 3.6f -> RearLens.Tele
            hasUw -> RearLens.UltraWide
            else -> current
        }
        RearLens.Tele -> when {
            equivalent < 0.85f && hasUw -> RearLens.UltraWide
            equivalent < 3.2f && hasWide -> RearLens.Wide
            hasTele -> RearLens.Tele
            hasWide -> RearLens.Wide
            else -> current
        }
    }
}

fun equivalentZoomBounds(lenses: List<RearLens>): Pair<Float, Float> {
    val min = when {
        RearLens.UltraWide in lenses -> 0.5f
        else -> 1f
    }
    val max = when {
        RearLens.Tele in lenses -> 10f
        RearLens.Wide in lenses -> 2f
        else -> 1f
    }
    return min to max
}

data class ZoomRange(
    val min: Float = 1f,
    val max: Float = 1f,
    val current: Float = 1f,
) {
    val canZoom: Boolean get() = max > min + 0.05f
}

enum class FlashMode { Off, Auto, On }

enum class PhotoQuality(val label: String, val width: Int, val height: Int, val jpeg: Int) {
    Standard("标准", 1080, 2400, 88),
    High("高清", 1440, 3200, 95),
}

enum class VideoQuality(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    HD("720p", 720, 1280, 5_000_000),
    FHD("1080p", 1080, 1920, 12_000_000),
}

data class BindConfig(
    val rearLens: RearLens,
    val flashMode: FlashMode,
    val saveSeparate: Boolean = false,
)

sealed interface CaptureEvent {
    data class PhotosSaved(val composed: Uri?, val back: Uri?, val front: Uri?) : CaptureEvent
    data class VideoSaved(val composed: Uri?, val back: Uri?, val front: Uri?) : CaptureEvent
    data class Failed(val message: String) : CaptureEvent
}
