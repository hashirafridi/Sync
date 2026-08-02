package com.hashir.sync.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.api.GraphConfig
import com.hashir.sync.api.GraphError
import com.hashir.sync.data.local.AccountWithPages
import com.hashir.sync.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    /**
     * False when local.properties has no Meta credentials. Worth separating from "no
     * accounts connected": the fix is editing a file and rebuilding, not tapping a
     * button, and saying so avoids a confusing dead end.
     */
    val isMetaAppConfigured: Boolean = false,
    val isBusy: Boolean = false,
    val accounts: List<AccountWithPages> = emptyList(),
    val query: String = "",
) {
    val hasAccounts: Boolean get() = accounts.isNotEmpty()
    val totalPages: Int get() = accounts.sumOf { it.pages.size }

    /**
     * Accounts with only the matching Pages left in them.
     *
     * An account whose Pages all filter out is dropped entirely, so searching does not
     * leave a column of empty headers behind.
     */
    val visibleAccounts: List<AccountWithPages>
        get() {
            val term = query.trim()
            if (term.isBlank()) return accounts
            return accounts.mapNotNull { account ->
                val matches = account.pages.filter { page ->
                    page.name.contains(term, ignoreCase = true) ||
                        page.category?.contains(term, ignoreCase = true) == true
                }
                if (matches.isEmpty()) null else account.copy(pages = matches)
            }
        }

    val isSearching: Boolean get() = query.isNotBlank()
    val hasVisiblePages: Boolean get() = visibleAccounts.isNotEmpty()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: AccountRepository,
    graphConfig: GraphConfig,
) : ViewModel() {

    private val isConfigured = graphConfig.isConfigured
    private val busy = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-shot user-facing messages, surfaced as snackbars. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /**
     * Driven straight off Room, so connecting an account, refreshing Pages or hitting an
     * expired token all update the dashboard with no explicit refresh call.
     */
    val uiState: StateFlow<DashboardUiState> =
        combine(
            repository.observeAccountsWithPages(),
            busy,
            query,
        ) { accounts, isBusy, searchTerm ->
            DashboardUiState(
                isLoading = false,
                isMetaAppConfigured = isConfigured,
                isBusy = isBusy,
                accounts = accounts,
                query = searchTerm,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DashboardUiState(isMetaAppConfigured = isConfigured),
        )

    fun onQueryChanged(value: String) {
        query.value = value
    }

    /** Called with the short-lived token the Facebook SDK just produced. */
    fun onLoginSucceeded(shortLivedToken: String) {
        viewModelScope.launch {
            busy.value = true
            repository.connectAccount(shortLivedToken)
                .onSuccess { account ->
                    messageChannel.send(connectedMessage(account.name, account.pageCount))
                }
                .onFailure { messageChannel.send(describe(it)) }
            busy.value = false
        }
    }

    fun onLoginFailed(reason: String) {
        viewModelScope.launch { messageChannel.send(reason) }
    }

    fun refreshPages(fbUserId: String) {
        viewModelScope.launch {
            busy.value = true
            repository.refreshPages(fbUserId)
                .onSuccess { count -> messageChannel.send("Refreshed: $count pages.") }
                .onFailure { messageChannel.send(describe(it)) }
            busy.value = false
        }
    }

    fun disconnectAccount(fbUserId: String) {
        viewModelScope.launch {
            repository.disconnectAccount(fbUserId)
            messageChannel.send("Account disconnected.")
        }
    }

    private fun connectedMessage(name: String, pageCount: Int): String = when (pageCount) {
        0 -> "Connected $name, but no Pages came back. On a Meta app in Development Mode " +
            "this usually means you have not accepted the Tester invite yet."
        1 -> "Connected $name with 1 page."
        else -> "Connected $name with $pageCount pages."
    }

    /**
     * Turns an exception into something worth reading.
     *
     * The three cases users actually hit are told apart deliberately -- "no signal",
     * "your token died" and "you lack permission" need completely different reactions,
     * and a generic failure message would leave someone retrying a thing that can never
     * succeed.
     */
    private fun describe(error: Throwable): String = when (error) {
        is GraphError.Network ->
            "No connection. Check the network and try again."

        is GraphError.Api -> when {
            error.isTokenExpired ->
                "That login expired. Please connect the account again."

            error.isPermissionDenied ->
                "Facebook refused the request: ${error.fbMessage} " +
                    "Check you are an admin of the Page and a Tester on the Meta app."

            error.isRateLimited ->
                "Facebook is rate limiting this app. Try again in a little while."

            else -> "Facebook error ${error.code}: ${error.fbMessage}"
        }

        else -> error.message ?: "Something went wrong."
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
