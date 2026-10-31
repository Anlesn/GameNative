package app.gamenative.ui.component

import com.winlator.core.envvars.EnvVars
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun parsePositiveFpsLimit(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }

internal fun parseBooleanExtra(value: String): Boolean? =
    when (value.trim().lowercase(Locale.US)) {
        "true" -> true
        "false" -> false
        else -> null
    }

internal fun fpsLimiterSteps(maxFps: Int): List<Int> {
    val sanitizedMax = maxFps.coerceAtLeast(5)
    val flooredMax = (sanitizedMax / 5) * 5
    return buildList {
        var value = 5
        while (value <= flooredMax) {
            add(value)
            value += 5
        }
        if (sanitizedMax != flooredMax) add(sanitizedMax)
    }
}

/**
 * Returns the index of the step that is the floor of [currentValue] — i.e. the highest
 * step that is still ≤ currentValue.  Falls back to 0 if currentValue is below the
 * first step, so navigation never goes in the wrong direction on a restored value that
 * isn't an exact multiple of 5.
 */
internal fun fpsLimiterCurrentIndex(steps: List<Int>, currentValue: Int): Int =
    steps.indexOfLast { it <= currentValue }.coerceAtLeast(0)

internal fun fpsLimiterProgress(currentValue: Int, maxFps: Int): Float {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return if (steps.lastIndex <= 0) 1f else currentIndex.toFloat() / steps.lastIndex.toFloat()
}

internal fun nextFpsLimiterValue(currentValue: Int, maxFps: Int): Int {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex + 1).coerceAtMost(steps.lastIndex)]
}

internal fun previousFpsLimiterValue(currentValue: Int, maxFps: Int): Int {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex - 1).coerceAtLeast(0)]
}

/** Engine-side frame-rate caps that GameNative's own limiter replaces at launch. */
internal val ENGINE_FRAME_RATE_CAP_VARS = listOf("DXVK_FRAME_RATE", "VKD3D_FRAME_RATE")

/**
 * Removes DXVK/VKD3D frame-rate caps from the launch environment, keeping only caps the user set in
 * the container Environment while GameNative does not pace the game itself (FPS limiter off, no LSFG
 * frame generation). Old defaults and best-config left `DXVK_FRAME_RATE=60` in many containers; such
 * a hidden cap fights the in-game limiter, which cannot lift it at runtime.
 *
 * @return names of user-set caps that were dropped because GameNative pacing is active.
 */
internal fun applyEngineFrameRateCaps(
    envVars: EnvVars,
    containerEnvVars: EnvVars,
    gameNativePacingActive: Boolean,
): List<String> {
    val dropped = mutableListOf<String>()
    for (name in ENGINE_FRAME_RATE_CAP_VARS) {
        envVars.remove(name)
        val userValue = containerEnvVars.get(name)
        if (userValue.isEmpty()) continue
        if (gameNativePacingActive) dropped.add(name) else envVars.put(name, userValue)
    }
    return dropped
}

// Relative slack when matching a panel rate to a multiple of the FPS cap, so NTSC-style
// 59.94 / 119.88 Hz modes (1000/1001, 0.1 % off) still count as exact multiples.
private const val REFRESH_MULTIPLE_TOLERANCE = 0.005f

/** True when [refreshHz] is an integer multiple (1x or more) of [capFps]. */
internal fun isRefreshMultipleOf(refreshHz: Float, capFps: Int): Boolean {
    if (capFps <= 0 || !refreshHz.isFinite() || refreshHz <= 0f) return false
    val ratio = refreshHz / capFps
    val multiple = ratio.roundToInt()
    return multiple >= 1 && abs(ratio - multiple) <= multiple * REFRESH_MULTIPLE_TOLERANCE
}

/**
 * Picks the panel refresh rate to request for an FPS [cap].
 *
 * Returns the highest supported rate that is an integer multiple of [cap], so every capped
 * frame spans the same number of vsync periods (40 → 120, 45 → 90). The highest multiple,
 * not the lowest, because the limiter releases frames on the first vsync after a wall-clock
 * target: a frame that misses its slot waits one more panel period, and a shorter period
 * means a smaller hitch (30 → 120 rather than 60).
 * No cap (0) or no supported multiple → the highest supported rate.
 * Returns 0 when [supportedHz] holds no usable rate.
 */
internal fun bestRefreshFor(cap: Int, supportedHz: List<Float>): Float {
    val rates = supportedHz.filter { it.isFinite() && it > 0f }
    val max = rates.maxOrNull() ?: return 0f
    if (cap <= 0) return max
    return rates.filter { isRefreshMultipleOf(it, cap) }.maxOrNull() ?: max
}

/** The [android.view.Display.Mode] fields a window refresh request needs, kept Android-free for tests. */
internal data class DisplayModeInfo(val modeId: Int, val width: Int, val height: Int, val refreshHz: Float)

/**
 * Picks the display mode a game window should prefer so it runs at [rateHz] without a resolution
 * change: among modes at [width]x[height], the one whose rate equals [rateHz] (NTSC-style slack as
 * in [isRefreshMultipleOf]), else the highest rate below it. Null when [rateHz] is unusable or no
 * mode at that resolution qualifies.
 */
internal fun pickWindowDisplayMode(
    modes: List<DisplayModeInfo>,
    width: Int,
    height: Int,
    rateHz: Float,
): DisplayModeInfo? {
    if (!rateHz.isFinite() || rateHz <= 0f) return null
    val slack = rateHz * REFRESH_MULTIPLE_TOLERANCE
    val candidates = modes.filter {
        it.width == width && it.height == height && it.refreshHz.isFinite() && it.refreshHz > 0f
    }
    return candidates.firstOrNull { abs(it.refreshHz - rateHz) <= slack }
        ?: candidates.filter { it.refreshHz <= rateHz + slack }.maxByOrNull { it.refreshHz }
}
