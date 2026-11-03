package com.visper.app.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR engine powered by Google ML Kit.
 * Extracts high-fidelity text blocks from documents, signs, receipts, and photos
 * in 100-200ms with zero network connectivity.
 */
object OcrExtractor {

    private const val TAG = "OcrExtractor"

    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val detected = visionText.text.trim()
                    Log.i(TAG, "ML Kit OCR extracted ${detected.length} chars (${visionText.textBlocks.size} blocks)")
                    if (cont.isActive) {
                        cont.resume(detected)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit OCR extraction failed", e)
                    if (cont.isActive) {
                        cont.resume("")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize ML Kit text recognition", e)
            if (cont.isActive) {
                cont.resume("")
            }
        }
    }
}
