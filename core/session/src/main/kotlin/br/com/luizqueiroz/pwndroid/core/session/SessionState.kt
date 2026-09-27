package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.model.Target

/**
 * Estado observável da sessão, publicado como StateFlow para a UI e o
 * serviço em primeiro plano.
 */
data class SessionState(
    /** Modo de operação atual. */
    val mode: PwnMode = PwnMode.AUTO,

    /** Número da época em curso (0 = sessão não iniciada). */
    val epoch: Long = 0,

    /** Fase do ciclo dentro da época. */
    val phase: EpochPhase = EpochPhase.IDLE,

    /** Alvos do recon atual (após filtros). */
    val candidates: List<Target> = emptyList(),

    /** Handshakes completos capturados nesta sessão. */
    val handshakes: Int = 0,

    /** PMKIDs capturados nesta sessão. */
    val pmkids: Int = 0,

    /** Épocas cegas consecutivas (nenhum AP visto). */
    val blindEpochs: Int = 0,

    /** Sessão parada pelo orquestrador (ex.: max_blind_epochs). */
    val stopped: Boolean = false,

    /** Motivo da parada, quando [stopped]. */
    val stopReason: String? = null,
)

/** Fase do ciclo de época, visível para a UI. */
enum class EpochPhase { IDLE, RECON, INTERACT, REPORT }
