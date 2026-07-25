package com.agroguide.rtk.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.coverage.CoverageEngine
import com.agroguide.rtk.data.db.AgroDb
import com.agroguide.rtk.data.db.JobEntity
import com.agroguide.rtk.data.journal.JournalReader
import com.agroguide.rtk.data.journal.JournalReplay
import com.agroguide.rtk.data.report.JobReport
import com.agroguide.rtk.data.report.JobReportData
import com.agroguide.rtk.data.report.PdfWriter
import com.agroguide.rtk.service.CoverageRepository
import com.agroguide.rtk.service.JobManager
import com.agroguide.rtk.service.PositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jobs tab: active job card with lifecycle buttons + finished-job history with reports. */
@Composable
fun JobsScreen() {
    val context = LocalContext.current
    val db = remember { AgroDb.get(context) }
    val scope = rememberCoroutineScope()
    val jobs by db.jobs().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val parcels by db.parcels().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val active by JobManager.activeJob.collectAsStateWithLifecycle()
    val stats by CoverageRepository.stats.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // active job card
        val job = active
        if (job == null) {
            Text(stringResource(R.string.job_none_active), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.job_new))
            }
        } else {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(job.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    val activeState = job.state == JobManager.STATE_ACTIVE
                    Text(
                        stringResource(if (activeState) R.string.job_active else R.string.job_paused),
                        color = if (activeState) Color(0xFF2E7D32) else Color(0xFFF9A825),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    String.format(
                        Locale.getDefault(), "%.2f ha · %.2f ha solape · %.2f km",
                        stats.treatedM2 / 10_000, stats.overlapM2 / 10_000, stats.distanceM / 1000,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job.state == JobManager.STATE_ACTIVE) {
                        Button(onClick = { JobManager.pause(context) }, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(stringResource(R.string.job_pause))
                        }
                    } else {
                        Button(onClick = { JobManager.resume(context) }, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(stringResource(R.string.job_resume))
                        }
                    }
                    OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(stringResource(R.string.job_finish))
                    }
                }
            }
        }

        Text(stringResource(R.string.job_history), style = MaterialTheme.typography.titleMedium)
        val history = jobs.filter { it.state == JobManager.STATE_FINISHED }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(history, key = { it.id }) { j ->
                Column(Modifier.fillMaxWidth()) {
                    Text(j.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        String.format(
                            Locale.getDefault(), "%s · %.2f ha · %s h",
                            dateStr(j.startedAtMs), j.treatedM2 / 10_000, JobReport.durationHmm(j.activeSeconds),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { generateReport(context, db, j, html = true) } }) {
                            Text(stringResource(R.string.job_report_html))
                        }
                        TextButton(onClick = { scope.launch { generateReport(context, db, j, html = false) } }) {
                            Text(stringResource(R.string.job_report_pdf))
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("Trabajo " + dateStr(System.currentTimeMillis())) }
        var parcelId by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(stringResource(R.string.job_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.job_name)) }, singleLine = true)
                    Text(stringResource(R.string.job_parcel), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = parcelId == null, onClick = { parcelId = null })
                        Text(stringResource(R.string.job_no_parcel))
                    }
                    parcels.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = parcelId == p.id, onClick = { parcelId = p.id })
                            Text(p.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val implement = com.agroguide.rtk.service.GuidanceRepository.implement.value
                    val ok = JobManager.start(context, name.ifBlank { "Trabajo" }, parcelId, implement.widthM, implement.sections)
                    if (!ok) Toast.makeText(context, R.string.job_need_position, Toast.LENGTH_LONG).show()
                    showNew = false
                }) { Text(stringResource(R.string.job_start)) }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            text = { Text(stringResource(R.string.job_finish_confirm, active?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = { JobManager.finish(context); confirmFinish = false }) {
                    Text(stringResource(R.string.job_finish))
                }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

fun dateStr(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(ms))

/**
 * Builds an HTML or PDF report and opens the share sheet. The journal only
 * contributes the track and average speed: if it is missing or unreadable the
 * report is still produced from the stored job totals, because a report with
 * no thumbnail is far more useful than an error message.
 */
private suspend fun generateReport(context: Context, db: AgroDb, job: JobEntity, html: Boolean) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val summary = runCatching {
                JournalReader.read(JobManager.journalFile(context, job.id))?.let { JournalReplay.summarize(it) }
            }.getOrNull()
            val parcel = runCatching { job.parcelId?.let { db.parcels().byId(it) } }.getOrNull()
            val report = JobReportData(
                jobName = job.name, parcelName = parcel?.name, dateStr = dateStr(job.startedAtMs),
                activeSeconds = job.activeSeconds, treatedM2 = job.treatedM2, overlapM2 = job.overlapM2,
                parcelAreaM2 = parcel?.areaM2, widthM = job.implementWidthM,
                avgSpeedMps = summary?.avgSpeedMps ?: 0.0, distanceM = job.distanceM,
                trail = summary?.trail ?: emptyList(),
            )
            val safeName = job.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "trabajo" }
            val dir = ExportFiles.exportDir(context)
            if (html) {
                val f = File(dir, "informe-$safeName.html")
                f.writeText(JobReport.html(report))
                f to "text/html"
            } else {
                val f = File(dir, "informe-$safeName.pdf")
                PdfWriter.write(report, f)
                f to "application/pdf"
            }
        }
    }
    result.fold(
        onSuccess = { (file, mime) ->
            runCatching { ExportFiles.share(context, file, mime) }.onFailure { e ->
                Toast.makeText(
                    context,
                    context.getString(R.string.job_report_fail) + ": " + (e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        onFailure = { e ->
            Toast.makeText(
                context,
                context.getString(R.string.job_report_fail) + ": " + (e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        },
    )
}
