package br.com.luizqueiroz.pwndroid.data

import br.com.luizqueiroz.pwndroid.data.db.HandshakeDao
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repositório de handshakes (issue #24): dedup e registro das capturas
 * do [HandshakeSlicer] — o "estoque de comida" do tamagotchi.
 *
 * Regra de dedup (chave `bssid+essid+type`):
 * - FULL existente → ignora HALF/PMKID do mesmo par (o FULL é o melhor
 *   formato; o índice único bloqueia a repetição do próprio FULL);
 * - PMKID existente → mantém (coexiste com FULL: formatos distintos
 *   para o crack; um HALF do mesmo par ainda entra, e o upgrade para
 *   FULL substitui o HALF na troca de prioridade abaixo).
 *
 * Retorno de [record]: `null` quando o dedup descartou (não é novo),
 * senão o registro persistido — o chamador decide alimentar o humor
 * (feedback "excited" no rosto) apenas de novo.
 */
class HandshakeRepository(private val db: PwnDatabase) {

    private val dao: HandshakeDao get() = db.handshakeDao()

    /** Resultado do registro: novo, upgrade (FULL substituiu) ou duplicado. */
    enum class RecordOutcome { NEW, DUPLICATE, UPGRADED }

    /**
     * Registra uma captura. Retorna [RecordOutcome] indicando se o
     * registro foi criado ([RecordOutcome.NEW]), descartado como
     * duplicado ([RecordOutcome.DUPLICATE]) ou se fez upgrade de um
     * HALF/PMKID existente para FULL ([RecordOutcome.UPGRADED]).
     */
    suspend fun record(capture: HandshakeEntity): RecordOutcome {
        val existing = dao.byKey(capture.bssid, capture.essid, capture.type)
        if (existing != null) {
            // Re-captura do mesmo tipo: atualiza path/timestamp, sem
            // duplicar registro (o arquivo do slicer é re-escrito).
            if (existing.pcapPath == capture.pcapPath) return RecordOutcome.DUPLICATE
            dao.update(existing.copy(pcapPath = capture.pcapPath, capturedAtMillis = capture.capturedAtMillis))
            return RecordOutcome.UPGRADED
        }

        // FULL novo: se o par já tem HALF ou PMKID, são substituídos
        // (o FULL é o melhor formato p/ crack; PMKID coexiste com HALF,
        // mas não sobrevive ao FULL).
        if (capture.type == TYPE_FULL && capture.essid != null) {
            val downgraded = dao.byKey(capture.bssid, capture.essid, TYPE_HALF)
            val pmkid = dao.byKey(capture.bssid, capture.essid, TYPE_PMKID)
            if (downgraded != null) dao.delete(downgraded.id)
            if (pmkid != null) dao.delete(pmkid.id)
            if (downgraded != null || pmkid != null) {
                dao.insert(capture)
                return RecordOutcome.UPGRADED
            }
        }

        // HALF/PMKID novo com FULL existente do mesmo par: descartado
        // (o FULL já é o melhor formato).
        if (capture.type != TYPE_FULL && dao.byKey(capture.bssid, capture.essid, TYPE_FULL) != null) {
            return RecordOutcome.DUPLICATE
        }

        val rowId = dao.insert(capture)
        return if (rowId == -1L) RecordOutcome.DUPLICATE else RecordOutcome.NEW
    }

    /** Lista da tela Handshakes. */
    fun observeAll(): Flow<List<HandshakeEntity>> = dao.observeAll()

    /** Todos (export/web API). */
    suspend fun all(): List<HandshakeEntity> = dao.all()

    /** Detalhe de um registro. */
    suspend fun byId(id: Long): HandshakeEntity? = dao.byId(id)

    /** Contagem de handshakes da sessão (contador da tela Home). */
    suspend fun countOfSession(sessionId: Long): Int = dao.countOfSession(sessionId)

    /** Marca o upload do plugin (flags da v0.3). */
    suspend fun markUploadedWpaSec(id: Long, uploaded: Boolean) =
        dao.setUploadedWpaSec(id, uploaded)

    /** Marca o upload do plugin onlinehashcracking (v0.3). */
    suspend fun setUploadedOnlineHashCracking(id: Long, uploaded: Boolean) =
        dao.setUploadedOnlineHashCracking(id, uploaded)

    companion object {
        /** Vocabulário de tipo persistido (mesmos valores do hcxtools). */
        const val TYPE_FULL = "FULL"
        const val TYPE_HALF = "HALF"
        const val TYPE_PMKID = "PMKID"
    }
}
