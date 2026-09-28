package br.com.luizqueiroz.pwndroid.core.capture

/**
 * Parser EAPOL-Key (issue #23): classificação das mensagens do 4-way
 * handshake e extração do PMKID do KDE do M1. Porte da lógica do
 * hcxtools/slicer do pwnagotchi.
 */
object EapolParser {

    /** Mensagens EAPOL-Key do 4-way handshake. */
    enum class Message { M1, M2, M3, M4 }

    /** Tipo do handshake extraído. */
    enum class HandshakeType { FULL, HALF, PMKID }

    /** Bits do campo Key Information (offset 5, big-endian). */
    private const val KEY_INFO_KEY_ACK = 0x0080
    private const val KEY_INFO_MIC = 0x0100
    private const val KEY_INFO_SECURE = 0x0200

    /** Offset do Key Data no EAPOL-Key (header completo de 95 bytes). */
    private const val KEY_DATA_OFFSET = 95

    /**
     * Classifica uma mensagem EAPOL-Key. [eapol] começa em Version;
     * **Key Information está no offset 5** (Version 1, Type 1, Length
     * 2, Descriptor 1, KeyInfo 2 — validado no golden wpa-Induction:
     * M1 0x008a, M2 0x010a, M3 0x13ca, M4 0x030a). Retorna null se o
     * frame não for EAPOL-Key reconhecível.
     */
    fun classify(eapol: ByteArray): Message? {
        if (eapol.size < 7) return null
        // Frame Type 0x03 = EAPOL-Key (offset 1 do EAPOL).
        if ((eapol[1].toInt() and 0xff) != 3) return null
        return messageOf(u16be(eapol, 5))
    }

    /** Tabela de decisão do 4-way pelos bits Key Information. */
    private fun messageOf(keyInfo: Int): Message? {
        val keyAck = keyInfo and KEY_INFO_KEY_ACK
        val mic = keyInfo and KEY_INFO_MIC
        val secure = keyInfo and KEY_INFO_SECURE
        return when {
            keyAck != 0 && mic == 0 -> Message.M1
            keyAck == 0 && mic != 0 && secure == 0 -> Message.M2
            keyAck != 0 && mic != 0 -> Message.M3
            keyAck == 0 && mic != 0 && secure != 0 -> Message.M4
            else -> null
        }
    }

    /**
     * Extrai o PMKID do KDE do M1 (WPA2: Key Data com KDEs; PMKID KDE =
     * `DD 14 00 0F AC 04` + 16 bytes — validado no golden frame 87).
     * Retorna não-null quando presente.
     */
    fun extractPmkid(eapol: ByteArray): ByteArray? {
        if (eapol.size < KEY_DATA_OFFSET + 6) return null
        val keyData = eapol.copyOfRange(KEY_DATA_OFFSET, eapol.size)
        var pos = 0
        while (pos + 2 <= keyData.size) {
            val kdeType = keyData[pos].toInt() and 0xff
            val kdeLen = keyData[pos + 1].toInt() and 0xff
            if (isPmkidKde(keyData, pos, kdeType, kdeLen)) {
                return keyData.copyOfRange(pos + 6, pos + 6 + 16)
            }
            pos += 2 + kdeLen
        }
        return null
    }

    /** KDE do PMKID: type `0xDD`, len ≥ 20, OUI 00-0F-AC, type 04. */
    private fun isPmkidKde(keyData: ByteArray, pos: Int, type: Int, len: Int): Boolean {
        if (type != 0xdd || len < 20 || pos + 6 + 16 > keyData.size) return false
        val oui = ((keyData[pos + 2].toInt() and 0xff) shl 16) or
            ((keyData[pos + 3].toInt() and 0xff) shl 8) or
            (keyData[pos + 4].toInt() and 0xff)
        return oui == 0x000fac && (keyData[pos + 5].toInt() and 0xff) == 0x04
    }

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
}
