package br.com.luizqueiroz.pwndroid.core.model

/**
 * Domínio de rádio do pwndroid — porte do modelo do pwnagotchi, mesmo
 * vocabulário (AP, Station, handshake, epoch, personality).
 */

/** Um ponto de acesso Wi-Fi visto durante o recon. */
data class AccessPoint(
    val mac: MacAddress,
    val ssid: String?,
    val rssi: Int,
    val channel: Int,
    val frequencyMhz: Int,
    val encryption: String? = null,
    val clients: List<Station> = emptyList(),
    val firstSeen: Long,
    val lastSeen: Long,
    /** true quando este AP é outro pwnagotchi/pwndroid na rede. */
    val isPeer: Boolean = false,
)

/** Uma estação (cliente) associada a um AP. */
data class Station(
    val mac: MacAddress,
    val rssi: Int,
    val apMac: MacAddress?,
)

/** Tipo de handshake capturado (vocabulário hcxtools). */
enum class HandshakeType { FULL, HALF, PMKID }

/** Uma captura de handshake com o PCAP escrito. */
data class HandshakeCapture(
    val ap: MacAddress,
    val path: String,
    val type: HandshakeType,
    val capturedAt: Long,
    val gps: GpsFix? = null,
)

/** Fix de GPS anexado às capturas (plugin gps). */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val accuracyMeters: Float,
    val timestamp: Long,
)

/** Par de chaves ed25519 + fingerprint no grid. */
data class PeerUnit(
    val fingerprint: String,
    val name: String,
    val appearedAt: Long,
)
