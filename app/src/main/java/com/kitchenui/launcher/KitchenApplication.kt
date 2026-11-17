package com.kitchenui.launcher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Sets up one shared image cache: posters and recipe photos load once and then come from disk. */
class KitchenApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
            // Poster links rarely change; keep them even when the server says not to cache.
            .respectCacheHeaders(false)
            // Half the memory per image on the OptiPlex's integrated graphics; no visible difference for photos.
            .allowRgb565(true)
            .crossfade(false)
            .build()
}
