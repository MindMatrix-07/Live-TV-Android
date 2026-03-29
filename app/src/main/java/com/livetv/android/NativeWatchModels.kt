package com.livetv.android

data class NativeWatchChannel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String? = null,
    val playbackMode: String = "",
    val isDirectStream: Boolean = false,
)

data class NativeWatchProgram(
    val title: String,
    val subtitle: String,
    val isPlaceholder: Boolean = false,
    val isCurrent: Boolean = false,
)

data class NativeWatchAudioTrack(
    val id: String,
    val label: String,
    val shortLabel: String,
    val selected: Boolean = false,
)

data class NativeWatchLoadingState(
    val visible: Boolean = false,
    val label: String = "",
    val progress: Int = 0,
)

data class NativeWatchUiState(
    val channel: NativeWatchChannel? = null,
    val loading: NativeWatchLoadingState = NativeWatchLoadingState(),
    val epg: List<NativeWatchProgram> = emptyList(),
    val audioTracks: List<NativeWatchAudioTrack> = emptyList(),
    val previousChannelName: String = "",
    val nextChannelName: String = "",
    val isMenuVisible: Boolean = false,
    val isNativePlayerActive: Boolean = false,
)
