package br.com.luizqueiroz.pwndroid.core.model

import kotlinx.serialization.Serializable

/**
 * Um evento emitido pelo rádio (via [br.com.luizqueiroz.pwndroid.core.radio.RadioBackend]).
 */
@Serializable
sealed interface RadioEvent {

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
    data class StaSeen(
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

    /** O canal do rádio mudou (hop). */
    @Serializable
    data class ChannelChanged(val channel: Int) : RadioEvent

    /** Erro não fatal reportado pelo backend. */
    @Serializable
    data class Error(val message: String) : RadioEvent
}

/**
 * Estados de operação do rádio, espelhando os modos do pwnagotchi original
 * (MANU/AUTO/AI) mais o estado offline dos backends sem injeção.
 */
enum class PwnMode { MANU, AUTO, AI, PASSIVE }

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