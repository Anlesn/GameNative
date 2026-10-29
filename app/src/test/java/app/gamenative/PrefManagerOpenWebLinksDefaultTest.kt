package app.gamenative

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WineRequestComponent reads [PrefManager.openWebLinksExternally] for game URLs, so the launcher must see the
 * same default (true) and the same stored key as the Settings switch.
 */
class PrefManagerOpenWebLinksDefaultTest {

    @Test
    fun openWebLinksExternallyDefaultsToTrue() {
        installFakePrefManager(FakeDataStore()).use {
            assertTrue(PrefManager.openWebLinksExternally)
        }
    }

    @Test
    fun openWebLinksExternallyReadsTheSettingsKey() {
        val stored = preferencesOf(booleanPreferencesKey("open_web_links_externally") to false)
        installFakePrefManager(FakeDataStore(stored)).use {
            assertFalse(PrefManager.openWebLinksExternally)
        }
    }
}
