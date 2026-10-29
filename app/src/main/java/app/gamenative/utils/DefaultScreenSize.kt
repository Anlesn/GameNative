package app.gamenative.utils

import com.winlator.container.Container
import kotlin.math.abs

private const val ASPECT_4_3 = 4f / 3f
private const val ASPECT_16_10 = 16f / 10f
private const val ASPECT_16_9 = 16f / 9f
private const val ASPECT_TOLERANCE = 0.05f
private const val HIGH_RES_MIN_SHORT_SIDE = 1600

/**
 * Picks the default container screen size for a display of [width] x [height] physical pixels
 * (either orientation).
 *
 * - 4:3 only when the display is close to 4:3.
 * - 16:10 when the display is close to 16:10 or between 16:10 and 16:9 and nearer to 16:10.
 * - 16:9 otherwise. In-between "square" tablets (e.g. 3392x2400 = 1.41) get 16:9: no preset
 *   matches their aspect, and 16:9 is what games support best.
 * - 16:9 is 1920x1080 when the short side is at least 1600 px; 4:3 and 16:10 stay at 1280 wide
 *   because the screen size list has no 1080-line entry for them.
 */
fun defaultScreenSizeFor(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return Container.DEFAULT_SCREEN_SIZE_16_9

    val longSide = maxOf(width, height)
    val shortSide = minOf(width, height)
    val aspectRatio = longSide.toFloat() / shortSide.toFloat()

    val nearerTo16By10 = abs(aspectRatio - ASPECT_16_10) < abs(aspectRatio - ASPECT_16_9)
    return when {
        abs(aspectRatio - ASPECT_4_3) < ASPECT_TOLERANCE -> Container.DEFAULT_SCREEN_SIZE_4_3
        nearerTo16By10 && aspectRatio > ASPECT_16_10 - ASPECT_TOLERANCE -> Container.DEFAULT_SCREEN_SIZE_16_10
        shortSide >= HIGH_RES_MIN_SHORT_SIDE -> Container.DEFAULT_SCREEN_SIZE_16_9_1080P
        else -> Container.DEFAULT_SCREEN_SIZE_16_9
    }
}
