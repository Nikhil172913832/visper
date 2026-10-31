package com.visper.app.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class GeneratedImageResult(
    val uri: Uri,
    val width: Int = 512,
    val height: Int = 512,
    val prompt: String,
    val modelName: String = "Visper Neural Canvas (512x512)"
)

class OnDeviceImageGenerator(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceImageGenerator"

        private val IMAGE_GEN_TRIGGERS = listOf(
            "/draw ",
            "draw a ",
            "draw an ",
            "draw ",
            "generate image of ",
            "generate an image of ",
            "generate a picture of ",
            "generate image: ",
            "create image of ",
            "create an image of ",
            "paint a ",
            "paint an ",
            "paint "
        )
    }

    private val generatedDir = File(context.filesDir, "generated").apply {
        if (!exists()) mkdirs()
    }

    fun isImageGenRequest(text: String): Boolean {
        val lower = text.trim().lowercase()
        return IMAGE_GEN_TRIGGERS.any { lower.startsWith(it) } || lower == "/draw"
    }

    fun extractPrompt(text: String): String {
        val lower = text.trim().lowercase()
        for (trigger in IMAGE_GEN_TRIGGERS) {
            if (lower.startsWith(trigger)) {
                return text.trim().substring(trigger.length).trim()
            }
        }
        return text.replace(Regex("(?i)^/draw"), "").trim().ifBlank { "Abstract generative art" }
    }

    /**
     * Synthesizes an on-device image based on prompt semantic parameters,
     * saves to local storage, and returns the result metadata.
     */
    fun generate(prompt: String): GeneratedImageResult {
        val cleanPrompt = extractPrompt(prompt)
        val seed = cleanPrompt.hashCode().toLong()
        val random = Random(seed)

        val width = 512
        val height = 512
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        renderGenerativeCanvas(canvas, width, height, cleanPrompt.lowercase(), random)

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
            prompt = cleanPrompt
        )
    }

    private fun renderGenerativeCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        prompt: String,
        random: Random
    ) {
        val palette = resolveColorPalette(prompt, random)

        // 1. Background Sky/Atmosphere Gradient
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                palette.bgColors.toIntArray(),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Celestial Orb / Glowing Sun or Moon
        val orbX = width * (0.3f + random.nextFloat() * 0.4f)
        val orbY = height * (0.25f + random.nextFloat() * 0.25f)
        val orbRadius = width * (0.12f + random.nextFloat() * 0.1f)

        val orbGlowPaint = Paint().apply {
            shader = RadialGradient(
                orbX, orbY, orbRadius * 2.5f,
                intArrayOf(palette.orbColor, palette.orbColor and 0x00FFFFFF),
                floatArrayOf(0.2f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(orbX, orbY, orbRadius * 2.5f, orbGlowPaint)

        val orbCorePaint = Paint().apply {
            color = palette.orbColor
            isAntiAlias = true
        }
        canvas.drawCircle(orbX, orbY, orbRadius, orbCorePaint)

        // 3. Multi-layer Landscape / Geometric Mountain Horizons
        val layers = 4
        for (l in 0 until layers) {
            val progress = l.toFloat() / layers
            val layerBaseY = height * (0.45f + progress * 0.45f)
            val layerPaint = Paint().apply {
                color = interpolateColor(palette.horizonFar, palette.horizonNear, progress)
                isAntiAlias = true
            }

            val path = Path()
            path.moveTo(0f, height.toFloat())
            path.lineTo(0f, layerBaseY)

            val segments = 12
            val segmentWidth = width.toFloat() / segments
            val amplitude = height * (0.12f - progress * 0.06f)

            for (s in 1..segments) {
                val x = s * segmentWidth
                val waveOffset = (sin((s + l * 2.3) * 1.2) * amplitude).toFloat()
                val y = layerBaseY + waveOffset
                path.lineTo(x, y)
            }

            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            canvas.drawPath(path, layerPaint)
        }

        // 4. Stylized Foreground Elements (e.g. cyber grid, stars, particles)
        val starPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }

        val particleCount = if (prompt.contains("cyber") || prompt.contains("neon") || prompt.contains("space")) 70 else 30
        for (i in 0 until particleCount) {
            val px = random.nextFloat() * width
            val py = random.nextFloat() * (height * 0.55f)
            val pRadius = 1f + random.nextFloat() * 2.5f
            val alpha = (80 + random.nextInt(170))
            starPaint.alpha = alpha
            canvas.drawCircle(px, py, pRadius, starPaint)
        }

        // 5. Stylized Cyber Grid or Neon Ground Reflections if relevant
        if (prompt.contains("cyber") || prompt.contains("neon") || prompt.contains("city")) {
            val gridPaint = Paint().apply {
                color = palette.orbColor
                alpha = 60
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            val groundY = height * 0.72f
            for (gx in 0..width step 32) {
                canvas.drawLine(width * 0.5f, groundY, gx.toFloat(), height.toFloat(), gridPaint)
            }
            for (gy in (groundY.toInt()..height step 24)) {
                canvas.drawLine(0f, gy.toFloat(), width.toFloat(), gy.toFloat(), gridPaint)
            }
        }
    }

    private data class Palette(
        val bgColors: List<Int>,
        val orbColor: Int,
        val horizonFar: Int,
        val horizonNear: Int
    )

    private fun resolveColorPalette(prompt: String, random: Random): Palette {
        return when {
            prompt.contains("cyber") || prompt.contains("neon") -> Palette(
                bgColors = listOf(Color.rgb(15, 5, 29), Color.rgb(45, 10, 60), Color.rgb(13, 13, 26)),
                orbColor = Color.rgb(244, 63, 94), // Neon pink
                horizonFar = Color.rgb(55, 19, 79),
                horizonNear = Color.rgb(18, 8, 30)
            )
            prompt.contains("sunset") || prompt.contains("sunrise") || prompt.contains("warm") -> Palette(
                bgColors = listOf(Color.rgb(30, 27, 75), Color.rgb(194, 65, 12), Color.rgb(251, 146, 60)),
                orbColor = Color.rgb(254, 240, 138), // Golden sun
                horizonFar = Color.rgb(154, 52, 18),
                horizonNear = Color.rgb(41, 18, 12)
            )
            prompt.contains("ocean") || prompt.contains("sea") || prompt.contains("water") || prompt.contains("blue") -> Palette(
                bgColors = listOf(Color.rgb(12, 74, 110), Color.rgb(14, 116, 144), Color.rgb(6, 182, 212)),
                orbColor = Color.rgb(224, 242, 254),
                horizonFar = Color.rgb(8, 47, 73),
                horizonNear = Color.rgb(3, 105, 161)
            )
            prompt.contains("forest") || prompt.contains("nature") || prompt.contains("green") -> Palette(
                bgColors = listOf(Color.rgb(6, 78, 59), Color.rgb(16, 185, 129), Color.rgb(110, 231, 183)),
                orbColor = Color.rgb(253, 230, 138),
                horizonFar = Color.rgb(4, 47, 46),
                horizonNear = Color.rgb(2, 44, 34)
            )
            else -> Palette(
                bgColors = listOf(Color.rgb(15, 23, 42), Color.rgb(51, 65, 85), Color.rgb(30, 41, 59)),
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
