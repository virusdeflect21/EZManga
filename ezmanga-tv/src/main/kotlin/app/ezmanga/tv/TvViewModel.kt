package app.ezmanga.tv

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ezmanga.core.EzMangaServer
import app.ezmanga.core.LocalBookStore
import app.ezmanga.core.NetworkUtils
import app.ezmanga.core.PairingCodeState
import app.ezmanga.core.PairingPayload
import app.ezmanga.core.RotatingPairingCode
import app.ezmanga.core.StoredBook
import app.ezmanga.tv.discovery.TvDiscoveryAdvertiser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.Base64

data class TvUiState(
    val starting: Boolean = true,
    val pairing: PairingPayload? = null,
    val pairingCode: PairingCodeState? = null,
    val books: List<StoredBook> = emptyList(),
    val paired: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val currentBook: StoredBook? = null,
)

class TvViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalBookStore(File(application.filesDir, "ezmanga-library"))
    private val advertiser = TvDiscoveryAdvertiser(application)
    private val _state = MutableStateFlow(TvUiState())
    val state = _state.asStateFlow()

    private var server: EzMangaServer? = null

    private val listener = object : EzMangaServer.Listener {
        override fun onAuthorized() {
            _state.update { it.copy(paired = true) }
        }

        override fun onLibraryChanged() {
            refreshBooks("Library updated")
        }
    }

    init {
        startServer()
    }

    fun refresh() {
        refreshBooks(null)
    }

    fun openBook(book: StoredBook) {
        _state.update { it.copy(currentBook = book, message = null, error = null) }
    }

    fun closeReader() {
        _state.update { it.copy(currentBook = null) }
    }

    fun deleteBook(book: StoredBook) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching { store.delete(book.id) }.getOrDefault(false)
            val books = runCatching { store.books() }.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(
                    books = books,
                    currentBook = if (current.currentBook?.id == book.id) null else current.currentBook,
                    message = if (deleted) {
                        "Deleted \"${book.title}\" from this TV. The phone's copy was not touched."
                    } else {
                        "Could not delete \"${book.title}\" from this TV."
                    },
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = NetworkUtils.findLocalIpv4Address()
                val token = generateToken()
                val deviceName = "EZManga TV (${Build.MODEL})"
                val newServer = EzMangaServer(
                    store = store,
                    pairingToken = token,
                    deviceName = deviceName,
                    listener = listener,
                    pairingCode = RotatingPairingCode(),
                )
                val port = newServer.start()
                server = newServer
                advertiser.start(deviceName, port)

                val pairing = PairingPayload(
                    host = host ?: "127.0.0.1",
                    port = port,
                    token = token,
                    deviceName = deviceName,
                )
                val books = store.books()

                if (BuildConfig.DEBUG) {
                    Log.i("EZMangaTv", "Pairing URI: ${pairing.toUri()}")
                }

                _state.update {
                    it.copy(
                        starting = false,
                        pairing = pairing,
                        pairingCode = newServer.currentPairingCode(),
                        books = books,
                        error = if (host == null) {
                            "No LAN IPv4 address was found. Connect the TV to Wi-Fi or Ethernet before pairing."
                        } else {
                            null
                        },
                    )
                }
                monitorPairingCode(newServer)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        starting = false,
                        error = "The local server could not start: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun refreshBooks(message: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val books = runCatching { store.books() }.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(
                    books = books,
                    message = message ?: current.message,
                )
            }
            if (message != null) {
                delay(4_000)
                _state.update { current ->
                    if (current.message == message) current.copy(message = null) else current
                }
            }
        }
    }

    private fun monitorPairingCode(activeServer: EzMangaServer) {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val snapshot = runCatching { activeServer.currentPairingCode() }.getOrNull()
                if (snapshot != null) {
                    _state.update { current ->
                        if (current.pairingCode?.code ==
                            snapshot.code
                        ) {
                            current
                        } else {
                            current.copy(pairingCode = snapshot)
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun onCleared() {
        advertiser.stop()
        server?.close()
        server = null
        super.onCleared()
    }
}
