package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.translation.translationTarget
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The target follows the app language, but providers spell the same language
 * differently. These are the spellings the settings no longer ask the user for.
 */
class TranslationTargetTest {

    @Test
    fun `chinese keeps its script`() {
        val simplified = Locale.forLanguageTag("zh-CN")
        val traditional = Locale.forLanguageTag("zh-TW")

        assertEquals("ZH-HANS", translationTarget(TranslationProvider.DEEPL, simplified))
        assertEquals("ZH-HANT", translationTarget(TranslationProvider.DEEPL, traditional))
        assertEquals("zh-CN", translationTarget(TranslationProvider.GOOGLE, simplified))
        assertEquals("zh-TW", translationTarget(TranslationProvider.GOOGLE, traditional))
        assertEquals("zh-Hans", translationTarget(TranslationProvider.MICROSOFT, simplified))
        assertEquals("zh-Hant", translationTarget(TranslationProvider.MICROSOFT, traditional))
        assertEquals("zh", translationTarget(TranslationProvider.LIBRE_TRANSLATE, traditional))
    }

    @Test
    fun `deepL gets the region it needs`() {
        assertEquals("EN-US", translationTarget(TranslationProvider.DEEPL, Locale.US))
        assertEquals("EN-GB", translationTarget(TranslationProvider.DEEPL, Locale.UK))
        assertEquals("PT-BR", translationTarget(TranslationProvider.DEEPL, Locale.forLanguageTag("pt-BR")))
        assertEquals("PT-PT", translationTarget(TranslationProvider.DEEPL, Locale.forLanguageTag("pt-PT")))
        assertEquals("NB", translationTarget(TranslationProvider.DEEPL, Locale.forLanguageTag("nb-NO")))
    }

    @Test
    fun `an undetermined language falls back to English`() {
        assertEquals("EN-US", translationTarget(TranslationProvider.DEEPL, Locale.forLanguageTag("und")))
        assertEquals("en", translationTarget(TranslationProvider.GOOGLE, Locale.forLanguageTag("und")))
    }
}
