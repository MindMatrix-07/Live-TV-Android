package com.livetv.android

data class NativeWatchChannel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String? = null,
    val playbackMode: String = "",
    val isDirectStream: Boolean = false,
)

data class NativeWatchChannelListItem(
    val id: String = "",
    val name: String = "",
    val logoUrl: String? = null,
    val playbackMode: String = "",
    val isDirectStream: Boolean = false,
    val isSelected: Boolean = false,
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
    val programTitle: String = "",
    val programSubtitle: String = "",
)

data class NativeWatchJioCatalogItem(
    val channelId: String = "",
    val channelName: String = "",
    val categoryName: String = "",
    val logoUrl: String? = null,
    val imported: Boolean = false,
)

data class NativeWatchJioState(
    val authenticated: Boolean = false,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val userIdentifier: String = "",
    val error: String = "",
    val message: String = "",
    val catalogLoading: Boolean = false,
    val catalogError: String = "",
    val otpStage: String = "send",
    val channels: List<NativeWatchJioCatalogItem> = emptyList(),
)

data class NativeWatchUiState(
    val channel: NativeWatchChannel? = null,
    val channels: List<NativeWatchChannelListItem> = emptyList(),
    val loading: NativeWatchLoadingState = NativeWatchLoadingState(),
    val epg: List<NativeWatchProgram> = emptyList(),
    val audioTracks: List<NativeWatchAudioTrack> = emptyList(),
    val previousChannelName: String = "",
    val nextChannelName: String = "",
    val isMenuVisible: Boolean = false,
    val isNativePlayerActive: Boolean = false,
    val jio: NativeWatchJioState = NativeWatchJioState(),
)
