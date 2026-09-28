package br.com.luizqueiroz.pwndroid.ui.home

import br.com.luizqueiroz.pwndroid.core.brain.BrainSnapshot
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId

/** Ações da tela Home (agrupadas para manter a assinatura enxuta). */
data class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onModeSelected: (PwnMode) -> Unit,
    val onBackendSelected: (BackendId?) -> Unit,
)

/**
 * Config corrente exibida pela tela Home: modo e backend preferidos da
 * config persistida, mais o snapshot do cérebro em operação (issue #26).
 * A troca de backend vale apenas na próxima sessão.
 */
data class HomeConfig(
    val mode: PwnMode,
    val backendPreference: BackendId?,
    val availableBackends: List<BackendId>,
    /** Snapshot observável do Brain (id, persona, última época). */
    val brain: BrainSnapshot? = null,
)

