package com.hashir.sync.feature.queue

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashir.sync.data.local.JobState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue and history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.items.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing published yet. Posts you send will appear here with their progress.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.job.id }) { item ->
                    JobCard(
                        item = item,
                        onRetry = { viewModel.retry(item.job.id) },
                        onDelete = { viewModel.delete(item.job.id) },
                        onOpen = {
                            item.job.resultPostId?.let { postId ->
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://www.facebook.com/$postId".toUri(),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    item: QueueItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val job = item.job
    val visuals = job.state.visuals()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (job.state == JobState.FAILED) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(visuals.icon, contentDescription = null, tint = visuals.tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.pageName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${visuals.label}  ${formatTime(job.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (job.state.isTerminal()) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove from history",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (job.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = job.caption.take(140),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            }

            // Videos are the only thing slow enough for a bar to be worth showing, and
            // they are exactly the case where a still screen looks like a hang.
            if (job.state == JobState.UPLOADING && job.totalBytes > 0) {
                Spacer(Modifier.height(10.dp))
                val fraction = (job.uploadedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(fraction * 100).toInt()}%  " +
                        "${formatBytes(job.uploadedBytes)} of ${formatBytes(job.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (job.state == JobState.TRANSCODING) {
                Spacer(Modifier.height(10.dp))
                // Transformer reports no usable percentage here, so an indeterminate bar
                // is honest rather than inventing progress.
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            job.errorMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            if (job.state == JobState.FAILED || job.state == JobState.DONE) {
                Spacer(Modifier.height(4.dp))
                Row {
                    if (job.state == JobState.FAILED) {
                        TextButton(onClick = onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                    if (job.resultPostId != null) {
                        TextButton(onClick = onOpen) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("View on Facebook")
                        }
                    }
                }
            }
        }
    }
}

private data class StateVisuals(val icon: ImageVector, val label: String, val tint: Color)

@Composable
private fun JobState.visuals(): StateVisuals = when (this) {
    JobState.PENDING -> StateVisuals(
        Icons.Default.HourglassEmpty,
        "Waiting",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )

    JobState.DOWNLOADING -> StateVisuals(
        Icons.Default.CloudUpload,
        "Downloading",
        MaterialTheme.colorScheme.primary,
    )

    JobState.TRANSCODING -> StateVisuals(
        Icons.Default.CloudUpload,
        "Preparing video",
        MaterialTheme.colorScheme.primary,
    )

    JobState.UPLOADING -> StateVisuals(
        Icons.Default.CloudUpload,
        "Uploading",
        MaterialTheme.colorScheme.primary,
    )

    JobState.PUBLISHING -> StateVisuals(
        Icons.Default.CloudUpload,
        "Publishing",
        MaterialTheme.colorScheme.primary,
    )

    JobState.DONE -> StateVisuals(
        Icons.Default.CheckCircle,
        "Published",
        MaterialTheme.colorScheme.primary,
    )

    JobState.FAILED -> StateVisuals(
        Icons.Default.ErrorOutline,
        "Failed",
        MaterialTheme.colorScheme.error,
    )
}

private fun JobState.isTerminal(): Boolean = this == JobState.DONE || this == JobState.FAILED

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
