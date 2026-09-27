package br.com.luizqueiroz.pwndroid.core.capture

/**
 * Analisador de EAPOL: reconhece mensagens M1..M4 e decide quando um
 * handshake está completo (M1+M2) ou um PMKID foi obtido (M1 com PMKID
 * no KDE). Porte da lógica do hcxtools/slicer do pwnagotchi.
 */
object HandshakeSlicer {

    /** Tipos de mensagem EAPOL-Key (bits Key Information). */
    const val EAPOL_KEY_INFO_OFFSET = 6

    /**
     * Resultado da análise de um frame EAPOL.
     */
    sealed interface Verdict {
        /** Mensagem reconhecida, sem handshake completo ainda. */
        data class Partial(val message: Message) : Verdict

        /** Handshake M1+M2 completo (half handshake suficiente para hashcat). */
        data class Complete(val isPmkidOnly: Boolean) : Verdict

        /** Frame não é EAPOL. */
        object NotEapol : Verdict
    }

    /** Mensagens EAPOL-Key do 4-way handshake. */
    enum class Message { M1, M2, M3, M4 }

    /**
     * Classifica um frame a partir do payload EAPOL (sem o header 802.11).
     * [eapolFrame] deve começar no campo Version do EAPOL (offset 0).
     */
    fun classify(eapolFrame: ByteArray): Verdict {
        if (eapolFrame.size < EAPOL_KEY_INFO_OFFSET + 4) return Verdict.NotEapol
        // Frame Type 0x03 = EAPOL-Key.
        val frameType = eapolFrame[1].toInt() and 0xff
        if (frameType != 0x03) return Verdict.NotEapol

        // Key Information: 2 bytes big-endian em offset 6 (layout hcxtools
        // simplificado).
        val keyInfo = ((eapolFrame[6].toInt() and 0xff) shl 8) or (eapolFrame[7].toInt() and 0xff)
        val key = messageOf(keyInfo) ?: return Verdict.NotEapol
        // TODO(issue #11): detectar PMKID no KDE do M1 e rastrear pares M1/M2.
        return Verdict.Partial(key)
    }

    private fun messageOf(keyInfo: Int): Message? = when {
        hasBit(keyInfo, KEY_INFO_KEY_ACK) && !hasBit(keyInfo, KEY_INFO_MIC) -> Message.M1
        !hasBit(keyInfo, KEY_INFO_KEY_ACK) &&
            hasBit(keyInfo, KEY_INFO_MIC) &&
            !hasBit(keyInfo, KEY_INFO_SECURE) -> Message.M2
        hasBit(keyInfo, KEY_INFO_KEY_ACK) &&
            hasBit(keyInfo, KEY_INFO_MIC) &&
            !hasBit(keyInfo, KEY_INFO_SECURE) -> Message.M3
        !hasBit(keyInfo, KEY_INFO_KEY_ACK) &&
            hasBit(keyInfo, KEY_INFO_MIC) &&
            hasBit(keyInfo, KEY_INFO_SECURE) -> Message.M4
        else -> null
    }

    private fun hasBit(value: Int, bit: Int) = (value and bit) != 0

    private const val KEY_INFO_KEY_ACK = 0x0080
    private const val KEY_INFO_MIC = 0x0100
    private const val KEY_INFO_SECURE = 0x2000
}
