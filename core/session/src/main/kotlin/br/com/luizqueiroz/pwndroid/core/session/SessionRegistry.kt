package br.com.luizqueiroz.pwndroid.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton compartilhado do estado da sessão: a UI e a futura web API
 * observam por aqui, sem conhecer o controlador (que vive no FGS).
 */
class SessionRegistry {
    private val _state = MutableStateFlow(SessionUiState())

    /** Fluxo de leitura: estado vazio quando o serviço não está vivo. */
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    /** Publicado pelo SessionController a cada mudança; limpo no stop. */
    fun publish(value: SessionUiState) {
        _state.value = value
    }
}
