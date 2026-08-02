package com.hashir.sync.feature.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.data.local.FbPageEntity
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PageDetailUiState(
    val isLoading: Boolean = true,
    val page: FbPageEntity? = null,
)

@HiltViewModel
class PageDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pageDao: PageDao,
) : ViewModel() {

    val pageId: String = checkNotNull(savedStateHandle[Routes.ARG_PAGE_ID]) {
        "PageDetail opened without a page id."
    }

    private val _uiState = MutableStateFlow(PageDetailUiState())
    val uiState: StateFlow<PageDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = PageDetailUiState(isLoading = false, page = pageDao.getById(pageId))
        }
    }
}
