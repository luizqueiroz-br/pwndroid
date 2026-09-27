package br.com.luizqueiroz.pwndroid.core.identity

import java.security.SecureRandom

/**
 * Identidade criptográfica do device (par de chaves ed25519), equivalente
 * ao identity.pem do pwnagotchi, usado para assinar mensagens no pwngrid.
 * O backend real com Keystore Android chega na issue #17; esta é a API
 * pura-Kotlin com uma implementação in-memory para testes.
 */
interface IdentityManager {
    /** Fingerprint público (hex) da identidade — o "nome" no grid. */
    val fingerprint: String

    /** Assina os bytes com a chave privada. */
    suspend fun sign(data: ByteArray): ByteArray

    /** true se a identidade já foi gerada/carregada. */
    suspend fun isInitialized(): Boolean
}

/**
 * Identidade in-memory para testes: chaves aleatórias não persistidas.
 * A implementação real (lazysodium ed25519 + Keystore/EncryptedFile)
 * substitui esta no wiring do :app.
 */
class InMemoryIdentityManager : IdentityManager {
    private val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }

    override val fingerprint: String =
        seed.take(8).joinToString("") { "%02x".format(it) }

    override suspend fun sign(data: ByteArray): ByteArray {
        // Placeholder determinístico; a assinatura ed25519 real chega na issue #17.
        return data.copyOf()
    }

    override suspend fun isInitialized(): Boolean = true
}