

package com.example.golfswinganalyzer.camera

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Camera
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.golfswinganalyzer.MainActivity
import com.example.golfswinganalyzer.R
import com.example.golfswinganalyzer.dao.FullSwing
import com.example.golfswinganalyzer.graphics.CameraSourcePreview
import com.example.golfswinganalyzer.graphics.GraphicOverlay
import com.example.golfswinganalyzer.mlkit.SwingDetectorProcessor
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread


// TODO: Inits a back facing camera. Eventually refactor to be able to set which camera to use
class CameraManager(
    private val context: MainActivity,
    private val preview: CameraSourcePreview?,
    private val graphicOverlay: GraphicOverlay?,
) {

    private val recordingSound = MediaActionSound()

    var imageProcessor = SwingDetectorProcessor(
        context,
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
        this
    )

    private lateinit var mCameraSource: Camera2Source
    private var mIsRecordingVideo = false
    private var mIsUsingFrontCamera = false
    private val cameraError: Camera2Source.CameraError = object : Camera2Source.CameraError {
        override fun onCameraOpened() {}

        override fun onCameraDisconnected() {}

        override fun onCameraError(err: Int) {
            context.runOnUiThread {
                val dialogBuilder: AlertDialog.Builder = AlertDialog.Builder(context)
                dialogBuilder.setCancelable(false)
                dialogBuilder.setTitle(R.string.camera_error)
                dialogBuilder.setMessage("Error Number: $err")
                dialogBuilder.setPositiveButton(R.string.ok_button) { _, _ ->}
                val alertDialog = dialogBuilder.create()
                alertDialog.show()
            }
        }
    }

    private val cameraVideoStartCallback: Camera2Source.VideoStartCallback = object : Camera2Source.VideoStartCallback {
        override fun onVideoStart() {
            mIsRecordingVideo = true
            Toast.makeText(context, "Recording Started", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraVideoStopCallback: Camera2Source.VideoStopCallback = object : Camera2Source.VideoStopCallback {
        override fun onVideoStop(videoFile: String?) {
            mIsRecordingVideo = true
            Toast.makeText(context, "Recording Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraVideoErrorCallback: Camera2Source.VideoErrorCallback = object : Camera2Source.VideoErrorCallback {
        override fun onVideoError(error: String?) {
            Toast.makeText(context, "Video Error: $error", Toast.LENGTH_LONG).show()
        }
    }

    init {
        createCameraSourceBack()
    }

    private fun createCameraSourceBack() {
        mIsUsingFrontCamera = false
        mCameraSource = Camera2Source().apply {
            mFacing = Camera2Source.CAMERA_FACING_BACK
            mFocusMode = Camera2Source.CAMERA_AF_CONTINUOUS_PICTURE
            mFlashMode = Camera2Source.CAMERA_FLASH_AUTO
            mContext = context
            mFrameProcessorRunnable = FrameProcessingRunnable(imageProcessor, graphicOverlay)
        }

        startCamera()
    }

    private fun createCameraSourceFront() {
        mIsUsingFrontCamera = true
        mCameraSource = Camera2Source().apply {
            mFacing = Camera2Source.CAMERA_FACING_FRONT
            mFocusMode = Camera2Source.CAMERA_AF_CONTINUOUS_PICTURE
            mFlashMode = Camera2Source.CAMERA_FLASH_AUTO
            mContext = context
            mFrameProcessorRunnable = FrameProcessingRunnable(imageProcessor, graphicOverlay)
        }

        startCamera()
    }


    fun startCamera() {
        preview?.start(mCameraSource, graphicOverlay, cameraError)
    }

    fun stopCamera() {
        preview?.stop()
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
        mCameraSource.recordVideo(cameraVideoStartCallback, cameraVideoStopCallback, cameraVideoErrorCallback, false)
    }

    fun onSwingFinished(fullSwingData: FullSwing) {
    }

    fun flipCamera() {
        stopCamera()
        if (mIsUsingFrontCamera) {
            createCameraSourceBack()
        } else {
            createCameraSourceFront()
        }
    }

    companion object {
        private const val TAG = "Camera2Basic"
    }
}