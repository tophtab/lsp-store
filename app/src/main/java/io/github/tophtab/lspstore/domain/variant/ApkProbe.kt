package io.github.tophtab.lspstore.domain.variant

import io.github.tophtab.lspstore.domain.model.ReleaseAsset

/** Fetches remote APK metadata without downloading the whole file (P2). */
fun interface ApkProbe {
    suspend fun fetch(asset: ReleaseAsset): ApkMeta?
}
