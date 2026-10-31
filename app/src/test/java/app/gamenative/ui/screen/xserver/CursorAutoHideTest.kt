package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorAutoHideTest {

    @Test
    fun startsHidden_gamepadKeepsItHidden() {
        val policy = CursorAutoHide()
        assertTrue(policy.isHidden())
        assertFalse(policy.onGamepadInput(nowMs = 1_000L))
        assertTrue(policy.isHidden())
    }

    @Test
    fun pointerMove_showsCursor_onceOnly() {
        val policy = CursorAutoHide()
        assertTrue(policy.onPointerMove(nowMs = 1_000L))
        assertFalse(policy.isHidden())
        assertFalse(policy.onPointerMove(nowMs = 1_010L))
    }

    @Test
    fun gamepadAfterGrace_hidesCursor() {
        val policy = CursorAutoHide(pointerGraceMs = 500L)
        policy.onPointerMove(nowMs = 1_000L)
        assertTrue(policy.onGamepadInput(nowMs = 1_500L))
        assertTrue(policy.isHidden())
        assertFalse(policy.onGamepadInput(nowMs = 1_600L))
    }

    @Test
    fun gamepadWithinGrace_keepsCursorShown() {
        val policy = CursorAutoHide(pointerGraceMs = 500L)
        policy.onPointerMove(nowMs = 1_000L)
        assertFalse(policy.onGamepadInput(nowMs = 1_499L))
        assertFalse(policy.isHidden())
    }

    @Test
    fun stickBoundToMouse_doesNotFlicker() {
        // A stick bound to the mouse sends axis events and the injected moves they cause, interleaved.
        val policy = CursorAutoHide(pointerGraceMs = 500L)
        policy.onPointerMove(nowMs = 0L)
        for (t in 16L..2_000L step 16L) {
            assertFalse(policy.onGamepadInput(nowMs = t))
            assertFalse(policy.onPointerMove(nowMs = t + 1))
        }
        assertFalse(policy.isHidden())
    }

    @Test
    fun mouseAfterGamepad_showsCursorAgain() {
        val policy = CursorAutoHide(pointerGraceMs = 500L)
        policy.onPointerMove(nowMs = 0L)
        policy.onGamepadInput(nowMs = 1_000L)
        assertTrue(policy.onPointerMove(nowMs = 1_001L))
        assertFalse(policy.isHidden())
    }
}
