package br.com.luizqueiroz.pwndroid.core.capture

import java.io.File
import java.io.RandomAccessFile

/**
 * Escritor de PCAP no formato do pwnagotchi: linktype 105 (IEEE802_11),
 * nomes `ESSID_BSSID.pcap`, pronto para hashcat.
 */
class PcapWriter(private val dir: File) {

    init {
        dir.mkdirs()
    }

    /**
     * Cria/abre um PCAP para o alvo; nome segue o padrão `ESSID_BSSID.pcap`
     * (o ESSID sanitizado de `/` e `\`).
     */
    fun openFor(essid: String?, bssid: String): PcapFile {
        val safeEssid = (essid ?: "unknown").replace('/', '_').replace('\\', '_')
        val fileName = "${safeEssid}_${bssid.replace(":", "-").uppercase()}.pcap"
        val file = File(dir, fileName)
        return PcapFile(file)
    }

    /** Um PCAP individual, com o header global gravado no momento da criação. */
    class PcapFile internal constructor(private val file: File) {
        private val out = RandomAccessFile(file, "rw")

        init {
            if (out.length() == 0L) {
                out.write(GLOBAL_HEADER)
            }
        }

        /** Grava um frame 802.11 bruto com timestamp em µs. */
        fun writePacket(data: ByteArray, timestampMicros: Long) {
            val packetHeader = PcapPacketHeader(timestampMicros, data.size)
            out.write(packetHeader.encode())
            out.write(data)
        }

        /** Fecha o arquivo. */
        fun close() {
            out.close()
        }

        private class PcapPacketHeader(private val micros: Long, private val capturedLen: Int) {
            fun encode(): ByteArray {
                val buf = java.nio.ByteBuffer.allocate(16)
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.putInt((micros / 1_000_000).toInt()) // ts_sec
                buf.putInt((micros % 1_000_000).toInt()) // ts_usec
                buf.putInt(capturedLen) // incl_len
                buf.putInt(capturedLen) // orig_len
                return buf.array()
            }
        }

        private companion object {
            // PCAP global header: magic LE 0xa1b2c3d4, v2.4, thiszone 0,
            // sigfigs 0, snaplen 65535, linktype 105 (IEEE802_11).
            val GLOBAL_HEADER = byteArrayOf(
                0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte(),
                0x02, 0x00, 0x04, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0xff.toByte(), 0xff.toByte(), 0x00, 0x00,
                0x69, 0x00, 0x00, 0x00,
            )
        }
    }
}