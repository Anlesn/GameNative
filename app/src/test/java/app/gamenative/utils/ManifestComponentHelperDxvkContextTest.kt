package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestComponentHelperDxvkContextTest {
    private val dxWrappers = listOf("DXVK", "VKD3D")
    private val dxvkVersions = listOf("1.11.1-sarek", "async-1.10.3", "2.4.1", "2.6.1-gplasync")

    private fun context(dxWrapperIndex: Int, versions: List<String> = dxvkVersions) =
        ManifestComponentHelper.buildDxvkContext(
            containerVariant = "bionic",
            graphicsDrivers = listOf("Wrapper"),
            graphicsDriverIndex = 0,
            dxWrappers = dxWrappers,
            dxWrapperIndex = dxWrapperIndex,
            inspectionMode = false,
            isBionicVariant = false,
            dxvkVersionsBase = versions,
            dxvkOptions = ManifestComponentHelper.VersionOptionList(emptyList(), emptyList(), emptyList()),
        )

    @Test
    fun `vkd3d hides dxvk below 2_1 and flags it`() {
        val ctx = context(dxWrapperIndex = 1)

        assertEquals(listOf("2.4.1", "2.6.1-gplasync"), ctx.labels)
        assertTrue(ctx.hidesPreVkd3dVersions)
    }

    @Test
    fun `dxvk wrapper keeps all versions without the flag`() {
        val ctx = context(dxWrapperIndex = 0)

        assertEquals(dxvkVersions, ctx.labels)
        assertFalse(ctx.hidesPreVkd3dVersions)
    }

    @Test
    fun `vkd3d with only new dxvk versions does not flag`() {
        val ctx = context(dxWrapperIndex = 1, versions = listOf("2.4.1"))

        assertFalse(ctx.hidesPreVkd3dVersions)
    }
}
