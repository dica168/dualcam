package com.dualcam.app.camera

import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * One RGBA_8888 camera frame copied out of an [ImageProxy]. [rotationDegrees] is the clockwise
 * rotation that makes the image upright; it is applied on the GPU instead of by re-encoding
 * a rotated bitmap.
 */
class RgbaFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
)

/** Recycles direct buffers so steady-state recording allocates nothing per frame. */
class FramePool {
    private val free = ArrayDeque<ByteBuffer>()

    fun copy(image: ImageProxy): RgbaFrame {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowBytes = width * 4
        val src = plane.buffer.duplicate()
        val dst = obtain(rowBytes * height)
        if (plane.rowStride == rowBytes) {
            src.position(0)
            src.limit(min(src.capacity(), rowBytes * height))
            dst.put(src)
        } else {
            // Padded rows: compact them so the GL upload needs no UNPACK_ROW_LENGTH (ES 3 only).
            for (row in 0 until height) {
                val start = row * plane.rowStride
                val end = min(start + rowBytes, src.capacity())
                if (start >= end) break
                src.limit(end)
                src.position(start)
                dst.put(src)
            }
        }
        dst.flip()
        return RgbaFrame(dst, width, height, image.imageInfo.rotationDegrees)
    }

    fun recycle(frame: RgbaFrame) {
        synchronized(free) {
            if (free.size < MAX_FREE) free.addLast(frame.buffer)
        }
    }

    private fun obtain(bytes: Int): ByteBuffer {
        synchronized(free) {
            val iterator = free.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.capacity() >= bytes) {
                    iterator.remove()
                    candidate.clear()
                    return candidate
                }
            }
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    private companion object {
        const val MAX_FREE = 6
    }
}

/**
 * Renders the dual-camera layout straight into a MediaCodec input surface. Rotation, mirroring,
 * centre-crop and the rounded picture-in-picture frame are all done by the GPU; the CPU only
 * uploads the two camera textures.
 */
class GlDualComposer(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: Int = 0
    private val aPosition: Int
    private val aTexCoord: Int
    private val aLocal: Int
    private val uTexture: Int
    private val uUseTexture: Int
    private val uColor: Int
    private val uHalfSize: Int
    private val uRadius: Int
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val vertices = FloatArray(4 * FLOATS_PER_VERTEX)
    private val backTexture = CameraTexture()
    private val frontTexture = CameraTexture()

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                display = EGL14.EGL_NO_DISPLAY
                throw IllegalStateException("eglInitialize failed")
            }
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
                configs[0] == null
            ) {
                throw IllegalStateException("eglChooseConfig failed")
            }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }

            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            aLocal = GLES20.glGetAttribLocation(program, "aLocal")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uUseTexture = GLES20.glGetUniformLocation(program, "uUseTexture")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uHalfSize = GLES20.glGetUniformLocation(program, "uHalfSize")
            uRadius = GLES20.glGetUniformLocation(program, "uRadius")
            backTexture.allocate()
            frontTexture.allocate()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            checkGl("init")
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /**
     * Draws one output frame. [uploadBack]/[uploadFront] say whether the frame data changed since
     * the last call, so unchanged textures are not re-uploaded.
     */
    fun draw(
        back: RgbaFrame,
        front: RgbaFrame,
        layout: DualLayout,
        backPrimary: Boolean,
        uploadBack: Boolean,
        uploadFront: Boolean,
        ptsNs: Long,
    ) {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
        if (uploadBack) backTexture.upload(back)
        if (uploadFront) frontTexture.upload(front)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val place = DualLayoutComposer.placement(layout, width, height)
        val primary = if (backPrimary) backTexture else frontTexture
        val secondary = if (backPrimary) frontTexture else backTexture
        val mirrorPrimary = !backPrimary
        val mirrorSecondary = backPrimary

        drawImage(primary, RectF(place.primary), mirrorPrimary, radius = -1f)
        if (layout == DualLayout.Pip) {
            val half = place.secondaryStroke / 2f
            val outer = RectF(place.secondary).apply { inset(-half, -half) }
            val inner = RectF(place.secondary).apply { inset(half, half) }
            drawSolid(outer, radius = place.secondaryRadius + half)
            drawImage(secondary, inner, mirrorSecondary, radius = place.secondaryRadius - half)
        } else {
            drawImage(secondary, RectF(place.secondary), mirrorSecondary, radius = -1f)
        }
        checkGl("draw")

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        if (!EGL14.eglSwapBuffers(display, eglSurface)) {
            throw IllegalStateException("eglSwapBuffers failed")
        }
    }

    fun release() {
        runCatching {
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (eglSurface != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
                    backTexture.delete()
                    frontTexture.delete()
                    if (program != 0) {
                        GLES20.glDeleteProgram(program)
                        program = 0
                    }
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }
        runCatching { surface.release() }
    }

    private fun drawImage(texture: CameraTexture, dest: RectF, mirror: Boolean, radius: Float) {
        if (texture.width == 0) return
        fillQuad(dest, texture, mirror)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
        GLES20.glUniform1f(uUseTexture, 1f)
        GLES20.glUniform4f(uColor, 0f, 0f, 0f, 1f)
        GLES20.glUniform2f(uHalfSize, dest.width() / 2f, dest.height() / 2f)
        GLES20.glUniform1f(uRadius, radius)
        drawQuad()
    }

    private fun drawSolid(dest: RectF, radius: Float) {
        fillQuad(dest, null, false)
        GLES20.glUniform1f(uUseTexture, 0f)
        GLES20.glUniform4f(uColor, 1f, 1f, 1f, 1f)
        GLES20.glUniform2f(uHalfSize, dest.width() / 2f, dest.height() / 2f)
        GLES20.glUniform1f(uRadius, radius)
        drawQuad()
    }

    /**
     * Builds the four vertices of [dest]: NDC position, texture coordinate that centre-crops the
     * upright (rotated) image into [dest], and the pixel offset from the rect centre used by the
     * rounded-corner mask.
     */
    private fun fillQuad(dest: RectF, texture: CameraTexture?, mirror: Boolean) {
        var cx0 = 0f
        var cx1 = 1f
        var cy0 = 0f
        var cy1 = 1f
        var rotation = 0
        if (texture != null && texture.width > 0 && texture.height > 0) {
            rotation = texture.rotationDegrees
            val rotated = rotation % 180 != 0
            val uprightW = if (rotated) texture.height else texture.width
            val uprightH = if (rotated) texture.width else texture.height
            val srcRatio = uprightW.toFloat() / uprightH
            val destRatio = dest.width() / dest.height()
            if (srcRatio > destRatio) {
                val visible = destRatio / srcRatio
                cx0 = (1f - visible) / 2f
                cx1 = 1f - cx0
            } else {
                val visible = srcRatio / destRatio
                cy0 = (1f - visible) / 2f
                cy1 = 1f - cy0
            }
        }
        val centerX = dest.centerX()
        val centerY = dest.centerY()
        // Order: top-left, top-right, bottom-left, bottom-right (triangle strip).
        putVertex(0, dest.left, dest.top, cx0, cy0, mirror, rotation, centerX, centerY)
        putVertex(1, dest.right, dest.top, cx1, cy0, mirror, rotation, centerX, centerY)
        putVertex(2, dest.left, dest.bottom, cx0, cy1, mirror, rotation, centerX, centerY)
        putVertex(3, dest.right, dest.bottom, cx1, cy1, mirror, rotation, centerX, centerY)
        vertexBuffer.position(0)
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)
    }

    private fun putVertex(
        index: Int,
        px: Float,
        py: Float,
        displayX: Float,
        displayY: Float,
        mirror: Boolean,
        rotation: Int,
        centerX: Float,
        centerY: Float,
    ) {
        val dx = if (mirror) 1f - displayX else displayX
        val dy = displayY
        // Map a point of the upright image back to the stored (unrotated) texture.
        val u: Float
        val v: Float
        when (rotation) {
            90 -> { u = dy; v = 1f - dx }
            180 -> { u = 1f - dx; v = 1f - dy }
            270 -> { u = 1f - dy; v = dx }
            else -> { u = dx; v = dy }
        }
        val base = index * FLOATS_PER_VERTEX
        vertices[base] = px / width * 2f - 1f
        vertices[base + 1] = 1f - py / height * 2f
        vertices[base + 2] = u
        vertices[base + 3] = v
        vertices[base + 4] = px - centerX
        vertices[base + 5] = py - centerY
    }

    private fun drawQuad() {
        val stride = FLOATS_PER_VERTEX * 4
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        vertexBuffer.position(4)
        GLES20.glEnableVertexAttribArray(aLocal)
        GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisableVertexAttribArray(aLocal)
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("GL link failed: $log")
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("GL compile failed: $log")
        }
        return shader
    }

    private fun checkGl(where: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw IllegalStateException("GL error 0x${error.toString(16)} at $where")
        }
    }

    /** A GL_TEXTURE_2D holding the latest frame of one camera plus that frame's geometry. */
    private class CameraTexture {
        var id = 0
            private set
        var width = 0
            private set
        var height = 0
            private set
        var rotationDegrees = 0
            private set

        fun allocate() {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            id = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun upload(frame: RgbaFrame) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            frame.buffer.position(0)
            if (frame.width == width && frame.height == height) {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.buffer,
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.width, frame.height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.buffer,
                )
                width = frame.width
                height = frame.height
            }
            rotationDegrees = frame.rotationDegrees
        }

        fun delete() {
            if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            id = 0
        }
    }

    companion object {
        private const val FLOATS_PER_VERTEX = 6

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            attribute vec2 aLocal;
            varying vec2 vTexCoord;
            varying vec2 vLocal;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
                vLocal = aLocal;
            }
        """

        // highp: vLocal holds pixel offsets up to ~1000; mediump would quantise the edge mask.
        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vLocal;
            uniform sampler2D uTexture;
            uniform float uUseTexture;
            uniform vec4 uColor;
            uniform vec2 uHalfSize;
            uniform float uRadius;
            void main() {
                vec4 color = uUseTexture > 0.5 ? texture2D(uTexture, vTexCoord) : uColor;
                color.a = 1.0;
                if (uRadius >= 0.0) {
                    vec2 q = abs(vLocal) - (uHalfSize - vec2(uRadius));
                    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
                    color.a = 1.0 - smoothstep(-0.75, 0.75, d);
                }
                gl_FragColor = color;
            }
        """
    }
}
