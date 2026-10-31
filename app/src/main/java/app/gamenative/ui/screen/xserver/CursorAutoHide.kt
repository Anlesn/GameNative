package app.gamenative.ui.screen.xserver

/**
 * Hides the X cursor while the player uses a gamepad and shows it again when a pointer moves.
 *
 * A pointer move is any input the X server injects as cursor motion: a mouse, a trackpad, the
 * on-screen touchpad, or a gamepad binding that drives the cursor. A gamepad event less than
 * [pointerGraceMs] after a pointer move keeps the cursor shown, so a stick bound to the mouse
 * does not flicker it. The cursor starts hidden, so a gamepad session never shows it.
 *
 * Thread-safe: pointer moves arrive from input, timer and Bluetooth threads.
 */
class CursorAutoHide(private val pointerGraceMs: Long = POINTER_GRACE_MS) {
    private var hidden = true
    private var lastPointerMoveMs: Long? = null

    @Synchronized
    fun isHidden(): Boolean = hidden

    /** A gamepad button or axis event reached the game. Returns whether the cursor became hidden. */
    @Synchronized
    fun onGamepadInput(nowMs: Long): Boolean {
        if (hidden) return false
        val last = lastPointerMoveMs
        if (last != null && nowMs - last < pointerGraceMs) return false
        hidden = true
        return true
    }

    /** The X server injected pointer motion. Returns whether the cursor became shown. */
    @Synchronized
    fun onPointerMove(nowMs: Long): Boolean {
        lastPointerMoveMs = nowMs
        if (!hidden) return false
        hidden = false
        return true
    }

    companion object {
        const val POINTER_GRACE_MS = 500L
    }
}
