package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.BackendUnavailableException
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import br.com.luizqueiroz.pwndroid.core.radio.RadioCapabilities
import br.com.luizqueiroz.pwndroid.core.radio.StartedBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Estado do ambiente root no device, coletado no start do backend
 * (issue #19): disponibilidade de su, arquitetura e SELinux — suficiente
 * para o [BackendSelector] degradar para o passivo com causa clara.
 */
data class RootEnvironment(
    val rootAvailable: Boolean,
    val abi: String?,
    val selinuxEnforcing: Boolean,
) {
    /** Mensagem user-actionable quando o root não está disponível. */
    fun unavailableReason(): String = when {
        !rootAvailable -> "sem root neste device (Magisk não disponível?)"
        abi == null -> "arquitetura do device não suportada"
        else -> "root disponível"
    }
}

/**
 * Backend bettercap — shell root e binário (issue #19). A API REST/WS
 * (recon/assoc/deauth de verdade) é a #20/#21; por ora o backend declara
 * as capacidades de injeção que o bettercap oferecerá e o [start] valida
 * o bootstrap (root + binário executável). Se o root não subir, lança
 * [BackendUnavailableException] com causa user-actionable e o selector
 * degrada para o backend passivo sem crash.
 */
class BettercapBackend(
    private val shell: RootShell,
    private val installer: BettercapInstaller,
    /**
     * Binário bettercap extraído do APK (jniLibs → nativeLibraryDir);
     * o :app resolve o caminho com o Context — o módulo :core:radio não
     * conhece Android.
     */
    private val apkBinaryProvider: () -> java.io.File,
) : RadioBackend {

    override val id: BackendId = BackendId.BETTERCAP
    override val capabilities = RadioCapabilities(
        canRecon = true,
        canAssoc = true,
        canDeauth = true,
        canCaptureEapol = true,
        canCapturePmkid = true,
        canSetChannel = true,
        canSeePeers = false, // pwngrid/p2p via oPwngrid (#36), não via bettercap
        canBle = false,
    )

    override suspend fun start(env: BackendEnvironment): StartedBackend {
        // Root disponível? (su responde; sem isso tudo aqui é impossível)
        if (!shell.isRootAvailable()) {
            throw BackendUnavailableException(
                "sem root neste device — bettercap precisa de su (Magisk não disponível?)",
            )
        }
        // ABI compatível? (falha rápido com erro user-actionable)
        installer.selectAbi()
        // Binário do APK (jniLibs, extraído para nativeLibraryDir) →
        // /data/local/tmp via su (cp + chmod 755).
        val installed = installer.install(apkBinaryProvider(), BettercapInstaller.TARGET_BINARY)
        val started = BettercapStarted(
            shell = shell,
            binaryPath = installed,
            events = MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
        )
        // Verificação final: `bettercap -version` via su. Falha com
        // SELinux em Enforcing é user-actionable (critério da #19).
        if (!installer.verifyBinary(installed)) {
            if (installer.selinuxEnforcing()) {
                throw RootUnavailableException.SelinuxBlocked(
                    "teste: `su -c \"setenforce 0\"` ou use o modo passivo",
                )
            }
            throw BackendUnavailableException(
                "bettercap instalado mas não executou (-version falhou)",
            )
        }
        return started
    }
}

/**
 * Backend bettercap em operação (issue #19): na v0.2 inicial só o shell
 * e o binário estão prontos — recon/assoc/deauth de verdade chegam com a
 * API REST/WS da #20. As operações de rádio retornam falha "não
 * conectado ao bettercap ainda" (não ImplementError) para o
 * orquestrador tratar como backend sem capacidade.
 */
// A interface StartedBackend define exatamente estas operações: o limite
// de funções por classe não se aplica a implementações de contrato.
@Suppress("TooManyFunctions")
internal class BettercapStarted(
    private val shell: RootShell,
    val binaryPath: String,
    private val events: kotlinx.coroutines.flow.MutableSharedFlow<RadioEvent>,
) : StartedBackend {

    override val backendId: BackendId = BackendId.BETTERCAP

    override suspend fun startRecon(channels: Set<Int>, dwellMs: Long) {
        // API REST/WS da #20; na #19 o recon de verdade não existe ainda.
        opNotAvailableYet("recon")
    }

    override suspend fun stopRecon() = Unit

    override suspend fun accessPoints(): List<AccessPoint> {
        opNotAvailableYet("accessPoints")
    }

    override suspend fun assoc(ap: MacAddress): Result<Unit> = opFailed("assoc")

    override suspend fun deauth(sta: MacAddress?, ap: MacAddress): Result<Unit> = opFailed("deauth")

    override suspend fun setChannel(channel: Int, widthMhz: Int): Result<Unit> = opFailed("setChannel")

    override fun events(): SharedFlow<RadioEvent> = events.asSharedFlow()

    override suspend fun applyPersonality(p: Personality) = Unit

    override suspend fun shutdown() {
        shell.close()
    }

    /** Ops de rádio exigem a API REST/WS (#20): falha clara, não crash. */
    private fun opNotAvailableYet(op: String): Nothing =
        throw BackendUnavailableException(
            "operação '$op' chega com a API REST/WS do bettercap (issue #20)",
        )

    private fun opFailed(op: String): Result<Unit> =
        Result.failure(BackendUnavailableException("'$op' exige a API REST/WS do bettercap (issue #20)"))
}
