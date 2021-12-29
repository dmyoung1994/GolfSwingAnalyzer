package com.example.golfswinganalyzer.util

import android.content.Context
import android.graphics.Point
import android.media.Image
import android.util.Size
import android.view.Window
import android.view.WindowManager
import java.nio.ByteBuffer

class ImageUtils {
    companion object {
        fun quarterNV21(data: ByteArray, iWidth: Int, iHeight: Int): ByteBuffer {
            val yuv = ByteArray(iWidth/4 * iHeight/4 * 3/2)
            var index = 0
            // halve yuma
            for (i in 0..iHeight step 4) {
                for (j in 0..iWidth step 4) {
                    yuv[index++] = data[i * iWidth + j]
                }
            }

            // halve U and V color components
//            for (i in 0..(iHeight / 2) step 4) {
//                for (j in 0..iWidth step 8) {
//                    yuv[index++] = data[(iWidth * iHeight) + (i * iWidth) + j]
//                    yuv[index++] = data[(iWidth * iHeight) + (i * iWidth) + (j + 1)]
//                }
//            }

            return ByteBuffer.wrap(yuv)
        }

        fun getScreenRatio(context: Context) : Float {
            val metrics = context.resources.displayMetrics
            return metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
        }

        fun getScreenSize(context: Context) : Point {
            val display = context.display
            val point = Point()
            display!!.getRealSize(point)

            return point
        }

        fun getScreenRotation(context: Context) : Int {
            return context.display!!.rotation
        }

        fun sizeToImageSize(sizes: Array<Size>) : Array<com.google.android.gms.common.images.Size> {
            val imageSizes: MutableList<com.google.android.gms.common.images.Size> = mutableListOf()
            for (size in sizes) {
                imageSizes.add(com.google.android.gms.common.images.Size(size.width, size.height))
            }

            return imageSizes.toTypedArray()
        }

        fun convertYUV420888ToNV21(yuvImage: Image) : ByteArray {
            val y: ByteBuffer = yuvImage.planes[0].buffer
            val cb: ByteBuffer = yuvImage.planes[2].buffer
            val yBufferSize = y.remaining()
            val cbBufferSize = cb.remaining()
            val data = ByteArray(yBufferSize + cbBufferSize)
            y.get(data, 0, yBufferSize)
            cb.get(data, yBufferSize, cbBufferSize)
            return data
        }
    }
}