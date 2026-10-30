# Visper // Minimalist On-Device AI Terminal

> High-performance, zero-bloat, dark terminal-style Android on-device AI chatbot with **Text, Image, Document, and Audio** support.

---

## ⚡ Tech Stack & Architecture Choices

| Component | Technology | Rationale & Optimizations |
| :--- | :--- | :--- |
| **Language** | **Kotlin 2.0 (JVM 17)** | High-performance, zero-overhead value classes, native coroutines. |
| **UI Engine** | **Jetpack Compose (Foundation + BOM)** | Zero legacy XML view hierarchy overhead; pure OLED AMOLED black (`#050505`) with 0-draw pixel savings; 60fps throttled token rendering. |
| **Inference Core** | **Google LiteRT / MediaPipe GenAI (LLM Inference)** | Direct C++ hardware acceleration via **Vulkan GPU & NPU**, zero-copy direct memory mapping (`mmap`), 16KB page alignment. Compatible with Gemma-2B/3B, PaliGemma 2, Llama 3.2, and Phi-3.5. |
| **Audio (STT)** | **Android Native Speech Services (Offline Mode) / Whisper** | Zero-latency on-device speech-to-text without cloud roundtrips; ASCII amplitude waveform visualizer. |
| **Vision (Multimodal)** | **Direct `inSampleSize` Bitmap Decoder + ASCII Tensor** | Never spikes the JVM heap on 50MP+ photos (`inJustDecodeBounds`), transforms inputs into normalized float tensors and real-time ASCII terminal previews. |
| **Document Engine** | **Native Zero-Dependency Stream Parser** | Zero-bloat PDF / TXT / Markdown extractor (eliminates 30MB+ Apache PDFBox bloatware); intelligent context token budgeting (~1500 tokens cap) to prevent KV-cache overflow. |
| **Build Optimization** | **R8 Full Mode & ProGuard** | Aggressive dead-code elimination, unused resource stripping, JNI export safety. |

---

## 🖥 Terminal UI & Aesthetics

Visper is styled after a high-contrast cybernetic hacker console:
- **AMOLED True Black (`#050505`)**: Turns off OLED pixels entirely to minimize battery drain during intense NPU/GPU workloads.
- **CRT Phosphor Green (`#00FF66`)**: Standard monospace command prompt and AI generation.
- **Cyber Amber (`#FFB000`) & Cyan (`#00E5FF`)**: Telemetry stats, system probes, and document badges.
- **ASCII Art Vision**: Attached photos render in direct ASCII luminance art right inside the terminal feed.
- **Live Waveform Audio**: Real-time ASCII amplitude bar meter while speaking.

---

## 🚀 Terminal Commands

| Command | Action |
| :--- | :--- |
| `/help` | Print command palette and active features |
| `/sys` | Hardware probe: CPU cores, RAM usage, Vulkan/NPU support, ABI |
| `/bench` | Run on-device token generation benchmark |
| `/clear` | Flush chat memory and reset KV-cache context |
| `/model <path>` | Bind custom `.bin`, `.task`, or quantized model weights |
| `[DOC]` | Select and attach PDF, TXT, MD, CSV, or Code files |
| `[IMG]` | Select image for vision processing |
| `[MIC]` | Hold or tap to record voice speech-to-text |

---

## 🛠 Setup & Running

### Requirements
- **Android SDK**: API 35 (minSdk 26)
- **NDK**: 28.2+
- **JDK**: OpenJDK 17

### Build & Install
```bash
cd /home/darklord/Work/Projects/visper
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Loading Real Model Weights
To bind a local model file (e.g., Gemma-2B-IT, PaliGemma-3B, Llama-3.2-1B-Instruct):
1. Push the `.bin` or `.task` model file to the device storage:
   ```bash
   adb push gemma-2b-it-gpu.bin /data/local/tmp/
   ```
2. In the Visper terminal, run:
   ```bash
   /model /data/local/tmp/gemma-2b-it-gpu.bin
   ```
*(Note: If no model file is specified, Visper runs in built-in fallback mode so all modalities, ASCII rendering, document parsers, and speech STT can be tested instantly!)*
