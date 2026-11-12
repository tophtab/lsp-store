package io.github.tophtab.lspstore.data.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PackageIcon(val packageName: String, val lastUpdateTime: Long, val sizePx: Int)

internal class PackageIconKeyer : Keyer<PackageIcon> {
    override fun key(data: PackageIcon, options: Options): String =
        "package-icon:${data.packageName}:${data.lastUpdateTime}:${data.sizePx}"
}

/** PackageManager and drawable conversion both run off the Compose thread. */
internal class PackageIconFetcher(private val data: PackageIcon, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val drawable = context.packageManager.getApplicationIcon(data.packageName)
        val bitmap = drawable.toBitmap(data.sizePx, data.sizePx, Bitmap.Config.ARGB_8888)
        DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<PackageIcon> {
        override fun create(data: PackageIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            PackageIconFetcher(data, options.context)
    }
}
