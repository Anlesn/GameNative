package app.gamenative.ui.screen.xserver

import app.gamenative.utils.GameHelperProcesses
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import java.util.Locale

internal fun normalizeProcessName(name: String): String {
    val trimmed = name.trim().trim('"')
    val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
    val lower = base.lowercase(Locale.getDefault())
    return if (lower.endsWith(".exe")) lower.removeSuffix(".exe") else lower
}

/** True when a mapped application window other than the explorer desktop is on screen. */
internal fun hasMappedApplicationWindow(xServer: XServer): Boolean =
    xServer.lock(XServer.Lockable.WINDOW_MANAGER).use {
        containsMappedApplicationWindow(xServer, xServer.windowManager.rootWindow)
    }

private fun containsMappedApplicationWindow(xServer: XServer, window: Window): Boolean =
    window.children.any { child ->
        child.attributes.isMapped() &&
            !xServer.isDesktopWindow(child) &&
            (child.isApplicationWindow() || containsMappedApplicationWindow(xServer, child))
    }

internal sealed interface ExitWatchDecision {
    data object Continue : ExitWatchDecision

    data class Exit(val reason: String, val remaining: List<String>) : ExitWatchDecision

    data class GiveUp(val remaining: List<String>) : ExitWatchDecision
}

/**
 * Decides what the exit watch does after one process snapshot taken while the game window is unmapped.
 *
 * - Only essential processes left -> exit (`processes_exited`).
 * - Game exe gone and only game helpers (crash handlers, overlays) left -> exit (`processes_exited_helpers`).
 * - Timeout reached with the game exe gone and no application window on screen -> exit (`processes_exited_timeout`).
 * - Timeout reached otherwise -> give up: the game exe still runs (e.g. a launcher window closed), or another
 *   process shows a window (e.g. a launcher that exited after starting the real game).
 */
internal fun decideExitWatch(
    processNames: List<String>,
    targetExecutable: String,
    essentialProcesses: Set<String>,
    elapsedMs: Long,
    timeoutMs: Long,
    appWindowVisible: Boolean,
): ExitWatchDecision {
    val normalized = processNames.map(::normalizeProcessName)
    val nonEssential = normalized.filter { it !in essentialProcesses }
    if (nonEssential.isEmpty()) return ExitWatchDecision.Exit("processes_exited", nonEssential)

    val target = normalizeProcessName(targetExecutable)
    val targetRunning = target.isEmpty() || target in normalized
    if (!targetRunning && nonEssential.all(GameHelperProcesses::isGameHelper)) {
        return ExitWatchDecision.Exit("processes_exited_helpers", nonEssential)
    }
    if (elapsedMs < timeoutMs) return ExitWatchDecision.Continue
    return if (targetRunning || appWindowVisible) {
        ExitWatchDecision.GiveUp(nonEssential)
    } else {
        ExitWatchDecision.Exit("processes_exited_timeout", nonEssential)
    }
}
