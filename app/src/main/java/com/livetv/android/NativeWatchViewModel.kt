package com.livetv.android

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NativeWatchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NativeWatchUiState())
    val uiState: StateFlow<NativeWatchUiState> = _uiState.asStateFlow()

    fun syncFromWeb(
        channel: NativeWatchChannel?,
        loading: NativeWatchLoadingState,
        epg: List<NativeWatchProgram>,
        audioTracks: List<NativeWatchAudioTrack>,
        previousChannelName: String,
        nextChannelName: String,
        isMenuVisible: Boolean,
        isNativePlayerActive: Boolean,
    ) {
        _uiState.update {
            NativeWatchUiState(
                channel = channel,
                loading = loading.copy(progress = loading.progress.coerceIn(0, 100)),
                epg = epg,
                audioTracks = audioTracks,
                previousChannelName = previousChannelName,
                nextChannelName = nextChannelName,
                isMenuVisible = isMenuVisible,
                isNativePlayerActive = isNativePlayerActive,
            )
        }
    }

    fun clear() {
        _uiState.value = NativeWatchUiState()
    }
}
