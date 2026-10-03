package com.dualcam.app.camera

import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LiveDualRecorder(
    private val quality: VideoQuality,
    private val audioEnabled: Boolean,
    private val cacheDir: File,
) {
    @Volatile var layout: DualLayout = DualLayout.Pip
    @Volatile var backPrimary: Boolean = true

    private val lock = Any()
    private var latestBack: Bitmap? = null
    private var latestFront: Bitmap? = null
    private val running = AtomicBoolean(false)
    private var encoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var worker: Thread? = null
    private var audioWorker: Thread? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var wroteSamples = false
    private var expectingAudio = false
    private val muxLock = Any()
    private val fps = 24
    private val output = File(cacheDir, "dual_live_${System.currentTimeMillis()}.mp4")
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var startNs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "dual-live-video") {
            try {
                encodeLoop()
            } catch (error: Throwable) {
                failure.set(error)
                running.set(false)
            }
        }
    }

    fun submitBack(bitmap: Bitmap) {
        if (!running.get()) {
            bitmap.recycle()
            return
        }
        synchronized(lock) {
            latestBack?.recycle()
            latestBack = bitmap
        }
    }

    fun submitFront(bitmap: Bitmap) {
        if (!running.get()) {
            bitmap.recycle()
            return
        }
        synchronized(lock) {
            latestFront?.recycle()
            latestFront = bitmap
        }
    }

    fun stop(): File {
        running.set(false)
        worker?.join(6_000)
        audioWorker?.join(3_000)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { if (muxerStarted && wroteSamples) muxer?.stop() }
        runCatching { muxer?.release() }
        encoder = null
        audioEncoder = null
        audioRecord = null
        muxer = null
        synchronized(lock) {
            latestBack?.recycle()
            latestFront?.recycle()
            latestBack = null
            latestFront = null
        }
        failure.get()?.let { throw it }
        if (!wroteSamples || !output.exists() || output.length() < 64) {
            throw IllegalStateException("录像文件为空，请再试一次")
        }
        return output
    }

    private fun encodeLoop() {
        startNs = System.nanoTime()
        val videoEncoder = createVideoEncoder()
        val inputSurface = videoEncoder.createInputSurface()
        val egl = EglEncoderSurface(inputSurface, quality.width, quality.height)
        videoEncoder.start()
        encoder = videoEncoder
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (audioEnabled) startAudio()

        val bufferInfo = MediaCodec.BufferInfo()
        val frameNs = 1_000_000_000L / fps
        try {
            val formatWaitDeadline = System.nanoTime() + 800_000_000L
            while (running.get() && System.nanoTime() < formatWaitDeadline) {
                drainEncoder(videoEncoder, bufferInfo, isAudio = false)
                val audioReady = !expectingAudio || audioTrack >= 0
                if (videoTrack >= 0 && audioReady) break
                Thread.sleep(10)
            }
            maybeStartMuxer(force = true)

            while (running.get()) {
                val loopStart = System.nanoTime()
                drainEncoder(videoEncoder, bufferInfo, isAudio = false)
                maybeStartMuxer(force = false)

                val back: Bitmap?
                val front: Bitmap?
                synchronized(lock) {
                    back = latestBack?.copy(Bitmap.Config.ARGB_8888, false)
                    front = latestFront?.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (back != null && front != null) {
                    val composed = DualLayoutComposer.compose(
                        back = back,
                        front = front,
                        layout = layout,
                        backPrimary = backPrimary,
                        outWidth = quality.width,
                        outHeight = quality.height,
                    )
                    back.recycle()
                    front.recycle()
                    val ptsNs = (System.nanoTime() - startNs).coerceAtLeast(0L)
                    egl.drawBitmap(composed, ptsNs)
                    composed.recycle()
                } else {
                    back?.recycle()
                    front?.recycle()
                }

                val elapsed = System.nanoTime() - loopStart
                val sleepNs = frameNs - elapsed
                if (sleepNs > 1_000_000) {
                    Thread.sleep(sleepNs / 1_000_000)
                }
            }
            maybeStartMuxer(force = true)
            videoEncoder.signalEndOfInputStream()
            drainEncoder(videoEncoder, bufferInfo, isAudio = false, untilEos = true)
        } finally {
            egl.release()
        }
    }

    private fun createVideoEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            quality.width,
            quality.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    @Suppress("DEPRECATION")
    private fun startAudio() {
        val sampleRate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (_: Exception) {
            recorder.release()
            return
        }
        audioRecord = recorder
        audioEncoder = codec
        expectingAudio = true
        recorder.startRecording()
        audioWorker = thread(name = "dual-live-audio") {
            val buffer = ByteArray(minBuf)
            val info = MediaCodec.BufferInfo()
            try {
                while (running.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            codec.getInputBuffer(index)?.let { input ->
                                input.clear()
                                input.put(buffer, 0, read)
                                val ptsUs = ((System.nanoTime() - startNs) / 1000L).coerceAtLeast(0L)
                                codec.queueInputBuffer(index, 0, read, ptsUs, 0)
                            }
                        }
                    }
                    drainEncoder(codec, info, isAudio = true)
                }
                val eos = codec.dequeueInputBuffer(20_000)
                if (eos >= 0) {
                    val ptsUs = ((System.nanoTime() - startNs) / 1000L).coerceAtLeast(0L)
                    codec.queueInputBuffer(eos, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(codec, info, isAudio = true, untilEos = true)
            } catch (error: Throwable) {
                expectingAudio = false
                if (failure.get() == null) {
                    // Audio must not fail the whole recording.
                    error.printStackTrace()
                }
            }
        }
    }

    private fun maybeStartMuxer(force: Boolean) {
        synchronized(muxLock) {
            if (muxerStarted) return
            if (videoTrack < 0) return
            val audioReady = !expectingAudio || audioTrack >= 0
            val waited = startNs > 0L && System.nanoTime() - startNs > 700_000_000L
            if (audioReady || waited || force) {
                muxer?.start()
                muxerStarted = true
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        isAudio: Boolean,
        untilEos: Boolean = false,
    ) {
        val deadline = System.nanoTime() + if (untilEos) 2_000_000_000L else 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (untilEos && System.nanoTime() < deadline) continue else return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer ?: return
                    synchronized(muxLock) {
                        if (muxerStarted) return@synchronized
                        if (isAudio) {
                            if (audioTrack < 0) audioTrack = mux.addTrack(codec.outputFormat)
                        } else if (videoTrack < 0) {
                            videoTrack = mux.addTrack(codec.outputFormat)
                        }
                    }
                    maybeStartMuxer(force = false)
                }
                index >= 0 -> {
                    val encoded = codec.getOutputBuffer(index)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && info.size > 0 && !codecConfig) {
                        maybeStartMuxer(force = false)
                        if (muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            val track = if (isAudio) audioTrack else videoTrack
                            if (track >= 0) {
                                synchronized(muxLock) {
                                    muxer?.writeSampleData(track, encoded, info)
                                    wroteSamples = true
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }
}

private class EglEncoderSurface(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val textureId: Int
    private val positionHandle: Int
    private val texCoordHandle: Int
    private val vertexBuffer: FloatBuffer
    private var textureAllocated = false

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
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
        context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed")
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertexBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("init")
    }

    fun drawBitmap(bitmap: Bitmap, ptsNs: Long) {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (!textureAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureAllocated = true
        } else if (bitmap.width == width && bitmap.height == height) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        checkGl("draw")

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        if (!EGL14.eglSwapBuffers(display, eglSurface)) {
            throw IllegalStateException("eglSwapBuffers failed")
        }
    }

    fun release() {
        runCatching {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        runCatching { surface.release() }
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
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

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        // Bitmap origin is top-left; GL origin is bottom-left, so v is flipped.
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
    }
}
