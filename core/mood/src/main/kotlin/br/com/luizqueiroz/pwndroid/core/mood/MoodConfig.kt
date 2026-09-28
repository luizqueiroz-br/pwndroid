package br.com.luizqueiroz.pwndroid.core.mood

/**
 * Configuração da máquina de humores — espelha a seção `personality` do
 * `defaults.toml` do pwnagotchi original (issue #11).
 */
data class MoodConfig(
    /** Épocas inativas até BORED. */
    val boredNumEpochs: Int = 15,
    /** Épocas inativas até SAD (sad > bored; um exclui o outro). */
    val sadNumEpochs: Int = 25,
    /** Épocas ativas consecutivas até EXCITED. */
    val excitedNumEpochs: Int = 10,
    /** Interações perdidas (assoc/deauth para alvo sumido) até stale. */
    val maxMissesForRecon: Int = 5,
    /** Escala de vínculo: total_encounters/bond_encounters_factor ≥ fator. */
    val bondEncountersFactor: Int = 20_000,
)
