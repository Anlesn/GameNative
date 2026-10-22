package app.gamenative.service.epic

import android.net.Uri

/**
 * Epic device-code activation page opened by a game through winebrowser.
 *
 * When EOS rejects the exchange code passed on the command line, the SDK falls back to device-code login and
 * opens `https://www.epicgames.com/activate?userCode=XXXXXXXX`. The game then waits (drawing black frames) until
 * the user approves that code, so the URL must reach the user.
 *
 * @property pageUrl the activation page, always over https.
 * @property userCode the code to approve, or null when the URL carries none.
 */
data class EpicDeviceActivation(val pageUrl: String, val userCode: String?) {
    companion object {
        private val HOSTS = setOf("epicgames.com", "www.epicgames.com")
        private const val ACTIVATE_PATH = "/activate"
        private const val USER_CODE_PARAM = "userCode"

        /** Returns the activation request when [url] is an Epic device-code activation page, else null. */
        @JvmStatic
        fun parse(url: String?): EpicDeviceActivation? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return null
            if (uri.host?.lowercase() !in HOSTS) return null
            val path = uri.path?.trimEnd('/')?.lowercase() ?: return null
            if (path != ACTIVATE_PATH && !path.endsWith(ACTIVATE_PATH)) return null

            val userCode = runCatching { uri.getQueryParameter(USER_CODE_PARAM) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return EpicDeviceActivation(uri.buildUpon().scheme("https").build().toString(), userCode)
        }
    }
}
