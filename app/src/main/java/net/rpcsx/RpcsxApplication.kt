package net.rpcsx

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/**
 * Builds the singleton Coil [ImageLoader] explicitly so remote game-cover loading
 * works in RELEASE builds. Coil's OkHttp network fetcher is normally registered
 * via a ServiceLoader, which R8 strips during minification - so on release APKs
 * (what testers run) every https cover fetch silently failed and the grid fell
 * back to the local icon. Adding the fetcher by hand here is R8-proof, and a
 * small disk cache keeps fetched covers across launches.
 */
class RpcsxApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
}
