package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Defaults únicos do app — fonte única para [AppConfig] e
 * [AppConfigMapper.fromPreferences] (defaults idênticos ao defaults.toml
 * do pwnagotchi original quando keys ausentes).
 */
object ConfigDefaults {
    val MODE = PwnMode.AUTO
    const val WEB_API_PORT = 8080
    val PERSONALITY = Personality()
}

/** Chaves do DataStore — centralizadas e type-safe (sem strings soltas). */
object ConfigKeys {
    val MODE = stringPreferencesKey("mode")
    val BACKEND_PREFERENCE = stringPreferencesKey("backend_preference")
    val PERSONALITY = stringPreferencesKey("personality")
    val EXPERIMENTAL_AI = booleanPreferencesKey("experimental_ai")
    val AUTO_START = booleanPreferencesKey("auto_start")
    val WEB_API_ENABLED = booleanPreferencesKey("web_api_enabled")
    val WEB_API_PORT = intPreferencesKey("web_api_port")
    val WEB_API_PASSWORD = stringPreferencesKey("web_api_password")
    val ONBOARDED = booleanPreferencesKey("onboarded")
    val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
}

/** Configuração completa do app, observável (Flow reativo). */
data class AppConfig(
    val mode: PwnMode = ConfigDefaults.MODE,
    val backendPreference: BackendId? = null, // null = selector decide
    val personality: Personality = ConfigDefaults.PERSONALITY,
    val experimentalAi: Boolean = false,
    val autoStart: Boolean = false,
    val webApiEnabled: Boolean = false,
    val webApiPort: Int = ConfigDefaults.WEB_API_PORT,
    val webApiPassword: String = "",
    val onboarded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
)

/**
 * ConfigStore sobre DataStore Preferences (issue #16): API reativa via
 * [config] Flow. A personalidade aplicável à próxima época e o modo valem
 * quando o observador (SessionController) ler o Flow; a preferência de
 * backend vale na próxima sessão (issue #59 faz o wiring).
 *
 * Erros de leitura: arquivo corrompido ([CorruptionException]) loga erro e
 * emite defaults (config degradada, NÃO silenciosa); I/O transiente usa o
 * conteúdo em cache quando disponível. O flow não completa após um
 * fallback — nova escrita volta a propagar no mesmo collector.
 */
class ConfigStore(
    internal val store: DataStore<Preferences>,
    private val logger: AppLogger? = null,
) {

    /** Última config lida com sucesso; usada em erro de I/O transiente. */
    @Volatile
    private var lastGood: AppConfig? = null

    /**
     * Fluxo reativo de config. Em erro, o catch emite preferências vazias
     * (defaults) ou a última config boa — e o flow NÃO completa: o
     * DataStore re-tenta a leitura na próxima emissão, então nova escrita
     * volta a propagar no mesmo collector (issue #56).
     */
    val config: Flow<AppConfig> = store.data
        .catch { e ->
            when (e) {
                is CorruptionException ->
                    // Logado, não silencioso: arquivo corrompido emite
                    // defaults (onboarded/disclaimer podem re-armar).
                    logger?.w(
                        TAG,
                        "arquivo de config corrompido; usando defaults",
                        e,
                    )
                is IOException ->
                    logger?.w(TAG, "erro de I/O lendo config; usando último valor bom/defaults", e)
                else ->
                    logger?.w(TAG, "erro lendo config; usando defaults", e)
            }
            // I/O transiente com config boa em cache: mantém a última boa
            // (evita reset de tela); caso contrário, defaults.
            lastGood?.takeIf { e is IOException }?.let { good ->
                emit(AppConfigMapper.toPreferences(good, AppConfigMapper.json.encodeToString(good.personality)))
            } ?: emit(emptyPreferences())
        }
        .map { p ->
            AppConfigMapper.fromPreferences(p).also { lastGood = it }
        }

    suspend fun setMode(mode: PwnMode) = store.edit { it[ConfigKeys.MODE] = mode.name }

    /** null persiste como string vazia (selector decide). */
    suspend fun setBackendPreference(backend: BackendId?) = store.edit {
        it[ConfigKeys.BACKEND_PREFERENCE] = backend?.name ?: ""
    }

    suspend fun setPersonality(personality: Personality) = store.edit {
        it[ConfigKeys.PERSONALITY] = AppConfigMapper.json.encodeToString(personality)
    }

    suspend fun setExperimentalAi(enabled: Boolean) = store.edit {
        it[ConfigKeys.EXPERIMENTAL_AI] = enabled
    }

    suspend fun setAutoStart(enabled: Boolean) = store.edit {
        it[ConfigKeys.AUTO_START] = enabled
    }

    /** Persiste a config da web API; [port] precisa ser 1..65535 (issue #60). */
    suspend fun setWebApi(enabled: Boolean, port: Int, password: String) {
        require(port in 1..65535) { "porta da web API fora da faixa: $port" }
        store.edit {
            it[ConfigKeys.WEB_API_ENABLED] = enabled
            it[ConfigKeys.WEB_API_PORT] = port
            // TODO(issue #60): senha em texto plano — mover para
            // EncryptedSharedPreferences/Keystore antes do v0.2.
            it[ConfigKeys.WEB_API_PASSWORD] = password
        }
    }

    suspend fun setOnboarded(onboarded: Boolean) = store.edit {
        it[ConfigKeys.ONBOARDED] = onboarded
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) = store.edit {
        it[ConfigKeys.DISCLAIMER_ACCEPTED] = accepted
    }

    private companion object {
        const val TAG = "ConfigStore"
    }
}

/** Mapeamento Preferences -> AppConfig (extraído para testar sem DataStore). */
object AppConfigMapper {

    /** Leniente: recupera JSON não canônico (vírgula final, quotes simples). */
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun fromPreferences(p: Preferences): AppConfig {
        val personality = p[ConfigKeys.PERSONALITY]?.let { raw ->
            runCatching { json.decodeFromString<Personality>(raw) }.getOrNull()
        } ?: ConfigDefaults.PERSONALITY
        return AppConfig(
            mode = p[ConfigKeys.MODE]?.let { m -> PwnMode.entries.firstOrNull { it.name == m } }
                ?: ConfigDefaults.MODE,
            backendPreference = p[ConfigKeys.BACKEND_PREFERENCE]
                ?.takeUnless { it.isEmpty() }
                ?.let { b -> BackendId.entries.firstOrNull { it.name == b } },
            personality = personality,
            experimentalAi = p[ConfigKeys.EXPERIMENTAL_AI] ?: false,
            autoStart = p[ConfigKeys.AUTO_START] ?: false,
            webApiEnabled = p[ConfigKeys.WEB_API_ENABLED] ?: false,
            webApiPort = p[ConfigKeys.WEB_API_PORT] ?: ConfigDefaults.WEB_API_PORT,
            webApiPassword = p[ConfigKeys.WEB_API_PASSWORD] ?: "",
            onboarded = p[ConfigKeys.ONBOARDED] ?: false,
            disclaimerAccepted = p[ConfigKeys.DISCLAIMER_ACCEPTED] ?: false,
        )
    }

    /** Reconstrói Preferences a partir de um [AppConfig] (fallback de I/O). */
    fun toPreferences(config: AppConfig, personalityJson: String): Preferences =
        androidx.datastore.preferences.core.mutablePreferencesOf(
            ConfigKeys.MODE to config.mode.name,
            ConfigKeys.BACKEND_PREFERENCE to (config.backendPreference?.name ?: ""),
            ConfigKeys.PERSONALITY to personalityJson,
            ConfigKeys.EXPERIMENTAL_AI to config.experimentalAi,
            ConfigKeys.AUTO_START to config.autoStart,
            ConfigKeys.WEB_API_ENABLED to config.webApiEnabled,
            ConfigKeys.WEB_API_PORT to config.webApiPort,
            ConfigKeys.WEB_API_PASSWORD to config.webApiPassword,
            ConfigKeys.ONBOARDED to config.onboarded,
            ConfigKeys.DISCLAIMER_ACCEPTED to config.disclaimerAccepted,
        )
}
