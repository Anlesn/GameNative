package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitWatchDecisionTest {

    private val essential = setOf("wineserver", "services", "start", "winhandler", "explorer", "svchost", "rpcss")
    private val wine = listOf("wineserver", "C:\\windows\\system32\\services.exe", "start.exe", "winhandler.exe", "explorer.exe")

    private fun decide(
        names: List<String>,
        elapsedMs: Long = 1_000L,
        target: String = "A:\\DREDGE.exe",
        appWindowVisible: Boolean = false,
    ) = decideExitWatch(names, target, essential, elapsedMs, TIMEOUT_MS, appWindowVisible)

    @Test
    fun onlyEssentialProcesses_exits() {
        assertEquals(ExitWatchDecision.Exit("processes_exited", emptyList()), decide(wine))
    }

    @Test
    fun onlyGameHelpersRemain_afterTargetExited_exits() {
        assertEquals(
            ExitWatchDecision.Exit("processes_exited_helpers", listOf("unitycrashhandler32")),
            decide(wine + "UnityCrashHandler32.exe"),
        )
        assertEquals(
            ExitWatchDecision.Exit(
                "processes_exited_helpers",
                listOf("crashpad_handler", "eosoverlayrenderer-win64-shipping", "epiconlineservicesuserhelper"),
            ),
            decide(
                wine + "crashpad_handler.exe" + "EOSOverlayRenderer-Win64-Shipping.exe" +
                    "EpicOnlineServicesUserHelper.exe",
            ),
        )
    }

    @Test
    fun gameHelperWithTargetStillRunning_continuesThenGivesUp() {
        val names = wine + "DREDGE.exe" + "UnityCrashHandler32.exe"
        assertEquals(ExitWatchDecision.Continue, decide(names))
        assertEquals(
            ExitWatchDecision.GiveUp(listOf("dredge", "unitycrashhandler32")),
            decide(names, elapsedMs = TIMEOUT_MS),
        )
    }

    @Test
    fun unknownProcessRemains_afterTargetExited_continuesThenExitsOnTimeout() {
        val names = wine + "SomeService.exe"
        assertEquals(ExitWatchDecision.Continue, decide(names, elapsedMs = TIMEOUT_MS - 1))
        assertEquals(
            ExitWatchDecision.Exit("processes_exited_timeout", listOf("someservice")),
            decide(names, elapsedMs = TIMEOUT_MS),
        )
    }

    @Test
    fun unknownProcessWithVisibleWindow_onTimeout_givesUp() {
        assertEquals(
            ExitWatchDecision.GiveUp(listOf("game")),
            decide(wine + "Game.exe", elapsedMs = TIMEOUT_MS, target = "Launcher.exe", appWindowVisible = true),
        )
    }

    @Test
    fun targetName_isNormalized() {
        val names = wine + "C:\\Games\\DREDGE.EXE" + "UnityCrashHandler64.exe"
        assertEquals(ExitWatchDecision.Continue, decide(names, target = "A:\\DREDGE.exe"))
        assertEquals(ExitWatchDecision.Continue, decide(names, target = "\"/mnt/a/DREDGE.exe\""))
        assertEquals(
            ExitWatchDecision.Exit("processes_exited_helpers", listOf("unitycrashhandler64")),
            decide(wine + "UnityCrashHandler64.exe", target = "A:\\DREDGE.exe"),
        )
    }

    @Test
    fun blankTarget_isTreatedAsRunning() {
        assertEquals(
            ExitWatchDecision.GiveUp(listOf("unitycrashhandler32")),
            decide(wine + "UnityCrashHandler32.exe", elapsedMs = TIMEOUT_MS, target = ""),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
