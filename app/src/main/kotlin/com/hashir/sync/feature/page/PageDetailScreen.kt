package com.hashir.sync.feature.page

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hashir.sync.feature.editor.EditorMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageDetailScreen(
    onBack: () -> Unit,
    onCompose: (pageId: String, mode: EditorMode) -> Unit,
    onDownload: (pageId: String) -> Unit,
    viewModel: PageDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val page = state.page

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.name ?: "Page") },
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

            page == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "This Page is no longer connected. Refresh the account it belonged to.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                PageHeader(
                    name = page.name,
                    category = page.category,
                    pictureUrl = page.pictureUrl,
                )

                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Create a post",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                if (!page.canCreateContent) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "You do not hold publishing rights on this Page, so posting " +
                                "is disabled. You need the CREATE_CONTENT task, which comes " +
                                "with an admin or editor role on the Page itself.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    ComposeOption(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        title = "Upload from phone",
                        subtitle = "Pick a photo or video already on this device",
                        onClick = { onCompose(page.pageId, EditorMode.MEDIA) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ComposeOption(
                        icon = { Icon(Icons.Default.Link, contentDescription = null) },
                        title = "Download from URL",
                        subtitle = "Paste a video link, pick a resolution, then post it",
                        onClick = { onDownload(page.pageId) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ComposeOption(
                        icon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                        title = "Text or link post",
                        subtitle = "A caption on its own, optionally with a link",
                        onClick = { onCompose(page.pageId, EditorMode.TEXT) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(name: String, category: String?, pictureUrl: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (pictureUrl.isNullOrBlank()) {
                Icon(
                    Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    model = pictureUrl,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            if (!category.isNullOrBlank()) {
                Text(
                    category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ComposeOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (enabled) subtitle else "$subtitle (coming soon)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
