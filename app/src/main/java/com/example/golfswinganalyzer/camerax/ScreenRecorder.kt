package com.example.golfswinganalyzer.camerax

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.io.IOException
import java.lang.RuntimeException
import java.net.MalformedURLException


class ScreenRecorder(private val context: Context, private val mediaProjection: MediaProjection) {
    private val VIDEO_MIME_TYPE = "video/mp4"
    private val VIDEO_WIDTH = 1280
    private val VIDEO_HEIGHT = 720
    private val FRAME_RATE = 90 // TODO: MAKE THIS A SETTING

    private var muxerStarted = false
    private var trackIndex = -1

    private val drainHandler: Handler = Handler(Looper.getMainLooper())
    private val drainEncoderRunnable = Runnable {
        drainEncoder()
    }

    lateinit var inputSurface: Surface
    lateinit var muxer: MediaMuxer
    lateinit var videoEncoder: MediaCodec
    lateinit var videoBufferInfo: MediaCodec.BufferInfo

    fun startRecording(outputPath: String) {
        val dm: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(Display.DEFAULT_DISPLAY) ?: throw RuntimeException("No display found.")
        prepareVideoRecorder()

        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            throw RuntimeException("MediaMuxer creation failed $e")
        }

        val metrics: DisplayMetrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // Record the screen
        mediaProjection.createVirtualDisplay("Recording Display", screenWidth, screenHeight, density, 0, inputSurface, null, null)
        drainEncoder()
    }

    fun stopRecording() {
        drainHandler.removeCallbacks(drainEncoderRunnable)
        if (muxerStarted) {
            muxer.stop()
        }

        muxer.release()
        muxerStarted = false

        videoEncoder.stop()
        videoEncoder.release()
        inputSurface.release()
        mediaProjection.stop()
        trackIndex = -1
    }

    private fun prepareVideoRecorder() {
        videoBufferInfo = MediaCodec.BufferInfo()
        val format: MediaFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / FRAME_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        try {
            videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()
        } catch (e: IOException) {
            stopRecording()
        }
    }

    private fun drainEncoder(): Boolean {
        drainHandler.removeCallbacks(drainEncoderRunnable)
        while (true) {
            val bufferIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0)

            when {
                bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }
                bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex >= 0) {
                        throw RuntimeException("format changed twice")
                    }
                    trackIndex = muxer.addTrack(videoEncoder.outputFormat)
                    if (!muxerStarted && trackIndex >= 0) {
                        muxer.start()
                        muxerStarted = true
                    }
                }
                bufferIndex < 0 -> { }
                else -> {
                    val encodedData = videoEncoder.getOutputBuffer(bufferIndex)
                        ?: throw RuntimeException("couldn't fetch buffer at index $bufferIndex")

                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoBufferInfo.size = 0
                    }

                    if (videoBufferInfo.size != 0) {
                        if (muxerStarted) {
                            encodedData.position(videoBufferInfo.offset)
                            encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, videoBufferInfo)
                        }
                    }

                    videoEncoder.releaseOutputBuffer(bufferIndex, false)

                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }

        drainHandler.postDelayed(drainEncoderRunnable, 10)
        return false
    }
}