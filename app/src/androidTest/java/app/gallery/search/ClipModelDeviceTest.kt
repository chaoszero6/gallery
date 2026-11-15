package app.gallery.search

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled CLIP model on the device (preprocessing + tokenizer + ONNX Runtime with
 * 8-bit MatMulNBits) and compares with PyTorch reference embeddings from tools/export_clip.py.
 */
@RunWith(AndroidJUnit4::class)
class ClipModelDeviceTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun vec(a: JsonArray) = FloatArray(a.size) { a[it].jsonPrimitive.float }
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    @Test fun embeddingsMatchReference() {
        val clip = ClipModel(appCtx)
        assertThat(clip.available).isTrue()
        val expected = Json.parseToJsonElement(testCtx.assets.open("clip_expected.json").bufferedReader().readText()) as JsonObject

        val bmp = testCtx.assets.open("clip_sample.jpg").use { BitmapFactory.decodeStream(it) }
        val t0 = System.nanoTime()
        val img = clip.embedImage(bmp) // first call includes model load
        val t1 = System.nanoTime()
        repeat(5) { clip.embedImage(bmp) }
        val perImage = (System.nanoTime() - t1) / 5 / 1_000_000
        val imgCos = dot(img, vec(expected["image"] as JsonArray))
        Log.i("ClipModelDeviceTest", "image cosine=$imgCos load+first=${(t1 - t0) / 1_000_000}ms steady=${perImage}ms")
        // Bilinear (app) vs bicubic (PyTorch) resizing causes a small difference.
        assertThat(imgCos).isGreaterThan(0.97)

        for ((text, v) in expected["text"] as JsonObject) {
            val c = dot(clip.embedText(text), vec(v as JsonArray))
            Log.i("ClipModelDeviceTest", "text '$text' cosine=$c")
            assertThat(c).isGreaterThan(0.995)
        }
        clip.close()
    }
}
