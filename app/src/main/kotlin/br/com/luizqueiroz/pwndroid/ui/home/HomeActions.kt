package br.com.luizqueiroz.pwndroid.ui.home

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
 * config persistida. A troca de backend vale apenas na próxima sessão.
 */
data class HomeConfig(
    val mode: PwnMode,
    val backendPreference: BackendId?,
    val availableBackends: List<BackendId>,
)

