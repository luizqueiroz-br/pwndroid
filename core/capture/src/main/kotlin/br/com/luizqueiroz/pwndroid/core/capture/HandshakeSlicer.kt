package br.com.luizqueiroz.pwndroid.core.capture

import java.io.File

/**
 * Slicer de handshakes (issue #23): dado o pcap agregado do bettercap,
 * extrai os frames de um alvo (BSSID) e grava um sub-pcap
 * `<ESSID>_<BSSID>.pcap` com os frames relevantes (beacon + EAPOL),
 * hashcat-ready. Determina o tipo: FULL (M1+M2), HALF (EAPOL M2..M4
 * incompleto) ou PMKID (KDE no M1).
 */
class HandshakeSlicer(private val outDir: File) {

    init {
        require(outDir.mkdirs() || outDir.isDirectory) { "não foi possível criar $outDir" }
    }

    /**
     * Extrai do pcap agregado os frames do alvo [targetBssid] e grava o
     * sub-pcap + metadata `.pcap.eye`. Retorna null se não houver EAPOL
     * do alvo no arquivo (só beacon não justifica um arquivo de
     * handshake).
     */
    fun slice(
        aggregatedPcap: File,
        targetBssid: String,
        essidHint: String? = null,
    ): SlicedHandshake? {
        val bssid = targetBssid.uppercase()
        val parsed = PcapReader.read(aggregatedPcap)
            .mapNotNull { FrameParser.parse(it.data, it.timestampMicros) }
        val framesOfTarget = parsed.filter { it.isOfTarget(bssid) }

        val essid = essidHint?.takeIf { it.isNotBlank() }
            ?: framesOfTarget.firstBeaconEssid(bssid)
        val targets = collectHandshakes(framesOfTarget, bssid, essid)
        if (targets.isEmpty()) return null

        val pcapFile = writeSubPcap(framesOfTarget, essid, bssid)
        writeEyeFile(pcapFile, essid, targets.maxByOrNull { it.type.rank() }!!)
        return SlicedHandshake(
            pcapPath = pcapFile.absolutePath,
            essid = essid,
            bssid = bssid,
            targets = targets,
        )
    }

    /** Caminho do sub-pcap (mesma regra de nome do [PcapWriter]). */
    private fun outFile(essid: String?, bssid: String): File =
        File(outDir, PcapWriter.fileNameFor(essid, bssid))

    /** Grava o sub-pcap com os frames do alvo e retorna o arquivo. */
    private fun writeSubPcap(
        framesOfTarget: List<ParsedFrame>,
        essid: String?,
        bssid: String,
    ): File {
        val pcap = PcapWriter(outDir).openFor(essid, bssid)
        framesOfTarget.forEach { pcap.writePacket(it.raw, it.timestampMicros) }
        pcap.close()
        return outFile(essid, bssid)
    }

    /** Metadata `.pcap.eye` ao lado do pcap (formato do pwnagotchi original). */
    private fun writeEyeFile(pcapFile: File, essid: String?, best: HandshakeTarget) {
        val nowSec = System.currentTimeMillis() / 1000
        val type = if (best.type == HandshakeType.PMKID) "pmkid" else "handshake"
        File(pcapFile.path + ".eye").writeText(
            buildString {
                append("{\n")
                append("  \"essid\": ${jsonStr(essid)},\n")
                append("  \"bssid\": \"${best.bssid}\",\n")
                append("  \"station\": \"${best.station}\",\n")
                append("  \"timestamp\": $nowSec,\n")
                append("  \"type\": \"$type\"\n")
                append("}\n")
            },
        )
    }

    /** String JSON escapada (essid pode conter aspas). */
    private fun jsonStr(value: String?): String =
        if (value == null) "null" else "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""

    /** Estado EAPOL de uma estação contra o alvo. */
    private class StationState {
        var m1 = false
        var m2 = false
        var m3 = false
        var m4 = false
        var pmkid = false
        var full = false

        /**
         * Tipo por estação, ordem de prioridade do original: FULL >
         * PMKID > HALF (hcxpcapngtool aceita os três, FULL é o melhor).
         */
        fun handshakeType(): HandshakeType? = when {
            full -> HandshakeType.FULL
            pmkid -> HandshakeType.PMKID
            m2 || m3 || m4 -> HandshakeType.HALF
            else -> null
        }
    }

    /** State machine por estação: M1→M2 = FULL; M1 com PMKID = PMKID. */
    private fun collectHandshakes(
        frames: List<ParsedFrame>,
        bssid: String,
        essid: String?,
    ): List<HandshakeTarget> {
        val stations = LinkedHashMap<String, StationState>()
        frames.asSequence()
            .filterIsInstance<ParsedFrame.Eapol>()
            .mapNotNull { frame -> EapolParser.classify(frame.eapol)?.let { frame to it } }
            .forEach { (frame, msg) -> updateStation(stations, frame, msg) }
        return stations.mapNotNull { (station, state) ->
            val type = state.handshakeType() ?: return@mapNotNull null
            HandshakeTarget(bssid, essid, station, type)
        }
    }

    private fun updateStation(
        stations: LinkedHashMap<String, StationState>,
        frame: ParsedFrame.Eapol,
        msg: EapolParser.Message,
    ) {
        val state = stations.getOrPut(frame.station) { StationState() }
        when (msg) {
            EapolParser.Message.M1 -> {
                state.m1 = true
                if (EapolParser.extractPmkid(frame.eapol) != null) state.pmkid = true
            }
            EapolParser.Message.M2 -> {
                state.m2 = true
                if (state.m1) state.full = true
            }
            EapolParser.Message.M3 -> state.m3 = true
            EapolParser.Message.M4 -> state.m4 = true
        }
    }
}

/** Frame do alvo: EAPOL ou beacon/probe-resp com o BSSID. */
private fun ParsedFrame.isOfTarget(bssid: String): Boolean =
    when (this) {
        is ParsedFrame.Beacon -> this.bssid.uppercase() == bssid
        is ParsedFrame.Eapol -> this.bssid.uppercase() == bssid
        else -> false
    }

/** Primeiro ESSID visível nos beacons do alvo (null se oculto). */
private fun List<ParsedFrame>.firstBeaconEssid(bssid: String): String? =
    filterIsInstance<ParsedFrame.Beacon>()
        .firstOrNull { it.bssid.uppercase() == bssid && it.essid.isNotBlank() }
        ?.essid

/** Tipo do handshake extraído. */
enum class HandshakeType { FULL, HALF, PMKID }

/** Handshake de uma estação contra um AP. */
data class HandshakeTarget(
    val bssid: String,
    val essid: String?,
    val station: String,
    val type: HandshakeType,
)

/**
 * Resultado do slicing: sub-pcap por alvo + tipo de handshake por
 * estação.
 */
data class SlicedHandshake(
    val pcapPath: String,
    val essid: String?,
    val bssid: String,
    val targets: List<HandshakeTarget>,
) {
    /** O melhor handshake do alvo (FULL > PMKID > HALF). */
    fun best(): HandshakeTarget? = targets.maxByOrNull { it.type.rank() }
}

private fun HandshakeType.rank(): Int = when (this) {
    HandshakeType.FULL -> 3
    HandshakeType.PMKID -> 2
    HandshakeType.HALF -> 1
}
