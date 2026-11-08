package app.gamenative.powercontrol

import app.gamenative.powercontrol.AdpfThreadSelector.ThreadSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdpfBoosterTest {
    @Test
    fun `default is on only for the OnePlus Pad 4`() {
        assertTrue(AdpfBooster.isDefaultEnabledFor("OPD2514"))
        assertFalse(AdpfBooster.isDefaultEnabledFor("Retroid Pocket 6"))
        assertFalse(AdpfBooster.isDefaultEnabledFor(null))
    }

    @Test
    fun `target follows the fps cap, else the display period`() {
        assertEquals(33_333_333L, AdpfMath.targetWorkDurationNs(fpsCap = 30, refreshHz = 144f))
        assertEquals(6_944_444L, AdpfMath.targetWorkDurationNs(fpsCap = 0, refreshHz = 144f))
        assertEquals(16_666_666L, AdpfMath.targetWorkDurationNs(fpsCap = 0, refreshHz = 0f))
    }

    @Test
    fun `frame work is the interval since the previous frame within one second`() {
        assertEquals(0L, AdpfMath.frameWorkDurationNs(previousFrameNs = 0L, nowNs = 5_000_000L))
        assertEquals(16_000_000L, AdpfMath.frameWorkDurationNs(previousFrameNs = 1_000_000L, nowNs = 17_000_000L))
        assertEquals(0L, AdpfMath.frameWorkDurationNs(previousFrameNs = 1L, nowNs = 2_000_000_000L))
        assertEquals(0L, AdpfMath.frameWorkDurationNs(previousFrameNs = 10L, nowNs = 10L))
    }

    @Test
    fun `engine render threads match by comm prefix`() {
        assertTrue(AdpfThreadSelector.isRenderThreadName("UnityGfxDeviceW"))
        assertTrue(AdpfThreadSelector.isRenderThreadName("UnityMain"))
        assertTrue(AdpfThreadSelector.isRenderThreadName("dxvk-submit"))
        assertTrue(AdpfThreadSelector.isRenderThreadName("RenderThread 1"))
        assertTrue(AdpfThreadSelector.isRenderThreadName("wined3d_cs"))
        assertFalse(AdpfThreadSelector.isRenderThreadName("wine_sechost_se"))
        assertFalse(AdpfThreadSelector.isRenderThreadName("Job.Worker 3"))
    }

    @Test
    fun `selection keeps main, named and hot threads, drops idle workers`() {
        val samples = listOf(
            ThreadSample(tid = 100, pid = 100, comm = "Game.exe", cpuTicks = 1_000),
            ThreadSample(tid = 101, pid = 100, comm = "UnityGfxDeviceW", cpuTicks = 10),
            ThreadSample(tid = 102, pid = 100, comm = "Job.Worker 0", cpuTicks = 590),
            ThreadSample(tid = 103, pid = 100, comm = "Job.Worker 1", cpuTicks = 505),
        )
        val previous = mapOf(100 to 800L, 101 to 10L, 102 to 400L, 103 to 500L)
        // 300 ticks elapsed: main 67 %, worker 0 63 %, worker 1 2 %, gfx 0 % (kept by name).
        val tids = AdpfThreadSelector.select(samples, previous, elapsedTicks = 300.0, extraTids = listOf(7))
        assertEquals(listOf(7, 100, 101, 102), tids)
    }

    @Test
    fun `first scan without history keeps only main and named threads`() {
        val samples = listOf(
            ThreadSample(tid = 100, pid = 100, comm = "Game.exe", cpuTicks = 1_000),
            ThreadSample(tid = 102, pid = 100, comm = "Job.Worker 0", cpuTicks = 590),
            ThreadSample(tid = 110, pid = 100, comm = "dxvk-cs", cpuTicks = 3),
        )
        assertEquals(listOf(100, 110), AdpfThreadSelector.select(samples, emptyMap(), elapsedTicks = 0.0, extraTids = emptyList()))
    }

    @Test
    fun `selection is capped with hottest threads first and extras always kept`() {
        val samples = (1..10).map { ThreadSample(tid = 200 + it, pid = 200, comm = "worker", cpuTicks = it * 100L) }
        val previous = samples.associate { it.tid to 0L }
        val tids = AdpfThreadSelector.select(samples, previous, elapsedTicks = 100.0, extraTids = listOf(5), max = 4)
        assertEquals(listOf(5, 208, 209, 210), tids)
    }

    @Test
    fun `cpu ticks parse utime plus stime even with spaces in comm`() {
        val stat = "1234 (RenderThread 1) S 1 1234 1234 0 -1 4194560 100 0 0 0 250 50 0 0 20 0 30 0 12345"
        assertEquals(300L, AdpfThreadSelector.parseCpuTicks(stat))
        assertNull(AdpfThreadSelector.parseCpuTicks("garbage"))
    }
}
