package com.winlator.xserver

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VsyncPacingTest {
    private val anchor = 1_000_000_000L
    private val p60 = 16_666_667L
    private val p5994 = 16_683_350L // 59.94 Hz

    @Test
    fun `vsyncsPerFrame locks integer ratios and rejects the rest`() {
        val cases = listOf(
            Triple(60.0, 30, 2), Triple(120.0, 30, 4), Triple(120.0, 40, 3), Triple(90.0, 45, 2),
            Triple(60.0, 60, 1), Triple(120.0, 60, 2), Triple(144.0, 48, 3), Triple(60.0, 20, 3),
            Triple(59.94, 30, 2),
            Triple(60.0, 40, 0), Triple(60.0, 45, 0), Triple(120.0, 50, 0), Triple(60.0, 90, 0),
            Triple(60.0, 31, 0), Triple(60.0, 29, 0), Triple(0.0, 30, 0), Triple(60.0, 0, 0),
        )
        for ((hz, fps, expected) in cases) {
            assertEquals("$fps fps @ $hz Hz", expected, VsyncPacing.vsyncsPerFrame(hz, fps))
        }
    }

    @Test
    fun `snapToGrid picks the nearest vsync on both sides of the anchor`() {
        val grid = VsyncPacing.Grid(anchor, p60)
        assertEquals(anchor, VsyncPacing.snapToGrid(anchor + p60 / 2 - 1, grid))
        assertEquals(anchor + p60, VsyncPacing.snapToGrid(anchor + p60 / 2 + 1, grid))
        assertEquals(anchor + 3 * p60, VsyncPacing.snapToGrid(anchor + 3 * p60 + 100_000, grid))
        assertEquals(anchor - 2 * p60, VsyncPacing.snapToGrid(anchor - 2 * p60 - 100_000, grid))
    }

    @Test
    fun `nextSlot restarts on the first frame and after a stall, on the grid`() {
        val grid = VsyncPacing.Grid(anchor, p60)
        val now = anchor + 5_000_000L
        val first = VsyncPacing.nextSlot(0, now, grid, 2)
        assertEquals(anchor + 2 * p60, first)

        // Game stalled for 10 frames: restart from now instead of bursting to catch up.
        val stalledNow = first + 10 * 2 * p60 + 3_000_000L
        val restarted = VsyncPacing.nextSlot(first, stalledNow, grid, 2)
        assertOnGrid(restarted, grid)
        assertTrue(restarted - stalledNow >= 2 * p60 - p60 / 2)
    }

    @Test
    fun `nextSlot keeps the cadence when the game is less than a frame late`() {
        val grid = VsyncPacing.Grid(anchor, p60)
        val prev = anchor + 2 * p60
        val lateNow = prev + p60 // past its slot, but within one frame
        assertEquals(prev + 2 * p60, VsyncPacing.nextSlot(prev, lateNow, grid, 2))
    }

    @Test
    fun `nextSlot re-snaps to a moved grid`() {
        // Anchor re-estimated 0.4 ms later: the next slot follows the real vsync.
        val prev = anchor + 2 * p60
        val moved = VsyncPacing.Grid(anchor + 400_000L, p60)
        assertEquals(moved.anchorNs + 4 * p60, VsyncPacing.nextSlot(prev, prev - p60, moved, 2))
    }

    @Test
    fun `isMiss flags releases more than half a period late`() {
        assertFalse(VsyncPacing.isMiss(anchor + p60 / 2, anchor, p60))
        assertTrue(VsyncPacing.isMiss(anchor + p60 / 2 + 1, anchor, p60))
        assertFalse(VsyncPacing.isMiss(anchor - 700_000L, anchor, p60))
    }

    // Reproduces the Metro / Wolfenstein symptom: cap 30 on a 59.94 Hz panel, the game
    // presents 30-80 % of a period after its release. A wall-clock release drifts
    // through the vsync phase and produces 16.7 / 50 ms pairs; a vsync-locked one
    // shows every frame exactly 2 vsyncs apart.
    @Test
    fun `locked cadence shows every frame two vsyncs apart where wall clock pairs`() {
        val grid = VsyncPacing.Grid(anchor, p5994)
        val wallClock = displayIntervals(grid) { prev, now ->
            val frameNs = 1_000_000_000L / 30
            if (prev <= now - frameNs) now + frameNs else prev + frameNs
        }
        val locked = displayIntervals(grid) { prev, now -> VsyncPacing.nextSlot(prev, now, grid, 2) }

        assertTrue("wall clock should show off-cadence frames", wallClock.count { it != 2 * p5994 } > 0)
        assertEquals(0, locked.count { it != 2 * p5994 })
    }

    @Test
    fun `estimator takes the exact frame-timeline period`() {
        val e = VsyncPacing.PeriodEstimator()
        e.onVsync(anchor, 8_333_333L)
        assertEquals(8_333_333L, e.periodNs())
    }

    @Test
    fun `estimator seeds from the smallest delta and ignores skipped callbacks`() {
        val e = VsyncPacing.PeriodEstimator()
        var t = anchor
        e.onVsync(t, 0)
        for (skip in listOf(2, 1, 3, 1)) {
            t += skip * p5994
            e.onVsync(t, 0)
        }
        assertEquals(p5994, e.periodNs())
        // A run of skipped callbacks (main thread busy) leaves the estimate alone.
        repeat(5) {
            t += 2 * p5994
            e.onVsync(t, 0)
        }
        assertEquals(p5994, e.periodNs())
    }

    @Test
    fun `estimator follows refresh switches both ways`() {
        val e = VsyncPacing.PeriodEstimator()
        var t = anchor
        repeat(6) {
            e.onVsync(t, 0)
            t += p60
        }
        assertEquals(p60, e.periodNs())

        val p120 = p60 / 2
        repeat(3) {
            e.onVsync(t, 0)
            t += p120
        }
        assertEquals(p120.toDouble(), e.periodNs().toDouble(), 1_000.0)

        repeat(12) {
            e.onVsync(t, 0)
            t += p60
        }
        assertEquals(p60.toDouble(), e.periodNs().toDouble(), 1_000.0)
    }

    private fun assertOnGrid(t: Long, grid: VsyncPacing.Grid) {
        assertEquals(0L, Math.floorMod(t - grid.anchorNs, grid.periodNs))
    }

    // Simulates 600 frames: the slot releases the game, the next present arrives
    // after a random render time and is shown at the first vsync after it.
    private fun displayIntervals(grid: VsyncPacing.Grid, schedule: (Long, Long) -> Long): List<Long> {
        val rnd = Random(42)
        var slot = 0L
        var now = anchor + 5_000_000L
        val shown = mutableListOf<Long>()
        repeat(600) {
            slot = schedule(slot, now)
            val render = (grid.periodNs * (0.3 + 0.5 * rnd.nextDouble())).toLong()
            now = maxOf(now, slot) + render
            val k = Math.floorDiv(now - grid.anchorNs + grid.periodNs - 1, grid.periodNs)
            shown += grid.anchorNs + k * grid.periodNs
        }
        return shown.zipWithNext { a, b -> b - a }.drop(1)
    }
}
