package br.com.luizqueiroz.pwndroid.data

import br.com.luizqueiroz.pwndroid.core.brain.ArmCounts
import br.com.luizqueiroz.pwndroid.core.brain.ArmStore
import br.com.luizqueiroz.pwndroid.data.db.BrainArmDao
import br.com.luizqueiroz.pwndroid.data.db.BrainArmEntity

/**
 * [ArmStore] sobre a tabela `brain_arm` (Room, issue #27): persiste α/β
 * por (cérebro, param, value) — sobrevive a restarts do processo. O
 * `updatedAtMillis` é 0 aqui: o carimbo do DB não alimenta decisão nenhuma
 * (o decay é por época, não por relógio).
 */
class BrainArmRepository(private val dao: BrainArmDao) : ArmStore {

    override suspend fun armsOf(brainId: String, param: String): List<ArmCounts> =
        dao.armsOf(brainId, param).map { ArmCounts(it.value, it.alpha, it.beta) }

    override suspend fun upsert(brainId: String, param: String, value: String, alpha: Double, beta: Double) {
        dao.upsert(
            BrainArmEntity(
                brainId = brainId,
                param = param,
                value = value,
                alpha = alpha,
                beta = beta,
                updatedAtMillis = 0L,
            ),
        )
    }

    override suspend fun updateCounts(brainId: String, param: String, value: String, alpha: Double, beta: Double) {
        dao.updateCounts(brainId, param, value, alpha, beta, now = 0L)
    }
}
