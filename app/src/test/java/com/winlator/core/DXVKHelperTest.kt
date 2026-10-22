package com.winlator.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DXVKHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun envFor(config: String): EnvVars = EnvVars().also { DXVKHelper.setEnvVars(context, KeyValueSet(config), it) }

    @Test
    fun setEnvVars_noOptions_leavesDxvkConfigUnset() {
        val env = envFor("version=1.11.1-sarek,framerate=0,maxDeviceMemory=0")

        assertFalse(env.has("DXVK_CONFIG"))
    }

    @Test
    fun setEnvVars_singleOption_isPlainKeyValue() {
        val env = envFor("version=2.4.1,maxDeviceMemory=0,maxFeatureLevel=11_0")

        assertEquals("d3d11.maxFeatureLevel = 11_0", env.get("DXVK_CONFIG"))
    }

    @Test
    fun setEnvVars_multipleOptions_areSemicolonSeparatedWithoutQuotesOrNewlines() {
        val env = envFor("version=2.4.1,maxDeviceMemory=2048,maxFeatureLevel=11_0,constantBufferRangeCheck=1")

        assertEquals(
            "dxgi.maxDeviceMemory = 2048; dxgi.maxSharedMemory = 2048; d3d11.maxFeatureLevel = 11_0; " +
                "d3d11.constantBufferRangeCheck = \"True\"",
            env.get("DXVK_CONFIG"),
        )
    }

    @Test
    fun mergeDxvkConfig_keepsBuiltOptionsAndAddsUserOptions() {
        val built = DXVKHelper.buildDxvkConfig(KeyValueSet("maxDeviceMemory=2048"))
        val user = "dxvk.enableGraphicsPipelineLibrary=False"

        assertEquals(
            "dxgi.maxDeviceMemory = 2048; dxgi.maxSharedMemory = 2048; dxvk.enableGraphicsPipelineLibrary=False",
            DXVKHelper.mergeDxvkConfig(built, user),
        )
    }

    @Test
    fun mergeDxvkConfig_userKeyWinsOnConflict() {
        val built = DXVKHelper.buildDxvkConfig(KeyValueSet("maxDeviceMemory=2048"))
        val user = "dxgi.maxDeviceMemory=1024; dxvk.hud=fps;"

        assertEquals(
            "dxgi.maxDeviceMemory=1024; dxgi.maxSharedMemory = 2048; dxvk.hud=fps",
            DXVKHelper.mergeDxvkConfig(built, user),
        )
    }

    @Test
    fun mergeDxvkConfig_acceptsLegacyQuotedNewlineFormat() {
        val built = "\"dxgi.maxDeviceMemory = 2048\nd3d11.maxFeatureLevel = 11_0\n\""

        assertEquals(
            "dxgi.maxDeviceMemory = 2048; d3d11.maxFeatureLevel = 11_0; dxvk.hud=fps",
            DXVKHelper.mergeDxvkConfig(built, "dxvk.hud=fps"),
        )
    }

    @Test
    fun mergeDxvkConfig_nothingBuilt_returnsUserValueUnchanged() {
        val user = "d3d9.presentInterval = 1;"

        assertEquals(user, DXVKHelper.mergeDxvkConfig("", user))
        assertEquals(user, DXVKHelper.mergeDxvkConfig(null, user))
    }
}
