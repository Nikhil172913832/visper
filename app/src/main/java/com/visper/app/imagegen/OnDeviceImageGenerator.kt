package com.visper.app.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.core.graphics.PathParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.sin
import kotlin.random.Random

data class GeneratedImageResult(
    val uri: Uri,
    val width: Int = 512,
    val height: Int = 512,
    val prompt: String,
    val subject: String? = null,
    val modelName: String = "Visper Generative Canvas (512x512)"
)

class OnDeviceImageGenerator(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceImageGenerator"

        private val INTENT_REGEX = Regex(
            """^(?:/(?:draw|paint|render|canvas)(?:\s+(.*))?|(?:can\s+you\s+|please\s+|could\s+you\s+|i\s+want\s+you\s+to\s+)?(?:draw|paint|sketch|illustrate)(?:\s+(?:me\s+)?(?:an?\s+)?(.*))?|(?:can\s+you\s+|please\s+|could\s+you\s+|i\s+want\s+you\s+to\s+)?(?:generate|create|render|make)\s+(?:me\s+)?(?:an?\s+)?(?:image|picture|photo|painting|drawing|illustration|sketch|art|artwork)\s*(?:of|for|with|about|showing|depicting)?\s*:?\s*(.*))$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val generatedDir = File(context.filesDir, "generated").apply {
        if (!exists()) mkdirs()
    }

    fun isImageGenRequest(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return INTENT_REGEX.matches(trimmed)
    }

    fun extractPrompt(text: String): String {
        val trimmed = text.trim()
        val match = INTENT_REGEX.matchEntire(trimmed) ?: return cleanPromptSubject(trimmed)
        val extracted = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        return cleanPromptSubject(extracted ?: trimmed)
    }

    fun cleanPromptSubject(raw: String): String {
        var s = raw.trim()
        // Strip command slashes
        s = s.replace(Regex("""^/(?:draw|render|paint|canvas)\s*""", RegexOption.IGNORE_CASE), "")
        // Strip conversational prefixes
        s = s.replace(
            Regex(
                """^(?:can\s+you\s+|could\s+you\s+|please\s+|i\s+want\s+you\s+to\s+)?(?:draw|paint|sketch|illustrate|render|generate|create|make)\s+(?:me\s+)?(?:an?\s+)?(?:image|picture|photo|painting|drawing|illustration|sketch|art|artwork)?\s*(?:of|for|with|about|showing|depicting)?\s*:?\s*""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        // Strip leading articles
        s = s.replace(Regex("""^(?:an?\s+|the\s+)""", RegexOption.IGNORE_CASE), "")
        s = s.trim().trim('"', '\'', '.', '?', '!')
        return if (s.isNotBlank()) s else "generative art"
    }

    /**
     * Synthesizes an on-device image based on prompt semantic parameters,
     * saves to local storage, and returns the result metadata.
     */
    fun generate(prompt: String): GeneratedImageResult {
        val cleanPrompt = extractPrompt(prompt)
        val matchedSubject = VectorLibrary.matchSubject(cleanPrompt)
        val subjectKey = matchedSubject?.first
        val displaySubject = matchedSubject?.second ?: cleanPrompt

        val seed = cleanPrompt.hashCode().toLong()
        val random = Random(seed)

        val width = 512
        val height = 512
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        renderArtwork(
            canvas = canvas,
            width = width,
            height = height,
            prompt = cleanPrompt.lowercase(),
            subjectKey = subjectKey,
            random = random
        )

        // Save bitmap to internal file
        val file = File(generatedDir, "img_${UUID.randomUUID()}.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save generated image", e)
        }

        return GeneratedImageResult(
            uri = Uri.fromFile(file),
            width = width,
            height = height,
            prompt = cleanPrompt,
            subject = displaySubject
        )
    }

    private fun renderArtwork(
        canvas: Canvas,
        width: Int,
        height: Int,
        prompt: String,
        subjectKey: String?,
        random: Random
    ) {
        val palette = resolveColorPalette(prompt, subjectKey, random)

        // 1. Background Atmosphere Gradient
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                palette.bgColors.toIntArray(),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Celestial Orb / Glowing Sun or Moon
        val orbX = width * (if (subjectKey != null) 0.72f else (0.35f + random.nextFloat() * 0.3f))
        val orbY = height * (0.24f + random.nextFloat() * 0.1f)
        val orbRadius = width * (0.13f + random.nextFloat() * 0.06f)

        val orbGlowPaint = Paint().apply {
            shader = RadialGradient(
                orbX, orbY, orbRadius * 2.6f,
                intArrayOf(palette.orbColor, palette.orbColor and 0x00FFFFFF),
                floatArrayOf(0.18f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(orbX, orbY, orbRadius * 2.6f, orbGlowPaint)

        val orbCorePaint = Paint().apply {
            color = palette.orbColor
            isAntiAlias = true
        }
        canvas.drawCircle(orbX, orbY, orbRadius, orbCorePaint)

        // 3. Stars / Atmospheric Dust Particles
        val starPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        val starCount = if (prompt.contains("cyber") || prompt.contains("neon") || prompt.contains("space")) 65 else 35
        for (i in 0 until starCount) {
            val px = random.nextFloat() * width
            val py = random.nextFloat() * (height * 0.65f)
            val pRadius = 0.8f + random.nextFloat() * 2.0f
            starPaint.alpha = 50 + random.nextInt(180)
            canvas.drawCircle(px, py, pRadius, starPaint)
        }

        // 4. Distant Horizon / Mountains or Waves
        renderHorizon(canvas, width, height, palette, subjectKey != null)

        // 5. Subject Vector Silhouette Rendering (if recognized subject)
        if (subjectKey != null) {
            val pathData = VectorLibrary.SUBJECT_PATHS[subjectKey]
            if (!pathData.isNullOrBlank()) {
                renderSubject(canvas, width, height, pathData, subjectKey, palette)
            }
        }

        // 6. Foreground Ambient Particles / Embers
        val emberPaint = Paint().apply {
            color = palette.orbColor
            isAntiAlias = true
        }
        for (i in 0 until 18) {
            val ex = random.nextFloat() * width
            val ey = height * 0.6f + random.nextFloat() * (height * 0.38f)
            val er = 1.0f + random.nextFloat() * 2.2f
            emberPaint.alpha = 60 + random.nextInt(150)
            canvas.drawCircle(ex, ey, er, emberPaint)
        }

        // 7. Subtle Framing Outline
        val framePaint = Paint().apply {
            color = Color.WHITE
            alpha = 24
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, framePaint)
    }

    private fun renderHorizon(
        canvas: Canvas,
        width: Int,
        height: Int,
        palette: Palette,
        hasSubject: Boolean
    ) {
        // Multi-layer hills / terrain
        val layers = if (hasSubject) 2 else 4
        for (l in 0 until layers) {
            val progress = (l + 1).toFloat() / (layers + 1)
            val baseY = height * (if (hasSubject) (0.64f + progress * 0.16f) else (0.45f + progress * 0.45f))
            val layerPaint = Paint().apply {
                color = interpolateColor(palette.horizonFar, palette.horizonNear, progress)
                isAntiAlias = true
            }

            val path = Path()
            path.moveTo(0f, height.toFloat())
            path.lineTo(0f, baseY)

            val segments = 12
            val segmentWidth = width.toFloat() / segments
            val amplitude = height * (if (hasSubject) 0.04f else (0.12f - progress * 0.05f))

            for (s in 1..segments) {
                val x = s * segmentWidth
                val waveOffset = (sin((s + l * 2.3) * 1.1) * amplitude).toFloat()
                val y = baseY + waveOffset
                path.lineTo(x, y)
            }

            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            canvas.drawPath(path, layerPaint)
        }
    }

    @Suppress("DEPRECATION")
    private fun renderSubject(
        canvas: Canvas,
        width: Int,
        height: Int,
        pathData: String,
        subjectKey: String,
        palette: Palette
    ) {
        try {
            val rawPath = PathParser.createPathFromPathData(pathData) ?: return
            val bounds = RectF()
            rawPath.computeBounds(bounds, true)
            if (bounds.width() <= 0f || bounds.height() <= 0f) return

            // Determine category placement
            val isAirborne = subjectKey in setOf("EAGLE", "ROCKET", "AIRPLANE", "STAR", "MOON", "BUTTERFLY")
            val isCenteredItem = subjectKey in setOf("HEART", "SWORD", "CROWN", "SHIELD", "GUITAR", "COFFEE", "BOOK", "CAMERA", "FLOWER", "SKULL", "FLAME")

            val targetSize = width * (if (isCenteredItem) 0.44f else if (isAirborne) 0.48f else 0.52f)
            val maxDim = maxOf(bounds.width(), bounds.height())
            val scale = targetSize / maxDim

            val matrix = Matrix()
            matrix.postTranslate(-bounds.left, -bounds.top)
            matrix.postScale(scale, scale)

            val scaledW = bounds.width() * scale
            val scaledH = bounds.height() * scale

            val targetX = (width - scaledW) / 2f
            val targetY = when {
                isAirborne -> height * 0.28f - scaledH / 2f
                isCenteredItem -> height * 0.50f - scaledH / 2f
                else -> height * 0.82f - scaledH // Grounded on terrain
            }

            matrix.postTranslate(targetX, targetY)

            val transformedPath = Path()
            rawPath.transform(matrix, transformedPath)

            // Subject Center
            val centerX = targetX + scaledW / 2f
            val centerY = targetY + scaledH / 2f

            // 1. Atmospheric Backlight Aura
            val auraRadius = maxOf(scaledW, scaledH) * 0.85f
            val auraPaint = Paint().apply {
                shader = RadialGradient(
                    centerX, centerY, auraRadius,
                    intArrayOf(palette.orbColor and 0x55FFFFFF, palette.orbColor and 0x00FFFFFF),
                    floatArrayOf(0.2f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(centerX, centerY, auraRadius, auraPaint)

            // 2. Ground Contact Shadow (for grounded subjects)
            if (!isAirborne && !isCenteredItem) {
                val shadowPaint = Paint().apply {
                    color = Color.argb(120, 0, 0, 0)
                    isAntiAlias = true
                }
                val shadowWidth = scaledW * 0.7f
                val shadowHeight = 16f
                val shadowRect = RectF(
                    centerX - shadowWidth / 2f,
                    targetY + scaledH - 8f,
                    centerX + shadowWidth / 2f,
                    targetY + scaledH + shadowHeight
                )
                canvas.drawOval(shadowRect, shadowPaint)
            }

            // 3. Subject Silhouette Fill (Deep Obsidian Charcoal)
            val fillPaint = Paint().apply {
                color = Color.rgb(15, 17, 24)
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawPath(transformedPath, fillPaint)

            // 4. Highlight Rim Stroke (Accent Light Contour)
            val rimPaint = Paint().apply {
                color = palette.orbColor
                alpha = 115
                style = Paint.Style.STROKE
                strokeWidth = 2.2f
                isAntiAlias = true
            }
            canvas.drawPath(transformedPath, rimPaint)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to render subject vector path", e)
        }
    }

    private data class Palette(
        val bgColors: List<Int>,
        val orbColor: Int,
        val horizonFar: Int,
        val horizonNear: Int
    )

    private fun resolveColorPalette(prompt: String, subjectKey: String?, random: Random): Palette {
        return when {
            prompt.contains("cyber") || prompt.contains("neon") || subjectKey == "ROBOT" -> Palette(
                bgColors = listOf(Color.rgb(18, 8, 38), Color.rgb(58, 12, 75), Color.rgb(15, 12, 28)),
                orbColor = Color.rgb(244, 63, 94), // Neon pink
                horizonFar = Color.rgb(65, 20, 88),
                horizonNear = Color.rgb(18, 10, 32)
            )
            prompt.contains("sunset") || prompt.contains("sunrise") || prompt.contains("warm") || prompt.contains("golden") || subjectKey == "FLAME" -> Palette(
                bgColors = listOf(Color.rgb(36, 18, 65), Color.rgb(217, 83, 30), Color.rgb(251, 146, 60)),
                orbColor = Color.rgb(254, 240, 138), // Golden sun
                horizonFar = Color.rgb(154, 52, 18),
                horizonNear = Color.rgb(41, 18, 12)
            )
            prompt.contains("ocean") || prompt.contains("sea") || prompt.contains("water") || prompt.contains("blue") || subjectKey in setOf("FISH", "BOAT") -> Palette(
                bgColors = listOf(Color.rgb(10, 45, 80), Color.rgb(14, 116, 144), Color.rgb(6, 182, 212)),
                orbColor = Color.rgb(224, 242, 254),
                horizonFar = Color.rgb(8, 47, 73),
                horizonNear = Color.rgb(3, 35, 60)
            )
            prompt.contains("forest") || prompt.contains("nature") || prompt.contains("green") || subjectKey == "TREE" -> Palette(
                bgColors = listOf(Color.rgb(10, 48, 40), Color.rgb(16, 145, 105), Color.rgb(52, 211, 153)),
                orbColor = Color.rgb(253, 230, 138),
                horizonFar = Color.rgb(6, 52, 45),
                horizonNear = Color.rgb(4, 32, 28)
            )
            subjectKey == "HORSE" -> Palette(
                // Dramatic Twilight / Golden Mist for Horse
                bgColors = listOf(Color.rgb(28, 25, 50), Color.rgb(180, 83, 9), Color.rgb(245, 158, 11)),
                orbColor = Color.rgb(254, 243, 199),
                horizonFar = Color.rgb(120, 53, 15),
                horizonNear = Color.rgb(24, 24, 30)
            )
            else -> Palette(
                bgColors = listOf(Color.rgb(15, 23, 42), Color.rgb(40, 55, 78), Color.rgb(25, 33, 50)),
                orbColor = Color.rgb(96, 165, 250), // Cyan / Blue glow
                horizonFar = Color.rgb(30, 41, 59),
                horizonNear = Color.rgb(15, 23, 42)
            )
        }
    }

    private fun interpolateColor(colorA: Int, colorB: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val aA = Color.alpha(colorA)
        val rA = Color.red(colorA)
        val gA = Color.green(colorA)
        val bA = Color.blue(colorA)

        val aB = Color.alpha(colorB)
        val rB = Color.red(colorB)
        val gB = Color.green(colorB)
        val bB = Color.blue(colorB)

        return Color.argb(
            (aA + (aB - aA) * f).toInt(),
            (rA + (rB - rA) * f).toInt(),
            (gA + (gB - gA) * f).toInt(),
            (bA + (bB - bA) * f).toInt()
        )
    }
}
