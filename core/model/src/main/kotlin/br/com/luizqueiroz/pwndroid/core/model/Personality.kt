package br.com.luizqueiroz.pwndroid.core.model

import kotlinx.serialization.Serializable

/**
 * Parâmetros de personalidade do agente — defaults idênticos ao
 * `pwnagotchi/defaults.toml` do original: recon_time 30, ap_ttl 120,
 * sta_ttl 45, min_rssi -200, max_interactions 3, channels [1..13].
 */
@Serializable
data class Personality(
    /** Tempo total de recon por época, em segundos (recon_time). */
    val reconTimeSec: Long = 30,
    /** TTL (s) de um AP na lista de alvos desde a última vez visto (ap_ttl). */
    val apTtlSec: Long = 120,
    /** TTL (s) de um cliente (STA) na lista de alvos (sta_ttl). */
    val staTtlSec: Long = 45,
    /** RSSI mínimo para um AP/STA ser elegível como alvo (min_rssi). */
    val minRssi: Int = -200,
    /** Canais que o rádio pode hoppnar; vazio = todos (channels). */
    val channels: Set<Int> = (1..13).toSet(),
    /** Máximo de interações (assoc/deauth) por época (max_interactions). */
    val maxInteractions: Int = 3,
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
        require(minRssi <= 0) { "minRssi deve ser <= 0" }
    }
}
