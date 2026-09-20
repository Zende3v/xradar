package com.eona.app.feature.history

import com.eona.app.core.model.TripRecord

/** The three states the history screen must handle. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data object Empty : HistoryUiState
    data class Content(val trips: List<TripRecord>) : HistoryUiState
}
