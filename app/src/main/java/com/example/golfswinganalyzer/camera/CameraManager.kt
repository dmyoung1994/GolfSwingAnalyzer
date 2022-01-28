

package com.example.golfswinganalyzer.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.window.layout.WindowMetricsCalculator
import com.example.golfswinganalyzer.MainActivity
import com.example.golfswinganalyzer.dao.FullSwing
import com.example.golfswinganalyzer.graphics.GraphicOverlay
import com.example.golfswinganalyzer.mlkit.SwingDetectorProcessor
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.lang.IllegalStateException
import androidx.camera.video.QualitySelector
import androidx.core.content.ContextCompat
import com.example.golfswinganalyzer.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


// TODO: Inits a back facing camera. Eventually refactor to be able to set which camera to use
@ExperimentalGetImage
class CameraManager(
    private val context: MainActivity,
    private val previewView: PreviewView,
    private val graphicOverlay: GraphicOverlay?,
) {
    private val recordingSound = MediaActionSound()

    private var mIsRecordingVideo = false
    private var mLensFacing = CameraSelector.LENS_FACING_BACK
    private var mAnalysisExecutor = Executors.newSingleThreadScheduledExecutor()
    private var mVideoExecutor = Executors.newSingleThreadScheduledExecutor()

    private var mQualitySelector: QualitySelector = QualitySelector
        .fromOrderedList(listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD))

    private lateinit var mVideoCapture: VideoCapture
    private lateinit var mOutputDirectory: File

    private val RATIO_4_3_VALUE = 4.0 / 3.0
    private val RATIO_16_9_VALUE = 16.0 / 9.0

    private var mNeedUpdateGraphicOverlayImageSourceInfo = false

    // Video analysis and capture things
    private var mPreview: Preview? = null
    private var mCameraProvider: ProcessCameraProvider? = null
    private var mImageAnalyzer: ImageAnalysis? = null
    private var imageProcessor = SwingDetectorProcessor(
        context,
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
        this
    )

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = context.let { view ->
            if (displayId == view.display?.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display?.rotation}")
                mImageAnalyzer?.targetRotation = view.display?.rotation!!
            }
        }
    }

    init {
        startCamera()
    }

    fun flipCamera() {
        mLensFacing = if (mLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        // Re-bind camera use cases with the updated camera
        bindCameraUseCases()
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            mCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Bind all the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context).bounds
        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        val rotation = context.display?.rotation!!

        val cameraProvider = mCameraProvider ?: throw  IllegalStateException("Camera Init Failed")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(mLensFacing).build()

        mOutputDirectory = getOutputDirectory()

        mNeedUpdateGraphicOverlayImageSourceInfo = true

        mPreview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        mVideoCapture = VideoCapture.Builder().apply {
            setTargetAspectRatio(screenAspectRatio)
        }.build()

        mImageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setAnalyzer(mAnalysisExecutor, { imageProxy: ImageProxy ->
                    if (mNeedUpdateGraphicOverlayImageSourceInfo) {
                        val isImageFlipped = mLensFacing == CameraSelector.LENS_FACING_FRONT
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        if (rotationDegrees == 0 || rotationDegrees == 180) {
                            graphicOverlay!!.setImageSourceInfo(imageProxy.width, imageProxy.height, isImageFlipped)
                        } else {
                            graphicOverlay!!.setImageSourceInfo(imageProxy.height, imageProxy.width, isImageFlipped)
                        }
                        mNeedUpdateGraphicOverlayImageSourceInfo = false
                    }
                    try {
                        imageProcessor.processImageProxy(imageProxy, graphicOverlay!!)
                    } catch (e: MlKitException) {
                        Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                })
            }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(context, cameraSelector, mImageAnalyzer, mVideoCapture, mPreview)
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height) / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }

        return AspectRatio.RATIO_16_9
    }

    private fun getOutputDirectory() : File {
        val mediaDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES).let {
            File(it, context.resources.getString(R.string.app_name)).apply { mkdir() }
        }

        return if (mediaDir.exists()) {
            mediaDir
        } else {
            context.filesDir
        }
    }

    fun stopCamera() {
    }

    private fun recordVideo() {
        val file = createFile(mOutputDirectory, FILENAME, VIDEO_EXTENSION)

        val outputFileOptions = VideoCapture.OutputFileOptions.Builder(file).build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            context.requestPermissions()
            return
        }
        mVideoCapture.startRecording(outputFileOptions, mVideoExecutor, object : VideoCapture.OnVideoSavedCallback {
            override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                val msg = "Swing saved: ${file.absolutePath}"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }

            override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                val msg = "Swing capture failed: $message"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        })
    }

    fun promptGetInFrame() {
        Toast.makeText(context, "Please make sure your body is in the frame.", Toast.LENGTH_SHORT).show()
    }

    fun onSwingReady() {
        // If we cant' record, just dont do anything
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO )
            != PackageManager.PERMISSION_GRANTED)
        {
            return
        }

        recordingSound.play(MediaActionSound.START_VIDEO_RECORDING)
        recordVideo()
        mIsRecordingVideo = true
    }

    fun onSwingFinished(fullSwingData: FullSwing) {
        // TODO: Analyze swing and save data to a video file
        mVideoCapture.stopRecording()
        mIsRecordingVideo = false
    }
    

    companion object {
        private const val TAG = "CameraManager"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"

        fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US).format(System.currentTimeMillis()) + extension)
    }
}