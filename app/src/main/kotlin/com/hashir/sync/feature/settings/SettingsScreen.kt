package com.hashir.sync.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Uploads")

            ListItem(
                headlineContent = { Text("Upload on Wi-Fi only") },
                supportingContent = {
                    Text(
                        "Queued posts wait for Wi-Fi. Useful on a metered plan, since a " +
                            "video can be a few hundred megabytes.",
                    )
                },
                leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = state.wifiOnlyUploads,
                        onCheckedChange = viewModel::setWifiOnly,
                    )
                },
            )

            HorizontalDivider()
            SectionHeader("Downloader")

            ListItem(
                headlineContent = { Text("Update downloader") },
                supportingContent = {
                    Text(
                        state.downloaderVersion?.let { "Version $it" }
                            ?: "Sites change and break the downloader. Updating fixes it " +
                            "without reinstalling the app.",
                    )
                },
                leadingContent = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                trailingContent = {
                    TextButton(
                        onClick = viewModel::updateDownloader,
                        enabled = !state.isBusy,
                    ) { Text("Update") }
                },
            )

            ListItem(
                headlineContent = { Text("Check version") },
                supportingContent = { Text("Unpacks the downloader if it has not run yet") },
                trailingContent = {
                    TextButton(
                        onClick = viewModel::checkDownloaderVersion,
                        enabled = !state.isBusy,
                    ) { Text("Check") }
                },
            )

            HorizontalDivider()
            SectionHeader("Storage")

            ListItem(
                headlineContent = { Text("Clear cached files") },
                supportingContent = {
                    Text(
                        state.cacheSizeLabel?.let { "$it of downloads and staged uploads" }
                            ?: "Downloads and staged uploads",
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                },
                trailingContent = {
                    TextButton(onClick = viewModel::clearCache, enabled = !state.isBusy) {
                        Text("Clear")
                    }
                },
            )

            HorizontalDivider()
            SectionHeader("Connected accounts")

            if (state.accounts.isEmpty()) {
                ListItem(
                    headlineContent = { Text("No accounts connected") },
                    supportingContent = { Text("Connect one from the Pages screen") },
                )
            } else {
                state.accounts.forEach { accountWithPages ->
                    ListItem(
                        headlineContent = {
                            Text(accountWithPages.account.name.ifBlank { "Facebook account" })
                        },
                        supportingContent = {
                            Text(
                                if (accountWithPages.account.needsReauth) {
                                    "Reconnect needed"
                                } else {
                                    "${accountWithPages.pages.size} pages"
                                },
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    viewModel.disconnect(accountWithPages.account.fbUserId)
                                },
                            ) {
                                Icon(
                                    Icons.Default.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Disconnect")
                            }
                        },
                    )
                }
                Text(
                    text = "Disconnecting only forgets the account on this phone. It does " +
                        "not revoke access on Facebook; do that from Facebook's own app " +
                        "settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
