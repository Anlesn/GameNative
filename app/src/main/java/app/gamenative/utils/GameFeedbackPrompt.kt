package app.gamenative.utils

/** `Build.MODEL` of the OnePlus Pad 4, where the prompt is off by default (gamepad-first device). */
const val PAD4_MODEL = "OPD2514"

/**
 * Whether to ask "How did the game run?" after a game exits.
 *
 * [setting] is the user's explicit choice, or null when never set. Unset means on, except on the
 * OnePlus Pad 4 ([model] == [PAD4_MODEL]), where it means off.
 */
fun shouldAskGameFeedbackAfterExit(setting: Boolean?, model: String?): Boolean =
    setting ?: (model != PAD4_MODEL)
