package app.gamenative.utils

/**
 * FEX 2601–2608 crash before the first guest instruction for some executable paths.
 *
 * `ProcessInit` (ARM64EC and WOW64 `Module.cpp`) keeps `BaseName(GetExecutableFilePath())`, a `string_view`
 * into a temporary string. Paths of 23+ bytes do not fit the inline buffer, so the temporary lives on the heap
 * and is freed; the allocator then overwrites its first 8 bytes. A file name that starts there (an exe at the
 * drive root) reads back empty, and FEX asserts while loading the app config. `A:\WolfNewOrder_x64.exe` (23)
 * crashes, `A:\Disco Elysium.exe` (20) runs. FEX-2609 and later keep the path in a named local.
 */
internal object FexExePathRule {
    const val MIN_HEAP_PATH_BYTES = 23
    const val CLOBBERED_PREFIX_BYTES = 8
    private val AFFECTED_SERIES = 2601..2608
    private val SERIES = Regex("^(\\d{4})(?!\\d)")

    /** True for FEXCore ids of an affected series: bundled `2603`, manifest `2601-217d039-0`, `2608-0`. */
    fun isAffectedVersion(version: String): Boolean =
        SERIES.find(version.trim())?.groupValues?.get(1)?.toInt()?.let { it in AFFECTED_SERIES } ?: false

    /** True when the Windows path the process is started with hits the use-after-free. */
    fun isAffectedPath(windowsPath: String): Boolean {
        val nameStart = windowsPath.substring(0, windowsPath.lastIndexOf('\\') + 1)
        return windowsPath.toByteArray(Charsets.UTF_8).size >= MIN_HEAP_PATH_BYTES &&
            nameStart.toByteArray(Charsets.UTF_8).size < CLOBBERED_PREFIX_BYTES
    }

    /**
     * Windows path of an exe on the game drive as the launcher builds it for Epic and custom games: absolute
     * Windows paths stay as they are, anything else becomes `A:\<path relative to [installPath]>`.
     */
    fun gameDrivePath(exePath: String, installPath: String = ""): String =
        if (ContainerUtils.isAbsoluteWindowsPath(exePath)) {
            exePath
        } else {
            val relative = if (installPath.isEmpty()) exePath else exePath.removePrefix(installPath).removePrefix("/")
            "A:\\$relative".replace("/", "\\")
        }
}
