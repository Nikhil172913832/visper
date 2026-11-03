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
            val timeStr = if (min > 0 && sec > 0) "${min}m ${sec}s" else if (min > 0) "${min}m" else "${sec}s"
            return AgentAction(
                actionType = "TIMER",
                title = "Timer Scheduled",
                detail = if (success) "Set countdown timer for $timeStr" else "Failed to launch system timer",
                isSuccess = success
            )
        }

        // 4. COPY TO CLIPBOARD
        if (isClipboardIntent(lower)) {
            val textToCopy = extractClipboardText(prompt)
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
        // English: "turn on flashlight", "switch on flashlight", "turn flashlight on", "torch on"
        val enPattern = Regex("(turn|switch)\\s+(the\\s+)?(flashlight|torch)\\s+on|(turn|switch)\\s+on\\s+(the\\s+)?(flashlight|torch)|enable\\s+(the\\s+)?(flashlight|torch)|^(flashlight|torch)\\s+on$")
        if (enPattern.containsMatchIn(text)) return true

        // Hindi / Hinglish: टॉर्च चालू करो, टॉर्च ऑन करो, टॉर्च जलाओ, torch on karo, etc.
        val hiPattern = Regex("(टॉर्च|फ्लैशलाइट|torch|flashlight)\\s*(चालू|ऑन|jalao|chalu|on)\\s*(करो|karo)?|(jalao|on karo|chalu karo)\\s*(टॉर्च|torch)")
        if (hiPattern.containsMatchIn(text)) return true

        // Punjabi: ਟਾਰਚ ਚਲਾਓ, ਟਾਰਚ ਆਨ ਕਰੋ
        val paPattern = Regex("(ਟਾਰਚ|torch)\\s*(ਚਲਾਓ|ਆਨ ਕਰੋ|ਆਨ|ਬਾਲੋ)")
        if (paPattern.containsMatchIn(text)) return true

        return (text.contains("flashlight") || text.contains("torch")) &&
                (text.contains("turn on") || text.contains("switch on") || text.contains("enable") || text.endsWith(" on"))
    }

    private fun isFlashlightOffIntent(text: String): Boolean {
        // English: "turn off flashlight", "switch off flashlight", "turn flashlight off", "torch off"
        val enPattern = Regex("(turn|switch)\\s+(the\\s+)?(flashlight|torch)\\s+off|(turn|switch)\\s+off\\s+(the\\s+)?(flashlight|torch)|disable\\s+(the\\s+)?(flashlight|torch)|^(flashlight|torch)\\s+off$")
        if (enPattern.containsMatchIn(text)) return true

        // Hindi / Hinglish: टॉर्च बंद करो, टॉर्च ऑफ करो, टॉर्च बुझाओ, torch band karo, etc.
        val hiPattern = Regex("(टॉर्च|फ्लैशलाइट|torch|flashlight)\\s*(बंद|ऑफ|band|off|bujhao)\\s*(करो|karo)?|(bujhao|band karo|off karo)\\s*(टॉर्च|torch)")
        if (hiPattern.containsMatchIn(text)) return true

        // Punjabi: ਟਾਰਚ ਬੰਦ ਕਰੋ
        val paPattern = Regex("(ਟਾਰਚ|torch)\\s*(ਬੰਦ ਕਰੋ|ਬੰਦ|ਆਫ ਕਰੋ)")
        if (paPattern.containsMatchIn(text)) return true

        return (text.contains("flashlight") || text.contains("torch")) &&
                (text.contains("turn off") || text.contains("switch off") || text.contains("disable") || text.endsWith(" off"))
    }

    private fun parseTimerSeconds(text: String): Int? {
        val hasTimerWord = text.contains("timer") || text.contains("टाइमर") || text.contains("ਟਾਈਮਰ")
        if (!hasTimerWord) return null

        val regexHour = Regex("(\\d+)\\s*(?:hour|hours|घंटे|ਘੰਟੇ)")
        val regexMin = Regex("(\\d+)\\s*(?:minute|min|mins|minutes|मिनट|ਮਿੰਟ)")
        val regexSec = Regex("(\\d+)\\s*(?:second|sec|secs|seconds|सेकंड|ਸਕਿੰਟ)")

        var totalSeconds = 0
        var found = false

        regexHour.find(text)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: 0
            totalSeconds += h * 3600
            found = true
        }

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
            // Check standalone number: "timer for 5" or "timer 10" or "5 का टाइमर"
            val fallback = Regex("(?:timer(?:\\s+for)?\\s+|(\\d+)\\s*(?:का\\s+)?(?:टाइमर|ਟਾਈਮਰ))(\\d+)?").find(text)
            if (fallback != null) {
                val num = fallback.groupValues[1].ifBlank { fallback.groupValues[2] }.toIntOrNull() ?: 0
                if (num > 0) {
                    totalSeconds = num * 60
                    found = true
                }
            }
        }

        return if (found && totalSeconds > 0) totalSeconds else null
    }

    private fun isClipboardIntent(text: String): Boolean {
        return text.startsWith("copy to clipboard") ||
                text.startsWith("copy this to clipboard") ||
                text.startsWith("copy:") ||
                text.startsWith("क्लिपबोर्ड पर कॉपी करो") ||
                text.startsWith("कॉपी करो:") ||
                text.contains("clipboard par copy")
    }

    private fun extractClipboardText(prompt: String): String {
        val colonIdx = prompt.indexOf(':')
        return if (colonIdx != -1) {
            prompt.substring(colonIdx + 1).trim()
        } else {
            prompt.replace(Regex("(?i)^copy (this )?to clipboard"), "")
                .replace(Regex("(?i)^क्लिपबोर्ड पर कॉपी करो"), "")
                .replace(Regex("(?i)^clipboard par copy karo"), "")
                .trim()
        }
    }

    private fun isMathCalculationIntent(text: String): Boolean {
        return text.startsWith("calculate ") ||
                text.startsWith("compute ") ||
                text.startsWith("what is ") && text.any { it in "+-*/^%" } ||
                text.startsWith("solve ") ||
                (text.any { it in "+-*/^%" } && (text.contains("kitna") || text.contains("कितना") || text.contains("solve")))
    }

    private fun extractMathExpression(text: String): String? {
        val cleaned = text
            .replace("calculate", "")
            .replace("compute", "")
            .replace("what is", "")
            .replace("solve", "")
            .replace("kitna hoga", "")
            .replace("kitna", "")
            .replace("कितना होगा", "")
            .replace("कितना", "")
            .replace("?", "")
            .trim()

        return if (cleaned.any { it.isDigit() } && cleaned.any { it in "+-*/%xX^" }) {
            cleaned.replace("x", "*").replace("X", "*")
        } else null
    }

    fun evaluateMath(expr: String): String? {
        return try {
            // Handle percentages like "15% of 850"
            val pctMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*(\\d+(?:\\.\\d+)?)").find(expr)
            if (pctMatch != null) {
                val p = pctMatch.groupValues[1].toDouble()
                val total = pctMatch.groupValues[2].toDouble()
                val ans = (p / 100.0) * total
                return formatNumber(ans)
            }

            // Recursive descent mathematical evaluator supporting +, -, *, /, %, ^ and parentheses ()
            val parser = MathEvaluator(expr)
            val result = parser.parse()
            if (result.isInfinite() || result.isNaN()) {
                "Undefined (division by zero)"
            } else {
                formatNumber(result)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Standard recursive-descent mathematical parser with proper operator precedence:
     * Expression -> Term (('+' | '-') Term)*
     * Term       -> Factor (('*' | '/' | '%') Factor)*
     * Factor     -> Power ('^' Power)*
     * Power      -> ('+' | '-')? Primary
     * Primary    -> Number | '(' Expression ')'
     */
    private class MathEvaluator(private val expression: String) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < expression.length) expression[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            while (ch == ' '.code) nextChar()
            if (pos < expression.length) throw RuntimeException("Unexpected: " + ch.toChar())
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> {
                        val divisor = parseFactor()
                        x = if (divisor == 0.0) Double.POSITIVE_INFINITY else x / divisor
                    }
                    eat('%'.code) -> x %= parseFactor()
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            var x = parseUnary()
            if (eat('^'.code)) {
                val exponent = parseFactor() // Right associative
                x = Math.pow(x, exponent)
            }
            return x
        }

        private fun parseUnary(): Double {
            while (ch == ' '.code) nextChar()
            if (eat('+'.code)) return parseUnary()
            if (eat('-'.code)) return -parseUnary()
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            while (ch == ' '.code) nextChar()
            val startPos = pos
            if (eat('('.code)) {
                val x = parseExpression()
                eat(')'.code)
                return x
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                return expression.substring(startPos, pos).toDouble()
            } else {
                throw RuntimeException("Unexpected token: " + ch.toChar())
            }
        }
    }
}
