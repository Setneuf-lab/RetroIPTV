package com.ale.cineiptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ale.cineiptv.data.Network

/**
 * Configuración global de la app: caché de logos y carátulas pensada para TV Box
 * con poca memoria (Fire TV Stick).
 */
class RetroApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        com.ale.cineiptv.data.SmartDns.mode = com.ale.cineiptv.data.Prefs(this).dnsMode
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // En memoria: como mucho el 15 % de la RAM de la app (el resto, para el vídeo)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        // En disco: las carátulas ya vistas se cargan al instante y sin gastar datos
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        // Formato de color ligero: la mitad de memoria por imagen, sin diferencia apreciable
        .allowRgb565(true)
        // Muchos servidores marcan los logos como "no guardar": se guardan igualmente
        .respectCacheHeaders(false)
        .crossfade(false)
        .okHttpClient { Network.client }
        .build()
}
