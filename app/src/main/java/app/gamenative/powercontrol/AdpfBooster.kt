package app.gamenative.powercontrol

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PerformanceHintManager
import android.system.Os
import android.system.OsConstants
import android.view.Display
import androidx.annotation.RequiresApi
import app.gamenative.PrefManager
import app.gamenative.utils.WineProcessSnapshotHelper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * ADPF performance hint session over the game's threads (Android 14+).
 *
 * The session covers threads of the wine game processes, which are children of this app with the
 * same UID but a different PID. `HintManagerService.checkTidValid` accepts a tid whose `Uid:`
 * matches the caller's UID ("tids can belong to different application processes"), so this needs
 * no root. The session also covers the in-app X server thread that receives the game's presents.
 *
 * Every presented game frame ([onFramePresented], called from the Present and MIT-SHM paths)
 * reports the interval since the previous frame as the actual work duration. The game runs in
 * another process, so its real per-frame CPU time is not visible here; the interval is longer
 * than the target exactly when the game misses its frame budget, which is when the power HAL
 * should raise clocks. The target is the frame-limiter interval, or the display period without
 * a cap. Threads are re-scanned every [RESCAN_PERIOD_MS] and pushed with `setThreads`.
 * Started and stopped with the game session by [PowerManager.autoStart] and [PowerManager.stop].
 */
object AdpfBooster {
    private const val TAG = "AdpfBooster"
    private const val PAD4_MODEL = "OPD2514"
    private const val RESCAN_PERIOD_MS = 3_000L
    private const val STATUS_EVERY_SCANS = 10
    private const val MAX_SESSION_ATTEMPTS = 3

    @Volatile
    private var session: PerformanceHintManager.Session? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastFrameNs = 0L

    @Volatile
    private var presentTid = 0

    private var executor: ScheduledExecutorService? = null
    private var appContext: Context? = null
    private var sessionTids: List<Int> = emptyList()
    private var sessionTargetNs = 0L
    private var previousTicks: Map<Int, Long> = emptyMap()
    private var lastScanNs = 0L
    private var scans = 0
    private var sessionAttempts = 0
    private var gaveUp = false

    private val framesReported = AtomicLong()
    private val framesSkipped = AtomicLong()
    private val reportErrors = AtomicLong()
    private var threadUpdates = 0
    private var threadUpdateRejects = 0

    /** Default of the settings toggle: on for the OnePlus Pad 4, where it was measured. */
    fun isDefaultEnabled(): Boolean = isDefaultEnabledFor(Build.MODEL)

    internal fun isDefaultEnabledFor(model: String?): Boolean = model == PAD4_MODEL

    @Synchronized
    fun start(context: Context) {
        if (running) return
        if (!PrefManager.adpfBoostEnabled) {
            Timber.tag(TAG).i("not started: disabled in settings")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.tag(TAG).i("not started: API %d < 34 (setThreads unavailable)", Build.VERSION.SDK_INT)
            return
        }
        appContext = context.applicationContext
        resetState()
        running = true
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AdpfBooster").apply { isDaemon = true }
        }.also { exec ->
            exec.scheduleWithFixedDelay({ safeRescan() }, 0L, RESCAN_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
        Timber.tag(TAG).i("started (rescan every %d ms)", RESCAN_PERIOD_MS)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        executor?.shutdownNow()
        executor = null
        logStatus("stopped")
        val closing = session
        session = null
        runCatching { closing?.close() }
            .onFailure { Timber.tag(TAG).w(it, "session close failed") }
        appContext = null
    }

    /** Called on the X server thread for every frame the game presents. */
    @JvmStatic
    fun onFramePresented() {
        if (!running) return
        val tid = android.os.Process.myTid()
        if (tid != presentTid) presentTid = tid
        val now = System.nanoTime()
        val workNs = AdpfMath.frameWorkDurationNs(lastFrameNs, now)
        lastFrameNs = now
        val current = session ?: return
        if (workNs <= 0L) {
            framesSkipped.incrementAndGet()
            return
        }
        try {
            current.reportActualWorkDuration(workNs)
            framesReported.incrementAndGet()
        } catch (e: Exception) {
            if (reportErrors.incrementAndGet() == 1L) Timber.tag(TAG).w(e, "reportActualWorkDuration failed")
        }
    }

    private fun resetState() {
        session = null
        sessionTids = emptyList()
        sessionTargetNs = 0L
        previousTicks = emptyMap()
        lastScanNs = 0L
        lastFrameNs = 0L
        presentTid = 0
        scans = 0
        sessionAttempts = 0
        gaveUp = false
        framesReported.set(0L)
        framesSkipped.set(0L)
        reportErrors.set(0L)
        threadUpdates = 0
        threadUpdateRejects = 0
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun safeRescan() {
        try {
            synchronized(this) { if (running) rescan() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "rescan failed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun rescan() {
        val nowNs = System.nanoTime()
        val samples = readGameThreads()
        val elapsedTicks = if (lastScanNs > 0L) (nowNs - lastScanNs) / 1e9 * clockTicksPerSecond() else 0.0
        val tids = AdpfThreadSelector.select(
            samples = samples,
            previousTicks = previousTicks,
            elapsedTicks = elapsedTicks,
            extraTids = listOf(presentTid).filter { it > 0 },
        )
        previousTicks = samples.associate { it.tid to it.cpuTicks }
        lastScanNs = nowNs
        val targetNs = AdpfMath.targetWorkDurationNs(PowerManager.targetFps, displayRefreshHz())

        val current = session
        if (current == null) {
            if (tids.isNotEmpty() && !gaveUp) createSession(tids, targetNs)
        } else {
            if (tids.isNotEmpty() && tids != sessionTids) updateThreads(current, tids)
            if (targetNs != sessionTargetNs) {
                current.updateTargetWorkDuration(targetNs)
                Timber.tag(TAG).i("target %.2f ms -> %.2f ms", sessionTargetNs / 1e6, targetNs / 1e6)
                sessionTargetNs = targetNs
            }
        }
        if (++scans % STATUS_EVERY_SCANS == 0) logStatus("status")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createSession(tids: List<Int>, targetNs: Long) {
        val manager = appContext?.getSystemService(PerformanceHintManager::class.java)
        if (manager == null) {
            giveUp("unsupported: no PerformanceHintManager")
            return
        }
        if (manager.preferredUpdateRateNanos < 0L) {
            giveUp("unsupported: power HAL has no hint sessions (preferredUpdateRateNanos < 0)")
            return
        }
        sessionAttempts++
        val created = try {
            manager.createHintSession(tids.toIntArray(), targetNs)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w("session rejected (attempt %d): %s", sessionAttempts, e.message)
            null
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("session rejected (attempt %d): %s", sessionAttempts, e.message)
            null
        }
        if (created == null) {
            if (sessionAttempts >= MAX_SESSION_ATTEMPTS) giveUp("rejected $sessionAttempts times")
            return
        }
        session = created
        sessionTids = tids
        sessionTargetNs = targetNs
        Timber.tag(TAG).i(
            "session created: %d tids %s, target %.2f ms, update rate %.2f ms",
            tids.size,
            tids,
            targetNs / 1e6,
            manager.preferredUpdateRateNanos / 1e6,
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun updateThreads(current: PerformanceHintManager.Session, tids: List<Int>) {
        try {
            current.setThreads(tids.toIntArray())
            threadUpdates++
            Timber.tag(TAG).i("threads updated: %d tids %s", tids.size, tids)
            sessionTids = tids
        } catch (e: Exception) {
            // A thread exited between the scan and the call; the next scan retries.
            threadUpdateRejects++
            Timber.tag(TAG).w("threads update rejected: %s", e.message)
        }
    }

    private fun giveUp(reason: String) {
        gaveUp = true
        Timber.tag(TAG).w("no session: %s", reason)
    }

    private fun logStatus(label: String) {
        Timber.tag(TAG).i(
            "%s: session=%s tids=%d target=%.2f ms framesReported=%d framesSkipped=%d reportErrors=%d " +
                "threadUpdates=%d threadUpdateRejects=%d attempts=%d",
            label,
            when {
                session != null -> "on"
                gaveUp -> "refused"
                else -> "none"
            },
            sessionTids.size,
            sessionTargetNs / 1e6,
            framesReported.get(),
            framesSkipped.get(),
            reportErrors.get(),
            threadUpdates,
            threadUpdateRejects,
            sessionAttempts,
        )
    }

    private fun readGameThreads(): List<AdpfThreadSelector.ThreadSample> {
        val gamePids = WineProcessSnapshotHelper.readFromProc()
            .filterNot { WineProcessSnapshotHelper.isSystemProcessName(it.name) }
            .map { it.pid }
        val samples = ArrayList<AdpfThreadSelector.ThreadSample>()
        for (pid in gamePids) {
            val tasks = File("/proc/$pid/task").listFiles() ?: continue
            for (task in tasks) {
                val tid = task.name.toIntOrNull() ?: continue
                try {
                    val comm = File(task, "comm").readText().trim()
                    val ticks = AdpfThreadSelector.parseCpuTicks(File(task, "stat").readText()) ?: continue
                    samples += AdpfThreadSelector.ThreadSample(tid, pid, comm, ticks)
                } catch (_: Exception) {
                    // Thread exited while enumerating.
                }
            }
        }
        return samples
    }

    private fun clockTicksPerSecond(): Double =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() }.getOrNull()?.takeIf { it > 0 } ?: 100.0

    private fun displayRefreshHz(): Float =
        appContext?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.refreshRate
            ?: 0f
}

internal object AdpfMath {
    /** Frames further apart than this are a stall or a pause, not a frame to report. */
    const val MAX_FRAME_NS = 1_000_000_000L
    private const val FALLBACK_HZ = 60f

    fun targetWorkDurationNs(fpsCap: Int, refreshHz: Float): Long {
        val hz = when {
            fpsCap > 0 -> fpsCap.toFloat()
            refreshHz >= 1f -> refreshHz
            else -> FALLBACK_HZ
        }
        return (1_000_000_000.0 / hz).toLong()
    }

    /** Interval since the previous frame, or 0 when there is none to report. */
    fun frameWorkDurationNs(previousFrameNs: Long, nowNs: Long): Long {
        if (previousFrameNs <= 0L) return 0L
        val delta = nowNs - previousFrameNs
        return if (delta in 1..MAX_FRAME_NS) delta else 0L
    }
}

internal object AdpfThreadSelector {
    const val MAX_THREADS = 16

    /** Share of one core over the scan interval from which a thread counts as hot. */
    const val HOT_SHARE = 0.25

    /** Lowercase prefixes of engine render/game threads, as truncated to 15 chars by comm. */
    private val RENDER_THREAD_PREFIXES = listOf(
        "unitymain",
        "unitygfxdevice",
        "gamethread",
        "renderthread",
        "rhithread",
        "dxvk-",
        "vkd3d",
        "wined3d",
    )

    data class ThreadSample(val tid: Int, val pid: Int, val comm: String, val cpuTicks: Long)

    fun isRenderThreadName(comm: String): Boolean {
        val lower = comm.lowercase()
        return RENDER_THREAD_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Picks the hint-session threads: each process's main thread, named engine threads and threads
     * busier than [HOT_SHARE] of a core since the previous scan, hottest first, plus [extraTids]
     * (in-app threads, always kept). Returns at most [max] tids in ascending order.
     */
    fun select(
        samples: List<ThreadSample>,
        previousTicks: Map<Int, Long>,
        elapsedTicks: Double,
        extraTids: List<Int>,
        max: Int = MAX_THREADS,
    ): List<Int> {
        fun share(sample: ThreadSample): Double {
            val before = previousTicks[sample.tid] ?: return 0.0
            if (elapsedTicks <= 0.0) return 0.0
            return (sample.cpuTicks - before).coerceAtLeast(0L) / elapsedTicks
        }

        val extras = extraTids.distinct().take(max)
        val picked = samples.asSequence()
            .filter { it.tid !in extras }
            .map { it to share(it) }
            .filter { (sample, share) -> sample.tid == sample.pid || isRenderThreadName(sample.comm) || share >= HOT_SHARE }
            .sortedByDescending { (_, share) -> share }
            .map { (sample, _) -> sample.tid }
            .distinct()
            .take(max - extras.size)
            .toList()
        return (picked + extras).sorted()
    }

    /** utime + stime from a `/proc/<pid>/task/<tid>/stat` line; the comm field may contain spaces. */
    fun parseCpuTicks(stat: String): Long? {
        val fields = stat.substringAfterLast(')', "").trim().split(' ')
        // fields[0] is the state (field 3 of stat), so utime (14) and stime (15) are at 11 and 12.
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }
}
