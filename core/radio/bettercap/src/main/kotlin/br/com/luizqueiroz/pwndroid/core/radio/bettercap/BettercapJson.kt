package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parse do JSON do bettercap (issue #20): `/api/session` e frames de
 * `/api/events`. Parse manual (sem ContentNegotiation) para mapear
 * exatamente o que o pwndroid usa e tolerar variações do bettercap
 * (campos ausentes, ints como string etc.).
 */
object BettercapJson {

    /** Tolerante: campos ausentes/desconhecidos não derrubam o parse. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse de `GET /api/session`: listas de APs e STAs do módulo wifi
     * (o bettercap aninha as listas em algum nível da sessão — busca
     * recursiva pelas chaves).
     */
    fun parseSession(body: String): BettercapSession {
        val root = parseObject(body, "sessão")
        return BettercapSession(
            accessPoints = root.arrayAtPath("aps").mapNotNull(::apOrNull),
            stations = root.arrayAtPath("stations").mapNotNull(::staOrNull),
        )
    }

    /**
     * Frame WS de evento → [BettercapEvent] com `tag` (ex.
     * `wifi.client.handshake`) + payload (objeto JSON anexado).
     */
    fun parseEvent(frame: String): BettercapEvent {
        val root = parseObject(frame, "evento WS")
        val tag = root.str("tag")
            ?: throw BettercapApiException("evento WS sem 'tag'")
        return BettercapEvent(
            tag = tag,
            payload = root,
        )
    }

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    private fun parseObject(body: String, what: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                throw BettercapApiException("JSON inválido ($what)", it)
            }

    /** Objeto `wifi.ap` do bettercap → [ApiAccessPoint]. */
    private fun apOrNull(element: JsonElement): ApiAccessPoint? {
        val obj = element as? JsonObject ?: return null
        val mac = obj.str("mac") ?: return null
        return ApiAccessPoint(
            mac = mac,
            essid = obj.str("essid"),
            rssi = obj.int("rssi") ?: -100,
            channel = obj.int("channel") ?: 0,
            frequency = obj.int("frequency") ?: 0,
            encryption = obj.str("encryption") ?: "OPEN",
            firstSeen = obj.str("first_seen") ?: "",
            lastSeen = obj.str("last_seen") ?: "",
        )
    }

    /** Objeto `wifi.sta` do bettercap → [ApiStation]. */
    private fun staOrNull(element: JsonElement): ApiStation? {
        val obj = element as? JsonObject ?: return null
        val mac = obj.str("mac") ?: return null
        return ApiStation(
            mac = mac,
            rssi = obj.int("rssi") ?: -100,
            apMac = obj.str("ap_mac"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** Int tolerante: bettercap às vezes manda string no lugar de int. */
    private fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
        null -> null
        is JsonPrimitive -> v.content.toIntOrNull()
        else -> null
    }

    /** Busca a lista [key] na raiz ou aninhada (sessão bettercap). */
    private fun JsonObject.arrayAtPath(key: String): List<JsonElement> {
        (this[key] as? JsonArray)?.let { return it }
        for ((_, value) in this) {
            findArray(value, key)?.let { return it }
        }
        return emptyList()
    }

    private fun findArray(element: JsonElement, key: String): List<JsonElement>? {
        val obj = element as? JsonObject ?: return null
        (obj[key] as? JsonArray)?.let { return it }
        for ((_, value) in obj) {
            findArray(value, key)?.let { return it }
        }
        return null
    }
}

/**
 * Evento normalizado do bettercap: [tag] (ex. `wifi.client.handshake`)
 * + [payload] (o objeto JSON inteiro do frame).
 */
data class BettercapEvent(
    val tag: String,
    val payload: JsonObject,
)

/**
 * Mapeamento de [BettercapSession] (API REST) → domínio (issue #21):
 * snapshot de `accessPoints()` do [BettercapStarted].
 */
object SessionMapper {

    /** APs do recon bettercap → domínio, com clientes aninhados. */
    fun toDomainAccessPoints(session: BettercapSession, nowMillis: Long): List<AccessPoint> {
        val stationsByAp = session.stations
            .filter { it.apMac != null }
            .groupBy(
                keySelector = { it.apMac!!.uppercase() },
                valueTransform = { sta ->
                    br.com.luizqueiroz.pwndroid.core.model.Station(
                        mac = MacAddress.parse(sta.mac),
                        rssi = sta.rssi,
                        apMac = sta.apMac?.let { MacAddress.parse(it) },
                    )
                },
            )
        return session.accessPoints.map { ap ->
            AccessPoint(
                mac = MacAddress.parse(ap.mac),
                ssid = ap.essid?.takeIf { it.isNotBlank() },
                rssi = ap.rssi,
                channel = ap.channel,
                frequencyMhz = ap.frequency,
                encryption = ap.encryption,
                clients = stationsByAp[ap.mac.uppercase()] ?: emptyList(),
                // Timestamps do bettercap são strings de data (UTC): o
                // domínio quer millis — o `nowMillis` marca o snapshot
                // (first/lastSeen do recon corrente).
                firstSeen = nowMillis,
                lastSeen = nowMillis,
            )
        }
    }
}

/**
 * Mapeamento de eventos bettercap → [RadioEvent] (issue #20), 1:1 contra
 * fixtures de JSON reais. Eventos que o pwndroid não consome viram null
 * (sem erro — o bettercap emite muito mais tipos do que usamos).
 */
object EventMapper {

    fun toRadioEvent(frame: String, pcapDir: String): RadioEvent? {
        val event = BettercapJson.parseEvent(frame)
        val payload = event.payload
        return when (event.tag) {
            "wifi.client.handshake" -> handshakeEvent(payload, pcapDir)
            "wifi.ap.new" -> apNewEvent(payload)
            "wifi.client.new" -> clientNewEvent(payload)
            "modem.internet_available" -> RadioEvent.InternetAvailable(viaPeer = false)
            else -> null
        }
    }

    /** `wifi.client.handshake` → [RadioEvent.HandshakeDetected]. */
    private fun handshakeEvent(payload: JsonObject, pcapDir: String): RadioEvent? {
        val ap = payload["ap"] as? JsonObject
        val client = payload["client"] as? JsonObject
        val bssid = ap?.str("mac") ?: payload.str("ap_mac") ?: return null
        val station = client?.str("mac") ?: payload.str("mac") ?: return null
        return RadioEvent.HandshakeDetected(
            bssid = bssid,
            station = station,
            essid = ap?.str("essid") ?: payload.str("essid"),
            pcapPath = payload.str("file") ?: "$pcapDir/handshake.pcap",
            isPmkid = payload.str("pmkid")?.isNotEmpty() == true,
        )
    }

    /** `wifi.ap.new` → [RadioEvent.ApSeen]. */
    private fun apNewEvent(payload: JsonObject): RadioEvent? {
        val ap = payload["ap"] as? JsonObject ?: payload
        val bssid = ap.str("mac") ?: return null
        return RadioEvent.ApSeen(
            essid = ap.str("essid"),
            bssid = bssid,
            channel = ap.int("channel") ?: 0,
            rssi = ap.int("rssi") ?: -100,
            encryption = ap.str("encryption"),
        )
    }

    /** `wifi.client.new` → [RadioEvent.StationSeen]. */
    private fun clientNewEvent(payload: JsonObject): RadioEvent? {
        val client = payload["client"] as? JsonObject ?: payload
        val mac = client.str("mac") ?: return null
        return RadioEvent.StationSeen(
            station = mac,
            bssid = client.str("ap_mac"),
            rssi = client.int("rssi") ?: -100,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
        null -> null
        is JsonPrimitive -> v.content.toIntOrNull()
        else -> null
    }
}
