package com.example.golfswinganalyzer

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.golfswinganalyzer.analyzer.FullSwingAnalyzer
import com.example.golfswinganalyzer.camera.CameraManager
import com.example.golfswinganalyzer.dao.FullSwing
import com.google.android.material.floatingactionbutton.FloatingActionButton

@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createCameraManager()

        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // On click listeners
        val cameraFlipButton = findViewById<FloatingActionButton>(R.id.cameraFlipButton)
        cameraFlipButton.setOnClickListener{
            handleCameraFlipPressed()
        }
    }

    fun analyzeSwing(fullSwing: FullSwing) {
        val fullSwingAnalyzer = FullSwingAnalyzer(fullSwing)
        fullSwingAnalyzer.backSwingAnalyzer.init()
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