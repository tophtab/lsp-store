package io.github.tophtab.lspstore.domain.usecase

import io.github.tophtab.lspstore.domain.model.Release

/** Only the current release/request may publish metadata into a detail picker. */
class ReleaseProbeRequests {
    data class Request(val sequence: Long, val release: Release)
    private var sequence = 0L
    private var current: Request? = null
    fun begin(release: Release): Request = Request(++sequence, release).also { current = it }
    fun isCurrent(request: Request): Boolean = current == request
    fun invalidate() { current = null }
}
