package com.agroguide.rtk.data.report

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertTrue

class JobReportTest {
    private val data = JobReportData(
        jobName = "Trabajo pradera",
        parcelName = "Finca Norte",
        dateStr = "19/07/2026",
        activeSeconds = 3900,          // 1:05
        treatedM2 = 120_000.0,         // 12.00 ha
        overlapM2 = 6_000.0,           // 0.60 ha, 5.0 %
        parcelAreaM2 = 125_000.0,
        widthM = 6.0,
        avgSpeedMps = 2.5,             // 9.0 km/h
        distanceM = 20_000.0,          // 20.00 km
        trail = listOf(EnuPoint(0.0, 0.0), EnuPoint(100.0, 0.0), EnuPoint(100.0, 50.0)),
    )

    @Test fun `html contains all report fields`() {
        val html = JobReport.html(data)
        assertTrue(html.contains("Trabajo pradera"))
        assertTrue(html.contains("Finca Norte"))
        assertTrue(html.contains("19/07/2026"))
        assertTrue(html.contains("1:05"))          // duration h:mm
        assertTrue(html.contains("12.00"))         // treated ha (Locale.US)
        assertTrue(html.contains("5.0"))           // overlap %
        assertTrue(html.contains("9.0"))           // km/h
        assertTrue(html.contains("20.00"))         // km
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("polyline"))
    }

    @Test fun `empty trail falls back to text`() {
        val html = JobReport.html(data.copy(trail = emptyList()))
        assertTrue(html.contains("Sin track"))
    }

    @Test fun `missing parcel shows dash`() {
        val html = JobReport.html(data.copy(parcelName = null, parcelAreaM2 = null))
        assertTrue(html.contains("&mdash;") || html.contains("—"))
    }
}
