package app.gamenative.utils

/**
 * Executables that games ship next to their main binary but that are not the game itself
 * (crash reporters, store overlays). They can outlive the game process after it quits.
 */
object GameHelperProcesses {
    /** Generic crash reporter prefixes; also used by [ContainerUtils] to rank/skip executables. */
    val CRASH_REPORTER_PREFIXES = listOf(
        "crashhandler",
        "crashreporter",
    )

    private val HELPER_PREFIXES = CRASH_REPORTER_PREFIXES + listOf(
        "unitycrashhandler",
        "crashpad",
        "eosoverlay",
        "epiconlineservices",
    )

    /** [normalizedName] is a lowercase basename without the `.exe` suffix. */
    fun isGameHelper(normalizedName: String): Boolean =
        HELPER_PREFIXES.any { normalizedName.startsWith(it) }
}
