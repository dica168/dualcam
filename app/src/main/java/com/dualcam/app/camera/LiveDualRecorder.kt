package com.dualcam.app.camera

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composes two live camera streams into one H.264/AAC MP4.
 *
 * Camera frames arrive as RGBA [ImageProxy]s from ImageAnalysis, are copied once into pooled
 * buffers, and are uploaded as GL textures; layout, rotation, mirroring and the PiP frame are
 * rendered on the GPU directly into the encoder surface ([GlDualComposer]).
 */
class LiveDualRecorder(
    private val quality: VideoQuality,
    private val audioEnabled: Boolean,
    cacheDir: File,
) {
    @Volatile var layout: DualLayout = DualLayout.Pip
    @Volatile var backPrimary: Boolean = true

    private val lock = Any()
    private var pendingBack: RgbaFrame? = null
    private var pendingFront: RgbaFrame? = null
    private val pool = FramePool()
    private val running = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private var encoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var worker: Thread? = null
    private var audioWorker: Thread? = null
    // Shared between the video worker and the audio worker; reads outside muxLock must be volatile.
    @Volatile private var videoTrack = -1
    @Volatile private var audioTrack = -1
    @Volatile private var muxerStarted = false
    @Volatile private var wroteSamples = false
    @Volatile private var expectingAudio = false
    @Volatile private var firstDrawNs = 0L
    @Volatile private var videoTrackReadyNs = 0L
    private val muxLock = Any()
    private val pendingSamples = ArrayDeque<PendingSample>()
    private val fps = 30
    private val output = File(cacheDir, "dual_live_${System.currentTimeMillis()}.mp4")
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var startNs = 0L

    /**
     * Invoked (on the worker thread) when the encoder dies after [start] has succeeded and before
     * [stop] was requested. The handler must not call [stop] synchronously: it runs on the thread
     * that [stop] joins.
     */
    @Volatile var onFailure: ((Throwable) -> Unit)? = null

    /** True when audio was requested but the microphone could not be used (video-only file). */
    @Volatile var audioDropped: Boolean = false
        private set

    @Volatile private var startCompleted = false
    @Volatile private var stopRequested = false
    // Set when stop()/start() gave up waiting for the worker: the worker then owns the resources
    // and the output file, and the caller must not touch either.
    private val abandoned = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    private class PendingSample(
        val isAudio: Boolean,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo,
    )

    /**
     * Starts the encoder thread and waits until the encoder, EGL surface and muxer are set up,
     * so configuration failures surface immediately instead of at [stop].
     */
    suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "dual-live-video") {
            var error: Throwable? = null
            try {
                encodeLoop()
            } catch (e: Throwable) {
                error = e
                failure.set(e)
                running.set(false)
            } finally {
                // The worker owns the codecs and the muxer: it is the only thread that releases
                // them, so a caller that timed out waiting for it never races with it here.
                audioWorker?.join(AUDIO_JOIN_MS)
                releaseResources()
                if (abandoned.get()) {
                    clearPending()
                    output.delete()
                }
                ready.countDown()
                // Report mid-recording failures right away; startup failures are thrown by
                // start() and failures while stopping are thrown by stop().
                if (error != null && startCompleted && !stopRequested &&
                    failureReported.compareAndSet(false, true)
                ) {
                    onFailure?.invoke(error)
                }
            }
        }
        val isReady = withContext(Dispatchers.IO) { ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        var error = failure.get() ?: if (!isReady) IllegalStateException("视频编码器启动超时") else null
        if (error == null) {
            startCompleted = true
            // The worker may have failed between ready.countDown() and the check above; whichever
            // side wins the flag reports it, so the session never sees the same failure twice.
            val late = failure.get()
            if (late != null && failureReported.compareAndSet(false, true)) error = late
        }
        if (error != null) {
            running.set(false)
            val alive = withContext(Dispatchers.IO) {
                worker?.join(START_JOIN_MS)
                worker?.isAlive == true
            }
            if (alive) {
                abandoned.set(true)
            } else {
                clearPending()
                output.delete()
            }
            throw error
        }
    }

    fun submitBack(image: ImageProxy) {
        if (!running.get()) return
        val frame = pool.copy(image)
        synchronized(lock) {
            pendingBack?.let(pool::recycle)
            pendingBack = frame
        }
    }

    fun submitFront(image: ImageProxy) {
        if (!running.get()) return
        val frame = pool.copy(image)
        synchronized(lock) {
            pendingFront?.let(pool::recycle)
            pendingFront = frame
        }
    }

    fun stop(): File {
        stopRequested = true
        running.set(false)
        val videoWorker = worker
        videoWorker?.join(STOP_JOIN_MS)
        if (videoWorker?.isAlive == true) {
            // Still encoding (or stuck in a codec call). Releasing the codec/muxer from here
            // would race with it; leave the cleanup to the worker's finally block.
            abandoned.set(true)
            Log.e(TAG, "stop(): encoder thread did not finish within ${STOP_JOIN_MS}ms; abandoning output")
            throw IllegalStateException("录像结束异常，编码线程未能及时退出")
        }
        clearPending()
        failure.get()?.let {
            output.delete()
            throw it
        }
        if (!wroteSamples || !output.exists() || output.length() < 64) {
            output.delete()
            throw IllegalStateException("录像文件为空，请再试一次")
        }
        return output
    }

    private fun releaseResources() {
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
    }

    private fun clearPending() {
        synchronized(lock) {
            pendingBack?.let(pool::recycle)
            pendingFront?.let(pool::recycle)
            pendingBack = null
            pendingFront = null
        }
        synchronized(muxLock) {
            pendingSamples.clear()
        }
    }

    private fun encodeLoop() {
        startNs = System.nanoTime()
        val videoEncoder = createVideoEncoder()
        encoder = videoEncoder
        val inputSurface = videoEncoder.createInputSurface()
        val gl = try {
            GlDualComposer(inputSurface, quality.width, quality.height)
        } catch (error: Throwable) {
            runCatching { inputSurface.release() }
            throw error
        }
        var currentBack: RgbaFrame? = null
        var currentFront: RgbaFrame? = null
        try {
            videoEncoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (audioEnabled) startAudio()
            ready.countDown()

            val bufferInfo = MediaCodec.BufferInfo()
            val frameNs = 1_000_000_000L / fps

            while (running.get()) {
                val loopStart = System.nanoTime()

                val newBack: RgbaFrame?
                val newFront: RgbaFrame?
                synchronized(lock) {
                    newBack = pendingBack
                    newFront = pendingFront
                    pendingBack = null
                    pendingFront = null
                }
                if (newBack != null) {
                    currentBack?.let(pool::recycle)
                    currentBack = newBack
                }
                if (newFront != null) {
                    currentFront?.let(pool::recycle)
                    currentFront = newFront
                }
                val back = currentBack
                val front = currentFront
                if (back != null && front != null) {
                    val now = System.nanoTime()
                    if (firstDrawNs == 0L) firstDrawNs = now
                    val ptsNs = (now - startNs).coerceAtLeast(0L)
                    gl.draw(
                        back = back,
                        front = front,
                        layout = layout,
                        backPrimary = backPrimary,
                        uploadBack = newBack != null,
                        uploadFront = newFront != null,
                        ptsNs = ptsNs,
                    )
                }

                drainEncoder(videoEncoder, bufferInfo, isAudio = false)
                maybeStartMuxer(force = false)

                val elapsed = System.nanoTime() - loopStart
                val sleepNs = frameNs - elapsed
                if (sleepNs > 1_000_000) {
                    Thread.sleep(sleepNs / 1_000_000)
                }
            }
            maybeStartMuxer(force = true)
            videoEncoder.signalEndOfInputStream()
            drainEncoder(videoEncoder, bufferInfo, isAudio = false, untilEos = true)
            maybeStartMuxer(force = true)
        } finally {
            currentBack?.let(pool::recycle)
            currentFront?.let(pool::recycle)
            gl.release()
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
        if (minBuf <= 0) {
            audioDropped = true
            return
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (_: SecurityException) {
            audioDropped = true
            return
        } catch (_: IllegalArgumentException) {
            audioDropped = true
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            audioDropped = true
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
            audioDropped = true
            return
        }
        // startRecording() throws IllegalStateException if the mic is unavailable (e.g. held by
        // another app); fall back to a silent recording instead of failing the whole video.
        val recording = try {
            recorder.startRecording()
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) {
            false
        }
        if (!recording) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            audioDropped = true
            return
        }
        audioRecord = recorder
        audioEncoder = codec
        expectingAudio = true
        audioWorker = thread(name = "dual-live-audio") {
            val buffer = ByteArray(minBuf)
            val info = MediaCodec.BufferInfo()
            val clock = AudioClock(sampleRate)
            try {
                while (running.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            codec.getInputBuffer(index)?.let { input ->
                                input.clear()
                                input.put(buffer, 0, read)
                                val ptsUs = clock.timestampUs(read / 2, nowUs())
                                codec.queueInputBuffer(index, 0, read, ptsUs, 0)
                            }
                        }
                    }
                    drainEncoder(codec, info, isAudio = true)
                }
                val eos = codec.dequeueInputBuffer(20_000)
                if (eos >= 0) {
                    codec.queueInputBuffer(eos, 0, 0, clock.timestampUs(0, nowUs()), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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

    private fun nowUs(): Long = ((System.nanoTime() - startNs) / 1000L).coerceAtLeast(0L)

    /**
     * Audio timestamps derived from the sample count (jitter-free), re-anchored to the wall clock
     * if they drift too far (e.g. after an AudioRecord overrun dropped data).
     */
    private class AudioClock(private val sampleRate: Int) {
        private var baseUs = -1L
        private var samples = 0L
        private var lastUs = -1L

        /** [sampleCount] samples were just read, finishing at wall time [readEndUs]. */
        fun timestampUs(sampleCount: Int, readEndUs: Long): Long {
            val durationUs = sampleCount * 1_000_000L / sampleRate
            val wallStartUs = (readEndUs - durationUs).coerceAtLeast(0L)
            if (baseUs < 0) baseUs = wallStartUs
            var pts = baseUs + samples * 1_000_000L / sampleRate
            if (abs(pts - wallStartUs) > MAX_DRIFT_US) {
                baseUs = wallStartUs - samples * 1_000_000L / sampleRate
                pts = wallStartUs
            }
            if (pts <= lastUs) pts = lastUs + 1
            lastUs = pts
            samples += sampleCount
            return pts
        }

        private companion object {
            const val MAX_DRIFT_US = 100_000L
        }
    }

    private fun maybeStartMuxer(force: Boolean) {
        synchronized(muxLock) {
            if (muxerStarted) return
            if (videoTrack < 0) return
            val audioReady = !expectingAudio || audioTrack >= 0
            val anchorNs = if (videoTrackReadyNs > 0L) videoTrackReadyNs else firstDrawNs
            val waited = anchorNs > 0L && System.nanoTime() - anchorNs > AUDIO_TRACK_WAIT_NS
            if (audioReady || waited || force) {
                val mux = muxer ?: return
                mux.start()
                muxerStarted = true
                for (sample in pendingSamples) {
                    val track = if (sample.isAudio) audioTrack else videoTrack
                    if (track >= 0) {
                        sample.data.position(0)
                        sample.data.limit(sample.info.size)
                        mux.writeSampleData(track, sample.data, sample.info)
                        wroteSamples = true
                    }
                }
                pendingSamples.clear()
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
                            if (videoTrackReadyNs == 0L) videoTrackReadyNs = System.nanoTime()
                        }
                    }
                    maybeStartMuxer(force = untilEos)
                }
                index >= 0 -> {
                    val encoded = codec.getOutputBuffer(index)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && info.size > 0 && !codecConfig) {
                        maybeStartMuxer(force = untilEos)
                        synchronized(muxLock) {
                            if (muxerStarted) {
                                val track = if (isAudio) audioTrack else videoTrack
                                if (track >= 0) {
                                    encoded.position(info.offset)
                                    encoded.limit(info.offset + info.size)
                                    muxer?.writeSampleData(track, encoded, info)
                                    wroteSamples = true
                                }
                            } else if (pendingSamples.size < MAX_PENDING_SAMPLES) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                val copy = ByteBuffer.allocateDirect(info.size)
                                copy.put(encoded)
                                copy.flip()
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, info.size, info.presentationTimeUs, info.flags)
                                }
                                pendingSamples.addLast(PendingSample(isAudio = isAudio, data = copy, info = infoCopy))
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

    private companion object {
        const val TAG = "LiveDualRecorder"
        const val START_TIMEOUT_MS = 5_000L
        const val START_JOIN_MS = 2_000L
        const val AUDIO_JOIN_MS = 3_000L
        // The worker joins the audio thread (AUDIO_JOIN_MS) before releasing; wait for both.
        const val STOP_JOIN_MS = 9_000L
        const val AUDIO_TRACK_WAIT_NS = 800_000_000L
        const val MAX_PENDING_SAMPLES = 128
    }
}
