package br.com.luizqueiroz.pwndroid.core.radio

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendSelectorTest {

    private class NoClock : AppClock {
        override fun nowMillis(): Long = 0
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun env() = object : BackendEnvironment {
        override val clock: AppClock get() = NoClock()
        override val captureDir: String get() = "/tmp/pwndroid-test/captures"
    }

    /** Backend de mentira: start lança, simulando device sem suporte. */
    private class BrokenBackend(
        override val id: BackendId,
        override val capabilities: RadioCapabilities,
    ) : RadioBackend {
        override suspend fun start(env: BackendEnvironment): StartedBackend =
            throw BackendUnavailableException("sem suporte: $id")
    }

    /** Backend mínimo que sobe e lembra a própria identidade. */
    private class WorkingBackend(
        override val id: BackendId,
        override val capabilities: RadioCapabilities,
    ) : RadioBackend {

        /** Última instância iniciada, para o teste identificar quem foi escolhido. */
        lateinit var startedInstance: StartedTestBackend

        inner class StartedTestBackend : StartedBackend {
            override suspend fun startRecon(channels: Set<Int>, dwellMs: Long) = Unit
            override suspend fun stopRecon() = Unit
            override suspend fun accessPoints(): List<AccessPoint> = emptyList()
            override suspend fun assoc(ap: MacAddress): Result<Unit> = Result.success(Unit)
            override suspend fun deauth(sta: MacAddress?, ap: MacAddress): Result<Unit> = Result.success(Unit)
            override suspend fun setChannel(channel: Int, widthMhz: Int): Result<Unit> = Result.success(Unit)
            override fun events(): MutableSharedFlow<RadioEvent> = bus
            override suspend fun applyPersonality(p: Personality) = Unit
            override suspend fun shutdown() = Unit
        }

        private val bus = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 16)

        override suspend fun start(env: BackendEnvironment): StartedBackend {
            startedInstance = StartedTestBackend()
            return startedInstance
        }
    }

    @Test
    fun `escolhe o backend com mais capacidades entre os que sobem`() = runTest {
        val passive = WorkingBackend(BackendId.PASSIVE, RadioCapabilities(canRecon = true))
        val rich = WorkingBackend(
            BackendId.BETTERCAP,
            RadioCapabilities(
                canRecon = true, canAssoc = true, canDeauth = true,
                canCaptureEapol = true, canCapturePmkid = true, canSetChannel = true,
            ),
        )
        val selector = BackendSelector(listOf(passive, rich))
        val selected = selector.select(env())

        assertEquals(rich.startedInstance, selected)
    }

    @Test
    fun `backend quebrado não quebra a seleção`() = runTest {
        val broken = BrokenBackend(BackendId.NEXMON, RadioCapabilities(canRecon = true))
        val passive = WorkingBackend(BackendId.PASSIVE, RadioCapabilities(canRecon = true))
        val selector = BackendSelector(listOf(broken, passive))
        val selected = selector.select(env())

        assertEquals(passive.startedInstance, selected)
    }

    @Test
    fun `nenhum backend disponível devolve null`() = runTest {
        val selector = BackendSelector(listOf(BrokenBackend(BackendId.NEXMON, RadioCapabilities())))
        assertNull(selector.select(env()))
    }
}
