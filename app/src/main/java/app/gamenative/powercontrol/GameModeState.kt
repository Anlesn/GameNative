package app.gamenative.powercontrol

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Tells Android's GameManager (13+) whether the running game is loading or in gameplay.
 *
 * The manifest opts into Game Mode through `game_mode_config`; this is the runtime half. The
 * power HAL and the OEM game services may hold or release boosts on these transitions.
 *
 * Signals: [Phase.LOADING] when the wine environment is set up (`setupXEnvironment`), [Phase.GAMEPLAY] on the
 * first frame of the first application window (the same point that flips
 * `XServerState.winStarted`), [Phase.ENDED] when the environment shuts down. A game that keeps
 * loading after its first window is shown is reported as in gameplay from that window on.
 */
object GameModeState {
    private const val TAG = "GameMode"

    enum class Phase(val isLoading: Boolean, val mode: Int) {
        LOADING(true, GameState.MODE_GAMEPLAY_INTERRUPTIBLE),
        GAMEPLAY(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE),
        ENDED(false, GameState.MODE_NONE),
    }

    @Volatile
    private var appContext: Context? = null

    private val tracker = GameStateTracker { phase, previous, reason -> apply(phase, previous, reason) }

    fun report(context: Context?, phase: Phase, reason: String) {
        context?.applicationContext?.let { appContext = it }
        tracker.moveTo(phase, reason)
    }

    private fun apply(phase: Phase, previous: Phase?, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Timber.tag(TAG).i("GameState %s -> %s (%s) skipped: API %d < 33", previous, phase, reason, Build.VERSION.SDK_INT)
            return
        }
        val gameManager = appContext?.getSystemService(GameManager::class.java)
        if (gameManager == null) {
            Timber.tag(TAG).w("GameState %s -> %s (%s) skipped: no GameManager", previous, phase, reason)
            return
        }
        try {
            gameManager.setGameState(GameState(phase.isLoading, phase.mode))
            Timber.tag(TAG).i(
                "GameState %s -> %s (%s): isLoading=%s mode=%d gameMode=%s",
                previous,
                phase,
                reason,
                phase.isLoading,
                phase.mode,
                gameModeName(gameManager.gameMode),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "GameState %s -> %s (%s) failed", previous, phase, reason)
        }
    }

    private fun gameModeName(mode: Int): String = when (mode) {
        GameManager.GAME_MODE_STANDARD -> "standard"
        GameManager.GAME_MODE_PERFORMANCE -> "performance"
        GameManager.GAME_MODE_BATTERY -> "battery"
        GameManager.GAME_MODE_CUSTOM -> "custom"
        else -> "unsupported"
    }
}

/**
 * Drops repeated phases and an [GameModeState.Phase.ENDED] without a started session, so each
 * real transition reaches [onTransition] exactly once.
 */
internal class GameStateTracker(
    private val onTransition: (phase: GameModeState.Phase, previous: GameModeState.Phase?, reason: String) -> Unit,
) {
    private var current: GameModeState.Phase? = null

    @Synchronized
    fun moveTo(phase: GameModeState.Phase, reason: String): Boolean {
        val previous = current
        if (phase == previous) return false
        if (phase == GameModeState.Phase.ENDED && previous == null) return false
        current = phase
        onTransition(phase, previous, reason)
        return true
    }
}
