package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DXVKHelperTest {

    @Test
    fun mergeDxvkConfig_keepsBuiltOptionsAndAddsUserOptions() {
        val built = "\"dxgi.maxDeviceMemory = 2048\ndxgi.maxSharedMemory = 2048\nd3d11.maxFeatureLevel = 11_0\n\""
        val user = "dxvk.enableGraphicsPipelineLibrary=False"

        assertEquals(
            "dxgi.maxDeviceMemory = 2048;dxgi.maxSharedMemory = 2048;d3d11.maxFeatureLevel = 11_0;" +
                "dxvk.enableGraphicsPipelineLibrary=False",
            DXVKHelper.mergeDxvkConfig(built, user),
        )
    }

    @Test
    fun mergeDxvkConfig_userKeyWinsOnConflict() {
        val built = "\"dxgi.maxDeviceMemory = 2048\ndxgi.maxSharedMemory = 2048\n\""
        val user = "dxgi.maxDeviceMemory=1024; dxvk.hud=fps;"

        assertEquals(
            "dxgi.maxDeviceMemory=1024;dxgi.maxSharedMemory = 2048;dxvk.hud=fps",
            DXVKHelper.mergeDxvkConfig(built, user),
        )
    }

    @Test
    fun mergeDxvkConfig_nothingBuilt_returnsUserValueUnchanged() {
        val user = "d3d9.presentInterval = 1;"

        assertEquals(user, DXVKHelper.mergeDxvkConfig("\"\"", user))
        assertEquals(user, DXVKHelper.mergeDxvkConfig("", user))
        assertEquals(user, DXVKHelper.mergeDxvkConfig(null, user))
    }
}
