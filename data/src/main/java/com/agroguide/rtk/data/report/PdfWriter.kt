package com.agroguide.rtk.data.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.Locale

/** One-page A4 PDF version of the job report (595 x 842 pt). */
object PdfWriter {

    private fun f(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    fun write(d: JobReportData, outFile: File) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas

        val title = Paint().apply { color = Color.rgb(27, 94, 32); textSize = 22f; isFakeBoldText = true }
        val label = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val value = Paint().apply { color = Color.BLACK; textSize = 12f }
        val track = Paint().apply {
            color = Color.rgb(46, 125, 50); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
        }

        c.drawText("AgroGuide RTK — Informe de trabajo", 40f, 60f, title)
        c.drawText(d.jobName, 40f, 90f, label.apply { textSize = 16f })
        label.textSize = 12f

        val overlapPct = if (d.treatedM2 > 0) d.overlapM2 / d.treatedM2 * 100 else 0.0
        val rows = listOf(
            "Parcela" to (d.parcelName ?: "—"),
            "Fecha" to d.dateStr,
            "Duración" to "${JobReport.durationHmm(d.activeSeconds)} h",
            "Área tratada" to f("%.2f ha", d.treatedM2 / 10_000),
            "Solape" to f("%.2f ha (%.1f %%)", d.overlapM2 / 10_000, overlapPct),
            "Área de parcela" to (d.parcelAreaM2?.let { f("%.2f ha", it / 10_000) } ?: "—"),
            "Ancho de apero" to f("%.2f m", d.widthM),
            "Velocidad media" to f("%.1f km/h", d.avgSpeedMps * 3.6),
            "Distancia" to f("%.2f km", d.distanceM / 1000),
        )
        var y = 130f
        for ((k, v) in rows) {
            c.drawText(k, 40f, y, label)
            c.drawText(v, 200f, y, value)
            y += 22f
        }

        // track thumbnail into a 400x300 box below the table
        if (d.trail.size >= 2) {
            val boxX = 40f; val boxY = y + 20f; val boxW = 400f; val boxH = 300f
            val minX = d.trail.minOf { it.x }; val maxX = d.trail.maxOf { it.x }
            val minY = d.trail.minOf { it.y }; val maxY = d.trail.maxOf { it.y }
            val spanX = (maxX - minX).coerceAtLeast(1.0)
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            val scale = minOf((boxW - 20) / spanX, (boxH - 20) / spanY).toFloat()
            val offX = boxX + (boxW - (spanX * scale).toFloat()) / 2
            val offY = boxY + (boxH - (spanY * scale).toFloat()) / 2
            val path = android.graphics.Path()
            d.trail.forEachIndexed { i, p ->
                val sx = offX + ((p.x - minX) * scale).toFloat()
                val sy = boxY + boxH - ((offY - boxY) + ((p.y - minY) * scale).toFloat())
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            c.drawPath(path, track)
        }

        doc.finishPage(page)
        outFile.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
