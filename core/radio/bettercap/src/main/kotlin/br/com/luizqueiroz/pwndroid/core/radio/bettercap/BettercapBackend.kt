package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * Backend bettercap completo (issue #21): shell root + binário (#19) +
 * processo bettercap lançado via su + API REST/WS (#20) amarrada na
 * interface [StartedBackend]. O [start] valida o bootstrap (root +
 * binário executável), lança o processo com `-iface mon0 -api-rest`,
 * espera a API subir e devolve o [BettercapStarted] com as operações
 * de rádio de verdade. Sem root, lança [BackendUnavailableException]
 * com causa user-actionable e o selector degrada para o passivo.
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
    private val clock: AppClock,
    private val logger: AppLogger? = null,
    /**
     * Fábrica da API REST/WS (injetável para testes com MockEngine/fake);
     * default: Ktor CIO com plugin WS contra o localhost do device.
     */
    private val apiFactory: (host: String, port: Int, user: String, pass: String) -> BettercapApi =
        { host, port, user, pass ->
            HttpBettercapApi(
                baseUrl = "http://$host:$port",
                username = user,
                password = pass,
                client = HttpClient(CIO) { install(WebSockets) },
            )
        },
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
        val api = apiFactory(HOST, PORT, API_USERNAME, API_PASSWORD)
        launchProcess(installed, setupMonitorInterface(), env.captureDir)
        waitForApi(api)
        return BettercapStarted(
            api = api,
            shell = shell,
            pcapDir = env.captureDir,
            clock = clock,
            logger = logger,
            events = MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
        ).also { it.beginEvents() }
    }

    /**
     * Lança o processo bettercap via su em background (nohup + &, o exec
     * volta na hora) com a API REST no localhost — a UI/orquestrador fala
     * com ele pela [BettercapApi], não via shell.
     *
     * Flags validadas no spike #22 (bettercap v2.41.7): não existem
     * flags `-api-rest*` — a API sobe via `-eval` com `set api.rest.*`
     * (endereço aceita só IP; a porta é `api.rest.port`) e
     * `api.rest.websocket true` é obrigatório para o `/api/events`
     * virar WebSocket (default: streaming HTTPS de um array de eventos).
     */
    private suspend fun launchProcess(binaryPath: String, iface: String, captureDir: String) {
        val logFile = "${BettercapInstaller.TARGET_DIR}/bettercap.log"
        val handshakesFile = "$captureDir/handshakes"
        val eval = BettercapBackend.evalArgs(handshakesFile = handshakesFile)
        val launch = shell.exec(
            "nohup '$binaryPath' -iface '$iface' -no-colors -no-history " +
                "-eval \"$eval\" " +
                ">> '$logFile' 2>&1 &",
        )
        if (!launch.ok) {
            throw BackendUnavailableException(
                "falha ao iniciar o bettercap: ${launch.err.joinToString("; ")}",
            )
        }
    }

    /**
     * Interface monitor via root (issue #21): `iw dev <base> interface add
     * mon0 type monitor` + `ip link set mon0 up`. Chipset sem suporte
     * (não-Nexmon/QCACLD) degrada para a interface base — o bettercap
     * coloca a iface em monitor por conta própria quando possível.
     */
    private suspend fun setupMonitorInterface(): String {
        val base = shell.exec("iw dev 2>/dev/null | grep 'Interface'").out
            .firstOrNull { it.trim().startsWith("Interface ") }
            ?.removePrefix("\tInterface ")?.trim()
            ?: return DEFAULT_IFACE
        val add = shell.exec("iw dev '$base' interface add mon0 type monitor 2>&1")
        if (!add.ok) return base
        val up = shell.exec("ip link set mon0 up 2>&1")
        if (!up.ok) {
            shell.exec("iw dev mon0 del 2>/dev/null")
            return base
        }
        return "mon0"
    }

    /** Espera a API REST subir (o processo leva segundos para abrir a porta). */
    private suspend fun waitForApi(api: BettercapApi) {
        repeat(API_PROBES) {
            if (api.session().isSuccess) return
            delay(API_PROBE_INTERVAL_MS)
        }
        throw BackendUnavailableException(
            "API REST do bettercap não respondeu após a instalação " +
                "(log: ${BettercapInstaller.TARGET_DIR}/bettercap.log)",
        )
    }

    companion object {
        /** API REST escuta no localhost do device (segura: sem rede exposta). */
        const val HOST = "127.0.0.1"

        /** Porta da API REST (acima de 1024 — sem privilégio extra). */
        const val PORT = 8081

        /** Credenciais da API local (localhost-only, geradas por sessão na #21+). */
        const val API_USERNAME = "pwndroid"

        /** Senha da API local. */
        const val API_PASSWORD = "pwndroid"

        /** Interface monitor padrão quando `iw dev` não responde. */
        const val DEFAULT_IFACE = "wlan0"

        /** Sondas da API antes de desistir. */
        const val API_PROBES = 20

        /** Intervalo entre sondas (ms). */
        const val API_PROBE_INTERVAL_MS = 500L

        /**
         * Comando `-eval` do bettercap para subir a API REST no
         * localhost (spike #22: flags `-api-rest*` não existem na
         * v2.41; `api.rest.websocket true` é obrigatório para o
         * `/api/events` ser WebSocket).
         */
        fun evalArgs(
            host: String = HOST,
            port: Int = PORT,
            username: String = API_USERNAME,
            password: String = API_PASSWORD,
            handshakesFile: String,
        ): String = listOf(
            "set api.rest.address $host",
            "set api.rest.port $port",
            "set api.rest.username '$username'",
            "set api.rest.password '$password'",
            "set api.rest.websocket true",
            "set wifi.handshakes.file '$handshakesFile'",
            "api.rest on",
        ).joinToString("; ")
    }
}

/**
 * Backend bettercap em operação (issue #21): amarra a [BettercapApi]
 * REST/WS da #20 na interface [StartedBackend] — recon (`wifi.recon on`,
 * canal + hop), associação (`wifi.assoc`), deauth (`wifi.deauth`),
 * snapshot do recon (`GET /api/session` → domínio) e eventos WS
 * re-publicados no [SharedFlow] (via [BettercapEventStream]). Falha de
 * comando REST volta como `Result.failure` — o orquestrador decide.
 */
// A interface StartedBackend define exatamente estas operações: o limite
// de funções por classe não se aplica a implementações de contrato.
@Suppress("TooManyFunctions")
internal class BettercapStarted(
    private val api: BettercapApi,
    private val shell: RootShell,
    private val pcapDir: String,
    private val clock: AppClock,
    private val logger: AppLogger? = null,
    private val events: MutableSharedFlow<RadioEvent>,
) : StartedBackend {

    override val backendId: BackendId = BackendId.BETTERCAP

    /** Escopo do consumidor WS — cancelado no shutdown. */
    private val scope = CoroutineScope(SupervisorJob() + clock.default)

    private var eventCollector: Job? = null

    override suspend fun startRecon(channels: Set<Int>, dwellMs: Long) {
        val channelCmd = if (channels.isEmpty()) {
            "wifi.recon.channel clear" // hop em todos os canais
        } else {
            "wifi.recon.channel ${channels.sorted().joinToString(",")}"
        }
        api.run(channelCmd).getOrThrow()
        api.run("set wifi.hop.period $dwellMs").getOrThrow()
        api.run("wifi.recon on").getOrThrow()
        events.tryEmit(RadioEvent.ReconStarted(channels.sorted()))
    }

    override suspend fun stopRecon() {
        runCatching { api.run("wifi.recon off") }
    }

    override suspend fun accessPoints(): List<AccessPoint> {
        val session = api.session().getOrThrow()
        return SessionMapper.toDomainAccessPoints(session, clock.nowMillis())
    }

    /** `wifi.assoc BSSID` (colhe PMKID do M1 — doc do módulo wifi). */
    override suspend fun assoc(ap: MacAddress): Result<Unit> =
        api.run("wifi.assoc ${ap.format()}")

    /** `wifi.deauth STA|BSSID` (broadcast quando [sta] é null). */
    override suspend fun deauth(sta: MacAddress?, ap: MacAddress): Result<Unit> =
        if (sta != null) api.run("wifi.deauth ${sta.format()}") else api.run("wifi.deauth ${ap.format()}")

    override suspend fun setChannel(channel: Int, widthMhz: Int): Result<Unit> =
        api.run("wifi.recon.channel $channel").onSuccess {
            events.tryEmit(RadioEvent.ChannelChanged(channel))
        }

    override fun events(): SharedFlow<RadioEvent> = events.asSharedFlow()

    /**
     * Parâmetros dirigidos pela personalidade da época (doc do módulo
     * wifi): `wifi.ap.ttl`, `wifi.sta.ttl` e `wifi.rssi.min`.
     */
    override suspend fun applyPersonality(p: Personality) {
        api.run("set wifi.ap.ttl ${p.apTtlSec}").getOrThrow()
        api.run("set wifi.sta.ttl ${p.staTtlSec}").getOrThrow()
        api.run("set wifi.rssi.min ${p.minRssi}").getOrThrow()
    }

    override suspend fun shutdown() {
        // Cancela o consumidor WS antes do "wifi.recon off" — o finally
        // do collector não emite BackendError em desligamento limpo.
        scope.cancel()
        runCatching { api.run("wifi.recon off") }
        api.close()
        shell.close()
    }

    /**
     * Inicia o consumidor WS (issue #21): [BettercapEventStream] reconecta
     * com backoff e re-publica os eventos no [SharedFlow]; esgotadas as
     * tentativas, emite [RadioEvent.BackendError] (o orquestrador decide
     * degradar).
     */
    fun beginEvents() {
        if (eventCollector?.isActive == true) return
        eventCollector = scope.launch {
            val stream = BettercapEventStream(api, pcapDir, logger)
            try {
                stream.radioEvents().collect { events.tryEmit(it) }
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // cancelamento do collector: silencioso
            } finally {
                if (scope.isActive) {
                    events.tryEmit(
                        RadioEvent.BackendError(
                            "stream de eventos bettercap encerrou (reconexões esgotadas)",
                        ),
                    )
                }
            }
        }
    }
}
