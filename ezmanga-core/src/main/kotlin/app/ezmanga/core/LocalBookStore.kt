package app.ezmanga.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class BookMetadata(
    val id: String,
    val title: String,
    val originalFilename: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val addedAt: Long,
)

data class StoredBook(val metadata: BookMetadata, val file: File) {
    val id: String get() = metadata.id
    val title: String get() = metadata.title
    val pageCount: Int get() = metadata.pageCount
    val sizeBytes: Long get() = metadata.sizeBytes
    val addedAt: Long get() = metadata.addedAt
}

/**
 * The TV's private library.
 *
 * Every imported file is moved into its own directory below [root]. Deleting a
 * book removes that directory only; the phone's original document is never
 * referenced or modified.
 */
class LocalBookStore(private val root: File) {
    private val lock = Any()

    val incomingDirectory: File get() = File(root, "incoming")

    init {
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not create library directory: $root")
        if (!incomingDirectory.exists() && !incomingDirectory.mkdirs()) {
            throw IOException("Could not create upload directory: $incomingDirectory")
        }
    }

    fun newIncomingFile(): File = File.createTempFile("upload-", ".part", incomingDirectory)

    fun books(): List<StoredBook> = synchronized(lock) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "incoming" }
            .mapNotNull { readBook(it) }
            .sortedWith(compareByDescending<StoredBook> { it.addedAt }.thenBy { it.title.lowercase() })
    }

    fun book(id: String): StoredBook? = synchronized(lock) {
        if (!isValidId(id)) return null
        readBook(File(root, id))
    }

    fun importDownload(tempFile: File, originalFilename: String): StoredBook {
        val contents = CbzInspector.inspect(tempFile)
        val safeName = sanitizeFilename(originalFilename)
        val title = safeName.substringBeforeLast('.', safeName).ifBlank { "Untitled manga" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val directory = File(root, id)
        val target = File(directory, BOOK_FILE)

        synchronized(lock) {
            if (!directory.mkdirs()) throw IOException("Could not create a directory for the book")

            try {
                move(tempFile, target)
                val metadata = BookMetadata(
                    id = id,
                    title = title.take(200),
                    originalFilename = safeName,
                    pageCount = contents.pageCount,
                    sizeBytes = target.length(),
                    addedAt = System.currentTimeMillis(),
                )
                writeMetadata(directory, metadata)
                return StoredBook(metadata, target)
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!isValidId(id)) return false

        val directory = File(root, id)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = runCatching { directory.canonicalFile.toPath() }.getOrNull() ?: return false
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) return false
        if (!directory.exists()) return false

        directory.deleteRecursively()
    }

    fun totalBytes(): Long = books().sumOf { it.sizeBytes }

    private fun readBook(directory: File): StoredBook? {
        if (!directory.isDirectory || !isValidId(directory.name)) return null

        val bookFile = File(directory, BOOK_FILE)
        val metadataFile = File(directory, METADATA_FILE)
        if (!bookFile.isFile || !metadataFile.isFile) return null

        return runCatching {
            val properties = Properties()
            FileInputStream(metadataFile).use { properties.load(it) }

            val id = properties.getProperty("id") ?: return@runCatching null
            val title = properties.getProperty("title") ?: return@runCatching null
            if (id != directory.name) return@runCatching null

            val metadata = BookMetadata(
                id = id,
                title = title,
                originalFilename = properties.getProperty("filename") ?: bookFile.name,
                pageCount = properties.getProperty("pageCount")?.toIntOrNull() ?: 0,
                sizeBytes = properties.getProperty("sizeBytes")?.toLongOrNull() ?: bookFile.length(),
                addedAt = properties.getProperty("addedAt")?.toLongOrNull() ?: 0L,
            )
            StoredBook(metadata, bookFile)
        }.getOrNull()
    }

    private fun writeMetadata(directory: File, metadata: BookMetadata) {
        val properties = Properties().apply {
            setProperty("id", metadata.id)
            setProperty("title", metadata.title)
            setProperty("filename", metadata.originalFilename)
            setProperty("pageCount", metadata.pageCount.toString())
            setProperty("sizeBytes", metadata.sizeBytes.toString())
            setProperty("addedAt", metadata.addedAt.toString())
        }

        val target = File(directory, METADATA_FILE)
        val temporary = File(directory, "$METADATA_FILE.tmp")
        FileOutputStream(temporary).use { properties.store(it, "EZManga TV local copy") }
        move(temporary, target)
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitizeFilename(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.map { if (it.isISOControl()) '_' else it }.joinToString("").trim()
        val name = cleaned.ifBlank { "ezmanga-upload.cbz" }
        return if (name.contains('.')) name else "$name.cbz"
    }

    private fun isValidId(id: String): Boolean = id.matches(ID_PATTERN)

    companion object {
        private const val BOOK_FILE = "book.cbz"
        private const val METADATA_FILE = "book.properties"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
