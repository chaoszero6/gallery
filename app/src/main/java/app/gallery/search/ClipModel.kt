package app.gallery.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device CLIP (image and text encoders exported to ONNX by tools/export_clip.py).
 * Both encoders output L2-normalised 512-d vectors, so a dot product is the cosine similarity.
 *
 * On Snapdragon phones the image encoder (the part that runs once per photo while indexing) runs
 * on the Hexagon NPU through ONNX Runtime's QNN backend, using a separately quantised copy of the
 * model (tools/npu_quantize.py). Anything that stops that working falls back to the CPU model.
 * The text encoder runs once per search and stays on the CPU.
 */
class ClipModel(private val context: Context) {
    enum class Accelerator(val label: String) { NPU("NPU"), CPU("CPU") }

    /** Speed test result: milliseconds per photo, and how closely the NPU agrees with the CPU. */
    data class Benchmark(val npuMs: Double?, val cpuMs: Double, val cosine: Float?, val npuError: String?)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var image: OrtSession? = null
    private var text: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null

    private val assetNames: Set<String> by lazy {
        runCatching { context.assets.list("clip")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
    }

    /** The build tools store "x.txt.gz" assets decompressed as "x.txt", so accept either. */
    private val vocabAsset: String? by lazy {
        listOf(VOCAB_FILE, VOCAB_FILE.removeSuffix(".gz")).firstOrNull { it in assetNames }
    }

    val available: Boolean by lazy { IMAGE_FILE in assetNames && TEXT_FILE in assetNames && vocabAsset != null }

    /** Where the image encoder runs; null until it is first loaded. */
    @Volatile var imageAccelerator: Accelerator? = null
        private set

    /** Why the NPU is not used, when it was tried and failed. */
    @Volatile var npuError: String? = null
        private set

    private val htpBackend: File get() = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so")

    /** Snapdragon phone, NPU model in this build, and QNN's libraries installed. */
    val npuSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) &&
            NPU_IMAGE_FILE in assetNames && htpBackend.exists()
    }

    /** ONNX Runtime needs a file path for large models: copy the asset once. */
    private fun assetFile(name: String): File {
        val f = File(context.filesDir, "clip/$name")
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            context.assets.open("clip/$name").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            tmp.renameTo(f)
        }
        return f
    }

    private fun session(name: String): OrtSession {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(assetFile(name).path, opts)
    }

    /**
     * Image encoder on the NPU. Compiling the graph for the NPU takes a while, so the compiled
     * form (a QNN "context binary") is saved next to the model and loaded directly next time.
     * CPU fallback inside the session is disabled: a half-NPU graph would be slower than the CPU model.
     */
    private fun npuSession(): OrtSession {
        val dir = File(context.filesDir, "clip")
        val ctxName = "npu_ctx_v${VERSION}_ort${env.version}.onnx"
        val ctx = File(dir, ctxName)
        dir.listFiles { f -> f.name.startsWith("npu_ctx_") && f.name != ctxName }?.forEach { it.delete() }
        fun opts(save: Boolean) = OrtSession.SessionOptions().apply {
            addConfigEntry("session.disable_cpu_ep_fallback", "1")
            if (save) {
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", ctx.path)
            }
            addQnn(
                mapOf(
                    "backend_path" to htpBackend.path,
                    "htp_performance_mode" to "sustained_high_performance",
                    "htp_graph_finalization_optimization_mode" to "3",
                ),
            )
        }
        if (ctx.exists()) {
            try {
                return env.createSession(ctx.path, opts(save = false))
            } catch (e: Exception) {
                Log.w(TAG, "saved NPU graph unusable, compiling again", e)
                ctx.delete()
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        return env.createSession(assetFile(NPU_IMAGE_FILE).path, opts(save = true)).also {
            Log.i(TAG, "compiled image encoder for the NPU in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    private fun imageSession(): OrtSession {
        image?.let { return it }
        var s: OrtSession? = null
        if (npuSupported) {
            s = try {
                npuSession().also { imageAccelerator = Accelerator.NPU }
            } catch (e: Throwable) {
                Log.w(TAG, "NPU unavailable, using the CPU", e)
                npuError = e.message ?: e.javaClass.simpleName
                null
            }
        }
        if (s == null) s = session(IMAGE_FILE).also { imageAccelerator = Accelerator.CPU }
        image = s
        return s
    }

    private fun run(s: OrtSession, input: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                normalize((out[0].value as Array<FloatArray>)[0])
            }
        }

    @Synchronized
    fun embedImage(bmp: Bitmap): FloatArray = run(imageSession(), preprocess(bmp))

    /** Times the image encoder on the NPU (when there is one) and on the CPU, [runs] photos each. */
    @Synchronized
    fun benchmark(bmp: Bitmap, runs: Int = 20): Benchmark {
        val input = preprocess(bmp)
        fun time(s: OrtSession): Pair<Double, FloatArray> {
            var v = run(s, input) // warm-up
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(runs) { v = run(s, input) }
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / runs to v
        }
        val main = imageSession()
        val npu = if (imageAccelerator == Accelerator.NPU) time(main) else null
        val cpu = if (imageAccelerator == Accelerator.CPU) time(main) else session(IMAGE_FILE).use { time(it) }
        val cos = npu?.second?.let { n -> cpu.second.indices.sumOf { (cpu.second[it] * n[it]).toDouble() }.toFloat() }
        return Benchmark(npu?.first, cpu.first, cos, npuError).also { Log.i(TAG, "benchmark: $it") }
    }

    @Synchronized
    fun embedText(query: String): FloatArray {
        val s = text ?: session(TEXT_FILE).also { text = it }
        val tok = tokenizer ?: vocabAsset!!.let { name ->
            context.assets.open("clip/$name").use { ClipTokenizer(it, gzipped = name.endsWith(".gz")) }
        }.also { tokenizer = it }
        val ids = tok.tokenize(query).map { it.toLong() }.toLongArray()
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return normalize((out[0].value as Array<FloatArray>)[0])
            }
        }
    }

    fun close() {
        runCatching { image?.close(); text?.close() }.onFailure { Log.w("ClipModel", "close", it) }
        image = null; text = null; imageAccelerator = null
    }

    companion object {
        private const val TAG = "ClipModel"
        const val IMAGE_FILE = "clip_image.onnx"
        /** Image encoder quantised for the Qualcomm NPU (tools/npu_quantize.py). */
        const val NPU_IMAGE_FILE = "clip_image_npu.onnx"
        const val TEXT_FILE = "clip_text.onnx"
        const val VOCAB_FILE = "bpe_simple_vocab_16e6.txt.gz"
        const val SIZE = 224
        /** Bump when the model changes so the index is rebuilt. */
        const val VERSION = 1
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        /**
         * Resize shortest side to 224, centre-crop, normalise, CHW.
         * Large images are halved step by step first: one big bilinear jump aliases badly
         * (measured cosine 0.91 vs CLIP's own preprocessing; with halving 0.999).
         */
        fun preprocess(input: Bitmap): FloatArray {
            var src = input
            while (minOf(src.width, src.height) / 2 >= SIZE) {
                val half = Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true)
                if (src !== input) src.recycle()
                src = half
            }
            val scale = SIZE.toFloat() / minOf(src.width, src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(SIZE)
            val h = (src.height * scale).toInt().coerceAtLeast(SIZE)
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val left = (w - SIZE) / 2
            val top = (h - SIZE) / 2
            c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(-left, -top, w - left, h - top), Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(SIZE * SIZE)
            out.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            out.recycle()
            if (src !== input) src.recycle()
            val f = FloatArray(3 * SIZE * SIZE)
            val plane = SIZE * SIZE
            for (i in px.indices) {
                val p = px[i]
                f[i] = (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0]
                f[plane + i] = (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1]
                f[2 * plane + i] = ((p and 0xff) / 255f - MEAN[2]) / STD[2]
            }
            return f
        }

        fun normalize(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s).takeIf { it > 0 } ?: 1f
            return FloatArray(v.size) { v[it] / n }
        }

        fun toBytes(v: FloatArray): ByteArray {
            val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            v.forEach { bb.putFloat(it) }
            return bb.array()
        }

        fun fromBytes(b: ByteArray): FloatArray {
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }
    }
}
