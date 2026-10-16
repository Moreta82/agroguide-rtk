package com.agroguide.rtk.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.agroguide.rtk.R
import com.agroguide.rtk.ntrip.NtripFailure

/** User-facing reason for an NTRIP failure, phrased as what to check. */
@Composable
fun ntripFailureText(failure: NtripFailure, detail: String?, hasGga: Boolean = true): String = when (failure) {
    NtripFailure.UNAUTHORIZED -> stringResource(R.string.ntrip_fail_unauthorized)
    NtripFailure.FORBIDDEN -> stringResource(R.string.ntrip_fail_forbidden)
    NtripFailure.MOUNTPOINT_NOT_FOUND -> stringResource(R.string.ntrip_fail_mount)
    NtripFailure.HOST_NOT_FOUND -> stringResource(R.string.ntrip_fail_host)
    NtripFailure.UNREACHABLE -> stringResource(R.string.ntrip_fail_unreachable)
    NtripFailure.NO_DATA ->
        if (hasGga) stringResource(R.string.ntrip_fail_nodata) else stringResource(R.string.ntrip_fail_nodata_gga)
    NtripFailure.REJECTED -> stringResource(R.string.ntrip_fail_rejected, detail ?: "?")
}
