package com.agroguide.rtk.service

import android.content.Context
import android.os.SystemClock
import com.agroguide.rtk.data.db.AgroDb
import com.agroguide.rtk.data.db.JobEntity
import com.agroguide.rtk.data.journal.JournalHeader
import com.agroguide.rtk.data.journal.JournalReader
import com.agroguide.rtk.data.journal.JournalWriter
import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.TangentPlane
import com.agroguide.rtk.geo.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Single-active-job lifecycle: journal recording, pause/resume gating, finish
 * stats, and resume-after-restart (journal replay + ENU plane pinning).
 * Journal writes happen inline on the service collector (fast appends at 5 Hz);
 * DB writes go through an IO scope.
 */
object JobManager {
    private val _activeJob = MutableStateFlow<JobEntity?>(null)
    val activeJob: StateFlow<JobEntity?> = _activeJob.asStateFlow()

    val isPaused: Boolean get() = _activeJob.value?.state == STATE_PAUSED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: JournalWriter? = null
    private var jobStartRealtime = 0L      // elapsedRealtime corresponding to journal t=0
    private var activeMsAccum = 0L
    private var spanStartMs = -1L          // journal-time start of the current active span
    private var wasMoving = false

    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_PAUSED = "PAUSED"
    const val STATE_FINISHED = "FINISHED"

    fun journalFile(context: Context, jobId: String): File =
        File(context.filesDir, "journals/$jobId.journal").apply { parentFile?.mkdirs() }

    /** Starts a new job anchored at the current ENU origin. False if no fix yet. */
    @Synchronized
    fun start(context: Context, name: String, parcelId: String?, widthM: Double, sections: Int): Boolean {
        if (_activeJob.value != null) return false
        val origin = PositionRepository.activePlaneOrigin ?: return false
        val job = JobEntity(
            id = UUID.randomUUID().toString(), name = name, parcelId = parcelId,
            startedAtMs = System.currentTimeMillis(), finishedAtMs = null, state = STATE_ACTIVE,
            implementWidthM = widthM, sections = sections,
            originLat = origin.first, originLon = origin.second,
        )
        writer = JournalWriter(
            journalFile(context, job.id),
            JournalHeader(origin.first, origin.second, widthM, sections),
        )
        jobStartRealtime = SystemClock.elapsedRealtime()
        activeMsAccum = 0; spanStartMs = 0; wasMoving = false
        PositionRepository.pinPlane(TangentPlane(origin.first, origin.second))
        CoverageRepository.reset()
        GuidanceRepository.clearTrail()
        watchLine(context)
        _activeJob.value = job
        scope.launch { AgroDb.get(context).jobs().insert(job) }
        return true
    }

    /** Fed from the service vehicle collector for every state while a job exists. */
    @Synchronized
    fun onState(state: VehicleState, workPoint: EnuPoint, sectionsMask: Int) {
        val job = _activeJob.value ?: return
        if (job.state != STATE_ACTIVE) return
        val w = writer ?: return
        val t = SystemClock.elapsedRealtime() - jobStartRealtime
        // Record exactly what is painted, so a replay after a restart matches.
        val moving = !CoverageRepository.isPaused(state)
        // A journal write failure (full disk, storage detached) must degrade to
        // "recording stopped", never to a crash mid-work.
        try {
            if (moving) {
                w.fix(t, workPoint.x, workPoint.y, state.headingDeg, state.speedMps, state.quality.ordinal, sectionsMask)
            } else if (wasMoving) {
                w.breakSegment(t)
            }
        } catch (e: Exception) {
            android.util.Log.e("JobManager", "journal write failed", e)
        }
        wasMoving = moving
    }

    @Synchronized
    fun pause(context: Context) {
        val job = _activeJob.value?.takeIf { it.state == STATE_ACTIVE } ?: return
        val t = SystemClock.elapsedRealtime() - jobStartRealtime
        writer?.pause(t)
        if (spanStartMs >= 0) { activeMsAccum += t - spanStartMs; spanStartMs = -1 }
        setState(context, job.copy(state = STATE_PAUSED))
    }

    @Synchronized
    fun resume(context: Context) {
        val job = _activeJob.value?.takeIf { it.state == STATE_PAUSED } ?: return
        val t = SystemClock.elapsedRealtime() - jobStartRealtime
        writer?.resume(t)
        spanStartMs = t
        setState(context, job.copy(state = STATE_ACTIVE))
    }

    @Synchronized
    fun finish(context: Context) {
        val job = _activeJob.value ?: return
        val t = SystemClock.elapsedRealtime() - jobStartRealtime
        if (job.state == STATE_ACTIVE && spanStartMs >= 0) activeMsAccum += t - spanStartMs
        writer?.close(); writer = null
        val stats = CoverageRepository.stats.value
        val finished = job.copy(
            state = STATE_FINISHED, finishedAtMs = System.currentTimeMillis(),
            treatedM2 = stats.treatedM2, overlapM2 = stats.overlapM2,
            distanceM = stats.distanceM, activeSeconds = activeMsAccum / 1000,
        )
        _activeJob.value = null
        PositionRepository.pinPlane(null)
        scope.launch { AgroDb.get(context).jobs().update(finished) }
    }

    /** On service start: reopen an unfinished job, replay its journal, pin its plane. */
    fun tryResumeOnStart(context: Context) {
        if (_activeJob.value != null) return
        watchLine(context)
        scope.launch {
            val job = AgroDb.get(context).jobs().findUnfinished() ?: return@launch
            val data = JournalReader.read(journalFile(context, job.id)) ?: return@launch
            reopen(context, job, data)
        }
    }

    /** Why [continueJob] could not reopen a job. */
    enum class ContinueResult { STARTED, ANOTHER_ACTIVE, NO_TRACK }

    /**
     * Reopens a finished job to carry on where it was left: its painted
     * coverage, track and guidance line come back from the journal, and new
     * passes are appended to the same journal (one job, one report).
     */
    suspend fun continueJob(context: Context, job: JobEntity): ContinueResult {
        if (_activeJob.value != null) return ContinueResult.ANOTHER_ACTIVE
        val data = kotlinx.coroutines.withContext(Dispatchers.IO) {
            JournalReader.read(journalFile(context, job.id))
        } ?: return ContinueResult.NO_TRACK
        watchLine(context)
        val reopened = job.copy(state = STATE_ACTIVE, finishedAtMs = null)
        if (!reopen(context, reopened, data)) return ContinueResult.ANOTHER_ACTIVE
        scope.launch { AgroDb.get(context).jobs().update(reopened) }
        return ContinueResult.STARTED
    }

    /**
     * Shared by crash-resume and "Continuar": pin the job's ENU plane, rebuild
     * its coverage from the journal, restore its line and reopen the journal
     * for appending. The tractor has moved since the last record, so a swath
     * break is written: the first new fix must not paint a band back to where
     * the job stopped.
     */
    private fun reopen(context: Context, job: JobEntity, data: com.agroguide.rtk.data.journal.JournalData): Boolean {
        synchronized(this) {
            if (_activeJob.value != null) return false
            PositionRepository.pinPlane(TangentPlane(data.header.originLat, data.header.originLon))
            loadLine(context, job.id)?.let { GuidanceRepository.restoreLine(it) }
            CoverageRepository.configure(data.header.widthM, data.header.sections)
            // passes, live paint and the journal must all use the job's width
            GuidanceRepository.setImplement(
                GuidanceRepository.implement.value.copy(widthM = data.header.widthM, sections = data.header.sections),
            )
            CoverageRepository.replay(data) { summary ->
                GuidanceRepository.restoreTrail(summary.trail)
                synchronized(this@JobManager) {
                    jobStartRealtime = SystemClock.elapsedRealtime() - summary.lastTMs
                    activeMsAccum = summary.activeSeconds * 1000
                    spanStartMs = if (job.state == STATE_ACTIVE) summary.lastTMs else -1
                    wasMoving = false
                    writer = JournalWriter(
                        journalFile(context, job.id),
                        JournalHeader(data.header.originLat, data.header.originLon, data.header.widthM, data.header.sections),
                    ).also { w ->
                        runCatching { w.breakSegment(summary.lastTMs) }
                        // a job closed while paused: record the resume so a
                        // replay counts the new work as active time
                        if (job.state == STATE_ACTIVE && data.records.lastOrNull() is com.agroguide.rtk.data.journal.JournalRecord.Pause) {
                            runCatching { w.resume(summary.lastTMs) }
                        }
                    }
                    _activeJob.value = job
                }
            }
            return true
        }
    }

    // ---- per-job guidance line ---------------------------------------------
    // Saved next to the journal, in the job's own ENU plane, so continuing a
    // job brings back the line its passes were driven on.

    fun lineFile(context: Context, jobId: String): File = File(context.filesDir, "journals/$jobId.line")

    private fun loadLine(context: Context, jobId: String) = runCatching {
        val f = lineFile(context, jobId)
        if (f.exists()) com.agroguide.rtk.data.guidance.GuidanceLineCodec.fromJson(f.readText()) else null
    }.getOrNull()

    private var lineWatcher: kotlinx.coroutines.Job? = null

    @Synchronized
    private fun watchLine(context: Context) {
        if (lineWatcher != null) return
        val app = context.applicationContext
        lineWatcher = scope.launch {
            kotlinx.coroutines.flow.combine(_activeJob, GuidanceRepository.line) { job, line -> job?.id to line }
                .collect { (jobId, line) ->
                    if (jobId == null) return@collect
                    runCatching {
                        val json = com.agroguide.rtk.data.guidance.GuidanceLineCodec.toJson(line)
                        val f = lineFile(app, jobId)
                        if (json == null) f.delete() else f.writeText(json)
                    }
                }
        }
    }

    private fun setState(context: Context, job: JobEntity) {
        _activeJob.value = job
        scope.launch { AgroDb.get(context).jobs().update(job) }
    }
}
