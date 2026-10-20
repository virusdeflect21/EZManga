package app.ezmanga.tv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ezmanga.core.PairingCodeState
import app.ezmanga.core.QrCodeEncoder
import app.ezmanga.core.StoredBook
import app.ezmanga.tv.TvUiState
import app.ezmanga.tv.TvViewModel
import app.ezmanga.tv.reader.MangaPageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class TvScreen { LIBRARY, PAIR }

/**
 * First-page thumbnails are shared between recompositions and between visits to
 * the library. The cache is byte-bounded so a large library cannot retain every
 * decoded cover in memory.
 */
private val coverCache = object : LruCache<String, Bitmap>(18 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
fun TvRoot(viewModel: TvViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val currentBook = state.currentBook
    var requestedScreen by remember { mutableStateOf<TvScreen?>(null) }
    val activeScreen = requestedScreen ?: if (state.books.isEmpty()) TvScreen.PAIR else TvScreen.LIBRARY

    LaunchedEffect(state.books.size) {
        if (requestedScreen == TvScreen.PAIR && state.books.isNotEmpty()) {
            requestedScreen = TvScreen.LIBRARY
        }
    }

    BackHandler(enabled = currentBook != null) {
        viewModel.closeReader()
    }
    BackHandler(
        enabled = currentBook == null && activeScreen == TvScreen.PAIR && state.books.isNotEmpty(),
    ) {
        requestedScreen = TvScreen.LIBRARY
    }

    when {
        currentBook != null -> ReaderScreen(
            book = currentBook,
            message = state.message,
            onBack = viewModel::closeReader,
            onDelete = viewModel::deleteBook,
            onDismissMessage = viewModel::clearMessage,
        )
        activeScreen == TvScreen.PAIR -> PairScreen(
            state = state,
            canGoBack = state.books.isNotEmpty(),
            onBack = { requestedScreen = TvScreen.LIBRARY },
            onDismissMessage = viewModel::clearMessage,
        )
        else -> LibraryScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onPair = { requestedScreen = TvScreen.PAIR },
            onOpen = viewModel::openBook,
            onDismissMessage = viewModel::clearMessage,
        )
    }
}

@Composable
private fun LibraryScreen(
    state: TvUiState,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onOpen: (StoredBook) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val firstBookFocus = remember { FocusRequester() }

    LaunchedEffect(state.books.firstOrNull()?.id) {
        if (state.books.isNotEmpty()) {
            delay(150)
            runCatching { firstBookFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvTopBar(
            title = "EZManga",
            subtitle = "Library",
            paired = state.paired,
            count = state.books.size,
            onPair = onPair,
            onRefresh = onRefresh,
        )

        state.message?.let { message ->
            MessageBanner(message = message, onDismiss = onDismissMessage)
        }

        if (state.books.isEmpty()) {
            EmptyLibrary(modifier = Modifier.weight(1f), onPair = onPair)
        } else {
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                columns = GridCells.Adaptive(minSize = 190.dp),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(state.books, key = { _, book -> book.id }) { index, book ->
                    BookGridItem(
                        book = book,
                        requester = if (index == 0) firstBookFocus else null,
                        onClick = { onOpen(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairScreen(
    state: TvUiState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvTopBar(
            title = "Pair phone",
            subtitle = "Local Wi-Fi",
            paired = state.paired,
            onBack = if (canGoBack) onBack else null,
        )

        state.message?.let { message ->
            MessageBanner(message = message, onDismiss = onDismissMessage)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Pair your phone",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Scan the QR code, or enter the six-digit code and TV address in the phone app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))

                    when {
                        state.starting -> StartingPairing()
                        state.pairing != null -> PairingContent(state)
                        else -> PairingUnavailable(state.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTopBar(
    title: String,
    subtitle: String,
    paired: Boolean,
    count: Int? = null,
    onBack: (() -> Unit)? = null,
    onPair: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Back")
                }
                Spacer(Modifier.width(4.dp))
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (count != null) {
                        Spacer(Modifier.width(8.dp))
                        CountPill(count)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))
            StatusPill(paired)

            if (onPair != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onPair,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Pair phone")
                }
            }
            if (onRefresh != null) {
                TextButton(
                    onClick = onRefresh,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(paired: Boolean) {
    val color = if (paired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary

    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (paired) "Phone connected" else "Waiting for phone",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CountPill(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = count.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PairingContent(state: TvUiState) {
    val pairing = state.pairing ?: return
    val qrBitmap = remember(pairing.toUri()) {
        runCatching {
            QrCodeEncoder.encode(pairing.toUri(), 620).toAndroidBitmap()
        }.getOrNull()
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(224.dp),
            shape = MaterialTheme.shapes.medium,
            color = Color.White,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "EZManga pairing QR code",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "QR unavailable",
                        color = Color.Black,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Six-digit code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            PairingCodeBoxes(state.pairingCode)
            Spacer(Modifier.height(10.dp))
            CodeCountdown(state.pairingCode)
            Spacer(Modifier.height(16.dp))
            AddressRow(pairing.host, pairing.port)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "The code changes every five minutes. The QR code stays valid until this TV app " +
                    "closes. Keep this screen open while pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.error?.let { error ->
        Spacer(Modifier.height(14.dp))
        ErrorSurface(error)
    }
}

@Composable
private fun StartingPairing() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Starting the local TV server…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingUnavailable(error: String?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Pairing is not available",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = error ?: "The TV could not start its local server. Check the network and restart the app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorSurface(error: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PairingCodeBoxes(code: PairingCodeState?) {
    val digits = code?.code.orEmpty().padEnd(6, ' ')
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        digits.forEach { digit ->
            Surface(
                modifier = Modifier.size(width = 42.dp, height = 52.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = digit.toString(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeCountdown(code: PairingCodeState?) {
    if (code == null) {
        Text(
            text = "Preparing a fresh code…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val remainingSeconds = rememberCountdownSeconds(code.expiresAt)
    val label = if (remainingSeconds > 0) {
        String.format(Locale.US, "New code in %d:%02d", remainingSeconds / 60, remainingSeconds % 60)
    } else {
        "Refreshing code…"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = {
            (remainingSeconds.toFloat() / (code.lifetimeMillis / 1000f).coerceAtLeast(1f))
                .coerceIn(0f, 1f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun rememberCountdownSeconds(expiresAt: Long): Long {
    var remaining by remember(expiresAt) {
        mutableLongStateOf(((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
    }

    LaunchedEffect(expiresAt) {
        while (true) {
            remaining = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    return remaining
}

@Composable
private fun AddressRow(host: String, port: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TV address",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$host:$port",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier, onPair: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No books yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pair your phone and send a CBZ. The TV keeps its own copy; your phone keeps the original.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPair) {
                Text("Pair phone")
            }
        }
    }
}

@Composable
private fun BookGridItem(
    book: StoredBook,
    requester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember(book.id) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.extraSmall
    val requesterModifier = if (requester != null) Modifier.focusRequester(requester) else Modifier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(requesterModifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (focused) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            BookCover(book)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CoverBadge(
                    text = book.pageCount.toString(),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                CoverBadge(
                    text = "CBZ",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun CoverBadge(text: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BookCover(book: StoredBook) {
    var cover by remember(book.id) { mutableStateOf(coverCache.get(book.id)) }

    LaunchedEffect(book.id) {
        if (cover == null) {
            cover = withContext(Dispatchers.IO) {
                runCatching { loadCover(book) }.getOrNull()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = book.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

private fun loadCover(book: StoredBook): Bitmap? {
    coverCache.get(book.id)?.let { return it }

    val loader = MangaPageLoader(book.file)
    return try {
        loader.load(0, 480, 720)?.also { coverCache.put(book.id, it) }
    } finally {
        loader.close()
    }
}

@Composable
private fun ReaderScreen(
    book: StoredBook,
    message: String?,
    onBack: () -> Unit,
    onDelete: (StoredBook) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val loader = remember(book.id) { MangaPageLoader(book.file) }
    var pageIndex by remember(book.id) { mutableIntStateOf(0) }
    var bitmap by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(book.id) { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceAtLeast(640)
    val maxHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceAtLeast(480)

    DisposableEffect(book.id) {
        onDispose { loader.close() }
    }

    LaunchedEffect(book.id, pageIndex) {
        val cached = loader.cached(pageIndex, maxWidth, maxHeight)
        bitmap = cached
        loading = cached == null

        if (cached == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loader.load(pageIndex, maxWidth, maxHeight) }.getOrNull()
            }
            bitmap = loaded
            loading = false
        }

        withContext(Dispatchers.IO) {
            loader.prefetch(pageIndex + 1, maxWidth, maxHeight)
            if (pageIndex > 0) loader.prefetch(pageIndex - 1, maxWidth, maxHeight)
        }
    }

    LaunchedEffect(confirmDelete) {
        if (!confirmDelete) {
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun previousPage() {
        if (pageIndex > 0) pageIndex--
    }

    fun nextPage() {
        if (pageIndex < book.pageCount - 1) pageIndex++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable(enabled = !confirmDelete)
            .onPreviewKeyEvent { event ->
                if (confirmDelete) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        previousPage()
                        true
                    }
                    Key.DirectionRight -> {
                        nextPage()
                        true
                    }
                    else -> false
                }
            },
    ) {
        bitmap?.let { pageBitmap ->
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = "Manga page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Could not decode this page.", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use Previous or Next to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }

        if (!confirmDelete) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Page ${pageIndex + 1} of ${book.pageCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) {
                    Text("Close")
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = ::previousPage,
                        enabled = pageIndex > 0,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.3f),
                        ),
                    ) {
                        Text("Previous")
                    }
                    TextButton(
                        onClick = ::nextPage,
                        enabled = pageIndex < book.pageCount - 1,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.3f),
                        ),
                    ) {
                        Text("Next")
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "${pageIndex + 1} / ${book.pageCount}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "D-pad to turn pages",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete from TV")
                    }
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (pageIndex + 1).toFloat() / book.pageCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.16f),
                )
            }

            if (message != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 82.dp, start = 24.dp, end = 24.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = onDismissMessage,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            book = book,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete(book)
            },
        )
    }
}

@Composable
private fun DeleteDialog(
    book: StoredBook,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Delete from this TV?",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "This removes the TV's local copy of \"${book.title}\". " +
                            "The original CBZ on your phone is not deleted or changed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogAction(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurface,
                            onClick = onCancel,
                        )
                        Spacer(Modifier.width(8.dp))
                        DialogAction(
                            text = "Delete TV copy",
                            color = MaterialTheme.colorScheme.error,
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(MaterialTheme.shapes.small)
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}
