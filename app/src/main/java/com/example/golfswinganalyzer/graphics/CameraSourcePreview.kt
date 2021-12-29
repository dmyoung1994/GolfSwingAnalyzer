package com.example.golfswinganalyzer.graphics

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import com.example.golfswinganalyzer.camera.Camera2Source
import com.example.golfswinganalyzer.camera.Camera2Source.Companion.CAMERA_FACING_BACK
import com.example.golfswinganalyzer.util.ImageUtils
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class CameraSourcePreview : ViewGroup {
    private val TAG = "CameraSourcePreview"
    private var mSurfaceView: SurfaceView = SurfaceView(context)
    private var mAutoFitTextureView: AutoFitTextureView
    private lateinit var  mCameraSource: Camera2Source
    private lateinit var mCameraErrorHandler: Camera2Source.CameraError
    private var mOverlay: GraphicOverlay? = null

    private var mStartRequested = false
    private var mSurfaceAvailable = false
    private var mViewAdded = false

    private var mScreenSize: Point = ImageUtils.getScreenSize(context)
    private var mScreenRotation = ImageUtils.getScreenRotation(context)

    private val mSurfaceViewListener: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(surface: SurfaceHolder) {
            mSurfaceAvailable = true
            mOverlay?.bringToFront()
            try {
                startIfReady()
            } catch(e: IOException) {
                Log.e(TAG, "Could not start camera source.", e)
            }
        }

        override fun surfaceChanged(surface: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(surface: SurfaceHolder) {
            mSurfaceAvailable = false
        }
    }

    private val mSurfaceTextureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            mSurfaceAvailable = true
            mOverlay?.bringToFront()
            try {
                startIfReady()
            } catch(e: IOException) {
                Log.e(TAG, "Could not start camera source.", e)
            }
        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
            mSurfaceAvailable = false
            return true
        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {}
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}
    }

    constructor(context: Context) : super(context) {
        mSurfaceView.holder.addCallback(mSurfaceViewListener)
        mAutoFitTextureView = AutoFitTextureView(context)
        mAutoFitTextureView.surfaceTextureListener = mSurfaceTextureListener
    }

    constructor(context: Context, attributeSet: AttributeSet) : super(context, attributeSet) {
        mSurfaceView.holder.addCallback(mSurfaceViewListener)
        mAutoFitTextureView = AutoFitTextureView(context)
        mAutoFitTextureView.surfaceTextureListener = mSurfaceTextureListener
    }

    private fun startIfReady() {
        if (mStartRequested && mSurfaceAvailable) {
            try {
                mCameraSource.start(mAutoFitTextureView, mScreenRotation, mCameraErrorHandler)
                val size = mCameraSource.mPreviewSize
                val min = min(size.width, size.height)
                val max = max(size.width, size.height)

                val isFlipped = mCameraSource.mFacing != CAMERA_FACING_BACK
                // FOR GRAPHIC OVERLAY, THE PREVIEW SIZE WAS REDUCED TO QUARTER
                // IN ORDER TO PREVENT CPU OVERLOAD
                mOverlay?.setImageSourceInfo(min/4, max/4, isFlipped)
                mOverlay?.clear()
                mStartRequested = false
            } catch (e: SecurityException) {
                Log.d(TAG, "Security Exception!", e)
            }
        }
    }

    private fun start(cameraSource: Camera2Source, errorHandler: Camera2Source.CameraError) {
        mCameraSource = cameraSource
        mCameraErrorHandler = errorHandler
        mStartRequested = true
        if (!mViewAdded) {
            addView(mAutoFitTextureView)
            mViewAdded = true
        }

        try {
            startIfReady()
        } catch (e: IOException) {
            Log.e(TAG, "Could not start camera source.", e)
        }
    }

    fun start(cameraSource: Camera2Source, overlay: GraphicOverlay?, errorHandler: Camera2Source.CameraError) {
        mOverlay = overlay
        start(cameraSource, errorHandler)
    }

    fun stop() {
        mStartRequested = false
        mCameraSource.stop()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var height = 720
        if (mCameraSource.isPreviewReady()) {
            val size = mCameraSource.mPreviewSize
            height = size.width
        }

        val newWidth = (height * mScreenSize.x) / mScreenSize.y
        val layoutWidth = right - left
        val layoutHeight = bottom - top
        var childWidth = layoutWidth
        var childHeight = (layoutWidth.toFloat() / newWidth.toFloat() * height).toInt()
        // If height is too tall using fit width, use height instead
        if (childHeight > layoutHeight) {
            childHeight = layoutHeight
            childWidth = (layoutHeight.toFloat() / height * newWidth).toInt()
        }

        for (i in 0..childCount) {
            getChildAt(i)?.layout(0,0, childWidth, childHeight)
        }

        try {
            startIfReady()
        } catch (e: IOException) {
            Log.e(TAG, "Could not start camera source.", e)
        }
    }
}