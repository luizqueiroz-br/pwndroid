package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Empacotamento e instalação do binário bettercap ARM (issue #19).
 *
 * Os binários ficam em `app/src/main/jniLibs/<abi>/libbettercap.so`
 * (o `.so` via jniLibs é extraído pelo instalador, evitando a restrição
 * W^X de `exec` em app-data desde a API 29 — mas o binário ainda precisa
 * de chmod 755 via su para ser executável).
 *
 * Estratégia de instalação (no start do backend):
 * 1. Detectar a ABI (`Build.SUPPORTED_ABIS`) e localizar o binário do APK
 *    em `applicationInfo.nativeLibraryDir` (quando via jniLibs, o
 *    instalador já extraiu para lá) ou em assets.
 * 2. Copiar para `/data/local/tmp/pwndroid/bettercap` via [RootShell] —
 *    `/data/local/tmp` é o único caminho onde `su` pode executar com W^X
 *    satisfeito (exec de app-data é bloqueado desde a API 29).
 * 3. `chmod 755` via su.
 * 4. Verificar SELinux: se `getenforce` == Enforcing e o exec falhar com
 *    EACCES, reportar [RootUnavailableException.SelinuxBlocked]
 *    user-actionable.
 */
class BettercapInstaller(
    private val shell: RootShell,
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {

    /** Aba de destino: arm64-v8a > armeabi-v7a > x86_64 > x86. */
    fun selectAbi(): String {
        val supported = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        // Preferência fixa (melhor binário para o device): o primeiro da
        // ordem canônica que o device também declara em SUPPORTED_ABIS.
        return supported.firstOrNull { it in abis }
            ?: throw RootUnavailableException.NoSuBinary(
                "arquitetura do device não suportada: ${abis.joinToString()}",
            )
    }

    /**
     * Instala o binário bettercap de [apkBinary] (em nativeLibraryDir ou
     * assets extraídos) para [targetPath] e devolve o caminho executável.
     * O binário já é o da ABI corrente: a seleção acontece no
     * empacotamento jniLibs (um `libbettercap.so` por ABI) e
     * [selectAbi] valida a compatibilidade antes de chamar este método.
     * Cada passo valida o resultado e lança erro user-actionable.
     */
    suspend fun install(apkBinary: File, targetPath: String): String {
        // 1. Diretório alvo em /data/local/tmp (único local com exec
        //    permitido p/ binários de app na API 29+).
        shell.exec("mkdir -p ${targetPath.substringBeforeLast('/')}")
        // 2. Copia o binário do APK para /data/local/tmp via su (o app
        //    não escreve lá direto).
        val copy = shell.exec("cp -f '${apkBinary.absolutePath}' '$targetPath'")
        if (!copy.ok) {
            throw RootUnavailableException.NoSuBinary(
                "falha copiando bettercap para $targetPath: ${copy.err.joinToString("; ")}",
            )
        }
        // 3. chmod 755 (executável).
        val chmod = shell.exec("chmod 755 '$targetPath'")
        if (!chmod.ok) {
            throw RootUnavailableException.NoSuBinary(
                "falha no chmod 755: ${chmod.err.joinToString("; ")}",
            )
        }
        return targetPath
    }

    /** `bettercap -version` roda via su (critério de pronto da #19). */
    suspend fun verifyBinary(binaryPath: String): Boolean {
        val version = shell.exec("'$binaryPath' -version 2>&1")
        return version.ok && version.out.any { it.contains("bettercap", ignoreCase = true) }
    }

    /**
     * Detecta enforcement do SELinux: "Enforcing" significa que o exec
     * pode ser bloqueado — [BettercapInstaller.install] reporta erro
     * user-actionable se o exec falhar.
     */
    suspend fun selinuxEnforcing(): Boolean {
        val result = shell.exec("getenforce 2>/dev/null")
        return result.out.firstOrNull()?.trim()?.equals("Enforcing", ignoreCase = true) == true
    }

    /** Limpa artefatos temporários (só em /data/local/tmp — critério #19). */
    suspend fun cleanup(targetDir: String) {
        shell.exec("rm -rf '$targetDir'")
    }

    companion object {
        /** Diretório de instalação (único permitido para exec na API 29+). */
        const val TARGET_DIR = "/data/local/tmp/pwndroid"
        const val TARGET_BINARY = "$TARGET_DIR/bettercap"

        /**
         * Nome do binário em jniLibs: `libbettercap.so` por ABI — o
         * empacotamento usa o padrão de biblioteca nativa, extraído pelo
         * instalador para `nativeLibraryDir`.
         */
        const val BINARY_NAME = "libbettercap.so"

        /**
         * Caminho do binário extraído pelo instalador do APK. O
         * [abi] documenta a variante esperada — com jniLibs o
         * instalador extrai só a ABI do device para `nativeLibraryDir`,
         * então o caminho é o mesmo para qualquer ABI (o parâmetro
         * evita chamar a função com binário de arquitetura errada).
         */
        @Suppress("UNUSED_PARAMETER")
        fun apkBinary(context: Context, abi: String): File =
            File(context.applicationInfo.nativeLibraryDir, "libbettercap.so")
    }
}
