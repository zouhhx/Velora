package com.zouhu.speedlimit.data.map.baidu

import android.graphics.Bitmap

/** Keeps the last static map bitmap so opening the map never decodes during the transition. */
object MapImageCache {
    private val lock = Any()
    private var key: String? = null
    private var image: Bitmap? = null

    fun get(key: String): Bitmap? = synchronized(lock) {
        if (this.key == key) image else null
    }

    fun put(key: String, bitmap: Bitmap) = synchronized(lock) {
        this.key = key
        image = bitmap
    }
}
