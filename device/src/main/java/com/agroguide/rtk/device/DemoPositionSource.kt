package com.agroguide.rtk.device

import android.os.SystemClock
import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.replay.SyntheticNmeaGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Demo mode: synthetic tractor near Leon, Spain, driving at a steady speed.
 * The UI can mutate [generator] fields (heading/speed/quality) live.
 */
class DemoPositionSource(
    rateHz: Double = 5.0,
    val generator: SyntheticNmeaGenerator = SyntheticNmeaGenerator(
        startLatDeg = 42.598, startLonDeg = -5.567,
        speedMps = 3.0, headingDeg = 0.0, quality = FixQuality.RTK_FIXED,
        epochIntervalS = 1.0 / rateHz,
    ),
) : PositionSource {
    override val name = "Demo"
    private val periodMs = (1000.0 / rateHz).toLong()

    override val events: Flow<SourceEvent> = flow {
        emit(SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.CONNECTED, "demo"))
        while (true) {
            val now = SystemClock.elapsedRealtime()
            for (line in generator.next()) emit(SourceEvent.Nmea(line, now))
            delay(periodMs)
        }
    }
}
