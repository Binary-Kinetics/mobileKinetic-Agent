package com.mobilekinetic.agent.data.rag

/**
 * Minimal SentencePiece tokenizer that parses a .model protobuf file
 * and performs greedy longest-match tokenization.
 *
 * Designed for EmbeddingGemma which uses a SentencePiece unigram model.
 * Avoids the DJL dependency — pure Kotlin with zero external deps.
 */
class SentencePieceTokenizer(modelBytes: ByteArray) {

    private val pieceToId: Map<String, Int>

    /** Number of vocabulary entries loaded from the model. */
    val vocabSize: Int get() = pieceToId.size

    init {
        val pieces = parsePieces(modelBytes)
        pieceToId = HashMap<String, Int>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { idx, text -> map[text] = idx }
        }
    }

    /** Diagnostic: look up a token string, returning its ID or null. */
    fun lookupId(piece: String): Int? = pieceToId[piece]

    /** Diagnostic: return first N entries as (piece, id) pairs. */
    fun sampleEntries(n: Int): List<Pair<String, Int>> =
        pieceToId.entries.take(n).map { it.key to it.value }

    /**
     * Encode text to token IDs (without BOS/EOS — caller adds those).
     * Spaces are replaced with the SentencePiece whitespace marker U+2581 (▁).
     */
    fun encode(text: String): IntArray {
        val normalized = "\u2581" + text.replace(" ", "\u2581")
        return tokenize(normalized)
    }

    private fun tokenize(text: String): IntArray {
        val tokens = mutableListOf<Int>()
        var pos = 0
        while (pos < text.length) {
            var bestLen = 0
            var bestId = 0
            val maxLen = minOf(text.length - pos, 48)
            for (len in maxLen downTo 1) {
                val candidate = text.substring(pos, pos + len)
                val id = pieceToId[candidate]
                if (id != null) {
                    bestLen = len
                    bestId = id
                    break
                }
            }
            if (bestLen == 0) {
                // Byte fallback: SentencePiece encodes unknown bytes as <0xHH>
                val byte = text[pos].code and 0xFF
                val hexToken = "<0x${byte.toString(16).uppercase().padStart(2, '0')}>"
                tokens.add(pieceToId[hexToken] ?: 0)
                pos++
            } else {
                tokens.add(bestId)
                pos += bestLen
            }
        }
        return tokens.toIntArray()
    }

    // ── Protobuf parser (SentencePiece ModelProto) ───────────────────────

    /**
     * Extracts piece strings from the SentencePiece .model protobuf.
     * ModelProto field 1 (repeated) = SentencePiece sub-messages.
     * Each SentencePiece has field 1 = piece string, field 2 = score, field 3 = type.
     * We only need the piece strings; their index IS the token ID.
     */
    private fun parsePieces(data: ByteArray): List<String> {
        val pieces = mutableListOf<String>()
        var offset = 0
        while (offset < data.size) {
            val tagByte = readVarint(data, offset)
            val tag = (tagByte.first.toInt() ushr 3)
            val wireType = (tagByte.first.toInt() and 0x7)
            offset += tagByte.second

            when {
                tag == 1 && wireType == 2 -> {
                    val lenResult = readVarint(data, offset)
                    offset += lenResult.second
                    val end = offset + lenResult.first.toInt()
                    val pieceText = parsePieceText(data, offset, end)
                    pieces.add(pieceText)
                    offset = end
                }
                wireType == 0 -> {
                    offset += readVarint(data, offset).second
                }
                wireType == 1 -> offset += 8
                wireType == 2 -> {
                    val lenResult = readVarint(data, offset)
                    offset += lenResult.second + lenResult.first.toInt()
                }
                wireType == 5 -> offset += 4
                else -> break
            }
        }
        return pieces
    }

    /** Extract the piece string (field 1) from a SentencePiece sub-message. */
    private fun parsePieceText(data: ByteArray, start: Int, end: Int): String {
        var offset = start
        while (offset < end) {
            val tagByte = readVarint(data, offset)
            val tag = (tagByte.first.toInt() ushr 3)
            val wireType = (tagByte.first.toInt() and 0x7)
            offset += tagByte.second

            when {
                tag == 1 && wireType == 2 -> {
                    val lenResult = readVarint(data, offset)
                    offset += lenResult.second
                    return String(data, offset, lenResult.first.toInt(), Charsets.UTF_8)
                }
                wireType == 0 -> offset += readVarint(data, offset).second
                wireType == 1 -> offset += 8
                wireType == 2 -> {
                    val lenResult = readVarint(data, offset)
                    offset += lenResult.second + lenResult.first.toInt()
                }
                wireType == 5 -> offset += 4
                else -> break
            }
        }
        return ""
    }

    /** Read a protobuf varint, returning (value, bytesConsumed). */
    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (pos - offset)
    }
}
