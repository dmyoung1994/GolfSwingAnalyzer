

package com.golfapp.swingly.camera

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.AspectRatio.RATIO_4_3
import androidx.camera.video.VideoCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import com.golfapp.swingly.activity.SwingRecorderFragment
import com.golfapp.swingly.dao.FullSwing
import com.golfapp.swingly.graphics.GraphicOverlay
import com.golfapp.swingly.mlkit.SwingDetectorProcessor
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.lang.IllegalStateException
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.golfapp.swingly.util.FileIO
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@ExperimentalGetImage
class CameraManager(
    private val context: SwingRecorderFragment,
    private val previewView: PreviewView,
    private val graphicOverlay: GraphicOverlay?,
) {
    private val mRecordingSound = MediaActionSound()

    private var mIsRecordingVideo = false
    private var mVideoID = ""
    private var mSwingName = ""
    private var mLensFacing = CameraSelector.LENS_FACING_BACK
    private var mAnalysisExecutor = Executors.newSingleThreadScheduledExecutor()

    private var mQualitySelector: QualitySelector = QualitySelector
        .fromOrderedList(listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD))

    private val recorder = Recorder.Builder()
        .setExecutor(ContextCompat.getMainExecutor(context))
        .setQualitySelector(mQualitySelector)
        .build()
    private var mVideoCapture = VideoCapture.withOutput(recorder)
    private var mRecording: Recording? = null

    private var mNeedUpdateGraphicOverlayImageSourceInfo = false

    // Video analysis and capture things
    private var mPreview: Preview? = null
    private var mCameraProvider: ProcessCameraProvider? = null
    private var mImageAnalyzer: ImageAnalysis? = null
    private var mCameraSelector: CameraSelector = CameraSelector.Builder().requireLensFacing(mLensFacing).build()
    private var mImageProcessor = SwingDetectorProcessor(
        context,
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
            .build(),
        this
    )

    private val mRecordingListener = Consumer<VideoRecordEvent> { event ->
        when (event) {
            is VideoRecordEvent.Finalize -> {
                val msg = if (!event.hasError()) {
                    "Swing saved: ${event.outputResults.outputUri}"
                } else {
                    mRecording?.close()
                    mRecording = null
                    "Swing capture failed: ${event.error}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun flipCamera() {
        mLensFacing = if (mLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        mCameraSelector = CameraSelector.Builder().requireLensFacing(mLensFacing).build()

        // Re-bind camera use cases with the updated camera
        bindCameraUseCases()
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            mCameraProvider = cameraProviderFuture.get()

            // Bind all the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = mCameraProvider ?: throw  IllegalStateException("Camera Init Failed")

        mNeedUpdateGraphicOverlayImageSourceInfo = true

        mPreview = Preview.Builder()
            .setTargetRotation(previewView.display.rotation)
            .setTargetAspectRatio(RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        mImageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(RATIO_4_3)
            .setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(mAnalysisExecutor) { imageProxy: ImageProxy ->
                    if (mNeedUpdateGraphicOverlayImageSourceInfo) {
                        val isImageFlipped = mLensFacing == CameraSelector.LENS_FACING_FRONT
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        if (rotationDegrees == 0 || rotationDegrees == 180) {
                            graphicOverlay!!.setImageSourceInfo(
                                imageProxy.width,
                                imageProxy.height,
                                isImageFlipped
                            )
                        } else {
                            graphicOverlay!!.setImageSourceInfo(
                                imageProxy.height,
                                imageProxy.width,
                                isImageFlipped
                            )
                        }
                        mNeedUpdateGraphicOverlayImageSourceInfo = false
                    }
                    try {
                        mImageProcessor.processImageProxy(imageProxy, graphicOverlay!!)
                    } catch (e: MlKitException) {
                        Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }

        resetProviderLifecycle(cameraProvider)
        // Save so we can unbind the preview while recording
        mCameraProvider = cameraProvider
    }

    private fun resetProviderLifecycle(cameraProvider: ProcessCameraProvider, useCaseGroup: UseCaseGroup) {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(context, mCameraSelector, useCaseGroup)
    }

    private fun resetProviderLifecycle(cameraProvider: ProcessCameraProvider) {
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(previewView.viewPort!!)
            .addUseCase(mPreview!!)
            .addUseCase(mImageAnalyzer!!)
            .build()
        resetProviderLifecycle(cameraProvider, useCaseGroup)
    }

    private fun recordVideo() {
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(previewView.viewPort!!)
            .addUseCase(mImageAnalyzer!!)
            .addUseCase(mVideoCapture)
            .build()
        resetProviderLifecycle(mCameraProvider!!, useCaseGroup)
        mSwingName = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
        val videoName = mSwingName + VIDEO_EXTENSION

        mVideoID = UUID.randomUUID().toString()
        val mediaStoreOutput = FileIO.mediaStoreOutput(videoName, "video/mp4", mVideoID, context)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            context.requestPermissions()
            return
        }

        mRecording = mVideoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context), mRecordingListener)
        Log.i(TAG, "Recording Started")
    }

    fun promptGetInFrame() {
        context.runOnUiThread {
            Toast.makeText(context, "Please make sure your body is in the frame.", Toast.LENGTH_SHORT).show()
        }
    }

    fun onSwingReady() {
        // If we cant' record, just don't do anything
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
        {
            return
        }

        context.runOnUiThread {
            mRecordingSound.play(MediaActionSound.START_VIDEO_RECORDING)
        }


        recordVideo()
        mIsRecordingVideo = true
    }

    fun onSwingFinished(fullSwingData: FullSwing?) {
        mRecording?.stop()
        mIsRecordingVideo = false
        context.analyzeSwing(fullSwingData!!, mVideoID, mSwingName)
        context.runOnUiThread {
            resetProviderLifecycle(mCameraProvider!!)
        }
    }
    

    companion object {
        private const val TAG = "CameraManager"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
    }
}