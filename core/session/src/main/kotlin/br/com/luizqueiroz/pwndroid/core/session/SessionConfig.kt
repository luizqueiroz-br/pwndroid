package br.com.luizqueiroz.pwndroid.core.session

/**
 * Configuração da sessão — porte de `main.filter`, `whitelist` e
 * `agent.mon_max_blind_epochs` do pwnagotchi.
 */
data class SessionConfig(
    /** Regex sobre o ESSID; APs sem match são ignorados (null = tudo). */
    val essidFilter: Regex? = null,

    /** ESSIDs que nunca são alvo (redes autorizadas do dono). */
    val whitelist: Set<String> = emptySet(),

    /** Épocas cegas consecutivas toleradas antes de parar a sessão. */
    val maxBlindEpochs: Int = 2,

    /** Multiplicador da janela de recon quando o rádio está cego. */
    val reconInactiveMultiplier: Float = 1.0f,
) {
    init {
        require(maxBlindEpochs >= 0) { "maxBlindEpochs não pode ser negativo" }
        require(reconInactiveMultiplier >= 0f) { "reconInactiveMultiplier não pode ser negativo" }
    }
}
