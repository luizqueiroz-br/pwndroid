package br.com.luizqueiroz.pwndroid.core.radio.bettercap

/**
 * Fronteira com o shell root (issue #19): a implementação real usa libsu
 * (`Shell.getShell`); testes usam um fake que grava os comandos.
 *
 * `exec` devolve [ShellResult] — out/err brutos e exitCode — para o
 * chamador decidir o que é erro (bettercap -version, chmod etc.).
 */
interface RootShell {

    /** Shell root disponível (`su` responde a `id` com uid=0)? */
    suspend fun isRootAvailable(): Boolean

    /** Executa um comando no shell root; nunca lança para falha lógica. */
    suspend fun exec(command: String): ShellResult

    /** Encerra o shell persistente (idempotente). */
    fun close()
}

/** Resultado bruto de um comando no shell root. */
data class ShellResult(
    val exitCode: Int,
    val out: List<String>,
    val err: List<String>,
) {
    val ok: Boolean get() = exitCode == 0
}

/** Causas de indisponibilidade de root (mensagens user-actionable). */
sealed class RootUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {

    /** `su` não existe no device (nenhum gerenciador de root instalado). */
    class NoSuBinary(detail: String = "su não encontrado") :
        RootUnavailableException("sem root neste device — $detail (Magisk não disponível?)")

    /** `su` existe mas negou o acesso ao app. */
    class SuDenied(cause: Throwable? = null) : RootUnavailableException(
        "root presente, mas o acesso foi negado pelo gerenciador " +
            "(Magisk: conceda permissão ao pwndroid)",
        cause,
    )

    /** SELinux em enforcement bloqueou o exec do binário. */
    class SelinuxBlocked(detail: String) :
        RootUnavailableException("SELinux bloqueou a execução: $detail")
}
