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

    private val wolfensteinAppName = "a64dcf9b711a4a60a3c0b6f052dfc7da"
    private val turnipT26 = "Turnip Adreno Driver T26 (@Mr_Purple_666)"

    @Test
    fun shippedProfile_parsesWithExpectedGames() {
        assertEquals("OPD2514", shippedProfile.model)
        assertEquals("adreno8EliteGen5", shippedProfile.gpu)
        assertTrue(shippedProfile.games.containsKey("1313140"))
        assertTrue(shippedProfile.games.containsKey("870780"))
        assertTrue(shippedProfile.games.containsKey("EPIC:$wolfensteinAppName"))
        assertEquals("2512", shippedProfile.rules.getValue("fexExePath").str("fexcoreVersion"))
    }

    @Test
    fun epicKeys_appNameFirstThenCatalogId() {
        assertEquals(
            listOf("EPIC:$wolfensteinAppName", "EPIC:cat"),
            DeviceProfileService.epicKeys(wolfensteinAppName, "cat"),
        )
        assertEquals(listOf("EPIC:x"), DeviceProfileService.epicKeys("x", "x"))
        assertEquals(listOf("EPIC:cat"), DeviceProfileService.epicKeys("", "cat"))
    }

    @Test
    fun gameConfig_epicWolfensteinUsesTurnipAndFex2512() {
        val keys = DeviceProfileService.epicKeys(wolfensteinAppName, "unknown-catalog")
        val config = requireNotNull(DeviceProfileService.gameConfig(shippedProfile, keys, base))

        assertEquals("2512", config.str("fexcoreVersion"))
        assertEquals(turnipT26, config.str("graphicsDriverVersion"))
        assertEquals("Wrapper", config.str("graphicsDriver"))
        assertEquals("proton-11.0-1-arm64ec-1", config.str("wineVersion"))
        assertEquals("INTERMEDIATE", config.str("fexcorePreset"))
        val driver = KeyValueSet(config.str("graphicsDriverConfig"))
        assertEquals(turnipT26, driver.get("version"))
        assertEquals("1", driver.get("adrenotoolsTurnip"))
        // Steam keys are bare appIds: an Epic name must not match them, nor the reverse.
        assertNull(DeviceProfileService.gameConfig(shippedProfile, listOf(wolfensteinAppName), base))
        assertNull(DeviceProfileService.gameConfig(shippedProfile, listOf("EPIC:870780"), base))
    }

    @Test
    fun gameKeys_steamContainerIsBareAppId_unknownStoreIsEmpty() {
        assertEquals(listOf("870780"), DeviceProfileService.gameKeys("STEAM_870780"))
        assertEquals(listOf("870780"), DeviceProfileService.gameKeys("STEAM_870780(1)"))
        assertEquals(emptyList<String>(), DeviceProfileService.gameKeys("GOG_1207658924"))
    }

    private fun fexData(fexcore: String, wine: String = "proton-11.0-1-arm64ec-1") =
        ContainerData(wineVersion = wine, fexcoreVersion = fexcore)

    @Test
    fun fexExePathOverride_firesOnlyForAffectedVersionAndPath() {
        val root = { "A:\\WolfNewOrder_x64.exe" }
        assertEquals("2512", DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2603"), root))
        assertEquals("2512", DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2608-0"), root))
        assertNull(DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2610-gn-4"), root))
        assertNull(DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2512"), root))
        assertNull(
            DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2603")) { "A:\\Game\\WolfNewOrder_x64.exe" },
        )
        assertNull(DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2603")) { null })
        // x86-64 Wine runs Box64, not FEX.
        assertNull(DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2603", "proton-11.0-1-x86_64"), root))
        // No rule in the profile: nothing changes.
        assertNull(DeviceProfileService.fexExePathOverride(shippedProfile.copy(rules = emptyMap()), fexData("2603"), root))
    }

    @Test
    fun fexExePathOverride_resolvesPathOnlyForAffectedVersion() {
        var resolved = 0
        DeviceProfileService.fexExePathOverride(shippedProfile, fexData("2610-gn-4")) {
            resolved++
            "A:\\X.exe"
        }
        assertEquals(0, resolved)
    }

    @Test
    fun applyRules_customGameAtDriveRootGetsFex2512() {
        ShadowBuild.setModel("OPD2514")
        assertNotNull(DeviceProfileService.load(context) { true })

        val atRoot = ContainerData(
            wineVersion = "proton-11.0-1-arm64ec-1",
            fexcoreVersion = "2605",
            executablePath = "WolfNewOrder_x64.exe",
        )
        assertEquals("2512", DeviceProfileService.applyRules(context, atRoot, "CUSTOM_GAME_42").fexcoreVersion)

        // `A:\Game\` is 8 bytes: the file name starts past the overwritten prefix.
        val inFolder = atRoot.copy(executablePath = "Game/WolfNewOrder_x64.exe")
        assertSame(inFolder, DeviceProfileService.applyRules(context, inFolder, "CUSTOM_GAME_42"))
        // Exe path passed separately (container creation keeps it on the container, not in the data).
        val noExe = atRoot.copy(executablePath = "")
        assertEquals(
            "2512",
            DeviceProfileService.applyRules(context, noExe, "CUSTOM_GAME_42", "WolfNewOrder_x64.exe").fexcoreVersion,
        )
        // Steam exes are launched from steamapps/common, not the drive root.
        assertSame(atRoot, DeviceProfileService.applyRules(context, atRoot, "STEAM_201810"))
    }

    @Test
    fun applyRules_noProfile_returnsSameData() {
        ShadowBuild.setModel("SM-S928B")
        val data = fexData("2603").copy(executablePath = "WolfNewOrder_x64.exe")
        assertSame(data, DeviceProfileService.applyRules(context, data, "CUSTOM_GAME_42"))
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

        val bundledFexcore = context.resources.getStringArray(app.gamenative.R.array.fexcore_version_entries).toList()

        for (entry in listOf(shippedProfile.defaults) + shippedProfile.games.values + shippedProfile.rules.values) {
            entry["fexcoreVersion"]?.jsonPrimitive?.content?.let {
                assertTrue(it, it in bundledFexcore || it in ids("fexcore"))
            }
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
    fun fetchBestConfig_localEpicEntryByProfileKeys() = runBlocking {
        ShadowBuild.setModel("OPD2514")
        assertNotNull(DeviceProfileService.load(context) { true })

        val response = BestConfigService.fetchBestConfig(
            context = context,
            gameName = "Wolfenstein: The New Order",
            gpuName = "Adreno (TM) 840",
            gameStore = "EPIC",
            localProfileKeys = DeviceProfileService.epicKeys(wolfensteinAppName, "catalog"),
        )

        assertNotNull(response)
        assertEquals(BestConfigService.SOURCE_LOCAL, response!!.source)
        assertEquals("EPIC", response.matchedStore)
        assertEquals("2512", response.bestConfig.str("fexcoreVersion"))
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
    fun shippedProfile_pinsX11ForDefaultsAndGameEntries() {
        assertEquals(Container.DISPLAY_SERVER_X11, DeviceProfileService.deviceConfig(shippedProfile, base).str("displayServer"))
        val control = requireNotNull(DeviceProfileService.gameConfig(shippedProfile, 870780, base))
        assertEquals(Container.DISPLAY_SERVER_X11, control.str("displayServer"))
    }

    @Test
    fun applyDeviceDefaults_replacesGamescopeWithX11() {
        ShadowBuild.setModel("OPD2514")
        assertNotNull(DeviceProfileService.load(context) { true })
        val container = Container("STEAM_220")
        val data = ContainerData(containerVariant = Container.BIONIC, displayServer = Container.DISPLAY_SERVER_GAMESCOPE)

        val updated = DeviceProfileService.applyDeviceDefaults(context, container, data, "STEAM_220", userSavedDefaults = false)

        assertEquals(Container.DISPLAY_SERVER_X11, updated.displayServer)
        assertEquals("1920x1080", updated.screenSize)
    }

    @Test
    fun applyDeviceDefaults_userSavedDefaults_keepDisplayServer() {
        ShadowBuild.setModel("OPD2514")
        assertNotNull(DeviceProfileService.load(context) { true })
        val data = ContainerData(containerVariant = Container.BIONIC, displayServer = Container.DISPLAY_SERVER_GAMESCOPE)
        assertSame(
            data,
            DeviceProfileService.applyDeviceDefaults(context, Container("STEAM_220"), data, "STEAM_220", userSavedDefaults = true),
        )
    }

    @Test
    fun applyLocalFields_gameEntryOverridesDisplayServer_unknownValueIgnored() {
        val profile = requireNotNull(
            DeviceProfileService.parse(
                """
                {"model": "OPD2514", "defaults": {"displayServer": "x11"},
                 "games": {"220": {"displayServer": "Gamescope"}, "400": {"displayServer": "wayland"}}}
                """.trimIndent(),
            ),
        )
        val x11Data = ContainerData(displayServer = Container.DISPLAY_SERVER_X11)

        val game = requireNotNull(DeviceProfileService.gameConfig(profile, 220, base))
        val updated = DeviceProfileService.applyLocalFields(
            Container("STEAM_220"),
            x11Data,
            game,
            DeviceProfileService.SOURCE_GAME,
            "STEAM_220",
        )
        assertEquals(Container.DISPLAY_SERVER_GAMESCOPE, updated.displayServer)

        val unknown = requireNotNull(DeviceProfileService.gameConfig(profile, 400, base))
        val kept = DeviceProfileService.applyLocalFields(
            Container("STEAM_400"),
            x11Data,
            unknown,
            DeviceProfileService.SOURCE_GAME,
            "STEAM_400",
        )
        assertEquals(Container.DISPLAY_SERVER_X11, kept.displayServer)
    }

    @Test
    fun mergeConfigString_overridesInPlaceAndAppendsNewKeys() {
        assertEquals("a=1,b=9,c=3", DeviceProfileService.mergeConfigString("a=1,b=2", "b=9,c=3"))
        assertEquals("x=1", DeviceProfileService.mergeConfigString("", "x=1"))
        assertEquals("a=1", DeviceProfileService.mergeConfigString("a=1", ""))
    }
}
