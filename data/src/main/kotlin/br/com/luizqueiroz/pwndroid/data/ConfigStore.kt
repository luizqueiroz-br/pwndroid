package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val mode: PwnMode = PwnMode.AUTO,
    val backendPreference: BackendId? = null, // null = selector decide
    val personality: Personality = Personality(),
    val experimentalAi: Boolean = false,
    val autoStart: Boolean = false,
    val webApiEnabled: Boolean = false,
    val webApiPort: Int = 8080,
    val webApiPassword: String = "",
    val onboarded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
)

/**
 * ConfigStore sobre DataStore Preferences (issue #16): API reativa via
 * [config] Flow — uma mudança propaga para UI e SessionController sem
 * restart. [Personality] serializada com kotlinx-serialization.
 */
class ConfigStore(internal val store: DataStore<Preferences>) {

    /** Defaults idênticos ao defaults.toml/original quando keys ausentes. */
    val config: Flow<AppConfig> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> AppConfigMapper.fromPreferences(p) }

    suspend fun setMode(mode: PwnMode) = store.edit { it[ConfigKeys.MODE] = mode.name }

    /** null persiste como string vazia (selector decide). */
    suspend fun setBackendPreference(backend: BackendId?) = store.edit {
        it[ConfigKeys.BACKEND_PREFERENCE] = backend?.name ?: ""
    }

    suspend fun setPersonality(personality: Personality) = store.edit {
        it[ConfigKeys.PERSONALITY] = Json.encodeToString(personality)
    }

    suspend fun setExperimentalAi(enabled: Boolean) = store.edit {
        it[ConfigKeys.EXPERIMENTAL_AI] = enabled
    }

    suspend fun setAutoStart(enabled: Boolean) = store.edit {
        it[ConfigKeys.AUTO_START] = enabled
    }

    suspend fun setWebApi(enabled: Boolean, port: Int, password: String) = store.edit {
        it[ConfigKeys.WEB_API_ENABLED] = enabled
        it[ConfigKeys.WEB_API_PORT] = port
        it[ConfigKeys.WEB_API_PASSWORD] = password
    }

    suspend fun setOnboarded(onboarded: Boolean) = store.edit {
        it[ConfigKeys.ONBOARDED] = onboarded
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) = store.edit {
        it[ConfigKeys.DISCLAIMER_ACCEPTED] = accepted
    }
}

/** Mapeamento Preferences -> AppConfig (extraído para testar sem DataStore). */
object AppConfigMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromPreferences(p: Preferences): AppConfig {
        val personality = p[ConfigKeys.PERSONALITY]?.let { raw ->
            runCatching { json.decodeFromString<Personality>(raw) }.getOrNull()
        } ?: Personality()
        return AppConfig(
            mode = p[ConfigKeys.MODE]?.let { m -> PwnMode.entries.firstOrNull { it.name == m } }
                ?: PwnMode.AUTO,
            backendPreference = p[ConfigKeys.BACKEND_PREFERENCE]
                ?.takeUnless { it.isEmpty() }
                ?.let { b -> BackendId.entries.firstOrNull { it.name == b } },
            personality = personality,
            experimentalAi = p[ConfigKeys.EXPERIMENTAL_AI] ?: false,
            autoStart = p[ConfigKeys.AUTO_START] ?: false,
            webApiEnabled = p[ConfigKeys.WEB_API_ENABLED] ?: false,
            webApiPort = p[ConfigKeys.WEB_API_PORT] ?: 8080,
            webApiPassword = p[ConfigKeys.WEB_API_PASSWORD] ?: "",
            onboarded = p[ConfigKeys.ONBOARDED] ?: false,
            disclaimerAccepted = p[ConfigKeys.DISCLAIMER_ACCEPTED] ?: false,
        )
    }
}
