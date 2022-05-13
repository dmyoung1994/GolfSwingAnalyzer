package com.golfapp.swingly.activity

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.golfapp.swingly.R
import com.golfapp.swingly.analyzer.FullSwingAnalyzer
import com.golfapp.swingly.camera.CameraManager
import com.golfapp.swingly.dao.FullSwing
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@androidx.camera.core.ExperimentalGetImage
class SwingRecorderFragment : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.swing_recorder_activity)
        createCameraManager()

        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            requestPermissions()
        }

        // On click listeners
        val cameraFlipButton = findViewById<FloatingActionButton>(R.id.cameraFlipButton)
        cameraFlipButton.setOnClickListener{
            handleCameraFlipPressed()
        }
    }

    fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    fun analyzeSwing(fullSwing: FullSwing, swingID: String) = runBlocking {
        val fullSwingAnalyzer = FullSwingAnalyzer(fullSwing)
        val fullSwingData = fullSwingAnalyzer.run()
        val fullSwingJSON = Json.encodeToString(fullSwingData)

    }

    private fun createCameraManager() {
        cameraManager = CameraManager(
            this,
            findViewById(R.id.preview_view),
            findViewById(R.id.graphic_overlay)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraManager.startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleCameraFlipPressed() {
        cameraManager.flipCamera()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    }
}