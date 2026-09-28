package br.com.luizqueiroz.pwndroid.core.capture

import java.io.File
import java.io.InputStream

/** Um frame cru do PCAP com timestamp (µs). */
data class PcapRecord(val timestampMicros: Long, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PcapRecord && timestampMicros == other.timestampMicros &&
            data.contentEquals(other.data)
    override fun hashCode(): Int = timestampMicros.hashCode() * 31 + data.contentHashCode()
}

/** Leitor de PCAP little-endian (records + global header). */
object PcapReader {

    /** Records do arquivo; lança [IllegalArgumentException] se o magic for inválido. */
    fun read(file: File): List<PcapRecord> {
        val data = file.readBytes()
        require(data.size >= 24) { "pcap truncado: ${file.name}" }
        val magic = u32le(data, 0)
        require(
            magic == 0xa1b2c3d4.toInt() || magic == 0xa1b23c4d.toInt(),
        ) { "magic PCAP inválido: ${data.copyOfRange(0, 4).hex()}" }
        val records = mutableListOf<PcapRecord>()
        var off = 24
        while (off + 16 <= data.size) {
            val tsSec = u32le(data, off)
            val tsUsec = u32le(data, off + 4)
            val inclLen = u32le(data, off + 8)
            off += 16
            if (off + inclLen > data.size) break // truncado no fim
            records += PcapRecord(tsSec * 1_000_000L + tsUsec, data.copyOfRange(off, off + inclLen))
            off += inclLen
        }
        return records
    }

    fun u32le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xff) or
            ((buf[off + 1].toInt() and 0xff) shl 8) or
            ((buf[off + 2].toInt() and 0xff) shl 16) or
            ((buf[off + 3].toInt() and 0xff) shl 24)

    fun u16le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8)

    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

/** Frame 802.11 parseado (radiotap removido). */
sealed interface ParsedFrame {

    /** BSSID do frame (addr1/addr2/addr3 conforme toDS/fromDS). */
    val bssid: String

    /** Frame cru (radiotap+802.11), pronto para re-gravação. */
    val raw: ByteArray

    val timestampMicros: Long

    /** Beacon ou probe-response com ESSID visível. */
    data class Beacon(
        override val bssid: String,
        val essid: String,
        override val raw: ByteArray,
        override val timestampMicros: Long,
    ) : ParsedFrame

    /** Frame de dados carregando um payload EAPOL. */
    data class Eapol(
        override val bssid: String,
        /** MAC da estação cliente (não-BSSID do par). */
        val station: String,
        /** Payload EAPOL a partir de Version (offset 0 do EAPOL). */
        val eapol: ByteArray,
        override val raw: ByteArray,
        override val timestampMicros: Long,
    ) : ParsedFrame

    /** Qualquer outro frame (não relevante ao slicing). */
    data class Other(
        override val bssid: String,
        override val raw: ByteArray,
        override val timestampMicros: Long,
    ) : ParsedFrame
}

/** Parser radiotap → 802.11 (beacon/SSIDs e EAPOL via LLC/SNAP). */
object FrameParser {

    /** Parseia um frame radiotap+802.11. Retorna null se malformado. */
    fun parse(raw: ByteArray, timestampMicros: Long): ParsedFrame? {
        val p = stripRadiotap(raw) ?: return null

        val fc = u16(p, 0)
        val type = (fc shr 2) and 0x3
        val subtype = (fc shr 4) and 0xf
        val addresses = Addresses(p, toDs = (fc shr 8) and 1, fromDs = (fc shr 9) and 1)
        val bssid = addresses.bssid()

        if (type == 0 && (subtype == 8 || subtype == 5)) {
            return beaconFrame(p, bssid, raw, timestampMicros)
        }
        if (type == 2 && ((fc shr 14) and 1) == 0) { // não-criptografado
            val eapol = eapolPayload(p, subtype)
            if (eapol != null) {
                return ParsedFrame.Eapol(
                    bssid = bssid,
                    station = addresses.station(),
                    eapol = eapol,
                    raw = raw,
                    timestampMicros = timestampMicros,
                )
            }
        }
        return ParsedFrame.Other(bssid, raw, timestampMicros)
    }

    /** Remove o pseudo-header radiotap (len em offset 2, little-endian). */
    private fun stripRadiotap(raw: ByteArray): ByteArray? {
        if (raw.size < 24) return null
        val radiotapLen = u16le(raw, 2)
        if (radiotapLen < 4 || radiotapLen > raw.size) return null
        val p = raw.copyOfRange(radiotapLen, raw.size)
        return if (p.size < 24) null else p
    }

    /** Beacon/probe-response → [ParsedFrame.Beacon], ou Other se sem SSID. */
    private fun beaconFrame(
        p: ByteArray,
        bssid: String,
        raw: ByteArray,
        ts: Long,
    ): ParsedFrame {
        val essid = beaconEssid(p)
            ?: return ParsedFrame.Other(bssid, raw, ts)
        return ParsedFrame.Beacon(bssid, essid, raw, ts)
    }

    /** Endereços 802.11 e derivação de BSSID/estação por toDS/fromDS. */
    private class Addresses(p: ByteArray, private val toDs: Int, private val fromDs: Int) {
        val addr1 = mac(p, 4)
        val addr2 = mac(p, 10)
        val addr3 = mac(p, 16)

        /** BSSID: STA→AP (toDS): addr1; AP→STA (fromDS): addr2; WDS: addr3. */
        fun bssid(): String = when {
            toDs == 1 && fromDs == 0 -> addr1
            toDs == 0 && fromDs == 1 -> addr2
            else -> addr3
        }

        /** Station é o endereço do par que não é o BSSID. */
        fun station(): String = if (toDs == 1 && fromDs == 0) addr2 else addr1

        private fun mac(p: ByteArray, off: Int): String =
            p.copyOfRange(off, off + 6).joinToString(":") { "%02x".format(it) }.uppercase()
    }

    private fun other(bssid: String, raw: ByteArray, ts: Long) =
        ParsedFrame.Other(bssid, raw, ts)

    /** SSID tag (id 0) do corpo do beacon/probe-response. */
    private fun beaconEssid(p: ByteArray): String? {
        var pos = 36 // 24 header + timestamp 8 + beacon interval 2 + capabilities 2
        while (pos + 2 <= p.size) {
            val tagId = p[pos].toInt() and 0xff
            val tagLen = p[pos + 1].toInt() and 0xff
            if (tagId == 0) {
                if (tagLen == 0) return null // SSID oculto
                return p.copyOfRange(pos + 2, pos + 2 + tagLen)
                    .toString(Charsets.ISO_8859_1)
            }
            pos += 2 + tagLen
        }
        return null
    }

    /** Payload EAPOL (após LLC/SNAP ethertype 0x888e), ou null. */
    private fun eapolPayload(p: ByteArray, subtype: Int): ByteArray? {
        var hdr = 24
        if (subtype in 8..9) hdr += 2 // QoS control
        if (p.size < hdr + 8) return null
        val llc = p.copyOfRange(hdr, hdr + 8)
        if (llc[0] != 0xaa.toByte() || llc[1] != 0xaa.toByte() || llc[2] != 3.toByte()) return null
        val ethertype = ((llc[6].toInt() and 0xff) shl 8) or (llc[7].toInt() and 0xff)
        if (ethertype != 0x888e) return null
        return p.copyOfRange(hdr + 8, p.size)
    }

    private fun mac(p: ByteArray, off: Int): String =
        p.copyOfRange(off, off + 6).joinToString(":") { "%02x".format(it) }.uppercase()

    private fun u16(p: ByteArray, off: Int): Int =
        (p[off].toInt() and 0xff) or ((p[off + 1].toInt() and 0xff) shl 8)

    /** u16 little-endian do buffer cru (len do radiotap). */
    private fun u16le(buf: ByteArray, off: Int): Int = u16(buf, off)
}
