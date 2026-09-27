package br.com.luizqueiroz.pwndroid.core.model

import kotlinx.serialization.Serializable

/**
 * Parâmetros de personalidade do agente — equivalente ao bloco
 * `main.confidence` / `personality` do pwnagotchi original. O cérebro
 * (Thompson Sampling ou A2C) ajusta esses valores ao longo das épocas.
 */
@Serializable
data class Personality(
    /** Tempo total de recon por época, em segundos. */
    val reconTimeSec: Long = 30,
    /** TTL (s) de um AP na lista de alvos desde a última vez visto. */
    val apTtlSec: Long = 600,
    /** TTL (s) de um cliente (STA) na lista de alvos. */
    val staTtlSec: Long = 600,
    /** RSSI mínimo para um AP/STA ser elegível como alvo. */
    val minRssi: Int = -200,
    /** Canais que o rádio pode hoppnar; vazio = todos. */
    val channels: Set<Int> = emptySet(),
    /** Máximo de interações (assoc/deauth) por época. */
    val maxInteractions: Int = 6,
    /** Deauths enviados por pacote de desautenticação. */
    val deauthCount: Int = 6,
    /** Quantos pacotes de associação (PMKID) por alvo. */
    val assocCount: Int = 1,
    /** Fator de excitação que aumenta o ritmo quando há sucesso. */
    val excitedPeriodSec: Long = 15,
    /** True quando o modo é totalmente autônomo (AI), false para AUTO/MANU. */
    val isAiEnabled: Boolean = true,
) {
    init {
        require(reconTimeSec > 0) { "reconTimeSec deve ser > 0" }
        require(maxInteractions >= 0) { "maxInteractions deve ser >= 0" }
    }
}