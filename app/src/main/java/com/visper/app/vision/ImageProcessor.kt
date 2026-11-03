package com.visper.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

object ImageProcessor {

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                java.io.File(uri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            try {
                context.contentResolver.openInputStream(uri)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Decode and downsample bitmap with zero heap spikes using inJustDecodeBounds.
     * Corrects EXIF rotation to ensure camera photos and screenshots are upright.
     */
    suspend fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = 1280,
        reqHeight: Int = 1280
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val firstInput = openStream(context, uri) ?: return@withContext null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            firstInput.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val secondInput = openStream(context, uri) ?: return@withContext null
            val bitmap = secondInput.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@withContext null

            // Read and apply EXIF orientation
            val orientation = try {
                openStream(context, uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Resizes bitmap down to max dimension [maxDim] and encodes to JPEG Base64
     * for native multimodal vision inference (mmproj).
     * Uses 896px max dimension (divisible by 28 ViT patch grid) for sharp document and text recognition.
     */
    fun bitmapToBase64Jpeg(bitmap: Bitmap, maxDim: Int = 896, quality: Int = 90): String {
        val width = bitmap.width
        val height = bitmap.height
        val maxOriginal = maxOf(width, height)
        val scaled = if (maxOriginal > maxDim) {
            val scale = maxDim.toFloat() / maxOriginal
            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (scaled != bitmap) {
            scaled.recycle()
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
