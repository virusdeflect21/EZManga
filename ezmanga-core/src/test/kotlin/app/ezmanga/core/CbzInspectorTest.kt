package app.ezmanga.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CbzInspectorTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `pages use natural ordering and skip metadata or hidden files`() {
        val file = tempDirectory.resolve("book.cbz").toFile()
        createTestCbz(file, listOf("10.jpg", "2.png", "1.jpg", "cover.PNG"))

        val contents = CbzInspector.inspect(file)

        assertEquals(listOf("1.jpg", "2.png", "10.jpg", "cover.PNG"), contents.pageEntries)
        assertEquals(4, contents.pageCount)
    }

    @Test
    fun `a non zip file is rejected`() {
        val file = File(tempDirectory.toFile(), "not-a-cbz.cbz")
        file.writeText("this is not a zip archive")

        assertThrows(InvalidCbzException::class.java) {
            CbzInspector.inspect(file)
        }
    }
}
