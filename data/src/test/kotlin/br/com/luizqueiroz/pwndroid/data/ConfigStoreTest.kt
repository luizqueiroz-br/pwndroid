package br.com.luizqueiroz.pwndroid.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Scopes dos DataStores criados no teste — cancelados no @After. */
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    /** Arquivos criados — deletados no @After (issue #61). */
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        files.forEach { it.delete() }
    }

    /** DataStore de teste com scope próprio, cancelado no tearDown (issue #61). */
    private fun newStore(): DataStore<Preferences> {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        scopes += scope
        val file = File(tmp.root, "test_${System.nanoTime()}.preferences_pb")
        files += file
        return androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

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
        assertEquals(ConfigDefaults.WEB_API_PORT, config.webApiPort)
    }

    @Test
    fun `mudança de config propaga em collector reativo`() = runTest {
        val store = ConfigStore(newStore())

        val emissions = mutableListOf<AppConfig>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.config.take(3).toList(emissions)
        }
        testScheduler.advanceUntilIdle()

        // Mudança DEVE aparecer no mesmo collector (issue #57) — sem first().
        store.setMode(PwnMode.MANUAL)
        testScheduler.advanceUntilIdle()
        store.setBackendPreference(BackendId.PASSIVE)
        testScheduler.advanceUntilIdle()

        collector.join()
        assertEquals(3, emissions.size)
        assertEquals(PwnMode.AUTO, emissions[0].mode)
        assertEquals(PwnMode.MANUAL, emissions[1].mode)
        assertEquals(BackendId.PASSIVE, emissions[2].backendPreference)
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

    // ---- issue #56: corrupção real do arquivo ----

    @Test
    fun `arquivo corrompido emite defaults com warning logado`() = runTest {
        // Corrupção real via arquivo não exercita o catch: o parser protobuf
        // do DataStore aceita bytes arbitrários sem lançar. Um DataStore fake
        // que lança CorruptionException testa o contrato do catch (issue #56).
        val logger = RecordingLogger()
        val store = ConfigStore(CorruptDataStore(), logger)
        val recovered = store.config.first()
        // Corrompido → defaults; onboarded/disclaimer resetados, mas LOGADOS.
        assertEquals(PwnMode.AUTO, recovered.mode)
        assertEquals(false, recovered.onboarded)
        assertTrue(
            "warnings=${logger.warnings}",
            logger.warnings.any { it.contains("corrompido") },
        )
    }

    @Test
    fun `erro de IO transiente emite a última config boa`() = runTest {
        val logger = RecordingLogger()
        val good = androidx.datastore.preferences.core.mutablePreferencesOf(
            ConfigKeys.MODE to PwnMode.MANUAL.name,
            ConfigKeys.ONBOARDED to true,
        )
        val store = ConfigStore(FlakyDataStore(good), logger)
        val emissions = store.config.take(2).toList()
        // 1ª leitura: config boa (vira lastGood). 2ª: erro de I/O → mantém a
        // última boa em vez de resetar para defaults (issue #56).
        assertEquals(PwnMode.MANUAL, emissions[0].mode)
        assertEquals(PwnMode.MANUAL, emissions[1].mode)
        assertEquals(true, emissions[1].onboarded)
        assertTrue(logger.warnings.any { it.contains("I/O") })
    }

    @Test
    fun `nova escrita propaga no mesmo collector após fallback`() = runTest {
        val file = File(tmp.root, "fallback_${System.nanoTime()}.preferences_pb")
        files += file
        val store = ConfigStore(newStoreAt(file))
        val emissions = mutableListOf<AppConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.config.take(2).toList(emissions)
        }
        testScheduler.advanceUntilIdle()
        store.setMode(PwnMode.MANUAL)
        testScheduler.advanceUntilIdle()
        job.join()
        assertEquals(2, emissions.size) // initial + escrita — flow vivo
        assertEquals(PwnMode.MANUAL, emissions[1].mode)
    }

    // ---- issue #57: propagação real com Turbine ----

    @Test
    fun `propagação turbina emite sequência no mesmo collector`() = runTest {
        val store = ConfigStore(newStore())
        store.config.test {
            val initial = awaitItem()
            assertEquals(PwnMode.AUTO, initial.mode)
            store.setMode(PwnMode.MANUAL)
            val second = awaitItem()
            assertEquals(PwnMode.MANUAL, second.mode)
            store.setMode(PwnMode.AI)
            val third = awaitItem()
            assertEquals(PwnMode.AI, third.mode)
        }
    }

    // ---- issue #58: parse leniente ----

    @Test
    fun `personality com vírgula final é recuperada pelo parse leniente`() = runTest {
        val store = ConfigStore(newStore())
        store.setPersonality(Personality())
        store.store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                // keys sem quotes: JSON não canônico que o parse estrito
                // rejeita (isLenient recupera; vírgula final não é recuperável)
                set(ConfigKeys.PERSONALITY, """{reconTimeSec:10, apTtlSec:60}""")
            }
        }
        val config = store.config.first()
        assertEquals(10L, config.personality.reconTimeSec)
        assertEquals(60L, config.personality.apTtlSec)
    }

    // ---- issue #60: validação de porta ----

    @Test
    fun `setWebApi rejeita porta fora da faixa`() = runTest {
        val store = ConfigStore(newStore())
        val invalid = listOf(0, -1, 65536, 99999)
        for (port in invalid) {
            try {
                store.setWebApi(enabled = true, port = port, password = "pw")
                fail("porta $port deveria ser rejeitada")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
        // dentro da faixa persiste:
        store.setWebApi(enabled = true, port = 9999, password = "pw")
        assertEquals(9999, store.config.first().webApiPort)
    }

    // ---- helpers ----

    private fun newStoreAt(file: File): DataStore<Preferences> {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        scopes += scope
        files += file
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }
}

/** Logger de gravação para asserções de warning (issue #58). */
class RecordingLogger : br.com.luizqueiroz.pwndroid.core.common.AppLogger {
    val warnings = mutableListOf<String>()
    override fun d(tag: String, message: String) {
        // debug não é gravado — só warnings interessam às asserções
    }
    override fun w(tag: String, message: String, throwable: Throwable?) {
        warnings += "$message [${throwable?.javaClass?.simpleName}]"
    }
    override fun e(tag: String, message: String, throwable: Throwable?) {
        // erro não é gravado — só warnings interessam às asserções
    }
}

/** DataStore fake: toda leitura lança CorruptionException (issue #56). */
private class CorruptDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow {
        throw androidx.datastore.core.CorruptionException(
            "arquivo de preferências ilegível",
            java.io.IOException("lixo protobuf"),
        )
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        error("não usado neste teste")
}

/** DataStore fake: emite uma config boa e depois falha com IOException. */
private class FlakyDataStore(private val good: Preferences) : DataStore<Preferences> {
    override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow {
        emit(good)
        throw java.io.IOException("I/O transiente simulado")
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        error("não usado neste teste")
}
