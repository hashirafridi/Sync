package com.hashir.sync.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.data.local.FbPageEntity
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.local.PublishJobEntity
import com.hashir.sync.data.repository.PublishQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A job paired with the Page it targets, so the list can show a name instead of an id. */
data class QueueItem(
    val job: PublishJobEntity,
    val pageName: String,
)

data class QueueUiState(
    val isLoading: Boolean = true,
    val items: List<QueueItem> = emptyList(),
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val publishQueue: PublishQueue,
    pageDao: PageDao,
) : ViewModel() {

    val uiState: StateFlow<QueueUiState> =
        combine(publishQueue.observeJobs(), pageDao.observeAll()) { jobs, pages ->
            val byId: Map<String, FbPageEntity> = pages.associateBy { it.pageId }
            QueueUiState(
                isLoading = false,
                items = jobs.map { job ->
                    QueueItem(
                        job = job,
                        // A Page can be disconnected while its jobs remain in history.
                        pageName = byId[job.pageId]?.name ?: "Unknown page",
                    )
                },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = QueueUiState(),
        )

    fun retry(jobId: Long) = viewModelScope.launch { publishQueue.retry(jobId) }

    fun delete(jobId: Long) = viewModelScope.launch { publishQueue.delete(jobId) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
