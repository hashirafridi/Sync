package com.hashir.sync.feature.downloader

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hashir.sync.ui.permissions.RequestLegacyStoragePermissionIfNeeded
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    onBack: () -> Unit,
    onDownloadReady: (pageId: String, downloadId: Long) -> Unit,
    viewModel: DownloaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Needed on Android 9 and below so finished downloads can be saved where the gallery
    // will see them. A no-op on newer versions.
    RequestLegacyStoragePermissionIfNeeded()

    // People arrive here having just copied a link, so offering it saves a paste.
    LaunchedEffect(Unit) {
        viewModel.offerClipboard(readClipboardText(context))
    }

    LaunchedEffect(Unit) {
        viewModel.downloadReady.collect { ready ->
            onDownloadReady(viewModel.pageId, ready.downloadId)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download from URL") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Update downloader") },
                            leadingIcon = {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                viewModel.updateDownloader()
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChanged,
                label = { Text("Video link") },
                placeholder = { Text("Paste a YouTube, TikTok, Instagram or Facebook link") },
                singleLine = true,
                enabled = !state.isDownloading,
                trailingIcon = {
                    if (state.url.isNotEmpty()) {
                        IconButton(
                            onClick = viewModel::clearUrl,
                            enabled = !state.isDownloading,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear link")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = viewModel::fetch,
                enabled = state.canFetch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isProbing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Reading link...")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch")
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        // The fix is one tap away, so offer it here rather than making the
                        // user find it in a menu after reading about it.
                        if (message.contains("Update downloader")) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = viewModel::updateDownloader,
                                enabled = !state.isProbing,
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Update downloader now")
                            }
                        }
                    }
                }
            }

            state.info?.let { info ->
                Spacer(Modifier.height(20.dp))

                Row {
                    info.thumbnailUrl?.let { thumb ->
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 120.dp, height = 68.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(
                            text = info.title.orEmpty().ifBlank { "Untitled" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        info.durationSeconds?.let { seconds ->
                            Text(
                                text = formatDuration(seconds),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    info.availableHeights.forEach { height ->
                        FilterChip(
                            selected = state.selectedHeight == height,
                            onClick = { viewModel.selectHeight(height) },
                            enabled = !state.isDownloading,
                            label = { Text(labelFor(height)) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (state.isDownloading) {
                    LinearProgressIndicator(
                        progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${state.percent.toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.etaSeconds > 0) {
                            Text(
                                text = "about ${formatDuration(state.etaSeconds)} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::cancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel") }
                } else {
                    Button(
                        onClick = viewModel::download,
                        enabled = state.canDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }

            if (state.info == null && !state.isProbing && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Paste a link and tap Fetch. The caption is filled in from " +
                            "the source and stays editable.\n\nDownloads are saved to " +
                            "${viewModel.savedFolderLabel} and appear in your gallery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun labelFor(height: Int): String = when (height) {
    2160 -> "4K"
    1440 -> "1440p"
    else -> "${height}p"
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return manager?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}
