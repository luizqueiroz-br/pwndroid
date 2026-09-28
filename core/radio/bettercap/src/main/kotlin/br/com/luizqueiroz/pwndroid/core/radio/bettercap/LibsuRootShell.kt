package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Implementação real do [RootShell] sobre libsu (issue #19): shell root
 * persistente criado sob demanda com `su`, comando com timeout (via
 * coroutines; o shell continua vivo) e `close()` idempotente.
 *
 * libsu mantém um shell root vivo por processo; [close] fecha o global
 * para liberar o `su` após a sessão (reconexão é automática no próximo
 * exec).
 */
class LibsuRootShell(
    /** Timeout de cada comando (default 15 s: cp/chmod no device). */
    private val timeoutSeconds: Long = 15,
) : RootShell {

    override suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.getShell().isRoot
        }.getOrDefault(false)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val result = withTimeout(timeoutSeconds * 1000) {
            Shell.getShell()
                .newJob()
                .add(command)
                .to(out, err)
                .exec()
        }
        ShellResult(
            exitCode = result.code,
            out = out.toList(),
            err = err.toList(),
        )
    }

    override fun close() {
        runCatching { Shell.getShell().close() }
    }
}
