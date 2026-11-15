package app.gallery.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device CLIP (image and text encoders exported to ONNX by tools/export_clip.py).
 * Both encoders output L2-normalised 512-d vectors, so a dot product is the cosine similarity.
 */
class ClipModel(private val context: Context) {
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

    private fun session(name: String): OrtSession {
        // ONNX Runtime needs a file path for large models: copy the asset once.
        val f = File(context.filesDir, "clip/$name")
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            context.assets.open("clip/$name").use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            tmp.renameTo(f)
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(f.path, opts)
    }

    @Synchronized
    fun embedImage(bmp: Bitmap): FloatArray {
        val s = image ?: session(IMAGE_FILE).also { image = it }
        val input = preprocess(bmp)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return normalize((out[0].value as Array<FloatArray>)[0])
            }
        }
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
        image = null; text = null
    }

    companion object {
        const val IMAGE_FILE = "clip_image.onnx"
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
