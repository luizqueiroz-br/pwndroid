package br.com.luizqueiroz.pwndroid.core.model

import kotlinx.serialization.Serializable

/**
 * Um evento emitido pelo rádio (via [br.com.luizqueiroz.pwndroid.core.radio.RadioBackend]).
 * Conjunto canônico da issue #6: todo fato observável do rádio chega como
 * um [RadioEvent] no fluxo quente do backend.
 */
@Serializable
sealed interface RadioEvent {

    /** O recon começou nos canais dados. */
    @Serializable
    data class ReconStarted(val channels: List<Int>) : RadioEvent

    /** O ciclo de recon terminou (uma passada completa nos canais). */
    @Serializable
    data class ReconFinished(val durationMs: Long) : RadioEvent

    /** Um AP foi visto durante o recon. */
    @Serializable
    data class ApSeen(
        val essid: String?,
        val bssid: String,
        val channel: Int,
        val rssi: Int,
        val encryption: String? = null,
    ) : RadioEvent

    /** Um cliente (STA) foi visto associado a um AP. */
    @Serializable
    data class StationSeen(
        val station: String,
        val bssid: String?,
        val rssi: Int,
    ) : RadioEvent

    /** Um handshake completo (ou PMKID) foi capturado. */
    @Serializable
    data class HandshakeDetected(
        val bssid: String,
        val station: String,
        val essid: String?,
        /** Caminho do arquivo .pcap escrito pelo backend. */
        val pcapPath: String,
        val isPmkid: Boolean = false,
    ) : RadioEvent

    /** Um peer (outro pwnagotchi/pwndroid) foi visto no canal. */
    @Serializable
    data class PeerSeen(val fingerprint: String, val name: String?) : RadioEvent

    /** Há internet disponível (upstream de internet do grid). */
    @Serializable
    data class InternetAvailable(val viaPeer: Boolean) : RadioEvent

    /** O canal do rádio mudou (hop). */
    @Serializable
    data class ChannelChanged(val channel: Int) : RadioEvent

    /** Erro não fatal reportado pelo backend. */
    @Serializable
    data class BackendError(val message: String, val recoverable: Boolean = true) : RadioEvent
}

/**
 * Estados de operação do rádio, espelhando os modos do pwnagotchi original
 * (MANU/AUTO/AI) mais o estado offline dos backends sem injeção.
 */
enum class PwnMode { MANUAL, AUTO, AI, PASSIVE }

/**
 * Alvo selecionado pelo cérebro para uma época.
 */
@Serializable
data class Target(
    val bssid: String,
    val essid: String?,
    val channel: Int,
    val rssi: Int,
    val clients: Int = 0,
)
