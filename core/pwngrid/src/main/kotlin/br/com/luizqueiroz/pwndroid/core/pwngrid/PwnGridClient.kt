package br.com.luizqueiroz.pwndroid.core.pwngrid

import kotlinx.serialization.Serializable

/**
 * Cliente do oPwngrid (api.pwnagotchi.ai) — interoperabilidade com a
 * comunidade Pi. Endpoints usados: GET /v1/inbox, POST /v1/peers, PUT /v1/inbox/<id>.
 */
interface PwnGridClient {
    /** Registra/renova o peer no grid. */
    suspend fun register(identityPayload: String): PwnGridResult<Unit>

    /** Lista a inbox (mensagens de peers, ex. handshakes presentes). */
    suspend fun inbox(): PwnGridResult<List<GridMail>>
}

/** Resultado tipado das chamadas ao grid (erros de rede não explodem). */
sealed interface PwnGridResult<out T> {
    data class Success<T>(val value: T) : PwnGridResult<T>
    data class Failure(val message: String, val code: Int? = null) : PwnGridResult<Nothing>
}

/** Uma mensagem na inbox do grid. */
@Serializable
data class GridMail(
    val id: String,
    val from: String,
    val subject: String,
    val receivedAt: String,
)

/** Configuração do cliente. */
data class PwnGridConfig(
    val baseUrl: String = "https://api.pwnagotchi.ai",
    val apiKey: String = "",
    val connectTimeoutMs: Int = 10_000,
    val requestTimeoutMs: Int = 20_000,
)
