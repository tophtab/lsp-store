package io.github.tophtab.lspstore.data.translation

/**
 * Translating rendered README HTML without losing its markup.
 *
 * Providers only translate text, so the markup is replaced by opaque tokens
 * before the request and put back afterwards. Whole elements whose contents
 * must never be translated (code, scripts, SVG paths) are protected as one
 * token, then the remaining tags, then bare URLs. The tokens are unicode
 * brackets, which the translation providers leave in place (Komi relies on the
 * same property for its markdown translation).
 */
internal object HtmlTranslation {
    private const val TOKEN_OPEN = '⟦'
    private const val TOKEN_CLOSE = '⟧'

    /**
     * One left-to-right pass, so token numbers follow document order. Comments
     * and whole volatile elements come before the generic tag pattern; URLs are
     * protected last because by then every tag (and its attributes) is already
     * a token.
     */
    private val MARKUP = Regex(
        listOf(
            "<!--[\\s\\S]*?-->",
            "<(pre|code|script|style|svg|textarea)\\b[^>]*>[\\s\\S]*?</\\1\\s*>",
            "<[^>]+>",
            "https?://[^\\s<>\"')]+",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN = Regex("\\u27E6(\\d+)\\u27E7")

    data class Protected(val text: String, val tokens: List<String>)

    /** Replaces markup and URLs with tokens that survive translation. */
    fun protect(html: String): Protected {
        val tokens = mutableListOf<String>()

        fun tokenFor(value: String): String {
            tokens += value
            return "$TOKEN_OPEN${tokens.lastIndex}$TOKEN_CLOSE"
        }

        val out = MARKUP.replace(html) { tokenFor(it.value) }
        return Protected(out, tokens)
    }

    /**
     * Puts every token back. A translator that lost, duplicated or reordered a
     * token produces HTML that no longer matches the original structure, so that
     * result is rejected instead of rendering it.
     */
    fun restore(text: String, tokens: List<String>): String? {
        val found = TOKEN.findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (found != tokens.indices.toList()) return null
        return TOKEN.replace(text) { tokens[it.groupValues[1].toInt()] }
    }

    /**
     * Splits [text] into provider-sized requests without cutting a token. A
     * newline break is preferred when one is available, which keeps paragraphs
     * together; CJK text has no spaces, so a hard character boundary is the
     * fallback.
     */
    fun chunks(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        if (text.length <= maxChars) return listOf(text)

        val tokenRanges = TOKEN.findAll(text).map { it.range }.toList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val spanning = tokenRanges.firstOrNull { it.first < end && end <= it.last }
                if (spanning != null) {
                    end = spanning.first
                } else {
                    val newline = text.lastIndexOf('\n', end - 1)
                    if (newline > start + maxChars / 2) end = newline + 1
                }
                // A single token larger than the limit cannot be split; keep it whole.
                if (end <= start) end = (spanning?.last ?: (start + maxChars - 1)) + 1
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
