package com.hashir.sync.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.data.local.AccountWithPages
import com.hashir.sync.data.local.PublishJobDao
import com.hashir.sync.data.prefs.AppPreferences
import com.hashir.sync.data.repository.AccountRepository
import com.hashir.sync.downloader.YtDlpMediaFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val accounts: List<AccountWithPages> = emptyList(),
    val wifiOnlyUploads: Boolean = false,
    val isBusy: Boolean = false,
    val downloaderVersion: String? = null,
    val cacheSizeLabel: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val preferences: AppPreferences,
    private val fetcher: YtDlpMediaFetcher,
    private val jobDao: PublishJobDao,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val version = MutableStateFlow<String?>(null)
    private val cacheSize = MutableStateFlow<String?>(null)

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        accountRepository.observeAccountsWithPages(),
        preferences.wifiOnlyUploads,
        busy,
        version,
        cacheSize,
    ) { accounts, wifiOnly, isBusy, downloaderVersion, cache ->
        SettingsUiState(
            accounts = accounts,
            wifiOnlyUploads = wifiOnly,
            isBusy = isBusy,
            downloaderVersion = downloaderVersion,
            cacheSizeLabel = cache,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    init {
        refreshCacheSize()
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { preferences.setWifiOnlyUploads(enabled) }
    }

    fun disconnect(fbUserId: String) {
        viewModelScope.launch {
            accountRepository.disconnectAccount(fbUserId)
            messageChannel.send("Account disconnected.")
        }
    }

    /**
     * Reads the yt-dlp version, which also forces the Python runtime to unpack.
     *
     * Deliberately on demand: that extraction takes a few seconds and hundreds of
     * megabytes, and someone who never downloads anything should never trigger it.
     */
    fun checkDownloaderVersion() {
        viewModelScope.launch {
            busy.value = true
            version.value = fetcher.versionName() ?: "unknown"
            busy.value = false
        }
    }

    fun updateDownloader() {
        viewModelScope.launch {
            busy.value = true
            runCatching { fetcher.update(nightly = true) }
                .onSuccess {
                    version.value = fetcher.versionName()
                    messageChannel.send("Downloader updated: $it")
                }
                .onFailure { messageChannel.send("Update failed: ${it.message ?: "unknown error"}") }
            busy.value = false
        }
    }

    /**
     * Clears staged uploads, transcodes and downloads.
     *
     * These add up fast: a downloaded video plus a re-encoded copy can be several hundred
     * megabytes, and nothing else prunes them.
     *
     * Files still referenced by an unfinished job are kept. Downloading and publishing are
     * separate steps by design -- a failed upload leaves the video on the phone so Retry
     * can re-send it -- and deleting it here would quietly turn a retryable failure into a
     * permanent one.
     */
    fun clearCache() {
        viewModelScope.launch {
            busy.value = true

            val protectedPaths = jobDao.getUnfinished()
                .mapNotNull { it.localMediaUri }
                .mapNotNull { uri -> runCatching { Uri.parse(uri).path }.getOrNull() }
                .toSet()

            val keptCount = withContext(Dispatchers.IO) {
                var kept = 0
                fun purge(dir: File) {
                    dir.walkBottomUp().forEach { file ->
                        if (!file.isFile) return@forEach
                        if (file.absolutePath in protectedPaths) kept++ else file.delete()
                    }
                }

                purge(File(context.cacheDir, "uploads"))
                purge(File(context.cacheDir, "normalized"))
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                    ?.let { purge(File(it, "downloads")) }
                kept
            }

            busy.value = false
            refreshCacheSize()
            messageChannel.send(
                if (keptCount > 0) {
                    "Cached files removed. $keptCount kept for posts still in the queue."
                } else {
                    "Cached files removed."
                },
            )
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                listOfNotNull(
                    File(context.cacheDir, "uploads"),
                    File(context.cacheDir, "normalized"),
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                        ?.let { File(it, "downloads") },
                ).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            }
            cacheSize.value = formatBytes(bytes)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
