package br.com.luizqueiroz.pwndroid.ui.wardrive

import br.com.luizqueiroz.pwndroid.data.WardriveRepository
import br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Estado da tela Wardrive (issue #18): expõe o Flow do repositório para
 * a UI e executa whitelist/export em IO.
 */
class WardriveState(
    private val repo: WardriveRepository,
    private val whitelist: br.com.luizqueiroz.pwndroid.data.WhitelistRepository,
) {
    /** Snapshot do detalhe de um AP (dialog). */
    data class Detail(
        val ap: br.com.luizqueiroz.pwndroid.data.db.AccessPointEntity,
        val sightings: List<br.com.luizqueiroz.pwndroid.data.db.SightingEntity>,
    )
    /** APs com meta, ordenados por recência. */
    fun aps(): Flow<List<AccessPointWithMeta>> = repo.observeAccessPoints()

    /** MACs na whitelist (Flow reativo: dialog reflete remoção/adição). */
    fun whitelistMacs(): Flow<Set<String>> =
        whitelist.observeAll().map { entries -> entries.map { it.mac }.toSet() }

    /** Detalhe de um AP (histórico completo de sightings). */
    suspend fun detail(mac: String): WardriveRepository.AccessPointDetail? =
        repo.accessPointDetail(mac)

    /** Toggle de whitelist ética. */
    suspend fun toggleWhitelist(mac: String, currentlyWhitelisted: Boolean) {
        if (currentlyWhitelisted) {
            whitelist.remove(mac)
        } else {
            whitelist.add(mac, note = null, addedAt = System.currentTimeMillis())
        }
    }

    /** (CSV WiGLE, KML) do histórico completo — chamado do callback de export. */
    suspend fun exportAll(): Pair<String, String> = repo.exportAllCsvKml()
}
