package io.github.tophtab.lspstore.data.translation

/**
 * Local, network-free language detection for the auto-translate decision.
 *
 * Mirrors komi's `ReadmeLocalizationHelper`: the first 1000 characters decide,
 * non-Latin scripts are recognised by their code-point share with a 15%
 * threshold, Latin text by English stopwords. [detect] returns `null` when the
 * sample identifies nothing, and callers treat that as "translate it": an
 * unknown language is never assumed to be the target language.
 */
internal object ContentLanguage {
    private const val SAMPLE_CHARS = 1000

    /**
     * Enough HTML for roughly [SAMPLE_CHARS] characters of text. The README
     * arrives as HTML, and literal tags would otherwise count as Latin text.
     */
    private const val HTML_SAMPLE_CHARS = 6_000

    private const val SCRIPT_SHARE = 0.15

    private val tags = Regex("<[^>]*>")

    private val englishIndicators = listOf(
        "\\bthe\\b",
        "\\band\\b",
        "\\bfor\\b",
        "\\bwith\\b",
        "\\bthis\\b",
        "\\bthat\\b",
        "\\bfrom\\b",
        "\\bare\\b",
        "\\bwas\\b",
        "\\bhave\\b",
        "\\bhas\\b",
        "\\bwill\\b",
        "\\byou\\b",
        "\\bcan\\b",
        "\\buse\\b",
        "\\binstall\\b",
    ).map { Regex(it) }

    /** Base language code (`zh`, `ja`, `en`, ...) of [text], or null when unknown. */
    fun detect(text: String): String? {
        val sample = text.take(SAMPLE_CHARS)
        if (sample.isBlank()) return null

        val threshold = sample.length * SCRIPT_SHARE
        val lower = sample.lowercase()
        val han = sample.count { it in '\u4e00'..'\u9fff' }
        val kana = sample.count { it in '\u3040'..'\u309f' } +
            sample.count { it in '\u30a0'..'\u30ff' }
        val hangul = sample.count { it in '\uac00'..'\ud7af' }
        val arabic = sample.count { it in '\u0600'..'\u06ff' }
        val cyrillic = sample.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

        return when {
            // Chinese first, like komi: a Han-heavy Japanese sample counts as zh.
            han > threshold -> "zh"
            kana > threshold -> "ja"
            hangul > threshold -> "ko"
            arabic > threshold -> "ar"
            cyrillic > threshold -> "ru"
            englishIndicators.count { it.containsMatchIn(lower) } >= 4 -> "en"
            else -> null
        }
    }

    /** True when [text] is already in [language]; unknown content never matches. */
    fun isLanguage(text: String, language: String): Boolean =
        detect(text)?.equals(language, ignoreCase = true) == true

    /**
     * True when at least one block is clearly not in [language]. Blank blocks
     * are ignored, so a page whose remaining blocks all match stays untranslated;
     * unknown text counts as needing translation.
     */
    fun needsTranslation(language: String, vararg texts: String?): Boolean =
        texts.any { text -> !text.isNullOrBlank() && !isLanguage(text, language) }

    /** Plain text of [html] for detection: tags are dropped, entities are left alone. */
    fun fromHtml(html: String): String = html.take(HTML_SAMPLE_CHARS).replace(tags, " ")
}
