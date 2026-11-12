package io.github.tophtab.lspstore.data.repository

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Ignore writes to other fields before decoding a potentially large payload. */
internal fun <T, R> Flow<Preferences>.decodedField(
    key: Preferences.Key<T>,
    decode: (T?) -> R,
): Flow<R> = map { it[key] }.distinctUntilChanged().map(decode)
