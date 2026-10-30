package com.visper.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object ImageProcessor {

    private const val ASCII_CHARS = "@%#*+=-:. "

    /**
     * Decode and downsample bitmap with zero heap spikes using inJustDecodeBounds.
     * Prevents OutOfMemory errors on 50MP+ device cameras.
     */
    suspend fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = 512,
        reqHeight: Int = 512
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val firstInput = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            firstInput.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            // Use RGB_565 or ARGB_8888; ARGB_8888 is safe for vision models
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val secondInput = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val bitmap = secondInput.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            bitmap
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
     * Converts a bitmap into an ASCII art string for the terminal UI.
     * Renders at roughly terminal width (e.g. 40-50 characters wide).
     */
    fun toAsciiArt(bitmap: Bitmap, targetCols: Int = 42): String {
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        // Characters in terminal are roughly 2x taller than they are wide
        val targetRows = ((targetCols * aspectRatio) * 0.55f).roundToInt().coerceAtLeast(8)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetCols, targetRows, true)
        val sb = StringBuilder()

        for (y in 0 until targetRows) {
            for (x in 0 until targetCols) {
                val pixel = scaled.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                // Luminance formula (ITU-R BT.601)
                val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val charIndex = ((1f - luminance) * (ASCII_CHARS.length - 1)).roundToInt()
                    .coerceIn(0, ASCII_CHARS.length - 1)
                sb.append(ASCII_CHARS[charIndex])
            }
            sb.append("\n")
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }
        return sb.toString()
    }

    /**
     * Converts bitmap into planar normalized float ByteBuffer [1, 3, H, W] for vision models.
     */
    fun toNormalizedFloatBuffer(bitmap: Bitmap, width: Int = 224, height: Int = 224): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val buffer = ByteBuffer.allocateDirect(4 * 3 * width * height).apply {
            order(ByteOrder.nativeOrder())
        }

        val intValues = IntArray(width * height)
        scaled.getPixels(intValues, 0, width, 0, 0, width, height)

        // ImageNet normalization constants: mean = [0.485, 0.456, 0.406], std = [0.229, 0.224, 0.225]
        for (i in 0 until width * height) {
            val pixel = intValues[i]
            val r = (((pixel shr 16) and 0xFF) / 255.0f - 0.485f) / 0.229f
            val g = (((pixel shr 8) and 0xFF) / 255.0f - 0.456f) / 0.224f
            val b = ((pixel and 0xFF) / 255.0f - 0.406f) / 0.225f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()

        if (scaled != bitmap) {
            scaled.recycle()
        }
        return buffer
    }
}
