package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.BackendUnavailableException
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do RootShell e do fluxo de bootstrap (issue #19) — com shell
 * fake (sem device): sequência de comandos, erros user-actionable,
 * seleção de ABI e verificação do binário.
 */
class RootShellTest {

    /** Fake que grava comandos e responde por prefixo. */
    private class FakeShell(
        private val rootAvailable: Boolean = true,
        private val responses: Map<String, (String) -> ShellResult> = emptyMap(),
    ) : RootShell {
        val commands = mutableListOf<String>()
        var closed = false

        override suspend fun isRootAvailable(): Boolean = rootAvailable

        override suspend fun exec(command: String): ShellResult {
            commands.add(command)
            val handler = responses.entries.firstOrNull { command.contains(it.key) }
            return handler?.value?.invoke(command) ?: ShellResult(0, emptyList(), emptyList())
        }

        override fun close() {
            closed = true
        }
    }

    private fun fakeBinary(): File = File("/tmp/fake-libbettercap.so").apply {
        writeText("fake") // caminho absoluto apenas; o fake shell não toca no FS
    }

    /** Ambiente mínimo: o backend bettercap não usa clock/captureDir na #19. */
    private fun fakeEnv() = object :
        br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment {
        override val clock = object :
            br.com.luizqueiroz.pwndroid.core.common.AppClock {
            override fun nowMillis() = 0L
            override val io =
                kotlinx.coroutines.Dispatchers.Unconfined
            override val default =
                kotlinx.coroutines.Dispatchers.Unconfined
        }
        override val captureDir = "/tmp"
    }

    @Test
    fun `start instala copia chmod e verifica versao`() = runTest {
        val shell = FakeShell(
            responses = mapOf(
                "getenforce" to { ShellResult(0, listOf("Enforcing"), emptyList()) },
                "-version" to {
                    ShellResult(0, listOf("bettercap v2.41.5"), emptyList())
                },
            ),
        )
        val installer = BettercapInstaller(shell, abis = listOf("arm64-v8a"))
        val backend = BettercapBackend(
            shell = shell,
            installer = installer,
            apkBinaryProvider = { fakeBinary() },
            clock = fakeClock(),
            apiFactory = { _, _, _, _ -> FakeBettercapApi() },
        )
        val started = backend.start(fakeEnv())
        assertEquals(BackendId.BETTERCAP, started.backendId)
        // Sequência esperada: mkdir → cp → chmod → getenforce → -version
        assertTrue(shell.commands.any { it.startsWith("mkdir -p /data/local/tmp/pwndroid") })
        assertTrue(shell.commands.any { it.startsWith("cp -f") })
        assertTrue(shell.commands.any { it.startsWith("chmod 755") })
        assertTrue(shell.commands.any { it.contains("-version") })
        assertFalse(shell.closed)
    }

    @Test
    fun `start sem root lança BackendUnavailable sem executar nada`() = runTest {
        val shell = FakeShell(rootAvailable = false)
        val installer = BettercapInstaller(shell, abis = listOf("arm64-v8a"))
        val backend = BettercapBackend(
            shell = shell,
            installer = installer,
            apkBinaryProvider = { fakeBinary() },
            clock = fakeClock(),
            apiFactory = { _, _, _, _ -> FakeBettercapApi() },
        )
        val error = runCatching {
            backend.start(fakeEnv())
        }.exceptionOrNull()
        assertTrue(error is BackendUnavailableException)
        assertTrue(error!!.message!!.contains("root"))
        // Nenhum comando rodou sem root.
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `cp que falha lança erro com stderr do comando`() = runTest {
        val shell = FakeShell(
            responses = mapOf(
                "cp -f" to { ShellResult(1, emptyList(), listOf("cp: Read-only file system")) },
            ),
        )
        val installer = BettercapInstaller(shell, abis = listOf("arm64-v8a"))
        val error = runCatching {
            installer.install(fakeBinary(), BettercapInstaller.TARGET_BINARY)
        }.exceptionOrNull()
        assertTrue(error is RootUnavailableException.NoSuBinary)
        assertTrue(error!!.message!!.contains("Read-only file system"))
    }

    @Test
    fun `SELinux enforcement bloqueia exec com erro user-actionable`() = runTest {
        val shell = FakeShell(
            responses = mapOf(
                "getenforce" to { ShellResult(0, listOf("Enforcing"), emptyList()) },
                "-version" to {
                    ShellResult(126, emptyList(), listOf("Permission denied"))
                },
            ),
        )
        val installer = BettercapInstaller(shell, abis = listOf("arm64-v8a"))
        val backend = BettercapBackend(
            shell = shell,
            installer = installer,
            apkBinaryProvider = { fakeBinary() },
            clock = fakeClock(),
            apiFactory = { _, _, _, _ -> FakeBettercapApi() },
        )
        val error = runCatching { backend.start(fakeEnv()) }
            .exceptionOrNull()
        assertTrue(error is RootUnavailableException.SelinuxBlocked)
        assertTrue(error!!.message!!.contains("SELinux"))
    }

    @Test
    fun `selectAbi escolhe arm64 primeiro`() {
        val installer = BettercapInstaller(
            shell = FakeShell(),
            abis = listOf("x86", "arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("arm64-v8a", installer.selectAbi())
    }

    @Test
    fun `selectAbi sem ABI suportada lança erro`() {
        val installer = BettercapInstaller(shell = FakeShell(), abis = listOf("mips"))
        val error = runCatching { installer.selectAbi() }.exceptionOrNull()
        assertTrue(error is RootUnavailableException.NoSuBinary)
    }

    @Test
    fun `isRootAvailable false quando su responde não-root`() {
        val shell = FakeShell(rootAvailable = false)
        runTest {
            assertFalse(shell.isRootAvailable())
        }
    }

    /** Clock mínimo para o construtor do backend. */
    private fun fakeClock(): AppClock = object : AppClock {
        override fun nowMillis() = 0L
        override val io = kotlinx.coroutines.Dispatchers.Unconfined
        override val default = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @Test
    fun `cleanup remove apenas o diretório temporário`() = runTest {
        val shell = FakeShell()
        val installer = BettercapInstaller(shell, abis = listOf("arm64-v8a"))
        installer.cleanup(BettercapInstaller.TARGET_DIR)
        assertEquals(listOf("rm -rf '/data/local/tmp/pwndroid'"), shell.commands)
    }
}

/** API fake compartilhada pelos testes de bootstrap (#21). */
private class FakeBettercapApi : BettercapApi {
    override suspend fun run(command: String): Result<Unit> = Result.success(Unit)

    override suspend fun session(): Result<BettercapSession> =
        Result.success(BettercapSession(emptyList(), emptyList()))

    override suspend fun events(): Result<WsSession> =
        Result.success(object : WsSession {
            override suspend fun receive(): String? = null
            override fun close() = Unit
        })

    override fun close() = Unit
}

