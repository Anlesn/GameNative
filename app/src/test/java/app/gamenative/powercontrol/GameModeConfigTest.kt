package app.gamenative.powercontrol

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class GameModeConfigTest {
    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `manifest points android game_mode_config at the config file`() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(R.xml.game_mode_config, info.metaData.getInt("android.game_mode_config"))
    }

    @Test
    fun `config opts into performance and battery modes and refuses OS interventions`() {
        val parser = context.resources.getXml(R.xml.game_mode_config)
        while (parser.eventType != XmlPullParser.START_TAG) parser.next()
        assertEquals("game-mode-config", parser.name)

        // Defaults are the opposite of the expected values, so a missing attribute fails.
        assertTrue(parser.getAttributeBooleanValue(ANDROID_NS, "supportsPerformanceGameMode", false))
        assertTrue(parser.getAttributeBooleanValue(ANDROID_NS, "supportsBatteryGameMode", false))
        assertFalse(parser.getAttributeBooleanValue(ANDROID_NS, "allowGameDownscaling", true))
        assertFalse(parser.getAttributeBooleanValue(ANDROID_NS, "allowGameFpsOverride", true))
    }
}
