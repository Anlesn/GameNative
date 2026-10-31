package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFeedbackPromptTest {

    @Test
    fun `unset asks on other devices`() {
        assertTrue(shouldAskGameFeedbackAfterExit(null, "SM-X910"))
        assertTrue(shouldAskGameFeedbackAfterExit(null, null))
        assertTrue(shouldAskGameFeedbackAfterExit(null, ""))
    }

    @Test
    fun `unset does not ask on Pad 4`() {
        assertFalse(shouldAskGameFeedbackAfterExit(null, PAD4_MODEL))
        assertFalse(shouldAskGameFeedbackAfterExit(null, "OPD2514"))
    }

    @Test
    fun `explicit choice wins over device default`() {
        assertTrue(shouldAskGameFeedbackAfterExit(true, PAD4_MODEL))
        assertFalse(shouldAskGameFeedbackAfterExit(false, "SM-X910"))
        assertTrue(shouldAskGameFeedbackAfterExit(true, "SM-X910"))
        assertFalse(shouldAskGameFeedbackAfterExit(false, PAD4_MODEL))
    }
}
