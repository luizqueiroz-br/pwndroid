package br.com.luizqueiroz.pwndroid.core.radio

import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.model.Target
import kotlinx.coroutines.flow.Flow

/**
 * Única fronteira entre o cérebro/orquestrador e o hardware de rádio.
 * Cada implementação (passivo sem root, bettercap, Nexmon, ESP32) fala a
 * mesma língua: um fluxo de eventos e operações suspend.
 */
interface RadioBackend {

    /** Identificador estável do backend (ex.: "passive", "bettercap", "nexmon", "esp32"). */
    val id: String

    /** Capacidades que este backend oferece no ambiente atual. */
    val capabilities: BackendCapabilities

    /** Fluxo frio de eventos; começa a emitir quando coletado. */
    fun events(): Flow<RadioEvent>

    /** true quando o backend está pronto para receber operações. */
    suspend fun isAvailable(): Boolean
}

/**
 * Backend que também aceita operações ativas (recon/hop/deauth/assoc).
 * Backends passivos só implementam [RadioBackend].
 */
interface StartedBackend : RadioBackend {
    suspend fun start()
    suspend fun stop()
    suspend fun setChannel(channel: Int)
    suspend fun deauth(bssid: String, station: String?, count: Int)
    suspend fun associate(bssid: String, station: String?)
}

/** Capacidades observáveis de um backend em um dado device. */
data class BackendCapabilities(
    /** Enxerga APs/STAs (todo backend deve). */
    val canRecon: Boolean = true,
    /** Pode trocar de canal. */
    val canHop: Boolean = false,
    /** Pode enviar deauth (exige root + driver com injeção). */
    val canDeauth: Boolean = false,
    /** Pode associar para colher PMKID. */
    val canAssoc: Boolean = false,
    /** Escreve PCAPs hashcat-ready por conta própria. */
    val writesPcap: Boolean = false,
    /** Escaneia BLE (modo sem root). */
    val canBle: Boolean = false,
)

/**
 * Seleciona o melhor backend disponível no device (root, USB OTG, etc.).
 * Implementação completa chega com a issue #6; por ora, o registro é
 * preenchido manualmente pelo DI.
 */
class BackendSelector(private val candidates: List<RadioBackend>) {
    /** Devolve o backend com mais capacidades que está disponível, ou null. */
    suspend fun select(): RadioBackend? =
        candidates
            .sortedByDescending { it.capabilities.score() }
            .firstOrNull { it.isAvailable() }
}

private fun BackendCapabilities.score(): Int =
    listOf(canRecon, canHop, canDeauth, canAssoc, writesPcap, canBle).count { it }

/**
 * Backend falsível para testes do orquestrador/cérebro: emite o que o teste
 * programar e registra as operações pedidas, sem tocar em hardware.
 */
class FakeRadioBackend(
    override val capabilities: BackendCapabilities = BackendCapabilities(),
    private val scripted: List<RadioEvent> = emptyList(),
) : StartedBackend {
    override val id: String = "fake"
    private val ops = mutableListOf<String>()

    /** Operações recebidas até agora, em ordem (para asserts de teste). */
    val operations: List<String> get() = ops.toList()

    override fun events(): Flow<RadioEvent> = kotlinx.coroutines.flow.flow {
        for (event in scripted) emit(event)
    }

    override suspend fun isAvailable(): Boolean = true
    override suspend fun start() = ops.add("start").let { }
    override suspend fun stop() = ops.add("stop").let { }
    override suspend fun setChannel(channel: Int) = ops.add("channel:$channel").let { }
    override suspend fun deauth(bssid: String, station: String?, count: Int) =
        ops.add("deauth:$bssid/$station#$count").let { }

    override suspend fun associate(bssid: String, station: String?) =
        ops.add("assoc:$bssid/$station").let { }
}
