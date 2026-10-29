package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
class DeviceProfileServiceTest {

    private lateinit var context: Context

    private val base = DeviceProfileService.BaseValues(
        graphicsDriverConfig = "vulkanVersion=1.3,version=System,bcnEmulation=auto",
        dxwrapperConfig = "version=2.4.1-gplasync,framerate=0,maxDeviceMemory=0",
        envVars = "WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox",
    )

    private val shippedProfile: DeviceProfileService.DeviceProfile by lazy {
        val file = listOf(
            File("src/main/assets/device_profiles/OPD2514.json"),
            File("app/src/main/assets/device_profiles/OPD2514.json"),
        ).first { it.exists() }
        requireNotNull(DeviceProfileService.parse(file.readText()))
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        val manifestFile = listOf(File("manifest.json"), File("../manifest.json")).firstOrNull { it.exists() }
        if (manifestFile != null) {
            PrefManager.componentManifestJson = manifestFile.readText()
            PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
        }
        DeviceProfileService.clearCache()
    }

    @After
    fun tearDown() {
        DeviceProfileService.clearCache()
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun shippedProfile_parsesWithExpectedGames() {
        assertEquals("OPD2514", shippedProfile.model)
        assertEquals("adreno8EliteGen5", shippedProfile.gpu)
        assertTrue(shippedProfile.games.containsKey("1313140"))
        assertTrue(shippedProfile.games.containsKey("870780"))
    }

    @Test
    fun select_requiresModelAndGpuMatch() {
        assertSame(shippedProfile, DeviceProfileService.select(shippedProfile, "OPD2514") { true })
        assertNull(DeviceProfileService.select(shippedProfile, "SM-S928B") { true })
        assertNull(DeviceProfileService.select(shippedProfile, "OPD2514") { false })
        assertNull(DeviceProfileService.select(null, "OPD2514") { true })
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        assertNull(DeviceProfileService.parse("{not json"))
        assertNull(DeviceProfileService.parse("[]"))
        assertNull(DeviceProfileService.parse("""{"model":"OPD2514"}"""))
        assertNull(DeviceProfileService.parse("""{"defaults":{}}"""))
        assertNull(DeviceProfileService.parse("""{"model":"OPD2514","defaults":{},"games":{"1":"x"}}"""))
    }

    @Test
    fun load_otherModel_returnsNoProfile() {
        ShadowBuild.setModel("SM-S928B")
        assertNull(DeviceProfileService.load(context))
    }

    @Test
    fun load_pad4WithMatchingGpu_returnsProfile() {
        ShadowBuild.setModel("OPD2514")
        // GPUInformation loads a native library, so the GPU check is injected here.
        assertEquals("OPD2514", DeviceProfileService.load(context) { it == "adreno8EliteGen5" }?.model)
    }

    @Test
    fun load_pad4WithOtherGpu_returnsNoProfile() {
        ShadowBuild.setModel("OPD2514")
        assertNull(DeviceProfileService.load(context) { false })
    }

    @Test
    fun deviceConfig_overridesBaseAndKeepsOtherKeys() {
        val config = DeviceProfileService.deviceConfig(shippedProfile, base)

        assertEquals("proton-11.0-1-arm64ec-1", config.str("wineVersion"))
        assertEquals("INTERMEDIATE", config.str("fexcorePreset"))
        assertEquals("1920x1080", config.str("screenSize"))
        val driver = KeyValueSet(config.str("graphicsDriverConfig"))
        assertEquals("qcom-849", driver.get("version"))
        assertEquals("1", driver.get("adrenotoolsTurnip"))
        assertEquals("auto", driver.get("bcnEmulation"))
        val dxvk = KeyValueSet(config.str("dxwrapperConfig"))
        assertEquals("1.11.1-sarek", dxvk.get("version"))
        assertEquals("0", dxvk.get("maxDeviceMemory"))
        val env = EnvVars(config.str("envVars"))
        assertEquals("1", env.get("WRAPPER_USE_BCN_CACHE"))
        assertEquals("1", env.get("WINEESYNC"))
    }

    @Test
    fun gameConfig_gameEntryOverridesDeviceDefaults() {
        val config = requireNotNull(DeviceProfileService.gameConfig(shippedProfile, 870780, base))

        assertEquals("proton-11.0-1-arm64ec-1", config.str("wineVersion"))
        assertEquals("COMPATIBILITY", config.str("fexcorePreset"))
        assertEquals("Control_DX11.exe", config.str("executablePath"))
        assertEquals("mailbox", config.str("rendererPresentMode"))
        assertEquals("1920x1080", config.str("screenSize"))
        val driver = KeyValueSet(config.str("graphicsDriverConfig"))
        assertEquals(ContainerUtils.WRAPPER_ADRENO_8ELITE_GEN5, driver.get("version"))
        assertEquals("1", driver.get("adrenotoolsTurnip"))
        assertEquals("2.4.1", KeyValueSet(config.str("dxwrapperConfig")).get("version"))
        val env = EnvVars(config.str("envVars"))
        assertEquals("1", env.get("WRAPPER_USE_BCN_CACHE"))
        assertEquals("dxvk.enableGraphicsPipelineLibrary=False", env.get("DXVK_CONFIG"))
    }

    @Test
    fun gameConfig_emptyEntryEqualsDeviceDefaults_unknownAppIsNull() {
        assertEquals(
            DeviceProfileService.deviceConfig(shippedProfile, base),
            DeviceProfileService.gameConfig(shippedProfile, 1313140, base),
        )
        assertNull(DeviceProfileService.gameConfig(shippedProfile, 42, base))
    }

    @Test
    fun deviceDefaults_winOverServerConfig() {
        // Device defaults are merged onto whatever the server config left in the container.
        val serverBase = DeviceProfileService.BaseValues(
            graphicsDriverConfig = "version=turnip25.3.0_R3_Auto;blacklistedExtensions=;adrenotoolsTurnip=0",
            dxwrapperConfig = "version=2.6.1-gplasync,async=1",
            envVars = "DXVK_ASYNC=1",
        )
        val config = DeviceProfileService.deviceConfig(shippedProfile, serverBase)

        assertEquals(
            "version=qcom-849;blacklistedExtensions=;adrenotoolsTurnip=1",
            config.str("graphicsDriverConfig"),
        )
        assertEquals("version=1.11.1-sarek,async=1", config.str("dxwrapperConfig"))
        assertEquals("1", EnvVars(config.str("envVars")).get("DXVK_ASYNC"))
    }

    @Test
    fun localResponse_goesThroughExistingParsePath() = runBlocking {
        val config = requireNotNull(DeviceProfileService.gameConfig(shippedProfile, 870780, base))
        val response = DeviceProfileService.toBestConfigResponse(config, "Adreno (TM) 840")
        assertEquals(BestConfigService.SOURCE_LOCAL, response.source)

        // forceApply: the manifest cache is written asynchronously, so component availability is checked
        // separately in shippedProfile_componentIdsExistInManifest.
        val result = BestConfigService.parseConfigResult(
            context = context,
            configJson = response.bestConfig,
            matchType = response.matchType,
            applyKnownConfig = true,
            storeMatch = true,
            forceApply = true,
            // Empty matchedGpu skips the GPU-family overrides (GPUInformation needs a native library).
            matchedGpu = "",
        )
        assertEquals("COMPATIBILITY", result.config["fexcorePreset"])
        assertEquals("Control_DX11.exe", result.config["executablePath"])
        assertEquals("dxvk", result.config["dxwrapper"])
        assertFalse(result.config.containsKey("screenSize"))
    }

    @Test
    fun shippedProfile_componentIdsExistInManifest() {
        val manifestFile = listOf(File("manifest.json"), File("../manifest.json")).first { it.exists() }
        val manifest = requireNotNull(ManifestRepository.parseManifest(manifestFile.readText()))
        fun ids(type: String) = manifest.items[type].orEmpty().filter { it.variant == "bionic" }.map { it.id }
        val bundledDxvk = context.resources.getStringArray(app.gamenative.R.array.dxvk_version_entries).toList()

        for (entry in listOf(shippedProfile.defaults) + shippedProfile.games.values) {
            entry["wineVersion"]?.jsonPrimitive?.content?.let { assertTrue(it, it in ids("proton")) }
            entry["graphicsDriverVersion"]?.jsonPrimitive?.content?.let { assertTrue(it, it in ids("driver")) }
            entry["dxwrapperConfig"]?.jsonPrimitive?.content?.let {
                val version = KeyValueSet(it).get("version")
                assertTrue(version, version in bundledDxvk || version in ids("dxvk"))
            }
        }
    }

    @Test
    fun fetchBestConfig_localGameEntryWinsOverServer() = runBlocking {
        ShadowBuild.setModel("OPD2514")
        // Prime the profile cache with the GPU check stubbed (GPUInformation needs a native library).
        assertNotNull(DeviceProfileService.load(context) { true })

        val response = BestConfigService.fetchBestConfig(
            context = context,
            gameName = "Control",
            gpuName = "Adreno (TM) 840",
            gameStore = "STEAM",
            steamAppId = 870780,
        )

        assertNotNull(response)
        assertEquals(BestConfigService.SOURCE_LOCAL, response!!.source)
        assertEquals("Control_DX11.exe", response.bestConfig.str("executablePath"))
    }

    @Test
    fun applyLocalFields_setsScreenPresentModeAndFpsLimiter() {
        val container = Container("STEAM_870780")
        val config = JsonObject(
            mapOf(
                "screenSize" to JsonPrimitive("1920x1080"),
                "rendererPresentMode" to JsonPrimitive("mailbox"),
                "fpsLimiterEnabled" to JsonPrimitive(true),
                "fpsLimiterTarget" to JsonPrimitive(30),
            ),
        )

        val updated = DeviceProfileService.applyLocalFields(
            container,
            ContainerData(screenSize = "1280x960", rendererPresentMode = "fifo"),
            config,
            DeviceProfileService.SOURCE_GAME,
            "STEAM_870780",
        )

        assertEquals("1920x1080", updated.screenSize)
        assertEquals("mailbox", updated.rendererPresentMode)
        assertEquals("true", container.getExtra("fpsLimiterEnabled"))
        assertEquals("30", container.getExtra("fpsLimiterTarget"))
    }

    @Test
    fun mergeConfigString_overridesInPlaceAndAppendsNewKeys() {
        assertEquals("a=1,b=9,c=3", DeviceProfileService.mergeConfigString("a=1,b=2", "b=9,c=3"))
        assertEquals("x=1", DeviceProfileService.mergeConfigString("", "x=1"))
        assertEquals("a=1", DeviceProfileService.mergeConfigString("a=1", ""))
    }
}
