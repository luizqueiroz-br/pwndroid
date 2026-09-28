package br.com.luizqueiroz.pwndroid.ui.onboarding

import android.os.Build
import java.io.File

/**
 * Detecção leve de root (issue #14): procura o binário `su` nos PATHs
 * padrão do Magisk/SuperSU e testa `which su` via shell. Apenas
 * informativo — o app NÃO exige root (fluxo roda limpo sem root).
 */
class RootDetector {

    /** true se um binário su (Magisk, SuperSU, KernelSU) for encontrado. */
    fun isRooted(): Boolean = suBinaryExists() || whichSuWorks()

    /** Procura `su` em /sbin, /system/bin, /system/xbin, /su/bin etc. */
    private fun suBinaryExists(): Boolean = SU_PATHS.any { path ->
        runCatching { File(path).exists() }.getOrDefault(false)
    }

    /** `which su` via Runtime.exec — funciona quando o PATH do app o vê. */
    private fun whichSuWorks(): Boolean = runCatching {
        val process = ProcessBuilder("which", "su")
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)

    private companion object {
        val SU_PATHS = listOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sd/xbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/tmp/su",
            "/cache/magisk", // marcador do Magisk
            "/sbin/.magisk", // diretório interno do Magisk
        ) + (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listOf("/system/app/Superuser.apk")
        } else {
            emptyList()
        })
    }
}
