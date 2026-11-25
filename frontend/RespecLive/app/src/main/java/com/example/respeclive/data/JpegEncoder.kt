package com.example.respeclive.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 일기 사진을 서버 규칙에 맞춰 JPEG로 바꿈: 긴 변 2048px, 품질 85, 10MB 이하.
 * HEIC·PNG 등 어떤 형식으로 골라도 JPEG로 보냄 (서버는 image/jpeg만 받음). 휴대폰 사진의 회전 정보도 반영.
 */
object JpegEncoder {
    private const val TAG = "JpegEncoder"
    private const val LONG_SIDE = 2048
    private const val QUALITY = 85
    private const val MAX_BYTES = 10 * 1024 * 1024

    suspend fun fromUri(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            // 큰 사진은 먼저 2배수로 줄여 읽고, 마지막에 긴 변을 2048px로 정확히 맞춤
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null

            val rotation = resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            val scale = minOf(1f, LONG_SIDE.toFloat() / maxOf(raw.width, raw.height))
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            val bitmap = if (scale < 1f || rotation != 0f) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
            } else raw

            // 10MB를 넘으면 품질을 낮춰 다시 (긴 변 2048px이면 보통 1~2MB)
            var quality = QUALITY
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                quality -= 10
            } while (bytes.size > MAX_BYTES && quality >= 40)
            bitmap.recycle()
            bytes.takeIf { it.size <= MAX_BYTES }
        } catch (e: Exception) {
            Log.w(TAG, "사진을 JPEG로 바꾸지 못했어요: ${e.message}")
            null
        }
    }
}
