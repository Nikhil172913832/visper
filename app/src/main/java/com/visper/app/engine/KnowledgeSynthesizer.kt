package com.visper.app.engine

object KnowledgeSynthesizer {

    fun generateExplanation(prompt: String): String? {
        val p = prompt.lowercase().trim()

        return when {
            // Quicksort
            p.contains("quicksort") || p.contains("quick sort") -> {
                """
                === QUICKSORT ALGORITHM ===
                
                Quicksort is an efficient, divide-and-conquer sorting algorithm. It partitions an array into two sub-arrays according to a chosen 'pivot' element, such that elements smaller than the pivot precede it, and larger elements follow it.
                
                [HOW IT WORKS (Lomuto Partition)]:
                1. Choose a Pivot: Typically the last element (or a random/median element).
                2. Partition: Scan the array from left to right. Maintain an index 'i' for smaller elements. Swap elements smaller than the pivot into the left section.
                3. Place Pivot: Swap the pivot into its final sorted position at index 'i + 1'.
                4. Recurse: Recursively apply quicksort to the left sub-array (elements < pivot) and right sub-array (elements > pivot).
                
                [COMPLEXITY]:
                • Best Case   : O(n log n)  (balanced partitions)
                • Average Case: O(n log n)
                • Worst Case  : O(n²)       (already sorted array with naive pivot)
                • Space       : O(log n)    (recursion call stack, in-place sorting)
                
                [CODE IMPLEMENTATION]:
                ```kotlin
                fun quickSort(arr: IntArray, low: Int = 0, high: Int = arr.size - 1) {
                    if (low < high) {
                        val p = partition(arr, low, high)
                        quickSort(arr, low, p - 1)
                        quickSort(arr, p + 1, high)
                    }
                }
                
                fun partition(arr: IntArray, low: Int, high: Int): Int {
                    val pivot = arr[high]
                    var i = low - 1
                    for (j in low until high) {
                        if (arr[j] <= pivot) {
                            i++
                            arr[i] = arr[j].also { arr[j] = arr[i] }
                        }
                    }
                    arr[i + 1] = arr[high].also { arr[high] = arr[i + 1] }
                    return i + 1
                }
                ```
                """.trimIndent()
            }

            // Mergesort
            p.contains("mergesort") || p.contains("merge sort") -> {
                """
                === MERGE SORT ALGORITHM ===
                
                Merge Sort is a stable, divide-and-conquer sorting algorithm that guarantees O(n log n) time complexity under all conditions.
                
                [HOW IT WORKS]:
                1. Divide: Split the array into two equal halves until each subarray has 1 element.
                2. Conquer: Recursively sort each half.
                3. Combine: Merge the two sorted subarrays by comparing front elements and copying the smaller one into a temporary buffer.
                
                [COMPLEXITY]:
                • Time : O(n log n) in Best, Average, and Worst cases.
                • Space: O(n) auxiliary memory for buffer merging.
                • Stability: Yes (preserves relative order of equal keys).
                """.trimIndent()
            }

            // Binary Search
            p.contains("binary search") -> {
                """
                === BINARY SEARCH ===
                
                An efficient algorithm for finding an item in a sorted array by repeatedly dividing the search interval in half.
                
                [HOW IT WORKS]:
                1. Set left = 0, right = n - 1.
                2. Find mid = left + (right - left) / 2.
                3. If arr[mid] == target, return mid.
                4. If arr[mid] < target, search right half (left = mid + 1).
                5. Otherwise, search left half (right = mid - 1).
                
                [COMPLEXITY]:
                • Time : O(log n)
                • Space: O(1) iterative, O(log n) recursive.
                """.trimIndent()
            }

            // Neural Networks / Transformers / LLM
            p.contains("transformer") || p.contains("attention mechanism") -> {
                """
                === TRANSFORMER ARCHITECTURE ===
                
                Introduced in 'Attention Is All You Need' (Vaswani et al., 2017), the Transformer replaces recurrent layers with Self-Attention.
                
                [KEY COMPONENTS]:
                1. Scaled Dot-Product Attention:
                   Attention(Q, K, V) = softmax( (Q * K^T) / sqrt(d_k) ) * V
                   Calculates how much focus each token gives to every other token.
                2. Multi-Head Attention: Projects Q, K, V into multiple subspaces to attend to different relationship types simultaneously.
                3. Feed-Forward Network (FFN): Applied independently to each position.
                4. Positional Encodings: Injects sequence order (e.g. RoPE - Rotary Position Embeddings).
                5. KV-Cache: Caches previous Key and Value matrices so generation doesn't recompute earlier tokens.
                """.trimIndent()
            }

            p.contains("neural network") || p.contains("how does a neural network work") -> {
                """
                === ARTIFICIAL NEURAL NETWORKS (ANN) ===
                
                A computational model inspired by biological brains, composed of layers of interconnected nodes (neurons).
                
                [CORE MECHANICS]:
                1. Forward Propagation: Each neuron computes y = activation( Σ(w_i * x_i) + b ).
                2. Activation Functions: Introduces non-linearity (e.g. ReLU, GELU, Sigmoid, Softmax).
                3. Loss Function: Measures discrepancy between prediction and ground truth (e.g. Cross-Entropy, MSE).
                4. Backpropagation: Calculates gradients via chain rule: ∂Loss/∂Weight.
                5. Optimizer: Updates weights to minimize loss (e.g. AdamW, SGD).
                """.trimIndent()
            }

            // Quantization (GGUF / INT4 / LiteRT)
            p.contains("quantization") || p.contains("int4") || p.contains("gguf") -> {
                """
                === ON-DEVICE LLM QUANTIZATION ===
                
                Quantization reduces floating-point precision (FP32/FP16) of model weight matrices to lower-bit representations (INT8, INT4, Q4_K_M).
                
                [BENEFITS]:
                • Memory: Gemma 2B FP16 requires ~5.2 GB VRAM; INT4 requires only ~1.3 GB.
                • Bandwidth: Memory bandwidth is the primary bottleneck on mobile SoCs. Loading 4-bit weights speeds up token generation 3-4x.
                • Hardware Support: Vector instructions (ARM NEON dot-product, Qualcomm HTP, Apple Neural Engine) execute int4 math with minimal thermal throttling.
                """.trimIndent()
            }

            // Operating Systems & Coroutines
            p.contains("coroutine") || p.contains("thread vs coroutine") -> {
                """
                === THREADS VS COROUTINES ===
                
                • Thread: OS-managed kernel resource. Preemptive multitasking. Stack allocation is typically 1MB+. Context switching requires kernel mode transitions.
                • Coroutine: User-space cooperative multitasking ('lightweight thread'). Non-blocking suspension. Millions of coroutines can run on a handful of OS threads with minimal memory footprint (~few KB).
                """.trimIndent()
            }

            p.contains("solid principle") || p.contains("solid") -> {
                """
                === SOLID DESIGN PRINCIPLES ===
                
                • S - Single Responsibility: A class should have only one reason to change.
                • O - Open/Closed: Software entities should be open for extension, closed for modification.
                • L - Liskov Substitution: Subtypes must be substitutable for their base types.
                • I - Interface Segregation: Clients shouldn't depend on interfaces they do not use.
                • D - Dependency Inversion: Depend on abstractions, not concretions.
                """.trimIndent()
            }

            // Python / Coding
            p.contains("fibonacci") -> {
                """
                === FIBONACCI SEQUENCE ===
                
                Formula: F(n) = F(n-1) + F(n-2), with F(0) = 0, F(1) = 1.
                
                [O(n) Time, O(1) Space Implementation]:
                ```python
                def fibonacci(n: int) -> int:
                    if n <= 1:
                        return n
                    a, b = 0, 1
                    for _ in range(2, n + 1):
                        a, b = b, a + b
                    return b
                ```
                """.trimIndent()
            }

            else -> null
        }
    }
}
