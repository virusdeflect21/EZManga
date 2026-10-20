package app.ezmanga.tv.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.ezmanga.core.CbzInspector
import mihon.core.archive.ArchiveReader
import java.io.Closeable
import java.io.File

/**
 * Loads CBZ pages through Mihon's own libarchive-backed [ArchiveReader].
 *
 * The archive is kept open (it is mmap-backed) and decoded pages are kept in a
 * byte-bounded LRU cache. Page turns therefore avoid both reopening the CBZ and
 * decoding the same page again.
 */
class MangaPageLoader(private val file: File) : Closeable {
    @Volatile
    private var cachedEntries: List<String>? = null

    private val lock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var archive: ArchiveReader? = null

    private val cache = object : LruCache<CacheKey, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun pageEntries(): List<String> = cachedEntries ?: synchronized(lock) {
        cachedEntries ?: CbzInspector.inspect(file).pageEntries.also { cachedEntries = it }
    }

    fun cached(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? =
        cache.get(CacheKey(index, maxWidth, maxHeight))

    fun load(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val key = CacheKey(index, maxWidth, maxHeight)
        cache.get(key)?.let { return it }

        val entry = pageEntries().getOrNull(index) ?: return null
        val bytes = readEntry(entry) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while ((bounds.outWidth / (sampleSize * 2)) >= maxWidth ||
            (bounds.outHeight / (sampleSize * 2)) >= maxHeight
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun prefetch(index: Int, maxWidth: Int, maxHeight: Int) {
        if (index !in pageEntries().indices) return
        if (cached(index, maxWidth, maxHeight) != null) return
        runCatching { load(index, maxWidth, maxHeight) }
    }

    private fun readEntry(entryName: String): ByteArray? = synchronized(lock) {
        try {
            val reader = archive ?: openArchive() ?: return null
            reader.getInputStream(entryName)?.use { it.readBytes() }
        } catch (_: Exception) {
            closeArchive()
            null
        }
    }

    private fun openArchive(): ArchiveReader? {
        val openedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            ArchiveReader(openedDescriptor).also {
                descriptor = openedDescriptor
                archive = it
            }
        } catch (e: Exception) {
            openedDescriptor.close()
            throw e
        }
    }

    override fun close() {
        synchronized(lock) {
            closeArchive()
        }
        cache.evictAll()
    }

    private fun closeArchive() {
        runCatching { archive?.close() }
        archive = null
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private data class CacheKey(val index: Int, val maxWidth: Int, val maxHeight: Int)

    private companion object {
        fun cacheSizeBytes(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 8).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024).toInt()
        }
    }
}
