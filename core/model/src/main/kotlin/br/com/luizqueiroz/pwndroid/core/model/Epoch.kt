package br.com.luizqueiroz.pwndroid.core.model

/**
 * Observação e desfecho de uma época — a unidade de aprendizado do
 * cérebro (porte de agent.py: epoch observation/result).
 */

/** O que o agente enxergou durante uma época. */
data class EpochObservation(
    val epoch: Long,
    val apsSeen: Int,
    val stationsSeen: Int,
    val handshakes: Int,
    /** true quando o rádio não viu nada (blind). */
    val blind: Boolean,
    /** Tempo (s) sem atividade do agente. */
    val inactive: Long,
    /** Histograma de RSSI dos APs vistos (bin → contagem). */
    val rssiHistogram: Map<Int, Int>,
    /** Carga observada por canal (canal → nº de APs). */
    val channelLoad: Map<Int, Int>,
    val peersVisible: Int,
    /** Percentual de bateria (0–100), se conhecido. */
    val batteryLevel: Int? = null,
    /** Percentual de CPU (0–100), se conhecido. */
    val cpuLoad: Int? = null,
)

/** Desfecho da época, entregue ao cérebro para o aprendizado. */
data class EpochResult(
    val epoch: Long,
    val handshakes: Int,
    val blind: Boolean,
    val inactive: Long,
    /** Duração da época em segundos. */
    val durationSec: Long,
    val personalityUsed: Personality,
)
