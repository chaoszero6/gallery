package app.gallery.search

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The CLIP byte-pair-encoding tokenizer (same algorithm as openai/CLIP simple_tokenizer.py).
 * [merges] is the content of bpe_simple_vocab_16e6.txt(.gz).
 */
class ClipTokenizer(mergesStream: InputStream, gzipped: Boolean = true) {
    private val byteEncoder: Map<Int, Char> = bytesToUnicode()
    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = HashMap<String, List<String>>()

    init {
        val text = (if (gzipped) GZIPInputStream(mergesStream) else mergesStream).bufferedReader().readText()
        val lines = text.split('\n')
        // Skip the version header line; CLIP uses the first 49152-256-2 merges.
        val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { val p = it.split(' '); p[0] to p[1] }
        val vocab = ArrayList<String>(49408)
        val base = byteEncoder.values.toList()
        vocab.addAll(base.map { it.toString() })
        vocab.addAll(base.map { "$it</w>" })
        merges.forEach { vocab.add(it.first + it.second) }
        vocab.add(SOT_TEXT); vocab.add(EOT_TEXT)
        encoder = vocab.withIndex().associate { it.value to it.index }
        bpeRanks = merges.withIndex().associate { it.value to it.index }
    }

    fun encode(text: String): List<Int> {
        val clean = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val out = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val token = m.value.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 0xff]!!.toString() }
            for (piece in bpe(token)) encoder[piece]?.let { out += it }
        }
        return out
    }

    /** Token ids padded/truncated to the CLIP context length, wrapped in start/end tokens. */
    fun tokenize(text: String, contextLength: Int = 77): IntArray {
        val ids = IntArray(contextLength)
        val body = encode(text).take(contextLength - 2)
        ids[0] = SOT
        body.forEachIndexed { i, t -> ids[i + 1] = t }
        ids[body.size + 1] = EOT
        return ids
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word: List<String> = token.dropLast(1).map { it.toString() } + (token.last() + "</w>")
        if (word.size == 1) return word.also { cache[token] = it }
        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = bpeRanks[word[i] to word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = word[i] to word[i + 1] }
            }
            best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == best.first && word[i + 1] == best.second) {
                    merged += best.first + best.second; i += 2
                } else { merged += word[i]; i++ }
            }
            word = merged
            if (word.size == 1) break
        }
        cache[token] = word
        return word
    }

    companion object {
        const val SOT = 49406
        const val EOT = 49407
        private const val SOT_TEXT = "<|startoftext|>"
        private const val EOT_TEXT = "<|endoftext|>"
        private val PATTERN = Regex("""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")

        fun bytesToUnicode(): Map<Int, Char> {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code); bs.addAll('¡'.code..'¬'.code); bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            return bs.indices.associate { bs[it] to cs[it].toChar() }
        }
    }
}
