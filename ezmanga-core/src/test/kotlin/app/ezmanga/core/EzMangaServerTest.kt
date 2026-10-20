package app.ezmanga.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EzMangaServerTest {
    @TempDir
    lateinit var tempDirectory: Path

    private lateinit var store: LocalBookStore
    private lateinit var server: EzMangaServer
    private lateinit var client: EzMangaClient

    @BeforeEach
    fun setUp() {
        store = LocalBookStore(tempDirectory.resolve("tv-library").toFile())
        server = EzMangaServer(store, TOKEN, "Test TV")
        val port = server.start()
        client = EzMangaClient(PairingPayload("127.0.0.1", port, TOKEN, "Test TV"))
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `upload list and delete keep the phone source file intact`() {
        val phoneCopy = tempDirectory.resolve("phone.cbz").toFile()
        createTestCbz(phoneCopy, listOf("001.jpg", "002.png", "003.jpeg"))

        assertTrue(client.ping())

        val uploadResponse = client.upload(phoneCopy, "phone.cbz") { _, _ -> }
        assertTrue(uploadResponse.contains("\"pageCount\":3"))

        val books = store.books()
        assertEquals(1, books.size)
        assertTrue(client.libraryRaw().contains("\"pageCount\":3"))
        assertTrue(phoneCopy.isFile)

        assertTrue(client.delete(books.single().id))
        assertTrue(store.books().isEmpty())
        assertTrue(phoneCopy.isFile, "Deleting from the TV must not touch the phone's original file")
    }

    @Test
    fun `an invalid pairing token is rejected`() {
        val wrongClient = EzMangaClient(PairingPayload("127.0.0.1", server.port, WRONG_TOKEN, "Test TV"))

        val error = assertThrows<EzMangaHttpException> { wrongClient.infoRaw() }

        assertEquals(401, error.statusCode)
        assertTrue(store.books().isEmpty())
    }

    @Test
    fun `a non CBZ upload is rejected`() {
        val notACbz = tempDirectory.resolve("notes.cbz").toFile()
        notACbz.writeText("not a comic archive")

        val error = assertThrows<EzMangaHttpException> {
            client.upload(notACbz, "notes.cbz") { _, _ -> }
        }

        assertEquals(422, error.statusCode)
        assertTrue(store.books().isEmpty())
    }

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
        private const val WRONG_TOKEN = "fedcba9876543210fedcba9876543210"
    }
}
