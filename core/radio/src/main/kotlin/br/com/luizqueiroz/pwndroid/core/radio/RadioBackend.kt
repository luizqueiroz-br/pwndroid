package br.com.luizqueiroz.pwndroid.core.radio

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.flow.SharedFlow

/** Identificador do backend (qual implementação de rádio está ativa). */
enum class BackendId { PASSIVE, BETTERCAP, NEXMON, ESP32, FAKE }

/**
 * Capacidades que um backend declara no device atual. O cérebro/orquestrador
 * nunca toca em hardware sem checar aqui primeiro.
 */
data class RadioCapabilities(
    val canRecon: Boolean = true,
    val canAssoc: Boolean = false,
    val canDeauth: Boolean = false,
    val canCaptureEapol: Boolean = false,
    val canCapturePmkid: Boolean = false,
    val canSetChannel: Boolean = false,
    val canSeePeers: Boolean = false,
    val canBle: Boolean = false,
)

/**
 * Serviços do ambiente oferecidos ao backend no start (escrita de PCAPs,
 * relógio). Nada de Android aqui — o :app monta a implementação real.
 */
interface BackendEnvironment {
    /** Relógio do app (fake em testes). */
    val clock: AppClock

    /** Diretório onde o backend escreve os PCAPs (`ESSID_BSSID.pcap`). */
    val captureDir: String
}

/**
 * Única fronteira entre o cérebro/orquestrador e o hardware de rádio.
 * `start()` devolve o backend no estado operacional ([StartedBackend]);
 * implementações sem injeção (passivo) ainda são [RadioBackend] válidas.
 */
interface RadioBackend {
    val id: BackendId
    val capabilities: RadioCapabilities

    /** Coloca o backend em operação; pode lançar [BackendUnavailableException]. */
    suspend fun start(env: BackendEnvironment): StartedBackend
}

/** O backend está indisponível no device atual (sem root, sem USB, etc.). */
class BackendUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Backend em operação: recon, interações e eventos. PCAP nunca aparece
 * nesta interface — quem captura escreve o arquivo e emite
 * [RadioEvent.HandshakeDetected] com o caminho.
 */
interface StartedBackend {
    /** Qual backend está em operação (para a UI e telemetria). */
    val backendId: BackendId

    /** Recon contínuo nos canais dados, com dwell (ms) por canal. */
    suspend fun startRecon(channels: Set<Int>, dwellMs: Long)

    suspend fun stopRecon()

    /** Snapshot dos APs vistos desde o início do recon. */
    suspend fun accessPoints(): List<AccessPoint>

    /** Associação aberta para colher PMKID (M1 com PMKID no KDE). */
    suspend fun assoc(ap: MacAddress): Result<Unit>

    /** Desautentica um cliente (ou broadcast quando [sta] é null). */
    suspend fun deauth(sta: MacAddress?, ap: MacAddress): Result<Unit>

    suspend fun setChannel(channel: Int, widthMhz: Int = 20): Result<Unit>

    /** Fluxo quente de eventos do backend. */
    fun events(): SharedFlow<RadioEvent>

    /** Ajusta parâmetros dirigidos pela personalidade da época. */
    suspend fun applyPersonality(p: Personality)

    suspend fun shutdown()
}

/**
 * Seleciona o melhor backend disponível: tenta `start()` em ordem de
 * capacidades e devolve o primeiro que sobe. Com [preferred], tenta o
 * backend preferido primeiro (config do usuário; se não subir, cai para
 * a ordem normal — a sessão não falha por preferência inválida).
 */
class BackendSelector(private val candidates: List<RadioBackend>) {

    /** O backend com mais capacidades que conseguiu subir, ou null. */
    suspend fun select(env: BackendEnvironment, preferred: BackendId? = null): StartedBackend? {
        // Ordem base: mais capacidades primeiro. Com preferido (issue #13),
        // ele vai para a frente; os demais seguem a ordem normal (fallback
        // se o preferido não subir).
        val ordered = candidates
            .sortedByDescending { it.capabilities.score() }
            .let { sorted ->
                if (preferred == null) {
                    sorted
                } else {
                    sorted.filter { it.id == preferred } +
                        sorted.filter { it.id != preferred }
                }
            }
        return ordered
            .firstNotNullOfOrNull { backend ->
                runCatching { backend.start(env) }.getOrNull()
            }
    }

    private fun RadioCapabilities.score(): Int =
        listOf(canRecon, canAssoc, canDeauth, canCaptureEapol, canCapturePmkid, canSetChannel, canSeePeers, canBle)
            .count { it }
}
