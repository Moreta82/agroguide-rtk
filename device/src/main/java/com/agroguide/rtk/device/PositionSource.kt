package com.agroguide.rtk.device

import kotlinx.coroutines.flow.Flow

sealed interface SourceEvent {
    /** A raw NMEA line (BT/replay sources) with local monotonic receive time. */
    data class Nmea(val line: String, val monotonicMs: Long) : SourceEvent

    /** A ready-made fix (Android Location source bypasses NMEA). */
    data class DirectFix(val fix: com.agroguide.rtk.nmea.GnssFix) : SourceEvent

    data class ConnectionState(val state: State, val detail: String? = null) : SourceEvent {
        enum class State { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }
}

/** One position provider (BT SPP, Android location, replay, later USB). */
interface PositionSource {
    val events: Flow<SourceEvent>
    val name: String
}
