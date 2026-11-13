package io.github.tophtab.lspstore.domain.model

import java.util.Locale

/** Each caller supplies its existing label for an empty or unknown size. */
internal fun formatByteSize(
    bytes: Long,
    emptyValue: String = "?",
    locale: Locale = Locale.getDefault(),
): String {
    if (bytes <= 0) return emptyValue
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) {
        String.format(locale, "%.1f MB", mb)
    } else {
        String.format(locale, "%.0f KB", bytes / 1024.0)
    }
}
