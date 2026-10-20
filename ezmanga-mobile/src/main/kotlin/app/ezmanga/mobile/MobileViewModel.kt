package app.ezmanga.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ezmanga.core.EzMangaClient
import app.ezmanga.core.EzMangaDiscovery
import app.ezmanga.core.EzMangaHttpException
import app.ezmanga.core.PairingAddress
import app.ezmanga.core.PairingPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque

data class TvBookSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val addedAt: Long,
)

data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
) {
    val address: String get() = "$host:$port"
}

data class MobileUiState(
    val pairing: PairingPayload? = null,
    val deviceName: String? = null,
    val connecting: Boolean = false,
    val selectedUri: Uri? = null,
    val selectedName: String? = null,
    val selectedSize: Long? = null,
    val uploading: Boolean = false,
    val progress: Float? = null,
    val loadingLibrary: Boolean = false,
    val tvBooks: List<TvBookSummary> = emptyList(),
    val discoveredTvs: List<DiscoveredTv> = emptyList(),
    val discovering: Boolean = false,
    val discoveryError: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class MobileViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MobileUiState())
    val state = _state.asStateFlow()

    private val nsdManager = application.getSystemService(Context.NSD_SERVICE) as? NsdManager

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryTimeout: Job? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolveLock = Any()
    private var resolving = false

    fun pair(raw: String) {
        if (raw.isBlank() || _state.value.connecting) return

        stopDiscovery()
        _state.update { it.copy(connecting = true, error = null, message = null, discoveryError = null) }
        viewModelScope.launch {
            try {
                val payload = PairingPayload.parse(raw)
                applyPairing(payload)
            } catch (e: Exception) {
                _state.update {
                    it.copy(connecting = false, error = humanError(e))
                }
            }
        }
    }

    fun pairWithCode(code: String, address: String) {
        if (_state.value.connecting) return

        val normalizedCode = code.filter { it.isDigit() }.take(6)
        if (normalizedCode.length != 6) {
            _state.update { it.copy(error = "Enter the six-digit code shown on the TV.", message = null) }
            return
        }
        val parsedAddress = PairingAddress.parse(address)
        if (parsedAddress == null) {
            _state.update {
                it.copy(
                    error = "Enter the TV address as IP:port, for example 192.168.1.40:41234.",
                    message = null,
                )
            }
            return
        }

        stopDiscovery()
        _state.update { it.copy(connecting = true, error = null, message = null, discoveryError = null) }

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    EzMangaClient.pairWithCode(parsedAddress.host, parsedAddress.port, normalizedCode)
                }
                val json = JSONObject(response)
                val token = json.optString("token")
                if (token.length < PairingPayload.MIN_TOKEN_LENGTH) {
                    throw IOException("The TV returned an invalid pairing token")
                }
                val version = json.optInt("version", PairingPayload.PROTOCOL_VERSION)
                val announcedName = json.optString("deviceName").takeIf { it.isNotBlank() }
                val payload = PairingPayload(
                    host = parsedAddress.host,
                    port = parsedAddress.port,
                    token = token,
                    deviceName = announcedName ?: "EZManga TV",
                    version = version,
                )
                applyPairing(payload)
            } catch (e: Exception) {
                _state.update {
                    it.copy(connecting = false, error = humanError(e))
                }
            }
        }
    }

    private suspend fun applyPairing(payload: PairingPayload) {
        val info = runCatching {
            withContext(Dispatchers.IO) { EzMangaClient(payload).infoRaw() }
        }.getOrNull()
        val announcedName = info?.let {
            runCatching { JSONObject(it).optString("deviceName") }.getOrNull()
        }?.takeIf { it.isNotBlank() }

        _state.update {
            it.copy(
                pairing = payload,
                deviceName = announcedName ?: payload.deviceName,
                connecting = false,
                discoveredTvs = emptyList(),
                discovering = false,
                discoveryError = null,
                message = "Paired with ${announcedName ?: payload.deviceName}.",
            )
        }
        refreshLibrary()
    }

    fun disconnect() {
        stopDiscovery()
        _state.update {
            MobileUiState(message = "Disconnected from the TV.")
        }
    }

    fun discoverTvs() {
        if (_state.value.connecting) return

        stopDiscovery()
        val manager = nsdManager
        if (manager == null) {
            _state.update {
                it.copy(
                    discovering = false,
                    discoveryError = "Automatic discovery is unavailable on this phone. " +
                        "Enter the TV address shown on screen.",
                )
            }
            return
        }

        _state.update {
            it.copy(discovering = true, discoveryError = null, discoveredTvs = emptyList())
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (this !== discoveryListener) return
                if (!service.serviceType.startsWith(EzMangaDiscovery.SERVICE_TYPE.trimEnd('.'))) return
                synchronized(resolveLock) { resolveQueue.addLast(service) }
                resolveNext()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                if (this !== discoveryListener) return
                _state.update { current ->
                    current.copy(discoveredTvs = current.discoveredTvs.filterNot { it.name == service.serviceName })
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (this !== discoveryListener) return
                _state.update { it.copy(discovering = false) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (this !== discoveryListener) return
                stopDiscovery(finish = false)
                _state.update {
                    it.copy(
                        discovering = false,
                        discoveryError = "Could not search for TVs (NSD $errorCode). " +
                            "Enter the address shown on the TV.",
                    )
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        discoveryListener = listener
        try {
            manager.discoverServices(EzMangaDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discoveryListener = null
            _state.update {
                it.copy(
                    discovering = false,
                    discoveryError = "Could not search for TVs: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            return
        }

        discoveryTimeout = viewModelScope.launch {
            delay(12_000)
            if (listener !== discoveryListener) return@launch
            stopDiscovery(finish = false)
            _state.update { it.copy(discovering = false) }
        }
    }

    fun stopDiscovery() = stopDiscovery(finish = true)

    private fun stopDiscovery(finish: Boolean) {
        discoveryTimeout?.cancel()
        discoveryTimeout = null
        discoveryListener?.let { active ->
            runCatching { nsdManager?.stopServiceDiscovery(active) }
        }
        discoveryListener = null
        synchronized(resolveLock) {
            resolveQueue.clear()
            resolving = false
        }
        if (finish) _state.update { it.copy(discovering = false) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val service = synchronized(resolveLock) {
            if (resolving || discoveryListener == null) return
            if (resolveQueue.isEmpty()) return
            val next = resolveQueue.removeFirst()
            resolving = true
            next
        }

        val activeListener = discoveryListener ?: return

        try {
            nsdManager?.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        finishResolve()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (discoveryListener !== activeListener) {
                            finishResolve()
                            return
                        }
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        if (host != null && !host.contains(':') && port in 1..65535) {
                            val tv = DiscoveredTv(
                                name = serviceInfo.serviceName,
                                host = host,
                                port = port,
                            )
                            _state.update { current ->
                                if (current.discoveredTvs.any { it.address == tv.address }) {
                                    current
                                } else {
                                    current.copy(discoveredTvs = current.discoveredTvs + tv)
                                }
                            }
                        }
                        finishResolve()
                    }
                },
            ) ?: finishResolve()
        } catch (e: Exception) {
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(resolveLock) { resolving = false }
        resolveNext()
    }

    fun selectBook(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val name = queryColumn(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: "manga.cbz"
            val size = queryColumn(uri, OpenableColumns.SIZE)?.toLongOrNull()?.takeIf { it > 0 }
            _state.update {
                it.copy(
                    selectedUri = uri,
                    selectedName = name,
                    selectedSize = size,
                    message = null,
                    error = null,
                )
            }
        }
    }

    fun upload() {
        val current = _state.value
        val pairing = current.pairing ?: return
        val uri = current.selectedUri ?: return
        if (current.uploading) return

        val displayName = current.selectedName ?: "manga.cbz"
        val expectedSize = current.selectedSize
        val app = getApplication<Application>()

        _state.update { it.copy(uploading = true, progress = 0f, error = null, message = null) }

        viewModelScope.launch(Dispatchers.IO) {
            var cache: File? = null
            try {
                val client = EzMangaClient(pairing)
                val response = if (expectedSize != null && expectedSize > 0) {
                    // Stream straight from the document provider, avoiding an extra copy on disk.
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IOException("The selected document could not be opened")
                    input.use {
                        client.upload(it, expectedSize, displayName) { sent, total ->
                            if (total > 0) {
                                _state.update { state ->
                                    state.copy(progress = (sent.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } else {
                    cache = File(app.cacheDir, "ezmanga-upload-${System.currentTimeMillis()}.cbz")
                    copyToCache(app, uri, cache)
                    client.upload(cache, displayName) { sent, total ->
                        if (total > 0) {
                            val ratio = (sent.toFloat() / total).coerceIn(0f, 1f)
                            _state.update { it.copy(progress = 0.1f + (ratio * 0.9f)) }
                        }
                    }
                }

                val title = runCatching {
                    JSONObject(response).optJSONObject("book")?.optString("title")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: displayName

                _state.update {
                    it.copy(
                        uploading = false,
                        progress = 1f,
                        selectedUri = null,
                        selectedName = null,
                        selectedSize = null,
                        message = "Uploaded \"$title\" to the TV. Your phone's original file is still untouched.",
                    )
                }
                refreshLibrary()
            } catch (e: Exception) {
                _state.update {
                    it.copy(uploading = false, progress = null, error = humanError(e))
                }
            } finally {
                cache?.delete()
            }
        }
    }

    private fun copyToCache(app: Application, uri: Uri, cache: File) {
        var copied = 0L
        var lastPercent = -1
        val source = app.contentResolver.openInputStream(uri)
            ?: throw IOException("The selected document could not be opened")

        source.use { input ->
            cache.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val expected = _state.value.selectedSize
                    if (expected != null && expected > 0) {
                        val percent = ((copied * 100) / expected).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.update { it.copy(progress = percent / 1000f) }
                        }
                    }
                }
            }
        }
    }

    fun refreshLibrary() {
        val pairing = _state.value.pairing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loadingLibrary = true) }
            try {
                val raw = EzMangaClient(pairing).libraryRaw()
                val books = parseBooks(raw)
                _state.update { it.copy(loadingLibrary = false, tvBooks = books) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingLibrary = false, error = humanError(e)) }
            }
        }
    }

    fun clearStatus() {
        _state.update { it.copy(message = null, error = null, discoveryError = null) }
    }

    private fun parseBooks(raw: String): List<TvBookSummary> {
        val array = JSONObject(raw).optJSONArray("books") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    TvBookSummary(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        pageCount = item.optInt("pageCount"),
                        sizeBytes = item.optLong("sizeBytes"),
                        addedAt = item.optLong("addedAt"),
                    ),
                )
            }
        }
    }

    private fun queryColumn(uri: Uri, column: String): String? {
        return getApplication<Application>().contentResolver
            .query(uri, arrayOf(column), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }

    private fun humanError(error: Exception): String = when (error) {
        is EzMangaHttpException -> when (error.statusCode) {
            401 -> "That code is not valid any more. Check the TV screen and try the new code."
            429 -> "Too many incorrect codes. Wait about a minute, then try the new code on the TV."
            else -> "The TV rejected the request (HTTP ${error.statusCode}). ${error.responseBody.take(240)}"
        }
        is ConnectException, is UnknownHostException -> {
            "Could not reach the TV. Make sure both devices are on the same Wi-Fi and the TV app is open."
        }
        is SocketTimeoutException -> "The TV did not respond in time."
        else -> error.message ?: error.javaClass.simpleName
    }

    override fun onCleared() {
        stopDiscovery(finish = false)
        super.onCleared()
    }
}
