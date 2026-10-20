package org.lspstore.domain.variant

import org.lspstore.domain.model.ReleaseAsset

/** Fetches remote APK metadata without downloading the whole file (P2). */
fun interface ApkProbe {
    suspend fun fetch(asset: ReleaseAsset): ApkMeta?
}
