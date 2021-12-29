package com.example.golfswinganalyzer.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.SparseIntArray
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.example.golfswinganalyzer.BuildConfig
import com.example.golfswinganalyzer.graphics.AutoFitTextureView
import com.example.golfswinganalyzer.graphics.GraphicOverlay
import com.example.golfswinganalyzer.mlkit.SwingDetectorProcessor
import com.example.golfswinganalyzer.util.ImageUtils
import com.google.mlkit.vision.common.InputImage
import java.lang.Integer.min
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import com.google.android.gms.common.images.Size
import java.io.File

class Camera2Source {
    companion object {
        val CAMERA_FACING_BACK = CameraCharacteristics.LENS_FACING_BACK
        val CAMERA_FACING_FRONT = CameraCharacteristics.LENS_FACING_FRONT

        val CAMERA_FLASH_OFF = CaptureRequest.CONTROL_AE_MODE_OFF
        val CAMERA_FLASH_ON = CaptureRequest.CONTROL_AE_MODE_ON
        val CAMERA_FLASH_AUTO = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        val CAMERA_FLASH_ALWAYS = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH

        val CAMERA_AF_AUTO = CaptureRequest.CONTROL_AF_MODE_AUTO
        val CAMERA_AF_CONTINUOUS_PICTURE = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val CAMERA_AF_CONTINUOUS_VIDEO = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

        private val TAG = "Camera2Source"
        private val ratioTolerance = 0.1
        private val maxRatioTolerance = 0.18
        private val ORIENTATIONS: SparseIntArray = SparseIntArray()
        private val INVERSE_ORIENTATIONS: SparseIntArray = SparseIntArray()

        private val MAX_PREVIEW_WIDTH = 1920
        private val MAX_PREVIEW_HEIGHT = 1080

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)

            INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
        }
    }

    /**
     * Callback interface used to indicate when video Recording Started.
     */
    interface VideoStartCallback {
        fun onVideoStart()
    }

    interface VideoStopCallback {
        // Called when Video Recording stopped.
        fun onVideoStop(videoFile: String?)
    }

    interface VideoErrorCallback {
        // Called when error occurred while recording video.
        fun onVideoError(error: String?)
    }

    /**
     * Callback interface used to notify an error while opening the camera.
     */
    interface CameraError {
        fun onCameraOpened()
        fun onCameraDisconnected()
        fun onCameraError(err: Int)
    }

    // Start Member Variables
    var mFacing = CAMERA_FACING_BACK
    var mFlashMode = CAMERA_FLASH_AUTO
    var mFocusMode = CAMERA_AF_AUTO

    var cameraStarted = false

    lateinit var mContext: Context
    var mSensorOrientation: Int = -1
    private lateinit var mCameraDevice: CameraDevice

    // Additional thread for running tasks that shouldn't be on the UI thread
    private lateinit var mBackgroundThread: HandlerThread
    // Handler for actually running tasks on the background thread
    private lateinit var mBackgroundHandler: Handler
    private var mDisplayOrientation: Int = -1
    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder
    private lateinit var mPreviewRequest: CaptureRequest
    private lateinit var mCaptureSession: CameraCaptureSession
    lateinit var mPreviewSize: Size
    private lateinit var mVideoSize: Size

    private lateinit var mMediaRecorder: MediaRecorder
    private val mDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private lateinit var mVideoFile: File
    private lateinit var mVideoStartCallback: VideoStartCallback
    private lateinit var mVideoStopCallback: VideoStopCallback

    private lateinit var mCameraId: String
    private lateinit var mPreviewView: AutoFitTextureView

    // TODO
    /*
    private lateinit var mShutterCallback: ShutterCallback
    private lateinit var mAutoFocusCallback: AutoFocusCallback
     */

    private lateinit var mCameraErrorCallback: CameraError

    private lateinit var mSensorArraySize: Rect
    private var mIsMeteringAreaSupported = false
    private var mIsSwappedDimensions = false

    private val mCameraOpenCloseLock = Semaphore(1)
    private var mIsFlashSupported = false
    private var mCameraImage: Image? = null

    private val mStateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            mCameraOpenCloseLock.release()
            mCameraDevice = device
            mCameraErrorCallback.onCameraOpened()
            createCameraPreviewSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            mCameraOpenCloseLock.release()
            mCameraDevice.close()
            mCameraErrorCallback.onCameraDisconnected()
        }

        override fun onError(device: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            device.close()
            mCameraErrorCallback.onCameraError(error)
        }
    }

    private val mOnPreviewAvailableListener: ImageReader.OnImageAvailableListener = ImageReader.OnImageAvailableListener {
        mCameraImage = it.acquireNextImage()
        mFrameProcessorRunnable.setNextFrame(mCameraImage)
        mCameraImage?.close()
    }

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private lateinit var mProcessingThread: Thread
    lateinit var mFrameProcessorRunnable: FrameProcessingRunnable

    private lateinit var mImageReaderPreview: ImageReader

    // End Member Variables

    // Start Member Functions
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread.start()
        mBackgroundHandler = Handler.createAsync(mBackgroundThread.looper)
    }

    private fun stopBackgroundThread() {
       mBackgroundThread.quitSafely()
        mBackgroundThread.join()
    }

    fun release() {
        mFrameProcessorRunnable.release()
        stop()
    }

    fun isPreviewReady() = this::mPreviewView.isInitialized
    fun stop() {
        try {
            mFrameProcessorRunnable.setActive(false)
            try {
                mProcessingThread.join()
            } catch (e: InterruptedException) {
                Log.d(TAG, "Frame processing thread interrupted on release.")
            }

            mCameraOpenCloseLock.acquire()
            mCaptureSession.close()
            mCameraDevice.close()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    fun start(previewView: AutoFitTextureView, displayOrientation: Int, errorHandler: CameraError) : Camera2Source {
        mDisplayOrientation = displayOrientation
        mCameraErrorCallback = errorHandler
        if (cameraStarted) {
            return this
        }

        cameraStarted = true
        startBackgroundThread()

        mProcessingThread = Thread(mFrameProcessorRunnable)
        mFrameProcessorRunnable.setActive(true)
        mProcessingThread.start()

        mPreviewView = previewView
        if (mPreviewView.isAvailable) {
            openCamera(mPreviewView.width, mPreviewView.height)
        }

        return this
    }

    fun recordVideo(videoStartCallback: VideoStartCallback, videoStopCallback: VideoStopCallback, videoErrorCallback: VideoErrorCallback, mute: Boolean) {
        mVideoStartCallback = videoStartCallback
        mVideoStopCallback = videoStopCallback

        mVideoFile = File(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).path, "${mDateFormatter.format(System.currentTimeMillis())}.mp4")

        mMediaRecorder = MediaRecorder()
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder.setOutputFile(mVideoFile)
        mMediaRecorder.setVideoEncodingBitRate(10000000)
        mMediaRecorder.setVideoFrameRate(30)
        mMediaRecorder.setVideoSize(mVideoSize.width, mVideoSize.height)
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder.setMaxDuration(-1)

        if (!mute) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mMediaRecorder.setAudioChannels(2)
        }

        if (mIsSwappedDimensions) {
            mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(mDisplayOrientation))
        } else {
            mMediaRecorder.setOrientationHint(ORIENTATIONS.get(mDisplayOrientation))
        }

        mMediaRecorder.prepare()
        closePreviewSession()
        createCameraRecordSession()
    }

    fun stopVideo() {
        mMediaRecorder.stop()
        mMediaRecorder.reset()
        mVideoStopCallback.onVideoStop(mVideoFile.absolutePath)
        closePreviewSession()
        createCameraPreviewSession()
    }

    private fun openCamera(width: Int, height: Int) {
        if (ActivityCompat.checkSelfPermission(
                mContext,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val cameraManager: CameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Timeout waiting to lock camera opening")
            }
            cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        val cameraManager: CameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (cameraId: String in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            Log.d(TAG, "FOUND CAMERA WITH ID: $cameraId AND FACING $facing")
            if (facing == mFacing) {
                mCameraId = cameraId
                val map: StreamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return

                val bestSize = getBestAspectPictureSize(map.getOutputSizes(ImageFormat.JPEG))

                mSensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                val maxAFRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                if (maxAFRegions != null) {
                    mIsMeteringAreaSupported = maxAFRegions >= 1
                }

                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                if (sensorOrientation != null) {
                    mSensorOrientation = sensorOrientation
                    when(mDisplayOrientation) {
                        Surface.ROTATION_0, Surface.ROTATION_180 -> {
                            if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                                mIsSwappedDimensions = true
                            }
                        }

                        Surface.ROTATION_90, Surface.ROTATION_270 -> {
                            if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                                mIsSwappedDimensions = true
                            }
                        }
                    }
                }

                val pointDisplay = ImageUtils.getScreenSize(mContext)
                val rotatedPreviewWidth = if (mIsSwappedDimensions) height else width
                val rotatedPreviewHeight = if (mIsSwappedDimensions) width else height
                var maxPreviewWidth = if (mIsSwappedDimensions) pointDisplay.y else pointDisplay.x
                var maxPreviewHeight = if (mIsSwappedDimensions) pointDisplay.x else pointDisplay.y

                maxPreviewHeight = min(maxPreviewHeight, MAX_PREVIEW_HEIGHT)
                maxPreviewWidth = min(maxPreviewWidth, MAX_PREVIEW_WIDTH)

                val outputSizes: Array<Size> = ImageUtils.sizeToImageSize(map.getOutputSizes(SurfaceTexture::class.java))
                val outputSizesRecording: Array<Size> = ImageUtils.sizeToImageSize(map.getOutputSizes(MediaRecorder::class.java))

                mPreviewSize = chooseOptimalSize(outputSizes, rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight, bestSize)
                mVideoSize = chooseVideoSize(outputSizesRecording)
                Log.d(TAG, "video recording resolution will be ${mVideoSize.width} x ${mVideoSize.height}")

                // Fit the aspect ratio of the texture view to be the one we picked
                if (mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mPreviewView.setAspectRatio(mPreviewSize.width, mPreviewSize.height)
                } else {
                    mPreviewView.setAspectRatio(mPreviewSize.height, mPreviewSize.width)
                }

                // check if flash is supported
                mIsFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            }
        }
    }

    private fun closePreviewSession() {
        mCaptureSession.close()
    }


    private fun createCameraPreviewSession() {
        try {
            val texture = mPreviewView.surfaceTexture
            if (BuildConfig.DEBUG && texture == null) {
                error("Assertion failed")
            }

            texture!!.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
            mImageReaderPreview = ImageReader.newInstance(mPreviewSize.width, mPreviewSize.height, ImageFormat.YUV_420_888, 1)
            mImageReaderPreview.setOnImageAvailableListener(mOnPreviewAvailableListener, mBackgroundHandler)

            // This is the output surface we need to start the preview
            val surface = Surface(texture)

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder.addTarget(surface)
            mPreviewRequestBuilder.addTarget(mImageReaderPreview.surface)
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range.create(30,60))

            val cameraCaptureSessionCallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    mCaptureSession = session

                    try {
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CAMERA_AF_CONTINUOUS_PICTURE)
                        if (mIsFlashSupported) {
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode)
                        }

                        mPreviewRequest = mPreviewRequestBuilder.build()
                        mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(p0: CameraCaptureSession) {
                    Log.d(TAG, "Camera Config failed!")
                }

            }

            val captureOutputs = mutableListOf(surface, mImageReaderPreview.surface)
            mCameraDevice.createCaptureSession(captureOutputs, cameraCaptureSessionCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraRecordSession() {
        val texture = mPreviewView.surfaceTexture
        if (BuildConfig.DEBUG && texture == null) {
            error("Assertion failed")
        }

        texture!!.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
        mImageReaderPreview = ImageReader.newInstance(mPreviewSize.width, mPreviewSize.height, ImageFormat.YUV_420_888, 1)
        mImageReaderPreview.setOnImageAvailableListener(mOnPreviewAvailableListener, mBackgroundHandler)

        // Handles both sending frames to the preview and to the media recorder
        val surface = Surface(texture)
        val recordingSurface = mMediaRecorder.surface

        mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mPreviewRequestBuilder.addTarget(surface)
        mPreviewRequestBuilder.addTarget(mImageReaderPreview.surface)
        mPreviewRequestBuilder.addTarget(recordingSurface)

        val recordingStateCallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                mCaptureSession = captureSession

                try {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CAMERA_AF_CONTINUOUS_VIDEO)
                    if (mIsFlashSupported) {
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode)
                    }

                    // Display camera preview
                    mPreviewRequest = mPreviewRequestBuilder.build()
                    mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                Log.d(TAG, "Camera Configuration failed!")
            }
        }

        val outputSurfaces = mutableListOf<Surface>(surface, mImageReaderPreview.surface, recordingSurface)
        mCameraDevice.createCaptureSession(outputSurfaces, recordingStateCallback, mBackgroundHandler)
    }


    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size) : Size {
        val bigEnoughSizes = mutableListOf<Size>()
        val notBigEnoughSizes = mutableListOf<Size>()
        for (option: Size in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight && option.height == (option.width * aspectRatio.height / aspectRatio.width)) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnoughSizes.add(option)
                } else {
                    notBigEnoughSizes.add(option)
                }
            }
        }

        if (bigEnoughSizes.size > 0) {
            return bigEnoughSizes.reduce(SizesByArea::compare)
        } else if (notBigEnoughSizes.size > 0) {
            return notBigEnoughSizes.reduce(SizesByArea::compare)
        } else {
            return choices[0]
        }
    }

    private fun chooseVideoSize(choices: Array<Size>) : Size {
        for (size: Size in choices) {
            if (size.width == size.height * 16 / 9) {
                return size
            }
        }

        return choices[0]
    }

    private fun addSizesWithinTolerance(targetRatio: Float, tolerance: Double, supportedPictureSizes: Array<android.util.Size>, outDiffs: TreeMap<Double, MutableList<android.util.Size>>) {
        for (size: android.util.Size in supportedPictureSizes) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            val diff: Double = abs(ratio - targetRatio)
            if (diff < tolerance) {
                if (outDiffs.containsKey(diff)) {
                    outDiffs[diff]!!.add(size)
                } else {
                    val newList: MutableList<android.util.Size> = mutableListOf()
                    newList.add(size)
                    outDiffs[diff] = newList
                }
            }
        }
    }

    private fun getBestAspectPictureSize(supportedPictureSizes: Array<android.util.Size>) : Size {
        val targetRatio = ImageUtils.getScreenRatio(mContext)
        var bestSize: Size? = null
        val diffs: TreeMap<Double, MutableList<android.util.Size>> = TreeMap()

        // Select supported sizes within a specific tolerance
        addSizesWithinTolerance(targetRatio, ratioTolerance, supportedPictureSizes, diffs)

        //If no sizes were supported, (strange situation) use a higher tolerance
        if (diffs.isEmpty()) {
            addSizesWithinTolerance(targetRatio, maxRatioTolerance, supportedPictureSizes, diffs)
        }

        for (entry: Map.Entry<Double, List<android.util.Size>> in diffs.entries.asIterable()) {
            val sizes = entry.value
            for (size: android.util.Size in sizes) {
                if (bestSize == null) {
                    bestSize = Size(size.width, size.height)
                } else if (bestSize.width < size.width || bestSize.height < size.height) {
                    bestSize = Size(size.width, size.height)
                }
            }
        }

        return bestSize!!
    }

    private fun configureTransform(width: Int, height: Int) {
        val rotation = mDisplayOrientation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = RectF(0f, 0f, mPreviewSize.height.toFloat(), mPreviewSize.width.toFloat())

        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(height / mPreviewView.height.toFloat(), width / mPreviewView.width.toFloat())
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation.toFloat() - 2), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        mPreviewView.setTransform(matrix)
    }

    private fun getDetectorOrientation(sensorOrientation: Int) : Int {
        when(sensorOrientation) {
            0, 360 -> return if (mIsSwappedDimensions) 180 else 0
            90 -> return if (mIsSwappedDimensions) 270 else 90
            180 -> return if (mIsSwappedDimensions) 0 else 180
            270 -> return if (mIsSwappedDimensions) 90 else 270
        }
        return 90
    }

    private fun getOrientation(rotation: Int) : Int {
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360
    }
    // End Member Functions

    // Start Helpers
    internal object SizesByArea {
        fun compare(p0: Size, p1: Size): Size {
            return if (p0.width * p0.height > p1.width * p1.height) p0 else p1
        }
    }

    inner class FrameProcessingRunnable(
        private val mProcessor: SwingDetectorProcessor,
        private val mGraphicOverlay: GraphicOverlay?
    ) : Runnable
    {
        private var mStartTimeMillis = SystemClock.elapsedRealtime()

        // This lock guards the members below
        private val mLock = Object()
        private var mIsActive = true

        private var mPendingTimeMillis = 0L
        private var mPendingFrameId = 0
        private var mPendingFrameData: Image? = null

        @SuppressLint("Assert")
        fun release() {
            try {
                assert(mProcessingThread.state == Thread.State.TERMINATED)
            } catch (ignore: AssertionError) {}

            mProcessor.stop()
        }

        fun setActive(isActive: Boolean) {
            synchronized(mLock) {
                mIsActive = isActive
                mLock.notifyAll()
            }
        }

        fun setNextFrame(data: Image?) {
            synchronized(mLock) {
                if (mPendingFrameData != null) {
                    mPendingFrameData = null
                }
                // Timestamp and frame ID are maintained here, which will give downstream code some
                // idea of the timing of frames received and when frames were dropped along the way.
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis
                ++mPendingFrameId
                mPendingFrameData = data

                // Notify the processor thread if it is waiting on the next frame (see below).
                mLock.notifyAll()
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames
         * continuously.  The next pending frame is either immediately available or hasn't been
         * received yet.  Once it is available, we transfer the frame info to local variables and
         * run detection on that frame.  It immediately loops back for the next frame without
         * pausing.
         *
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context
         * switching or frame acquisition time latency.
         *
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        override fun run() {
            var inputImage: InputImage
            while (true) {
                synchronized(mLock) {
                    while (mIsActive && mPendingFrameData == null) {
                        try {
                            // wait for the next image data to come from the camera
                            mLock.wait()
                        } catch (e: InterruptedException) {
                            Log.d(TAG, "Frame processing image loop terminated")
                            return
                        }
                    }

                    if (!mIsActive) {
                        // Exit the loop once this camera source is stopped or released.  We check
                        // this here, immediately after the wait() above, to handle the case where
                        // setActive(false) had been called, triggering the termination of this
                        // loop.
                        return
                    }

                    inputImage = InputImage.fromMediaImage(
                        mPendingFrameData,
                        getDetectorOrientation(mSensorOrientation)
                    )
                    mPendingFrameData = null
                }

                mProcessor.requestDetectInImage(inputImage, mGraphicOverlay, true)
            }
        }
    }
    // End Helpers
}