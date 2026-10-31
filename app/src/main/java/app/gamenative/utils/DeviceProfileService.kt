package app.gamenative.utils

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.GPUInformation
import com.winlator.core.envvars.EnvVars
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Local container profile for a device model, shipped as `assets/device_profiles/<Build.MODEL>.json`.
 *
 * Priority: explicit user choice > local game entry > local device defaults > server best-config.
 * Profile fields use the server best-config names so [BestConfigService.parseConfigResult] validates and
 * applies them. `graphicsDriverConfig`, `dxwrapperConfig` and `envVars` list only the keys to change and
 * are merged onto the base values. `screenSize`, `rendererPresentMode` and the fps limiter fields are not
 * part of the server path and are applied by [applyLocalFields].
 *
 * `games` keys: a Steam appId (`"870780"`) or `"EPIC:<appName or catalogId>"` (see [gameKeys]). `rules`
 * holds device-level fixes evaluated on the final container data, see [applyRules].
 */
object DeviceProfileService {
    private const val TAG = "DeviceProfile"
    private const val ASSET_DIR = "device_profiles"

    const val SOURCE_DEVICE = "device"
    const val SOURCE_GAME = "game"

    /** Prefix of `games` keys for Epic titles; GameNative's `EPIC_<n>` container id is a local row id. */
    const val EPIC_KEY_PREFIX = "EPIC:"

    /** Rule: swap FEXCore versions hit by [FexExePathRule] for the version given in the rule. */
    const val RULE_FEX_EXE_PATH = "fexExePath"
    private const val KEY_FEXCORE_VERSION = "fexcoreVersion"

    private const val KEY_GRAPHICS_DRIVER_CONFIG = "graphicsDriverConfig"
    private const val KEY_DXWRAPPER_CONFIG = "dxwrapperConfig"
    private const val KEY_ENV_VARS = "envVars"
    private const val KEY_SCREEN_SIZE = "screenSize"
    private const val KEY_RENDERER_PRESENT_MODE = "rendererPresentMode"

    // Container extras read by XServerScreen (FPS_LIMITER_ENABLED_EXTRA / FPS_LIMITER_TARGET_EXTRA).
    private const val KEY_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"
    private const val KEY_FPS_LIMITER_TARGET = "fpsLimiterTarget"

    data class DeviceProfile(
        val model: String,
        val gpu: String,
        val defaults: JsonObject,
        val games: Map<String, JsonObject>,
        val rules: Map<String, JsonObject> = emptyMap(),
    )

    /** Values the profile's partial config strings are merged onto. */
    data class BaseValues(
        val graphicsDriverConfig: String,
        val dxwrapperConfig: String,
        val envVars: String,
    )

    @Volatile
    private var loaded = false

    @Volatile
    private var cached: DeviceProfile? = null

    /** Returns the profile for this device, or null when there is none or it does not match the GPU. */
    fun load(context: Context, gpuMatches: (String) -> Boolean = { deviceGpuMatches(context, it) }): DeviceProfile? {
        if (loaded) return cached
        val model = Build.MODEL.orEmpty()
        val json = try {
            context.assets.open("$ASSET_DIR/$model.json").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        }
        cached = json?.let { select(parse(it), model, gpuMatches) }
        loaded = true
        return cached
    }

    @VisibleForTesting
    internal fun clearCache() {
        cached = null
        loaded = false
    }

    internal fun parse(json: String): DeviceProfile? =
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root["model"]?.jsonPrimitive?.contentOrNull
            val defaults = root["defaults"]?.jsonObject
            if (model.isNullOrBlank() || defaults == null) {
                Timber.tag(TAG).w("Device profile ignored: model or defaults missing")
                null
            } else {
                DeviceProfile(
                    model = model,
                    gpu = root["gpu"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    defaults = defaults,
                    games = root["games"]?.jsonObject?.mapValues { it.value.jsonObject }.orEmpty(),
                    rules = root["rules"]?.jsonObject?.mapValues { it.value.jsonObject }.orEmpty(),
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Device profile ignored: malformed JSON")
            null
        }

    internal fun select(profile: DeviceProfile?, model: String, gpuMatches: (String) -> Boolean): DeviceProfile? =
        profile?.takeIf { it.model == model && gpuMatches(it.gpu) }

    private fun deviceGpuMatches(context: Context, gpu: String): Boolean = when (gpu) {
        "" -> true
        "adreno8EliteGen5" -> GPUInformation.isAdreno8EliteGen5(context)
        else -> false
    }

    /** Device defaults merged onto [base]. */
    internal fun deviceConfig(profile: DeviceProfile, base: BaseValues): JsonObject =
        mergeLayers(listOf(profile.defaults), base)

    /** Device defaults with the game entry on top, or null when the profile has no entry for [steamAppId]. */
    internal fun gameConfig(profile: DeviceProfile, steamAppId: Int, base: BaseValues): JsonObject? =
        gameConfig(profile, listOf(steamAppId.toString()), base)

    /** Device defaults with the first game entry found under [keys] on top, or null when there is none. */
    internal fun gameConfig(profile: DeviceProfile, keys: List<String>, base: BaseValues): JsonObject? {
        val game = keys.firstNotNullOfOrNull { profile.games[it] } ?: return null
        return mergeLayers(listOf(profile.defaults, game), base)
    }

    /** `games` keys for an Epic title: app name first, then catalog id. */
    internal fun epicKeys(appName: String, catalogId: String): List<String> =
        listOf(appName, catalogId).filter { it.isNotBlank() }.distinct().map { EPIC_KEY_PREFIX + it }

    /**
     * `games` keys for a container id (`STEAM_870780`, `EPIC_82`); empty for stores the profile does not key.
     * Epic container ids hold a local database row id, so the stable app name and catalog id are looked up.
     */
    fun gameKeys(containerId: String): List<String> = try {
        val gameId = ContainerUtils.extractGameIdFromContainerId(containerId)
        when (ContainerUtils.extractGameSourceFromContainerId(containerId)) {
            GameSource.STEAM -> listOf(gameId.toString())
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.let { epicKeys(it.appName, it.catalogId) }.orEmpty()
            else -> emptyList()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "No profile game keys for $containerId")
        emptyList()
    }

    internal fun mergeLayers(layers: List<JsonObject>, base: BaseValues): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        var graphicsDriverConfig = base.graphicsDriverConfig
        var dxwrapperConfig = base.dxwrapperConfig
        val envVars = EnvVars(base.envVars)
        for (layer in layers) {
            for ((key, value) in layer) {
                val text = (value as? JsonPrimitive)?.contentOrNull
                when (key) {
                    KEY_GRAPHICS_DRIVER_CONFIG -> graphicsDriverConfig = mergeConfigString(graphicsDriverConfig, text.orEmpty())
                    KEY_DXWRAPPER_CONFIG -> dxwrapperConfig = mergeConfigString(dxwrapperConfig, text.orEmpty())
                    KEY_ENV_VARS -> envVars.putAll(text.orEmpty())
                    else -> result[key] = value
                }
            }
        }
        result[KEY_GRAPHICS_DRIVER_CONFIG] = JsonPrimitive(graphicsDriverConfig)
        result[KEY_DXWRAPPER_CONFIG] = JsonPrimitive(dxwrapperConfig)
        result[KEY_ENV_VARS] = JsonPrimitive(envVars.toString())
        return JsonObject(result)
    }

    /**
     * Overlays comma-separated `key=value` [overrides] onto [base], keeping the base separator
     * (server configs sometimes use `;`).
     */
    internal fun mergeConfigString(base: String, overrides: String): String {
        val sep = if (base.contains(";") && !base.contains(",")) ";" else ","
        val entries = LinkedHashMap<String, String>()
        fun addAll(data: String, separator: String) {
            data.split(separator).map { it.trim() }.filter { it.isNotEmpty() }.forEach { entries[it.substringBefore("=")] = it }
        }
        addAll(base, sep)
        addAll(overrides, ",")
        return entries.values.joinToString(sep)
    }

    /** Local game entry under [keys] as a best-config response, or null when the profile has none. */
    fun localBestConfig(
        context: Context,
        keys: List<String>,
        gpuName: String,
        gameStore: String,
    ): BestConfigService.BestConfigResponse? {
        if (keys.isEmpty()) return null
        val profile = load(context) ?: return null
        val base = BaseValues(PrefManager.graphicsDriverConfig, PrefManager.dxWrapperConfig, PrefManager.envVars)
        val config = gameConfig(profile, keys, base) ?: return null
        return toBestConfigResponse(config, gpuName, gameStore)
    }

    // matchedStore = the game's store, so executablePath in the entry survives filterConfigByMatchType.
    internal fun toBestConfigResponse(config: JsonObject, gpuName: String, gameStore: String = GameSource.STEAM.name) =
        BestConfigService.BestConfigResponse(
            bestConfig = config,
            matchType = "exact_gpu_match",
            matchedGpu = gpuName,
            matchedDeviceId = 0,
            matchedStore = gameStore,
            source = BestConfigService.SOURCE_LOCAL,
        )

    /**
     * Version to use instead of [ContainerData.fexcoreVersion] under [RULE_FEX_EXE_PATH], or null when the rule
     * is off or does not fire. [launchPath] is resolved only when the version is affected. arm64ec Wine runs 64-bit
     * guests through FEX regardless of the WoW64 emulator setting, so only the Wine build is checked.
     */
    internal fun fexExePathOverride(profile: DeviceProfile, data: ContainerData, launchPath: () -> String?): String? {
        val fallback = profile.rules[RULE_FEX_EXE_PATH]?.string(KEY_FEXCORE_VERSION) ?: return null
        if (!data.wineVersion.contains("arm64ec", ignoreCase = true)) return null
        if (!FexExePathRule.isAffectedVersion(data.fexcoreVersion)) return null
        val path = launchPath() ?: return null
        return fallback.takeIf { FexExePathRule.isAffectedPath(path) }
    }

    /**
     * Applies the profile `rules` to [containerData]. Called only where GameNative itself picks the config
     * (new container, "Use known config"), never at launch, so a version the user set later stays.
     */
    fun applyRules(
        context: Context,
        containerData: ContainerData,
        appId: String,
        executablePath: String = containerData.executablePath,
    ): ContainerData {
        val profile = load(context) ?: return containerData
        var launchPath: String? = null
        val fexcore = fexExePathOverride(profile, containerData) {
            launchedExePath(appId, executablePath).also { launchPath = it }
        } ?: return containerData
        Timber.tag(TAG).i(
            "Applied rule=$RULE_FEX_EXE_PATH appId=$appId exe=$launchPath" +
                " fexcore=${containerData.fexcoreVersion}->$fexcore",
        )
        return containerData.copy(fexcoreVersion = fexcore)
    }

    /**
     * Windows path the game exe is launched with, mirroring the Epic and custom-game branches of
     * `XServerScreen.getWineStartCommand`; null for other stores (their exe is not at `A:\`) or when unknown.
     */
    private fun launchedExePath(appId: String, executablePath: String): String? = try {
        when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
            GameSource.EPIC -> {
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                val game = EpicService.getEpicGameOf(gameId)?.takeIf { it.isInstalled && it.installPath.isNotEmpty() }
                val exe = executablePath.ifEmpty {
                    runBlocking(Dispatchers.IO) { EpicService.getInstance()?.epicManager?.getInstalledExe(gameId) }.orEmpty()
                }
                if (game == null || exe.isEmpty()) null else FexExePathRule.gameDrivePath(exe, game.installPath)
            }
            GameSource.CUSTOM_GAME -> executablePath.takeIf { it.isNotEmpty() }?.let { FexExePathRule.gameDrivePath(it) }
            else -> null
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Launch path unknown for $appId")
        null
    }

    /**
     * Applies device defaults over [containerData] (which may already hold a server config). Skipped when
     * the user saved default container settings: that is an explicit choice.
     */
    fun applyDeviceDefaults(context: Context, container: Container, containerData: ContainerData, appId: String): ContainerData {
        val profile = load(context) ?: return containerData
        if (PrefManager.hasSavedContainerDefaults) {
            Timber.tag(TAG).i("Local profile skipped source=$SOURCE_DEVICE appId=$appId: user saved default settings")
            return containerData
        }
        val base = BaseValues(containerData.graphicsDriverConfig, containerData.dxwrapperConfig, containerData.envVars)
        val config = deviceConfig(profile, base)
        val parsed = runBlocking(Dispatchers.IO) {
            BestConfigService.parseConfigToContainerData(
                context = context,
                configJson = config,
                matchType = "exact_gpu_match",
                applyKnownConfig = true,
                forceApply = true,
                preserveConfigValues = true,
            )
        }
        if (parsed.isNullOrEmpty()) {
            Timber.tag(TAG).w("Device profile defaults rejected for appId=$appId")
            return containerData
        }
        val updated = ContainerUtils.applyBestConfigMapToContainerData(containerData, parsed)
        return applyLocalFields(container, updated, config, SOURCE_DEVICE, appId)
    }

    /**
     * Applies the profile fields the server path does not carry: screen size and renderer present mode go
     * into the returned data, the fps limiter into [container] extras. Logs the applied source.
     */
    fun applyLocalFields(
        container: Container,
        containerData: ContainerData,
        config: JsonObject,
        source: String,
        appId: String,
    ): ContainerData {
        var updated = containerData
        config.string(KEY_SCREEN_SIZE)?.let { updated = updated.copy(screenSize = it) }
        config.string(KEY_RENDERER_PRESENT_MODE)?.let { updated = updated.copy(rendererPresentMode = it) }
        (config[KEY_FPS_LIMITER_ENABLED] as? JsonPrimitive)?.booleanOrNull?.let {
            container.putExtra(KEY_FPS_LIMITER_ENABLED, it)
        }
        (config[KEY_FPS_LIMITER_TARGET] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let {
            container.putExtra(KEY_FPS_LIMITER_TARGET, it)
        }
        Timber.tag(TAG).i("Applied local profile source=$source appId=$appId model=${Build.MODEL}")
        return updated
    }

    /** [applyLocalFields] for a best-config response; returns [containerData] unchanged for server responses. */
    fun applyLocalFields(
        container: Container,
        containerData: ContainerData,
        response: BestConfigService.BestConfigResponse,
        appId: String,
    ): ContainerData = if (response.source == BestConfigService.SOURCE_LOCAL) {
        applyLocalFields(container, containerData, response.bestConfig, SOURCE_GAME, appId)
    } else {
        containerData
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
