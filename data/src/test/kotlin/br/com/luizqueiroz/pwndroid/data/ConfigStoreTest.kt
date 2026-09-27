package br.com.luizqueiroz.pwndroid.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "test_${System.nanoTime()}.preferences_pb") },
        )

    @Test
    fun `defaults idênticos ao defaults-toml quando ausentes`() = runTest {
        val store = ConfigStore(newStore())
        val config = store.config.first()
        // Defaults do Personality original: recon_time 30, ap_ttl 120, sta_ttl 45...
        assertEquals(Personality(), config.personality)
        assertEquals(PwnMode.AUTO, config.mode)
        assertNull(config.backendPreference)
        assertTrue(!config.experimentalAi && !config.autoStart)
        assertTrue(!config.onboarded && !config.disclaimerAccepted)
        assertEquals(8080, config.webApiPort)
    }

    @Test
    fun `mudança de config propaga em collector reativo`() = runTest {
        val store = ConfigStore(newStore())
        store.setMode(PwnMode.MANUAL)
        store.setBackendPreference(BackendId.PASSIVE)

        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.config.collect { }
        }
        // dá um tick para o collector consumir o estado atual
        testScheduler.advanceUntilIdle()
        assertEquals(PwnMode.MANUAL, store.config.first().mode)
        assertEquals(BackendId.PASSIVE, store.config.first().backendPreference)

        store.setMode(PwnMode.AI)
        testScheduler.advanceUntilIdle()
        assertEquals(PwnMode.AI, store.config.first().mode)

        collector.cancelAndJoin()
    }

    @Test
    fun `personalidade serializada e desserializada fielmente`() = runTest {
        val store = ConfigStore(newStore())
        val custom = Personality(reconTimeSec = 10, apTtlSec = 60, maxInteractions = 0, channels = setOf(1, 6, 11))
        store.setPersonality(custom)
        assertEquals(custom, store.config.first().personality)
    }

    @Test
    fun `personality corrompida cai no default`() = runTest {
        val store = ConfigStore(newStore())
        // Grava um válido primeiro, depois corrompe a key direto no DataStore:
        store.setPersonality(Personality())
        store.store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(ConfigKeys.PERSONALITY, "{lixo json inválido")
            }
        }
        val config = store.config.first()
        assertEquals(Personality(), config.personality)
    }
}
