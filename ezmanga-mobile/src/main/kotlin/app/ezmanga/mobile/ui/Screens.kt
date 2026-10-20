package app.ezmanga.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ezmanga.core.PairingAddress
import app.ezmanga.mobile.DiscoveredTv
import app.ezmanga.mobile.MobileUiState
import app.ezmanga.mobile.MobileViewModel
import app.ezmanga.mobile.TvBookSummary
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val AppBackground = Brush.verticalGradient(
    colors = listOf(Color(0xFF171124), Color(0xFF0A0910), Color(0xFF08080C)),
)
private val AppGlow = Brush.radialGradient(
    colors = listOf(Color(0xFF6C4DFF).copy(alpha = 0.25f), Color.Transparent),
    center = Offset(120f, 20f),
    radius = 900f,
)
private val Hairline = Color.White.copy(alpha = 0.08f)

private val CbzMimeTypes = arrayOf(
    "application/vnd.comicbook+zip",
    "application/zip",
    "application/x-cbz",
    "application/octet-stream",
    "*/*",
)

private enum class PairingMode { QR_CODE, SIX_DIGIT_CODE }

@Composable
fun MobileRoot(viewModel: MobileViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current

    var mode by rememberSaveable { mutableStateOf(PairingMode.QR_CODE) }
    var manualLink by rememberSaveable { mutableStateOf("") }
    var showManualLink by rememberSaveable { mutableStateOf(false) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var tvAddress by rememberSaveable { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::pair)
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::selectBook)
    }

    LaunchedEffect(mode) {
        if (mode == PairingMode.SIX_DIGIT_CODE) {
            viewModel.discoverTvs()
        } else {
            viewModel.stopDiscovery()
        }
    }

    LaunchedEffect(state.discoveredTvs) {
        if (tvAddress.isBlank()) {
            state.discoveredTvs.firstOrNull()?.let { tvAddress = it.address }
        }
    }

    fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats("QR_CODE")
            setPrompt("Scan the EZMANGA QR code on the TV")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    fun linkWithCode() {
        focusManager.clearFocus()
        viewModel.pairWithCode(pairingCode, tvAddress)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        Box(Modifier.matchParentSize().background(AppGlow))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            AppHeader(connected = state.pairing != null)
            Spacer(Modifier.height(22.dp))

            StatusBanner(state, onDismiss = viewModel::clearStatus)

            if (state.pairing == null) {
                Text(
                    text = "Your manga,\non the big screen.",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pair over Wi-Fi, send a CBZ, and keep the original on your phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                PairingCard(
                    state = state,
                    mode = mode,
                    manualLink = manualLink,
                    showManualLink = showManualLink,
                    pairingCode = pairingCode,
                    tvAddress = tvAddress,
                    onModeChange = { mode = it },
                    onScan = ::launchScanner,
                    onManualLinkChange = { manualLink = it },
                    onToggleManualLink = { showManualLink = !showManualLink },
                    onConnectLink = {
                        focusManager.clearFocus()
                        viewModel.pair(manualLink)
                    },
                    onCodeChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    onAddressChange = { tvAddress = it },
                    onSelectTv = { tv -> tvAddress = tv.address },
                    onDiscover = viewModel::discoverTvs,
                    onConnectCode = ::linkWithCode,
                )
            } else {
                ConnectedCard(state = state, onDisconnect = viewModel::disconnect)
                Spacer(Modifier.height(16.dp))
                UploadCard(
                    state = state,
                    onChoose = { documentLauncher.launch(CbzMimeTypes) },
                    onUpload = viewModel::upload,
                )
                Spacer(Modifier.height(16.dp))
                TvLibraryCard(
                    state = state,
                    onRefresh = viewModel::refreshLibrary,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Local Wi-Fi only. No account, no cloud. The TV keeps its own copy and can " +
                    "delete it without touching the document on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AppHeader(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EzMangaMark(42.dp)
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = "EZMANGA",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "LOCAL MANGA TRANSFER",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Pill(
            text = if (connected) "CONNECTED" else "LAN ONLY",
            color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun EzMangaMark(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(size * 0.42f)
                    .height(size * 0.09f)
                    .clip(CircleShape)
                    .background(Color(0xFF241542)),
            )
            Spacer(Modifier.height(size * 0.09f))
            Box(
                Modifier
                    .width(size * 0.28f)
                    .height(size * 0.09f)
                    .clip(CircleShape)
                    .background(Color(0xFF241542).copy(alpha = 0.65f)),
            )
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = color,
        )
    }
}

@Composable
private fun PairingCard(
    state: MobileUiState,
    mode: PairingMode,
    manualLink: String,
    showManualLink: Boolean,
    pairingCode: String,
    tvAddress: String,
    onModeChange: (PairingMode) -> Unit,
    onScan: () -> Unit,
    onManualLinkChange: (String) -> Unit,
    onToggleManualLink: () -> Unit,
    onConnectLink: () -> Unit,
    onCodeChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onSelectTv: (DiscoveredTv) -> Unit,
    onDiscover: () -> Unit,
    onConnectCode: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        Color.Transparent,
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
                    ),
                ),
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "PAIR YOUR TV",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))

                ModeSwitcher(mode = mode, onModeChange = onModeChange)
                Spacer(Modifier.height(16.dp))

                when (mode) {
                    PairingMode.QR_CODE -> QrPairingContent(
                        state = state,
                        manualLink = manualLink,
                        showManualLink = showManualLink,
                        onScan = onScan,
                        onManualLinkChange = onManualLinkChange,
                        onToggleManualLink = onToggleManualLink,
                        onConnectLink = onConnectLink,
                    )
                    PairingMode.SIX_DIGIT_CODE -> CodePairingContent(
                        state = state,
                        code = pairingCode,
                        address = tvAddress,
                        onCodeChange = onCodeChange,
                        onAddressChange = onAddressChange,
                        onSelectTv = onSelectTv,
                        onDiscover = onDiscover,
                        onConnect = onConnectCode,
                    )
                }

                if (state.connecting) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Contacting the TV…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitcher(mode: PairingMode, onModeChange: (PairingMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        ModeTab(
            text = "QR code",
            selected = mode == PairingMode.QR_CODE,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(PairingMode.QR_CODE) },
        )
        ModeTab(
            text = "6-digit code",
            selected = mode == PairingMode.SIX_DIGIT_CODE,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(PairingMode.SIX_DIGIT_CODE) },
        )
    }
}

@Composable
private fun ModeTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QrPairingContent(
    state: MobileUiState,
    manualLink: String,
    showManualLink: Boolean,
    onScan: () -> Unit,
    onManualLinkChange: (String) -> Unit,
    onToggleManualLink: () -> Unit,
    onConnectLink: () -> Unit,
) {
    Text(
        text = "Point your camera at the QR code on the TV. No typing needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onScan,
        enabled = !state.connecting,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Scan TV QR code")
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onToggleManualLink) {
        Text(if (showManualLink) "Hide pairing link" else "Paste pairing link instead")
    }

    if (showManualLink) {
        OutlinedTextField(
            value = manualLink,
            onValueChange = onManualLinkChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing link") },
            placeholder = { Text("ezmanga://pair?host=…&port=…&token=…") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onConnectLink,
            enabled = manualLink.isNotBlank() && !state.connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Connect with link")
        }
    }
}

@Composable
private fun CodePairingContent(
    state: MobileUiState,
    code: String,
    address: String,
    onCodeChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onSelectTv: (DiscoveredTv) -> Unit,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
) {
    Text(
        text = "Type the code shown on the TV. It changes every five minutes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Six-digit code") },
        placeholder = { Text("000000") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 7.sp,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(14.dp),
    )

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "TV",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDiscover, enabled = !state.discovering && !state.connecting) {
            Text(if (state.discovering) "Searching…" else "Search again")
        }
    }

    if (state.discovering && state.discoveredTvs.isEmpty()) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Looking for EZManga TVs on this Wi-Fi…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    state.discoveredTvs.forEach { tv ->
        val selected = address == tv.address
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                )
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Hairline,
                    RoundedCornerShape(12.dp),
                )
                .clickable { onSelectTv(tv) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .padding(if (selected) 3.dp else 0.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tv.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tv.address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    text = "SELECTED",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
    }

    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("TV address") },
        placeholder = { Text("192.168.1.40:41234") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(12.dp),
    )

    val addressIsValid = PairingAddress.parse(address) != null
    if (address.isNotBlank() && !addressIsValid) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Use IP:port, for example 192.168.1.40:41234.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    state.discoveryError?.let { error ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onConnect,
        enabled = code.length == 6 && addressIsValid && !state.connecting,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Link with code")
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "The address is shown under the code on the TV. Automatic discovery uses mDNS " +
            "and can be blocked by some routers; typing the address always works.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectedCard(state: MobileUiState, onDisconnect: () -> Unit) {
    val pairing = state.pairing ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.11f),
                        Color.Transparent,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    ),
                ),
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("CONNECTED", MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.deviceName ?: pairing.deviceName,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pairing.baseUrl,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Ready to receive a CBZ over your local Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UploadCard(
    state: MobileUiState,
    onChoose: () -> Unit,
    onUpload: () -> Unit,
) {
    SectionCard(title = "SEND A CBZ") {
        Text(
            text = "Choose a CBZ from this phone. The TV receives a copy; your original is never moved or deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (state.selectedName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(1.dp, Hairline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 58.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "CBZ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF241542),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.selectedName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.selectedSize?.let {
                        Text(
                            text = formatBytes(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onChoose, enabled = !state.uploading) {
                    Text("Change")
                }
            }
        } else {
            OutlinedButton(
                onClick = onChoose,
                enabled = !state.uploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Choose CBZ")
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onUpload,
            enabled = state.selectedUri != null && !state.uploading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (state.uploading) "Uploading…" else "Upload to TV")
        }

        if (state.uploading) {
            Spacer(Modifier.height(12.dp))
            val progress = state.progress ?: 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Phone → TV",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TvLibraryCard(state: MobileUiState, onRefresh: () -> Unit) {
    SectionCard(title = "ON THE TV") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.tvBooks.size} book${if (state.tvBooks.size == 1) "" else "s"} ready to read",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh, enabled = !state.loadingLibrary) {
                Text(if (state.loadingLibrary) "Loading…" else "Refresh")
            }
        }

        if (state.tvBooks.isEmpty() && !state.loadingLibrary) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmptyShelfArt()
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Nothing sent yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your uploaded CBZ will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            state.tvBooks.forEachIndexed { index, book ->
                TvBookRow(book)
                if (index != state.tvBooks.lastIndex) Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun TvBookRow(book: TvBookSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StylizedCover(book, width = 42.dp, height = 58.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${book.pageCount} pages  ·  ${formatBytes(book.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatShortDate(book.addedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StylizedCover(book: TvBookSummary, width: Dp, height: Dp) {
    val colors = remember(book.id) { coverGradient(book.id) }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = book.title.trim().firstOrNull()?.uppercase() ?: "E",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun EmptyShelfArt() {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .width(18.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        )
        Box(
            Modifier
                .width(22.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)),
        )
        Box(
            Modifier
                .width(16.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun StatusBanner(state: MobileUiState, onDismiss: () -> Unit) {
    val error = state.error
    val message = state.message
    if (error == null && message == null) return

    val isError = error != null
    val container = if (isError) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
    }
    val content = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        color = container,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, top = 5.dp, end = 3.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error ?: message.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = content)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private fun coverGradient(seed: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFFCDBDFF), Color(0xFF5C3DB3)),
        listOf(Color(0xFFFFB3C8), Color(0xFF923653)),
        listOf(Color(0xFF8FE3D0), Color(0xFF1D6F60)),
        listOf(Color(0xFFFFC98F), Color(0xFF9A5426)),
        listOf(Color(0xFF9FB8FF), Color(0xFF304B9E)),
    )
    return palettes[(seed.hashCode() and Int.MAX_VALUE) % palettes.size]
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024) return String.format(Locale.US, "%.1f KB", kilobytes)
    val megabytes = kilobytes / 1024.0
    if (megabytes < 1024) return String.format(Locale.US, "%.1f MB", megabytes)
    return String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
}

private fun formatShortDate(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
}
