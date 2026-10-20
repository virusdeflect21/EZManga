package app.ezmanga.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LocalBookStoreTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `import copies the upload and delete only removes the TV copy`() {
        val phoneCopy = tempDirectory.resolve("phone copy.cbz").toFile()
        createTestCbz(phoneCopy, listOf("001.jpg", "002.jpg"))

        val store = LocalBookStore(tempDirectory.resolve("tv-library").toFile())
        val incoming = store.newIncomingFile()
        phoneCopy.copyTo(incoming, overwrite = true)

        val imported = store.importDownload(incoming, "My Manga.cbz")

        assertEquals("My Manga", imported.title)
        assertEquals(2, imported.pageCount)
        assertTrue(imported.file.isFile)
        assertEquals(1, store.books().size)
        assertTrue(phoneCopy.isFile)

        assertTrue(store.delete(imported.id))
        assertFalse(imported.file.exists())
        assertTrue(store.books().isEmpty())
        assertTrue(phoneCopy.isFile, "Deleting the TV copy must not delete the phone's file")
    }

    @Test
    fun `metadata survives a new store instance`() {
        val phoneCopy = tempDirectory.resolve("phone.cbz").toFile()
        createTestCbz(phoneCopy, listOf("001.jpg"))
        val root = tempDirectory.resolve("library").toFile()

        val firstStore = LocalBookStore(root)
        val incoming = firstStore.newIncomingFile()
        phoneCopy.copyTo(incoming, overwrite = true)
        val imported = firstStore.importDownload(incoming, "Volume 1.cbz")

        val reopened = LocalBookStore(root)
        val books = reopened.books()

        assertEquals(1, books.size)
        assertEquals(imported.id, books.single().id)
        assertEquals("Volume 1", books.single().title)
        assertEquals(1, books.single().pageCount)
    }
}
