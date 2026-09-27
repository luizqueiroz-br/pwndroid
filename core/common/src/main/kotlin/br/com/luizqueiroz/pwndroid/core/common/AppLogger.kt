package br.com.luizqueiroz.pwndroid.core.common

/**
 * Logging central, agnóstico de plataforma: `android.util.Log` no lado
 * Android (wrapper fino no :app), stdout no JVM puro.
 */
interface AppLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** Logger de stdout para módulos JVM e testes. */
class PrintAppLogger : AppLogger {
    override fun d(tag: String, message: String) = println("[D/$tag] $message")
    override fun w(tag: String, message: String, throwable: Throwable?) =
        println("[W/$tag] $message${throwable?.let { " :: ${it.message}" } ?: ""}")

    override fun e(tag: String, message: String, throwable: Throwable?) =
        System.err.println("[E/$tag] $message${throwable?.let { " :: ${it.message}" } ?: ""}")
}
