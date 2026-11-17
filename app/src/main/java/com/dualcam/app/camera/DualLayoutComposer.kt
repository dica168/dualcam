package com.dualcam.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

object DualLayoutComposer {

    const val PIP_WIDTH_FRACTION = 0.32f
    const val PIP_ASPECT = 158f / 118f
    const val PIP_INSET_X_FRACTION = 0.045f
    const val PIP_INSET_Y_FRACTION = 0.09f

    /** Where the two cameras land in an output of the given size. Shared by the Canvas and GL paths. */
    data class Placement(
        val primary: Rect,
        val secondary: Rect,
        /** Corner radius of the secondary pane; 0 for split layouts. */
        val secondaryRadius: Float,
        /** Border stroke of the secondary pane, centered on its edge; 0 for split layouts. */
        val secondaryStroke: Float,
    )

    fun placement(layout: DualLayout, outWidth: Int, outHeight: Int): Placement = when (layout) {
        DualLayout.Pip -> {
            val pipW = (outWidth * PIP_WIDTH_FRACTION).toInt()
            val pipH = (pipW * PIP_ASPECT).toInt()
            val insetX = (outWidth * PIP_INSET_X_FRACTION).toInt()
            val insetY = (outHeight * PIP_INSET_Y_FRACTION).toInt()
            Placement(
                primary = Rect(0, 0, outWidth, outHeight),
                secondary = Rect(outWidth - insetX - pipW, insetY, outWidth - insetX, insetY + pipH),
                secondaryRadius = pipW * 0.08f,
                secondaryStroke = pipW * 0.025f,
            )
        }
        DualLayout.SplitVertical -> {
            val mid = outHeight / 2
            Placement(Rect(0, 0, outWidth, mid - 2), Rect(0, mid + 2, outWidth, outHeight), 0f, 0f)
        }
        DualLayout.SplitHorizontal -> {
            val mid = outWidth / 2
            Placement(Rect(0, 0, mid - 2, outHeight), Rect(mid + 2, 0, outWidth, outHeight), 0f, 0f)
        }
    }

    fun compose(
        back: Bitmap,
        front: Bitmap,
        layout: DualLayout,
        backPrimary: Boolean,
        outWidth: Int = 1080,
        outHeight: Int = 2400,
    ): Bitmap {
        val primary = if (backPrimary) back else front
        val secondary = if (backPrimary) front else back
        val mirrorPrimary = !backPrimary
        val mirrorSecondary = backPrimary

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val place = placement(layout, outWidth, outHeight)
        drawCover(canvas, primary, place.primary, mirrorPrimary)
        if (layout == DualLayout.Pip) {
            drawRounded(canvas, secondary, place.secondary, mirrorSecondary, place.secondaryRadius, place.secondaryStroke)
        } else {
            drawCover(canvas, secondary, place.secondary, mirrorSecondary)
        }
        return output
    }

    /**
     * Returns a bitmap rotated by [rotationDegrees]. When a new bitmap is produced the
     * receiver is recycled, since callers never use the unrotated source afterwards.
     */
    fun Bitmap.oriented(rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return this
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated !== this) recycle()
        return rotated
    }

    private fun drawCover(canvas: Canvas, source: Bitmap, dest: Rect, mirror: Boolean) {
        val src = centerCrop(source, dest.width(), dest.height())
        if (!mirror) {
            canvas.drawBitmap(source, src, dest, FilterPaint)
            return
        }
        canvas.save()
        canvas.scale(-1f, 1f, dest.exactCenterX(), dest.exactCenterY())
        canvas.drawBitmap(source, src, dest, FilterPaint)
        canvas.restore()
    }

    private fun drawRounded(
        canvas: Canvas,
        source: Bitmap,
        dest: Rect,
        mirror: Boolean,
        radius: Float,
        stroke: Float,
    ) {
        val destF = RectF(dest)
        // Clip instead of saveLayer: saveLayer allocates an offscreen bitmap on every call,
        // which is far too expensive at video frame rate on a software canvas.
        val saved = canvas.save()
        val clip = Path().apply { addRoundRect(destF, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        drawCover(canvas, source, dest, mirror)
        canvas.restoreToCount(saved)
        BorderPaint.strokeWidth = stroke
        canvas.drawRoundRect(destF, radius, radius, BorderPaint)
    }

    private fun centerCrop(source: Bitmap, destW: Int, destH: Int): Rect {
        val srcRatio = source.width.toFloat() / source.height
        val destRatio = destW.toFloat() / destH
        return if (srcRatio > destRatio) {
            val cropW = (source.height * destRatio).toInt().coerceAtLeast(1)
            val left = ((source.width - cropW) / 2).coerceAtLeast(0)
            Rect(left, 0, left + cropW, source.height)
        } else {
            val cropH = (source.width / destRatio).toInt().coerceAtLeast(1)
            val top = ((source.height - cropH) / 2).coerceAtLeast(0)
            Rect(0, top, source.width, top + cropH)
        }
    }

    private val FilterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val BorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
}
