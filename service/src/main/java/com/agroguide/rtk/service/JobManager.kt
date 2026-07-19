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
        val moving = state.speedMps > 0.2
        if (moving) {
            w.fix(t, workPoint.x, workPoint.y, state.headingDeg, state.speedMps, state.quality.ordinal, sectionsMask)
        } else if (wasMoving) {
            w.breakSegment(t)
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
        scope.launch {
            val job = AgroDb.get(context).jobs().findUnfinished() ?: return@launch
            val file = journalFile(context, job.id)
            val data = JournalReader.read(file) ?: return@launch
            synchronized(this@JobManager) {
                if (_activeJob.value != null) return@launch
                PositionRepository.pinPlane(TangentPlane(data.header.originLat, data.header.originLon))
                CoverageRepository.configure(data.header.widthM, data.header.sections)
                CoverageRepository.replay(data) { summary ->
                    GuidanceRepository.restoreTrail(summary.trail)
                    synchronized(this@JobManager) {
                        jobStartRealtime = SystemClock.elapsedRealtime() - summary.lastTMs
                        activeMsAccum = summary.activeSeconds * 1000
                        spanStartMs = if (job.state == STATE_ACTIVE) summary.lastTMs else -1
                        wasMoving = false
                        writer = JournalWriter(
                            file,
                            JournalHeader(data.header.originLat, data.header.originLon, data.header.widthM, data.header.sections),
                        )
                        _activeJob.value = job
                    }
                }
            }
        }
    }

    private fun setState(context: Context, job: JobEntity) {
        _activeJob.value = job
        scope.launch { AgroDb.get(context).jobs().update(job) }
    }
}
