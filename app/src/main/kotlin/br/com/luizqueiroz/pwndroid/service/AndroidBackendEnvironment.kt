package br.com.luizqueiroz.pwndroid.service

import android.content.Context
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment

/**
 * Implementação :app do [BackendEnvironment]: relógio do app e diretório
 * de capturas no armazenamento interno.
 */
class AndroidBackendEnvironment(
    context: Context,
    override val clock: AppClock,
) : BackendEnvironment {
    override val captureDir: String =
        context.getExternalFilesDir(null)?.resolve("captures")?.absolutePath
            ?: context.filesDir.resolve("captures").absolutePath
}
