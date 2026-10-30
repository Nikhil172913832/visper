package com.visper.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.visper.app.ui.TerminalScreen
import com.visper.app.ui.TerminalViewModel
import com.visper.app.ui.theme.AppBackground
import com.visper.app.ui.theme.VisperTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleVoiceRecording()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleIncomingIntent(intent)

        setContent {
            VisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    TerminalScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val testImg = intent.getStringExtra("test_image_path")
        val testPrompt = intent.getStringExtra("test_prompt")
        lifecycleScope.launch {
            if (!testImg.isNullOrBlank()) {
                val file = java.io.File(testImg)
                if (file.exists()) {
                    viewModel.onImageSelected(android.net.Uri.fromFile(file))
                    var waitCount = 0
                    while (viewModel.uiState.value.attachedImageBitmap == null && waitCount < 50) {
                        delay(100)
                        waitCount++
                    }
                    delay(200)
                }
            }
            if (!testPrompt.isNullOrBlank()) {
                viewModel.onInputTextChanged(testPrompt)
                viewModel.submitPrompt()
            }
        }
    }

    fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
