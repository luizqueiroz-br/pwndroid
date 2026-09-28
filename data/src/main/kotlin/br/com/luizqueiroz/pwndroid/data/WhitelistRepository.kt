package br.com.luizqueiroz.pwndroid.data

import br.com.luizqueiroz.pwndroid.data.db.WhitelistDao
import br.com.luizqueiroz.pwndroid.data.db.WhitelistEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Whitelist ética (issue #18): APs excluídos de interação — "não atacar
 * a própria casa". Adição/remoção pela UI do AP na tela Wardrive.
 */
class WhitelistRepository(private val dao: WhitelistDao) {

    /** Adiciona (ou mantém a entrada existente, com IGNORE no conflito). */
    suspend fun add(mac: String, note: String?, addedAt: Long) =
        dao.insert(WhitelistEntryEntity(mac, addedAt, note))

    suspend fun remove(mac: String) = dao.remove(mac)

    /** MACs para os filtros do orquestrador. */
    suspend fun macs(): List<String> = dao.macs()

    fun observeAll(): Flow<List<WhitelistEntryEntity>> = dao.observeAll()
}
