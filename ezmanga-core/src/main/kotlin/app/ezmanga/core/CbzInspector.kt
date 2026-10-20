package app.ezmanga.core

import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

class InvalidCbzException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class CbzContents(val pageEntries: List<String>) {
    val pageCount: Int get() = pageEntries.size
}

object CbzInspector {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
    private val chunkRegex = Regex("\\d+|\\D+")

    fun inspect(file: File): CbzContents {
        if (!file.isFile || file.length() == 0L) throw InvalidCbzException("The CBZ file is empty")

        try {
            ZipFile(file).use { zip ->
                val pages = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .filter { isPage(it) }
                    .sortedWith(Comparator { left, right -> naturalCompare(left, right) })
                    .toList()

                if (pages.isEmpty()) throw InvalidCbzException("The CBZ has no supported image pages")
                return CbzContents(pages)
            }
        } catch (e: InvalidCbzException) {
            throw e
        } catch (e: ZipException) {
            throw InvalidCbzException("The file is not a valid CBZ/ZIP archive", e)
        } catch (e: Exception) {
            throw InvalidCbzException("The CBZ could not be read: ${e.message}", e)
        }
    }

    fun isPage(name: String): Boolean {
        if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")) return false
        return name.substringAfterLast('.', "").lowercase() in imageExtensions
    }

    fun naturalCompare(left: String, right: String): Int {
        val leftChunks = chunkRegex.findAll(left).map { it.value }.toList()
        val rightChunks = chunkRegex.findAll(right).map { it.value }.toList()

        for (index in 0 until minOf(leftChunks.size, rightChunks.size)) {
            val result = compareChunk(leftChunks[index], rightChunks[index])
            if (result != 0) return result
        }

        val lengthResult = leftChunks.size.compareTo(rightChunks.size)
        return if (lengthResult != 0) lengthResult else left.compareTo(right)
    }

    private fun compareChunk(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            left.compareTo(right, ignoreCase = true)
        }
    }
}
