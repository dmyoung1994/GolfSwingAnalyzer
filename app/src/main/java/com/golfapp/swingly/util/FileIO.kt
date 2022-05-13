package com.golfapp.swingly.util

import android.app.Activity
import android.content.ContentValues
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions

class FileIO {
    companion object {
        fun mediaStoreOutput(fileName: String, fileType: String, uuid: String, context: Activity) : MediaStoreOutputOptions {
            val contentVales = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, fileType)
                put(MediaStore.Video.Media.DOCUMENT_ID, uuid)
            }
            return MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentVales)
                .build()
        }
    }
}