package com.visper.app.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import com.visper.app.model.AgentAction

class DeviceActionManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceActionManager"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var isTorchOn = false

    /**
     * Inspects the prompt for agentic device action intent.
     * Returns an AgentAction if detected and executed, or null if no action needed.
     */
    fun evaluateAndExecute(prompt: String): AgentAction? {
        val lower = prompt.lowercase().trim()

        // 1. FLASHLIGHT ON
        if (isFlashlightOnIntent(lower)) {
            val success = setTorch(true)
            return AgentAction(
                actionType = "FLASHLIGHT_ON",
                title = "Flashlight Activated",
                detail = if (success) "Physical rear LED torch turned ON" else "Failed to toggle torch (hardware unavailable)",
                isSuccess = success
            )
        }

        // 2. FLASHLIGHT OFF
        if (isFlashlightOffIntent(lower)) {
            val success = setTorch(false)
            return AgentAction(
                actionType = "FLASHLIGHT_OFF",
                title = "Flashlight Deactivated",
                detail = if (success) "Physical rear LED torch turned OFF" else "Failed to toggle torch",
                isSuccess = success
            )
        }

        // 3. SET TIMER
        val timerSeconds = parseTimerSeconds(lower)
        if (timerSeconds != null && timerSeconds > 0) {
            val success = launchTimer(timerSeconds, "Visper AI Timer")
            val min = timerSeconds / 60
            val sec = timerSeconds % 60
            val timeStr = if (min > 0) "${min}m ${sec}s" else "${sec}s"
            return AgentAction(
                actionType = "TIMER",
                title = "Timer Scheduled",
                detail = if (success) "Set countdown timer for $timeStr" else "Failed to launch system timer",
                isSuccess = success
            )
        }

        // 4. COPY TO CLIPBOARD
        if (lower.startsWith("copy to clipboard") || lower.startsWith("copy this to clipboard")) {
            val colonIdx = prompt.indexOf(':')
            val textToCopy = if (colonIdx != -1) {
                prompt.substring(colonIdx + 1).trim()
            } else {
                prompt.replace(Regex("(?i)^copy (this )?to clipboard"), "").trim()
            }
            if (textToCopy.isNotBlank()) {
                val success = copyToClipboard(textToCopy)
                return AgentAction(
                    actionType = "CLIPBOARD",
                    title = "Copied to Clipboard",
                    detail = "\"${textToCopy.take(40)}${if (textToCopy.length > 40) "…" else ""}\"",
                    isSuccess = success
                )
            }
        }

        // 5. MATH EVALUATION
        if (isMathCalculationIntent(lower)) {
            val mathExpr = extractMathExpression(lower)
            if (mathExpr != null) {
                val result = evaluateMath(mathExpr)
                if (result != null) {
                    return AgentAction(
                        actionType = "CALCULATE",
                        title = "Calculated Result",
                        detail = "$mathExpr = $result",
                        isSuccess = true
                    )
                }
            }
        }

        return null
    }

    fun setTorch(enable: Boolean): Boolean {
        return try {
            val camId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager?.cameraIdList?.firstOrNull()

            if (camId != null) {
                cameraManager?.setTorchMode(camId, enable)
                isTorchOn = enable
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch mode $enable", e)
            false
        }
    }

    fun toggleTorch(): Boolean {
        return setTorch(!isTorchOn)
    }

    fun isTorchActive(): Boolean = isTorchOn

    fun copyToClipboard(text: String, label: String = "Visper AI"): Boolean {
        return try {
            val clip = ClipData.newPlainText(label, text)
            clipboardManager?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
            false
        }
    }

    private fun launchTimer(seconds: Int, label: String): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AlarmClock timer intent", e)
            false
        }
    }

    private fun isFlashlightOnIntent(text: String): Boolean {
        return (text.contains("flashlight") || text.contains("torch")) &&
                (text.contains("turn on") || text.contains("switch on") || text.contains("enable") || text.endsWith(" on"))
    }

    private fun isFlashlightOffIntent(text: String): Boolean {
        return (text.contains("flashlight") || text.contains("torch")) &&
                (text.contains("turn off") || text.contains("switch off") || text.contains("disable") || text.endsWith(" off"))
    }

    private fun parseTimerSeconds(text: String): Int? {
        if (!text.contains("timer")) return null
        val regexMin = Regex("(\\d+)\\s*(?:minute|min|mins|minutes)")
        val regexSec = Regex("(\\d+)\\s*(?:second|sec|secs|seconds)")

        var totalSeconds = 0
        var found = false

        regexMin.find(text)?.let {
            val min = it.groupValues[1].toIntOrNull() ?: 0
            totalSeconds += min * 60
            found = true
        }

        regexSec.find(text)?.let {
            val sec = it.groupValues[1].toIntOrNull() ?: 0
            totalSeconds += sec
            found = true
        }

        if (!found) {
            // Check standalone number: "timer for 5" or "timer 10"
            val fallback = Regex("timer(?:\\s+for)?\\s+(\\d+)").find(text)
            if (fallback != null) {
                val num = fallback.groupValues[1].toIntOrNull() ?: 0
                // Default to minutes if under 60
                totalSeconds = num * 60
                found = true
            }
        }

        return if (found && totalSeconds > 0) totalSeconds else null
    }

    private fun isMathCalculationIntent(text: String): Boolean {
        return text.startsWith("calculate ") ||
                text.startsWith("compute ") ||
                text.startsWith("what is ") && text.any { it in "+-*/^%" } ||
                text.startsWith("solve ")
    }

    private fun extractMathExpression(text: String): String? {
        val cleaned = text
            .replace("calculate", "")
            .replace("compute", "")
            .replace("what is", "")
            .replace("solve", "")
            .replace("?", "")
            .trim()

        return if (cleaned.any { it.isDigit() } && cleaned.any { it in "+-*/%xX" }) {
            cleaned.replace("x", "*").replace("X", "*")
        } else null
    }

    private fun evaluateMath(expr: String): String? {
        return try {
            // Handle percentages like "15% of 850"
            val pctMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*(\\d+(?:\\.\\d+)?)").find(expr)
            if (pctMatch != null) {
                val p = pctMatch.groupValues[1].toDouble()
                val total = pctMatch.groupValues[2].toDouble()
                val ans = (p / 100.0) * total
                return if (ans == ans.toLong().toDouble()) ans.toLong().toString() else "%.2f".format(ans)
            }

            // Simple basic 2-operand or simple multi-operand parser
            val tokens = expr.replace(" ", "")
            val plusMinus = tokens.split(Regex("(?<=[-+])|(?=[-+])"))
            var current = 0.0
            var op = "+"
            for (token in plusMinus) {
                if (token == "+" || token == "-") {
                    op = token
                } else {
                    // Check for multiplication / division
                    val multDiv = token.split(Regex("(?<=[*/])|(?=[*/])"))
                    var subVal = multDiv[0].toDoubleOrNull() ?: 0.0
                    var subOp = "*"
                    for (i in 1 until multDiv.size step 2) {
                        subOp = multDiv[i]
                        val nextVal = multDiv.getOrNull(i + 1)?.toDoubleOrNull() ?: 1.0
                        subVal = if (subOp == "*") subVal * nextVal else if (nextVal != 0.0) subVal / nextVal else 0.0
                    }
                    if (op == "+") current += subVal else current -= subVal
                }
            }
            if (current == current.toLong().toDouble()) current.toLong().toString() else "%.2f".format(current)
        } catch (e: Exception) {
            null
        }
    }
}
