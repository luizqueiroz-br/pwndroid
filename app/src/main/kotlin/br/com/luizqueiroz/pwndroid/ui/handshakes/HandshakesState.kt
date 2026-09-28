package br.com.luizqueiroz.pwndroid.ui.handshakes

import br.com.luizqueiroz.pwndroid.data.HandshakeRepository
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Estado da tela Handshakes (issue #24): expõe o Flow do repositório
 * e o caminho do pcap para export (SAF/share sheet, mesmo padrão do
 * export CSV/KML da tela Wardrive).
 */
class HandshakesState(private val repo: HandshakeRepository) {

    /** Handshakes capturados, mais recentes primeiro. */
    fun handshakes(): Flow<List<HandshakeEntity>> = repo.observeAll()

    /** Detalhe de um registro (dialog de export). */
    suspend fun detail(id: Long): HandshakeEntity? = repo.byId(id)

    /** Rótulo curto do tipo (FULL/HALF/PMKID), cor por tipo na tela. */
    fun typeLabel(type: String): String = when (type) {
        HandshakeRepository.TYPE_FULL -> "FULL"
        HandshakeRepository.TYPE_PMKID -> "PMKID"
        else -> "HALF"
    }

    /** Verifica se o pcap do registro ainda existe no disco. */
    fun pcapExists(pcapPath: String): Boolean = File(pcapPath).exists()
}
