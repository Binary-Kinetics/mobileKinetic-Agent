package com.mobilekinetic.agent.data.rag

data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedInput) return false
        return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask) &&
                tokenTypeIds.contentEquals(other.tokenTypeIds)
    }

    override fun hashCode(): Int {
        var result = inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        return result
    }
}

class WordPieceTokenizer(vocabText: String) {

    private val vocab: Map<String, Int>
    private val clsTokenId: Int
    private val sepTokenId: Int
    private val padTokenId: Int
    private val unkTokenId: Int

    init {
        vocab = vocabText.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, token -> token to index }
            .toMap()

        clsTokenId = vocab["[CLS]"] ?: 101
        sepTokenId = vocab["[SEP]"] ?: 102
        padTokenId = vocab["[PAD]"] ?: 0
        unkTokenId = vocab["[UNK]"] ?: 100
    }

    fun tokenize(text: String, maxLength: Int): TokenizedInput {
        val normalizedText = text.lowercase().trim()
        val words = normalizedText.split(Regex("\\s+"))

        val tokenIds = mutableListOf(clsTokenId.toLong())

        for (word in words) {
            if (tokenIds.size >= maxLength - 1) break

            val subTokens = wordPieceTokenize(word)
            for (subToken in subTokens) {
                if (tokenIds.size >= maxLength - 1) break
                tokenIds.add(subToken.toLong())
            }
        }

        tokenIds.add(sepTokenId.toLong())

        val inputIds = LongArray(maxLength)
        val attentionMask = LongArray(maxLength)
        val tokenTypeIds = LongArray(maxLength)

        for (i in tokenIds.indices) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1L
        }
        for (i in tokenIds.size until maxLength) {
            inputIds[i] = padTokenId.toLong()
            attentionMask[i] = 0L
        }

        return TokenizedInput(inputIds, attentionMask, tokenTypeIds)
    }

    private fun wordPieceTokenize(word: String): List<Int> {
        if (word.isEmpty()) return listOf(unkTokenId)

        val tokens = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found = false

            while (start < end) {
                val subword = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }

                val id = vocab[subword]
                if (id != null) {
                    tokens.add(id)
                    found = true
                    start = end
                    break
                }
                end--
            }

            if (!found) {
                tokens.add(unkTokenId)
                start++
            }
        }

        return tokens
    }
}
