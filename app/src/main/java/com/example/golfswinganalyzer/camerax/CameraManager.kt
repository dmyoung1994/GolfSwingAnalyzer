

package com.example.golfswinganalyzer.camerax

import CircularBuffer
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.arthenica.mobileffmpeg.FFmpeg
import com.example.golfswinganalyzer.MainActivity
import com.example.golfswinganalyzer.dao.FullSwing
import com.example.golfswinganalyzer.graphics.GraphicOverlay
import com.example.golfswinganalyzer.mlkit.SwingDetectorProcessor
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread


// TODO: Assumes a back facing camera. Eventually refactor to be able to set which camera to use


@androidx.camera.core.ExperimentalGetImage
class CameraManager(
    private val context: MainActivity,
    private val finderView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val graphicOverlay: GraphicOverlay,
) {
    private lateinit var cameraExecutor: ExecutorService
    private var cameraSelectorOption = CameraSelector.LENS_FACING_FRONT

    private var cameraProvider: ProcessCameraProvider? = null
    var camera: Camera? = null

    private val recordingSound = MediaActionSound()

    private val circularBuffer: CircularBuffer<Bitmap> = CircularBuffer(150)

    private val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    var imageProcessor = SwingDetectorProcessor(
        context,
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)

            .build(),
        this
    )

    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null

    init {
        createNewExecutor()
    }

    private fun createNewExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            preview = buildPreview()
            imageAnalyzer = buildImageAnalysis().also {
                it.setAnalyzer(cameraExecutor, { image: ImageProxy ->
                    val isImageFlipped = cameraSelectorOption == CameraSelector.LENS_FACING_FRONT
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    if (rotationDegrees == 0 || rotationDegrees == 180) {
                        graphicOverlay.setImageSourceInfo(
                            image.width,
                            image.height,
                            isImageFlipped
                        )
                    } else {
                        graphicOverlay.setImageSourceInfo(
                            image.height,
                            image.width,
                            isImageFlipped
                        )
                    }

                    try {
                        imageProcessor.processImageProxy(image, graphicOverlay)
                    } catch (e: MlKitException) {
                        Log.e(TAG, "Failed to process image: " + e.localizedMessage)
                    }
                })
            }

            val cameraProvider = cameraProvider?: throw IllegalStateException("Camera initialization failed.")
            val cameraSelector = CameraSelector.Builder().apply {
                requireLensFacing(cameraSelectorOption)
            }.build()
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer, preview)

        }, ContextCompat.getMainExecutor(context))
    }

    private fun buildPreview() : Preview {
        return Preview.Builder().build().apply {
            setSurfaceProvider(finderView.surfaceProvider)
        }
    }

    private fun buildImageAnalysis() : ImageAnalysis {
        val builder = ImageAnalysis.Builder().apply {
            setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setTargetResolution(Size(200, 200))
        }
        return builder.build()
    }

    fun onSwingReady() {
        recordingSound.play(MediaActionSound.START_VIDEO_RECORDING)
    }

    fun onPhaseProcessed(bitmap: Bitmap?) {
        if (bitmap != null) {
            circularBuffer.add(bitmap)
        }
    }

    fun onSwingFinished(fullSwingData: FullSwing) {
        recordingSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        thread(start = true) {
            // TODO Display modal that video is saving with spiny wheel
            Log.d("VIDEO CREATOR", "CREATING VIDEO")
            val imagesFromSwing = circularBuffer.toList()
            val imageFilePaths = mutableListOf<String>()

            val tempDir = File(context.cacheDir, "tmp")
            tempDir.mkdir()

            val videoLocation = File(context.filesDir, "videos")

            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyMMddHHmmss")
            val formatted = current.format(formatter)

            val videoName = "${videoLocation.path}/SWING_${formatted}.mp4"

            for ((index, bitmap) in imagesFromSwing.withIndex()) {
                try {
                    val tempImage = File(tempDir, "${formatted}_img${index}.jpg")
                    val fOutStream = FileOutputStream(tempImage)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fOutStream)
                    fOutStream.close()
                    imageFilePaths.add(tempImage.path)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create temp file: $e")
                }
            }

            // Export to video
            val rc =
                FFmpeg.execute("-y -start_number 0 -r 20 -i '${tempDir.path}/${formatted}_img%d.jpg' -crf 25 $videoName")
            Log.d("", "ffmpeg response code $rc")

            for (path in imageFilePaths) {
                val toDelete = File(path)
                if (toDelete.exists()) {
                    toDelete.delete()
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoName)
                // TODO Come up with a better fucking name
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GolfSwingAnalyzer")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(videoCollection, values)
            uri?.let { insertedUri ->
                copyFileData(insertedUri, videoName, resolver)
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(insertedUri, values, null, null)
            }
        }
    }

    private fun copyFileData(destinationUri: Uri, filePathToExport: String, resolver: ContentResolver) {
        resolver.openFileDescriptor(destinationUri, "w").use { parcelFileDescriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(parcelFileDescriptor).write(File(filePathToExport).readBytes())
        }
    }

    fun flipCamera() {
        cameraProvider?.unbindAll()
        cameraSelectorOption =
            if (cameraSelectorOption == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    companion object {
        private const val TAG = "CameraXBasic"
    }
}