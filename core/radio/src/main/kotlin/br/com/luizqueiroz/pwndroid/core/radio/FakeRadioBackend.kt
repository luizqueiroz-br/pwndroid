package br.com.luizqueiroz.pwndroid.core.radio

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.model.Station
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Fake scriptável do backend de rádio: realista o suficiente para o
 * EpochOrchestrator (#8) não distinguir de um backend real. O teste escreve
 * o roteiro com o builder fluente e o fake obedece — eventos na ordem
 * programada, APs no snapshot e operações falhando quando programadas.
 */
class FakeRadioBackend(
    private val clock: AppClock,
) : RadioBackend {

    override val id: BackendId = BackendId.FAKE
    override val capabilities: RadioCapabilities = RadioCapabilities(
        canRecon = true,
        canAssoc = true,
        canDeauth = true,
        canCaptureEapol = true,
        canCapturePmkid = true,
        canSetChannel = true,
        canSeePeers = true,
    )

    /** Log de tudo o que o orquestrador pediu, para asserções do teste. */
    val operations = mutableListOf<String>()

    // --- Estado interno ---------------------------------------------------

    private val bus = MutableSharedFlow<RadioEvent>(replay = 64, extraBufferCapacity = 256)
    private val aps = linkedMapOf<String, AccessPoint>()
    private val failedAssoc = mutableSetOf<String>()
    private val failedDeauth = mutableSetOf<String>()
    private val failedChannel = mutableSetOf<Int>()
    private var personality: Personality = Personality()
    private var started = false
    private var reconRunning = false
    private var currentChannel = 0

    // --- Builder fluente ---------------------------------------------------

    /** Programação feita antes de `backend.start(env)`; encadeável. */
    val script = Script()

    inner class Script {
        /** AP visível no snapshot de `accessPoints()`; emite ApSeen. */
        fun ap(
            bssid: String,
            ssid: String? = null,
            channel: Int = 1,
            rssi: Int = -50,
            clients: List<Station> = emptyList(),
            at: Long = -1L,
        ): Script {
            val atMillis = if (at < 0) clock.nowMillis() else at
            val mac = MacAddress.parse(bssid)
            aps[bssid.lowercase()] = AccessPoint(
                mac = mac,
                ssid = ssid,
                rssi = rssi,
                channel = channel,
                frequencyMhz = channelToFrequency(channel),
                clients = clients,
                firstSeen = atMillis,
                lastSeen = atMillis,
            )
            bus.tryEmit(RadioEvent.ApSeen(ssid, mac.format(), channel, rssi))
            return this
        }

        /** Estação vista junto a um AP. */
        fun station(bssid: String, sta: String, rssi: Int = -60): Script {
            val ap = aps[bssid.lowercase()]
            requireNotNull(ap) { "programme o AP $bssid antes de sua estação" }
            val staMac = MacAddress.parse(sta)
            aps[bssid.lowercase()] = ap.copy(clients = ap.clients + Station(staMac, rssi, ap.mac))
            bus.tryEmit(RadioEvent.StationSeen(staMac.format(), ap.mac.format(), rssi))
            return this
        }

        /** Handshake capturado (com path de PCAP fictício). */
        fun handshake(bssid: String, sta: String, pmkid: Boolean = false): Script {
            val mac = MacAddress.parse(bssid)
            bus.tryEmit(
                RadioEvent.HandshakeDetected(
                    bssid = mac.format(),
                    station = MacAddress.parse(sta).format(),
                    essid = aps[bssid.lowercase()]?.ssid,
                    pcapPath = "/tmp/fake/${mac.format()}.pcap",
                    isPmkid = pmkid,
                ),
            )
            return this
        }

        /** Peer (outro pwnagotchi) visível. */
        fun peer(fingerprint: String, name: String? = null): Script {
            bus.tryEmit(RadioEvent.PeerSeen(fingerprint, name))
            return this
        }

        /** Internet disponível via peer (ou direto). */
        fun internet(viaPeer: Boolean = true): Script {
            bus.tryEmit(RadioEvent.InternetAvailable(viaPeer))
            return this
        }

        /** Próxima associação a [bssid] falha (Result.failure). */
        fun failNextAssoc(bssid: String): Script {
            failedAssoc.add(MacAddress.parse(bssid).value)
            return this
        }

        /** Próximo deauth a [bssid] falha. */
        fun failNextDeauth(bssid: String): Script {
            failedDeauth.add(MacAddress.parse(bssid).value)
            return this
        }

        /** Próximo setChannel para [channel] falha. */
        fun failNextChannel(channel: Int): Script {
            failedChannel.add(channel)
            return this
        }

        /** Erro genérico do backend. */
        fun error(message: String, recoverable: Boolean = true): Script {
            bus.tryEmit(RadioEvent.BackendError(message, recoverable))
            return this
        }
    }

    // --- RadioBackend -------------------------------------------------------

    override suspend fun start(env: BackendEnvironment): StartedBackend {
        check(!started) { "FakeRadioBackend já iniciado" }
        started = true
        return Started()
    }

    // --- StartedBackend -------------------------------------------------------

    private inner class Started : StartedBackend {
        override val backendId: BackendId get() = this@FakeRadioBackend.id

        override suspend fun startRecon(channels: Set<Int>, dwellMs: Long) {
            check(started) { "backend não iniciado" }
            reconRunning = true
            operations += "recon:$channels@$dwellMs"
            bus.tryEmit(RadioEvent.ReconStarted(channels.toList()))
        }

        override suspend fun stopRecon() {
            if (reconRunning) {
                reconRunning = false
                operations += "recon:stop"
                bus.tryEmit(RadioEvent.ReconFinished(durationMs = 0))
            }
        }

        override suspend fun accessPoints(): List<AccessPoint> = aps.values.toList()

        override suspend fun assoc(ap: MacAddress): Result<Unit> {
            operations += "assoc:${ap.format()}"
            return if (ap.value in failedAssoc) {
                failedAssoc.remove(ap.value)
                Result.failure(IllegalStateException("assoc falhou (programada) em ${ap.format()}"))
            } else {
                bus.tryEmit(RadioEvent.ChannelChanged(currentChannel))
                Result.success(Unit)
            }
        }

        override suspend fun deauth(sta: MacAddress?, ap: MacAddress): Result<Unit> {
            operations += "deauth:${sta?.format() ?: "broadcast"}@${ap.format()}"
            return if (ap.value in failedDeauth) {
                failedDeauth.remove(ap.value)
                Result.failure(IllegalStateException("deauth falhou (programada) em ${ap.format()}"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun setChannel(channel: Int, widthMhz: Int): Result<Unit> {
            operations += "channel:$channel/$widthMhz"
            return if (channel in failedChannel) {
                failedChannel.remove(channel)
                Result.failure(IllegalStateException("setChannel falhou (programado) em $channel"))
            } else {
                currentChannel = channel
                Result.success(Unit)
            }
        }

        override fun events(): SharedFlow<RadioEvent> = bus

        override suspend fun applyPersonality(p: Personality) {
            operations += "personality:${p.maxInteractions}"
            personality = p
        }

        override suspend fun shutdown() {
            operations += "shutdown"
            reconRunning = false
            started = false
        }
    }

    private fun channelToFrequency(channel: Int): Int = when {
        channel in 1..13 -> 2407 + channel * 5
        channel == 14 -> 2484
        channel in 36..165 -> 5000 + channel * 5
        else -> 0
    }
}
