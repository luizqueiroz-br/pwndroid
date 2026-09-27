package br.com.luizqueiroz.pwndroid.core.model

/**
 * Value class de endereço MAC (802.11): parse/formato válidos, equals
 * case-insensitive (sempre normalizado para lowercase).
 */
@JvmInline
value class MacAddress(val value: String) {

    /** Formato com dois-pontos (00:11:22:33:44:55). */
    fun format(): String = value.chunked(2).joinToString(":") { it.lowercase() }

    override fun toString(): String = format()

    companion object {
        private val MAC_PATTERN = Regex("^[0-9a-fA-F]{12}$")

        /** Aceita "AA:BB:CC:DD:EE:FF", "aa-bb-...", "aabbccddeeff". */
        fun parse(raw: String): MacAddress {
            val hex = raw.replace(":", "").replace("-", "")
            require(hex.matches(MAC_PATTERN)) { "MAC inválido: $raw" }
            return MacAddress(hex.lowercase())
        }
    }
}
