package io.github.tophtab.lspstore.domain.model

enum class TranslationProvider {
    DISABLED,
    GOOGLE,
    LIBRE_TRANSLATE,
    DEEPL,
    MICROSOFT,
}

data class TranslationSettings(
    val provider: TranslationProvider = TranslationProvider.DISABLED,
    val targetLang: String = "en",
    val deeplKey: String = "",
    val libreUrl: String = "",
    val libreKey: String = "",
    val microsoftKey: String = "",
    val microsoftRegion: String = "",
) {
    val isConfigured: Boolean
        get() = when (provider) {
            TranslationProvider.DISABLED -> false
            TranslationProvider.GOOGLE -> true
            TranslationProvider.LIBRE_TRANSLATE -> true
            TranslationProvider.DEEPL -> deeplKey.isNotBlank()
            TranslationProvider.MICROSOFT -> microsoftKey.isNotBlank()
        }
}
