package app.gallery.search

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Compares the Kotlin tokenizer with Hugging Face's CLIPTokenizer (fixtures from tools/export_clip.py). */
class ClipTokenizerTest {
    @Test fun matchesReferenceTokenizer() {
        val vocab = File("src/main/assets/clip/bpe_simple_vocab_16e6.txt.gz")
        val fixtures = javaClass.classLoader!!.getResourceAsStream("clip_tokens.json")
        assumeTrue("run tools/export_clip.py first", vocab.exists() && fixtures != null)
        val tok = vocab.inputStream().use { ClipTokenizer(it) }
        val expected = Json.parseToJsonElement(fixtures!!.bufferedReader().readText()) as JsonObject
        for ((text, ids) in expected) {
            val want = (ids as JsonArray).map { it.jsonPrimitive.int }
            assertWithMessage(text).that(tok.tokenize(text).toList()).isEqualTo(want)
        }
    }
}
