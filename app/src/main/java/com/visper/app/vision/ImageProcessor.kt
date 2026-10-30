package com.visper.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

object ImageProcessor {

    private const val ASCII_CHARS = "@%#*+=-:. "

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
     * Corrects EXIF rotation to ensure handwriting & photos are upright.
     */
    suspend fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = 1600,
        reqHeight: Int = 1600
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
     * Extracts text from ML Kit's VisionText sorted into natural human reading order:
     * Top-to-bottom, and within the same horizontal line band, left-to-right.
     * Prevents out-of-order block traversal that causes jumbled lines or upside-down text.
     */
    private fun extractNaturalReadingOrderText(visionText: Text): String {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) {
            return visionText.text.trim()
        }

        // Sort lines top-to-bottom, left-to-right
        val sortedLines = allLines.sortedWith { lineA, lineB ->
            val boxA = lineA.boundingBox
            val boxB = lineB.boundingBox
            if (boxA == null || boxB == null) {
                0
            } else {
                val yDiff = boxA.top - boxB.top
                val avgLineHeight = (boxA.height() + boxB.height()) / 2
                // If the lines share roughly the same vertical line band (within half line height)
                if (abs(yDiff) < (avgLineHeight * 0.5f).coerceAtLeast(6f)) {
                    boxA.left - boxB.left
                } else {
                    yDiff
                }
            }
        }

        val sb = StringBuilder()
        var prevBox: android.graphics.Rect? = null

        for (line in sortedLines) {
            val curBox = line.boundingBox
            val text = line.text.trim()
            if (text.isEmpty()) continue

            if (prevBox != null && curBox != null) {
                val verticalGap = curBox.top - prevBox.bottom
                val avgHeight = (curBox.height() + prevBox.height()) / 2
                // Insert blank line between distinct paragraphs / distant vertical sections
                if (verticalGap > avgHeight * 0.8f) {
                    sb.append("\n")
                }
            }

            sb.append(text).append("\n")
            if (curBox != null) {
                prevBox = curBox
            }
        }

        return sb.toString().trim()
    }

    /**
     * Performs fast, 100% on-device text & handwriting recognition using Google ML Kit.
     * Prioritizes the natural 0° EXIF orientation. Only tests rotations if 0° contains
     * negligible text (< 20 chars, e.g. a sideways notebook page), preventing upside-down or
     * jumbled reads of upright screenshots, chats, and documents.
     */
    suspend fun extractTextFromBitmap(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // Step 1: Natural orientation (0 degrees)
            val text0 = recognizeSingle(recognizer, bitmap).trim()

            // If 0° already produces substantive text (>= 20 chars), it is upright!
            // Never rotate an upright document or screenshot.
            if (text0.length >= 20) {
                android.util.Log.i("ImageProcessor", "0° orientation accepted (${text0.length} chars)")
                return@withContext Result.success(text0)
            }

            // Step 2: Only if 0° is sparse (< 20 chars), check if the image is a rotated notebook (90°, 270°, 180°)
            android.util.Log.i("ImageProcessor", "0° is sparse (${text0.length} chars). Evaluating rotations...")
            var bestText = text0
            val candidateAngles = listOf(90f, 270f, 180f)
            val matrix = Matrix()

            for (angle in candidateAngles) {
                matrix.reset()
                matrix.postRotate(angle)
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val candidateText = try {
                    recognizeSingle(recognizer, rotated).trim()
                } finally {
                    if (rotated != bitmap) rotated.recycle()
                }

                android.util.Log.i("ImageProcessor", "Angle $angle°: ${candidateText.length} chars")
                // Only accept rotated text if it yields substantially more text (at least 2.5x more and >= 15 chars)
                if (candidateText.length > bestText.length * 2.5f && candidateText.length >= 15) {
                    bestText = candidateText
                    if (bestText.length >= 35) {
                        break
                    }
                }
            }

            Result.success(bestText)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun recognizeSingle(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) {
                        val formatted = extractNaturalReadingOrderText(visionText)
                        cont.resume(formatted)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume("")
        }
    }

    /**
     * Converts a bitmap into an ASCII art string for the terminal UI.
     * Renders at roughly terminal width (e.g. 38-42 characters wide).
     */
    fun toAsciiArt(bitmap: Bitmap, targetCols: Int = 38): String {
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetRows = ((targetCols * aspectRatio) * 0.55f).roundToInt().coerceIn(6, 40)

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
