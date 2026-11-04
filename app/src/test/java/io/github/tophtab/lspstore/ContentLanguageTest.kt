package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.translation.ContentLanguage
import io.github.tophtab.lspstore.data.translation.translationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The auto-translate decision comes from this local detector, so its known
 * limits are pinned here: komi's 15% script share, the Chinese-before-Japanese
 * order, and English only through stopwords.
 */
class ContentLanguageTest {

    @Test
    fun `scripts are recognised by their share of the sample`() {
        assertEquals("zh", ContentLanguage.detect("把状态栏的电池图标换成圆形，并支持颜色自定义。"))
        assertEquals("ja", ContentLanguage.detect("これはテストです。モジュールの説明をここに書きます。"))
        assertEquals("ko", ContentLanguage.detect("이 모듈은 상태 표시줄의 아이콘을 변경합니다."))
        assertEquals("ru", ContentLanguage.detect("Этот модуль изменяет значки в строке состояния."))
        assertEquals("ar", ContentLanguage.detect("هذا الوحدة تغير أيقونات شريط الحالة."))
    }

    @Test
    fun `han-heavy japanese is chinese, matching komi's order`() {
        // Han characters and kana both clear the threshold; Chinese wins first.
        assertEquals("zh", ContentLanguage.detect("日本語説明のテスト"))
    }

    @Test
    fun `english needs four stopwords, so a short label stays unknown`() {
        assertEquals(
            "en",
            ContentLanguage.detect("This module lets you use the installer and it will work for that app."),
        )
        assertEquals(null, ContentLanguage.detect("Battery icon"))
    }

    @Test
    fun `a script below the threshold does not identify the text`() {
        assertEquals(null, ContentLanguage.detect("中" + "a".repeat(99)))
        assertEquals(null, ContentLanguage.detect("中".repeat(15) + "a".repeat(85)))
    }

    @Test
    fun `html markup is dropped before sampling`() {
        val html = "<p>这是一个中文说明</p><img src=\"preview.png\" alt=\"preview\">"
        assertEquals("zh", ContentLanguage.detect(ContentLanguage.fromHtml(html)))
    }

    @Test
    fun `only a known matching language is treated as already translated`() {
        assertTrue(ContentLanguage.isLanguage("把状态栏的电池图标换成圆形。", "zh"))
        assertFalse(ContentLanguage.isLanguage("把状态栏的电池图标换成圆形。", "en"))
        // Unknown text must be translated, not assumed to match anything.
        assertFalse(ContentLanguage.isLanguage("Battery icon", "en"))
    }

    @Test
    fun `a page is only skipped when every block matches the target`() {
        assertFalse(
            ContentLanguage.needsTranslation(
                "zh",
                "把状态栏的电池图标换成圆形。",
                "<p>支持 Android 12 和更高版本。</p>".let(ContentLanguage::fromHtml),
                null,
            ),
        )
        // One foreign block is enough to translate the page.
        assertTrue(
            ContentLanguage.needsTranslation(
                "zh",
                "把状态栏的电池图标换成圆形。",
                ContentLanguage.fromHtml("<p>This module replaces the battery icon and will work for that app.</p>"),
            ),
        )
        // Unknown text is translated rather than assumed to match.
        assertTrue(ContentLanguage.needsTranslation("en", "Battery icon"))
    }

    @Test
    fun `the comparison target is the base language, not a provider spelling`() {
        assertEquals("zh", translationLanguage(Locale.forLanguageTag("zh-TW")))
        assertEquals("en", translationLanguage(Locale.US))
        assertEquals("en", translationLanguage(Locale.forLanguageTag("und")))
    }
}
