package com.hashir.sync.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hashir.sync.R
import com.hashir.sync.data.local.AccountWithPages
import com.hashir.sync.data.local.FbPageEntity
import com.hashir.sync.ui.auth.rememberFacebookLogin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onPageSelected: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val login = rememberFacebookLogin(
        onShortLivedToken = viewModel::onLoginSucceeded,
        onError = viewModel::onLoginFailed,
    )

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.dashboard_title))
                            if (state.hasAccounts) {
                                Text(
                                    text = pageCountLabel(state.totalPages, state.accounts.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenQueue) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Queue and history",
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
                // Search only earns its place once there are enough Pages to scroll past.
                if (state.totalPages >= SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChanged,
                        placeholder = { Text("Search pages") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.isBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.isMetaAppConfigured) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // Passing true once an account exists switches the SDK to the web
                        // flow, so a *different* Facebook account can actually be entered
                        // rather than the installed app silently reusing the current one.
                        login(state.hasAccounts)
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.dashboard_add_account)) },
                )
            }
        },
    ) { padding ->
        when {
            !state.isMetaAppConfigured -> SetupNeededState(Modifier.padding(padding))
            state.isLoading -> LoadingState(Modifier.padding(padding))
            !state.hasAccounts -> EmptyState(Modifier.padding(padding))

            state.isSearching && !state.hasVisiblePages -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No pages match \"${state.query}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> AccountList(
                accounts = state.visibleAccounts,
                contentPadding = padding,
                onRefresh = viewModel::refreshPages,
                onDisconnect = viewModel::disconnectAccount,
                onPageSelected = onPageSelected,
            )
        }
    }
}

@Composable
private fun AccountList(
    accounts: List<AccountWithPages>,
    contentPadding: PaddingValues,
    onRefresh: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onPageSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            // Clears the FAB so the last Page is never stuck underneath it.
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
    ) {
        accounts.forEach { accountWithPages ->
            item(key = "header-${accountWithPages.account.fbUserId}") {
                AccountHeader(
                    accountWithPages = accountWithPages,
                    onRefresh = { onRefresh(accountWithPages.account.fbUserId) },
                    onDisconnect = { onDisconnect(accountWithPages.account.fbUserId) },
                )
            }
            items(
                items = accountWithPages.pages,
                key = { page -> page.pageId },
            ) { page ->
                PageRow(
                    page = page,
                    // Pages without publishing rights are shown but not navigable: there
                    // is nothing useful to do on the other side.
                    onClick = { onPageSelected(page.pageId) }.takeIf { page.canCreateContent },
                )
            }
            if (accountWithPages.pages.isEmpty()) {
                item(key = "empty-${accountWithPages.account.fbUserId}") {
                    NoPagesForAccount()
                }
            }
        }
    }
}

/**
 * Groups Pages under the account they came from. With three Facebook accounts connected
 * this is the only thing distinguishing otherwise identical Page lists, so it stays
 * visible rather than being collapsed away.
 */
@Composable
private fun AccountHeader(
    accountWithPages: AccountWithPages,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val account = accountWithPages.account
    var menuOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = account.pictureUrl, size = 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.name.ifBlank { "Facebook account" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (account.needsReauth) {
                        "Reconnect needed"
                    } else {
                        "${accountWithPages.pages.size} pages"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account.needsReauth) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (account.needsReauth) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Reconnect required",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Account actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh pages") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDisconnect()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageRow(page: FbPageEntity, onClick: (() -> Unit)?) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(page.name) },
        supportingContent = {
            Text(
                text = if (page.canCreateContent) {
                    page.category.orEmpty()
                } else {
                    "You cannot publish to this Page"
                },
                color = if (page.canCreateContent) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        leadingContent = { Avatar(url = page.pictureUrl, size = 40.dp) },
        trailingContent = {
            // Shown rather than hidden: knowing a Page exists but is read-only is more
            // useful than it silently missing from the list.
            if (!page.canCreateContent) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * The Development Mode trap: login succeeds, but the account holds no app role, so
 * Facebook returns an empty Page list. Naming the likely cause here saves a long hunt.
 */
@Composable
private fun NoPagesForAccount() {
    Text(
        text = "No Pages returned for this account. If you expected some, check that this " +
            "Facebook account has accepted the Tester invite on the Meta app, and that it " +
            "administers the Pages.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Avatar(url: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.dashboard_empty_title),
        body = stringResource(R.string.dashboard_empty_body),
    )
}

@Composable
private fun SetupNeededState(modifier: Modifier = Modifier) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.setup_needed_title),
        body = stringResource(R.string.setup_needed_body),
        isWarning = true,
    )
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (isWarning) Icons.Outlined.Warning else Icons.Outlined.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = if (isWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Below this, a search box costs more screen space than it saves scrolling. */
private const val SEARCH_THRESHOLD = 6

private fun pageCountLabel(pages: Int, accounts: Int): String {
    val pageWord = if (pages == 1) "page" else "pages"
    val accountWord = if (accounts == 1) "account" else "accounts"
    return "$pages $pageWord across $accounts $accountWord"
}
