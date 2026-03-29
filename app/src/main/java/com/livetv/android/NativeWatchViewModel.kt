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
        channels: List<NativeWatchChannelListItem>,
        loading: NativeWatchLoadingState,
        epg: List<NativeWatchProgram>,
        audioTracks: List<NativeWatchAudioTrack>,
        previousChannelName: String,
        nextChannelName: String,
        isMenuVisible: Boolean,
        isNativePlayerActive: Boolean,
        jio: NativeWatchJioState,
    ) {
        _uiState.update {
            NativeWatchUiState(
                channel = channel,
                channels = channels,
                loading = loading.copy(progress = loading.progress.coerceIn(0, 100)),
                epg = epg,
                audioTracks = audioTracks,
                previousChannelName = previousChannelName,
                nextChannelName = nextChannelName,
                isMenuVisible = isMenuVisible,
                isNativePlayerActive = isNativePlayerActive,
                jio = jio,
            )
        }
    }

    fun clear() {
        _uiState.value = NativeWatchUiState()
    }

    fun previewChannelSelection(channelId: String) {
        if (channelId.isBlank()) return

        _uiState.update { current ->
            val targetChannel = current.channels.firstOrNull { it.id == channelId } ?: return@update current
            val updatedChannels =
                current.channels.map { item ->
                    item.copy(isSelected = item.id == targetChannel.id)
                }
            val channelName = targetChannel.name.ifBlank { "Live TV" }

            current.copy(
                channel =
                    NativeWatchChannel(
                        id = targetChannel.id,
                        name = channelName,
                        logoUrl = targetChannel.logoUrl,
                        playbackMode = targetChannel.playbackMode,
                        isDirectStream = targetChannel.isDirectStream,
                    ),
                channels = updatedChannels,
                loading =
                    NativeWatchLoadingState(
                        visible = true,
                        label = "Loading $channelName",
                        progress = current.loading.progress.coerceAtLeast(8),
                        programTitle = "Programme info unavailable",
                        programSubtitle = channelName,
                    ),
                epg =
                    listOf(
                        NativeWatchProgram(
                            title = "No info",
                            subtitle = "Programme info unavailable",
                            isPlaceholder = true,
                            isCurrent = true,
                        ),
                    ),
            )
        }
    }
}
