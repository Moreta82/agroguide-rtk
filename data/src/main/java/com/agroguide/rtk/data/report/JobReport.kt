package com.agroguide.rtk.data.report

import com.agroguide.rtk.geo.EnuPoint
import java.util.Locale

data class JobReportData(
    val jobName: String,
    val parcelName: String?,
    val dateStr: String,
    val activeSeconds: Long,
    val treatedM2: Double,
    val overlapM2: Double,
    val parcelAreaM2: Double?,
    val widthM: Double,
    val avgSpeedMps: Double,
    val distanceM: Double,
    val trail: List<EnuPoint>,
)

/** Self-contained Spanish HTML report with an inline SVG track thumbnail. */
object JobReport {

    private fun f(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    fun durationHmm(seconds: Long): String = "${seconds / 3600}:${f("%02d", (seconds % 3600) / 60)}"

    fun html(d: JobReportData): String {
        val overlapPct = if (d.treatedM2 > 0) d.overlapM2 / d.treatedM2 * 100 else 0.0
        val parcelHa = d.parcelAreaM2?.let { f("%.2f ha", it / 10_000) } ?: "&mdash;"
        val parcelName = d.parcelName ?: "&mdash;"
        return """<!DOCTYPE html>
<html lang="es"><head><meta charset="utf-8"><title>AgroGuide RTK — ${d.jobName}</title>
<style>
 body { font-family: sans-serif; margin: 24px; color: #111; }
 h1 { color: #1B5E20; } h2 { margin-top: 24px; }
 table { border-collapse: collapse; min-width: 420px; }
 td { border: 1px solid #999; padding: 6px 12px; }
 td:first-child { font-weight: bold; background: #F1F5F1; }
</style></head><body>
<h1>AgroGuide RTK — Informe de trabajo</h1>
<h2>${d.jobName}</h2>
<table>
 <tr><td>Parcela</td><td>$parcelName</td></tr>
 <tr><td>Fecha</td><td>${d.dateStr}</td></tr>
 <tr><td>Duraci&oacute;n</td><td>${durationHmm(d.activeSeconds)} h</td></tr>
 <tr><td>&Aacute;rea tratada</td><td>${f("%.2f ha", d.treatedM2 / 10_000)}</td></tr>
 <tr><td>Solape</td><td>${f("%.2f ha (%.1f %%)", d.overlapM2 / 10_000, overlapPct)}</td></tr>
 <tr><td>&Aacute;rea de parcela</td><td>$parcelHa</td></tr>
 <tr><td>Ancho de apero</td><td>${f("%.2f m", d.widthM)}</td></tr>
 <tr><td>Velocidad media</td><td>${f("%.1f km/h", d.avgSpeedMps * 3.6)}</td></tr>
 <tr><td>Distancia</td><td>${f("%.2f km", d.distanceM / 1000)}</td></tr>
</table>
<h2>Track</h2>
${trackSvg(d.trail, 480, 360)}
</body></html>
"""
    }

    /** Track polyline scaled+centered into a viewBox with 20 px margin. */
    fun trackSvg(trail: List<EnuPoint>, w: Int, h: Int): String {
        if (trail.size < 2) return "<p>Sin track</p>"
        val minX = trail.minOf { it.x }; val maxX = trail.maxOf { it.x }
        val minY = trail.minOf { it.y }; val maxY = trail.maxOf { it.y }
        val margin = 20.0
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf((w - 2 * margin) / spanX, (h - 2 * margin) / spanY)
        val offX = (w - spanX * scale) / 2
        val offY = (h - spanY * scale) / 2
        val pts = trail.joinToString(" ") { p ->
            val sx = offX + (p.x - minX) * scale
            val sy = h - (offY + (p.y - minY) * scale)   // ENU y-up -> SVG y-down
            f("%.1f,%.1f", sx, sy)
        }
        return "<svg width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" xmlns=\"http://www.w3.org/2000/svg\">" +
            "<rect width=\"$w\" height=\"$h\" fill=\"#EFF3EF\"/>" +
            "<polyline points=\"$pts\" fill=\"none\" stroke=\"#2E7D32\" stroke-width=\"2\"/></svg>"
    }
}
