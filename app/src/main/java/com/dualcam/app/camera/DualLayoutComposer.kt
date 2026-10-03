package com.dualcam.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF

object DualLayoutComposer {

    const val PIP_WIDTH_FRACTION = 0.32f
    const val PIP_ASPECT = 158f / 118f
    const val PIP_INSET_X_FRACTION = 0.045f
    const val PIP_INSET_Y_FRACTION = 0.09f

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

        when (layout) {
            DualLayout.Pip -> {
                drawCover(canvas, primary, Rect(0, 0, outWidth, outHeight), mirrorPrimary)
                val pipW = (outWidth * PIP_WIDTH_FRACTION).toInt()
                val pipH = (pipW * PIP_ASPECT).toInt()
                val insetX = (outWidth * PIP_INSET_X_FRACTION).toInt()
                val insetY = (outHeight * PIP_INSET_Y_FRACTION).toInt()
                val pip = Rect(
                    outWidth - insetX - pipW,
                    insetY,
                    outWidth - insetX,
                    insetY + pipH,
                )
                drawRounded(canvas, secondary, pip, mirrorSecondary)
            }
            DualLayout.SplitVertical -> {
                val mid = outHeight / 2
                drawCover(canvas, primary, Rect(0, 0, outWidth, mid - 2), mirrorPrimary)
                drawCover(canvas, secondary, Rect(0, mid + 2, outWidth, outHeight), mirrorSecondary)
            }
            DualLayout.SplitHorizontal -> {
                val mid = outWidth / 2
                drawCover(canvas, primary, Rect(0, 0, mid - 2, outHeight), mirrorPrimary)
                drawCover(canvas, secondary, Rect(mid + 2, 0, outWidth, outHeight), mirrorSecondary)
            }
        }
        return output
    }

    fun Bitmap.oriented(rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return this
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
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

    private fun drawRounded(canvas: Canvas, source: Bitmap, dest: Rect, mirror: Boolean) {
        val radius = dest.width() * 0.08f
        val destF = RectF(dest)
        val saved = canvas.saveLayer(destF, null)
        val clip = Path().apply { addRoundRect(destF, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        drawCover(canvas, source, dest, mirror)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dest.width() * 0.025f
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawRoundRect(destF, radius, radius, border)
        canvas.restoreToCount(saved)
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
}
