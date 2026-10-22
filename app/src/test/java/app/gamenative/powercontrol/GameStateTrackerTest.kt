package app.gamenative.powercontrol

import app.gamenative.powercontrol.GameModeState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTrackerTest {
    private val transitions = mutableListOf<Pair<Phase?, Phase>>()
    private val tracker = GameStateTracker { phase, previous, _ -> transitions += previous to phase }

    @Test
    fun `session reports loading, gameplay and end once each`() {
        assertTrue(tracker.moveTo(Phase.LOADING, "setup"))
        assertTrue(tracker.moveTo(Phase.GAMEPLAY, "first frame"))
        assertTrue(tracker.moveTo(Phase.ENDED, "shutdown"))

        assertEquals(
            listOf(null to Phase.LOADING, Phase.LOADING to Phase.GAMEPLAY, Phase.GAMEPLAY to Phase.ENDED),
            transitions,
        )
    }

    @Test
    fun `repeated phase is dropped`() {
        tracker.moveTo(Phase.GAMEPLAY, "first frame")
        assertFalse(tracker.moveTo(Phase.GAMEPLAY, "recreated view"))
        assertEquals(1, transitions.size)
    }

    @Test
    fun `end without a started session is dropped`() {
        assertFalse(tracker.moveTo(Phase.ENDED, "shutdown"))
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `next session after end reports loading again`() {
        tracker.moveTo(Phase.LOADING, "setup")
        tracker.moveTo(Phase.ENDED, "shutdown")
        assertTrue(tracker.moveTo(Phase.LOADING, "setup"))
        assertEquals(Phase.ENDED to Phase.LOADING, transitions.last())
    }

    @Test
    fun `phases map to loading flag and gameplay mode`() {
        assertTrue(Phase.LOADING.isLoading)
        assertFalse(Phase.GAMEPLAY.isLoading)
        assertEquals(android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE, Phase.LOADING.mode)
        assertEquals(android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE, Phase.GAMEPLAY.mode)
        assertEquals(android.app.GameState.MODE_NONE, Phase.ENDED.mode)
    }
}
