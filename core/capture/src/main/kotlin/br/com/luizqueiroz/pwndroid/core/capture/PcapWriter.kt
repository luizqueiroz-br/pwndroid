package br.com.luizqueiroz.pwndroid.core.capture

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Escritor de PCAP (issue #23): global header com **linktype 105**
 * (IEEE802_11, igual ao bettercap/pwnagotchi), records little-endian,
 * nomes `ESSID_BSSID.pcap` compatíveis com hashcat/hcxpcapngtool.
 */
class PcapWriter(private val dir: File) {

    init {
        require(dir.mkdirs() || dir.isDirectory) { "não foi possível criar $dir" }
    }

    /**
     * Abre (cria ou anexa) o PCAP de um alvo. Nome no padrão do
     * original: `ESSID_BSSID.pcap`, com o ESSID sanitizado; ESSID
     * oculto (null/vazio) cai no fallback `<BSSID>.pcap`.
     */
    fun openFor(essid: String?, bssid: String): PcapFile {
        val file = File(dir, fileNameFor(essid, bssid))
        return PcapFile(file)
    }

    companion object {

        /** Linktype IEEE802_11 (radiotap vem como pseudo-header dos frames). */
        const val LINKTYPE_IEEE802_11 = 105

        /**
         * Nome do arquivo do alvo: `ESSID_BSSID.pcap`. ESSID oculto ou
         * sem caracteres úteis cai no fallback `<BSSID>.pcap`, mesma
         * regra do original (regex `[0-9a-fA-F]{12}` — o pwnagotchi
         * sanitiza o nome para `[0-9a-fA-F]` e, sem ESSID visível, o
         * resultado é o MAC puro).
         */
        fun fileNameFor(essid: String?, bssid: String): String {
            val sanitized = sanitizeEssid(essid)
            val bssidHex = bssid.replace(":", "").replace("-", "").uppercase()
            // Sanitização vazia (ESSID oculto/vazio): fallback é o
            // próprio BSSID em hex. ESSID legítimo — mesmo 12 hex —
            // é mantido.
            val prefix = sanitized.ifBlank { bssidHex }
            return "${prefix}_$bssidHex.pcap"
        }

        /**
         * Sanitiza o ESSID para nome de arquivo (mesma regra do
         * pwnagotchi: mantém alfanumérico, ponto, hífen e underscore;
         * o resto vira `_` — espaços múltiplos colapsam).
         */
        fun sanitizeEssid(essid: String?): String {
            val cleaned = (essid ?: "")
                .replace(Regex("[^0-9A-Za-z._\\- ]"), "_")
                .replace(Regex(" +"), " ")
                .trim()
            return cleaned.ifBlank { "" }
        }
    }

    /** Um PCAP individual: global header na criação, records em seguida. */
    class PcapFile internal constructor(private val file: File) : AutoCloseable {

        private val out: OutputStream = FileOutputStream(file, true)

        init {
            if (file.length() == 0L) {
                out.write(GLOBAL_HEADER)
            }
        }

        /** Grava um frame (radiotap+802.11) com timestamp em microssegundos. */
        fun writePacket(data: ByteArray, timestampMicros: Long) {
            val header = ByteArray(16)
            val sec = (timestampMicros / 1_000_000L).toLong()
            val usec = timestampMicros % 1_000_000L
            writeUint32(header, 0, sec)
            writeUint32(header, 4, usec)
            writeUint32(header, 8, data.size)
            writeUint32(header, 12, data.size)
            out.write(header)
            out.write(data)
        }

        /** Grava um frame com o timestamp de agora. */
        fun writePacket(data: ByteArray) {
            writePacket(data, System.currentTimeMillis() * 1000)
        }

        /** Número de frames gravados neste arquivo (para testes/logs). */
        fun packetCount(): Long {
            var total = 0L
            file.inputStream().use { input ->
                // Pula o global header.
                input.skip(24)
                val header = ByteArray(16)
                // Um único break: o read do header + do frame em cada
                // iteração retorna negativo/insuficiente no fim do
                // arquivo.
                while (input.read(header) == 16) {
                    val incl = readUint32(header, 8)
                    val data = ByteArray(incl)
                    if (input.readFullyCompat(data) < incl) break
                    total++
                }
            }
            return total
        }

        /** readFully que retorna bytes lidos (0 se EOF antes do 1º byte). */
        private fun java.io.InputStream.readFullyCompat(buf: ByteArray): Int {
            var read = 0
            while (read < buf.size) {
                val n = read(buf, read, buf.size - read)
                if (n < 0) return read
                read += n
            }
            return read
        }

        override fun close() {
            out.close()
        }

        /** Caminho do arquivo .pcap no disco. */
        fun path(): String = file.absolutePath

        private fun writeUint32(buf: ByteArray, off: Int, v: Int) {
            writeUint32(buf, off, v.toLong() and 0xffffffffL)
        }

        private fun writeUint32(buf: ByteArray, off: Int, v: Long) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v shr 8) and 0xff).toByte()
            buf[off + 2] = ((v shr 16) and 0xff).toByte()
            buf[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        private fun readUint32(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xff) or
                ((buf[off + 1].toInt() and 0xff) shl 8) or
                ((buf[off + 2].toInt() and 0xff) shl 16) or
                ((buf[off + 3].toInt() and 0xff) shl 24)

        private companion object {
            // Global header LE: magic 0xa1b2c3d4, v2.4, thiszone 0,
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

