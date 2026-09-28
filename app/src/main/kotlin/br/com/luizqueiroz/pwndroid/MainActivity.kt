package br.com.luizqueiroz.pwndroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.data.ConfigStore
import br.com.luizqueiroz.pwndroid.data.HandshakeRepository
import br.com.luizqueiroz.pwndroid.data.WhitelistRepository
import br.com.luizqueiroz.pwndroid.data.WardriveRepository
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta
import br.com.luizqueiroz.pwndroid.service.PwnForegroundService
import br.com.luizqueiroz.pwndroid.ui.handshakes.HandshakeDetailDialog
import br.com.luizqueiroz.pwndroid.ui.handshakes.HandshakesScreen
import br.com.luizqueiroz.pwndroid.ui.handshakes.HandshakesState
import br.com.luizqueiroz.pwndroid.ui.handshakes.fileName
import br.com.luizqueiroz.pwndroid.ui.home.HomeActions
import br.com.luizqueiroz.pwndroid.ui.home.HomeConfig
import br.com.luizqueiroz.pwndroid.ui.home.HomeScreen
import br.com.luizqueiroz.pwndroid.ui.home.HomeState
import br.com.luizqueiroz.pwndroid.ui.onboarding.OnboardingRequests
import br.com.luizqueiroz.pwndroid.ui.onboarding.OnboardingScreen
import br.com.luizqueiroz.pwndroid.ui.onboarding.OnboardingStep
import br.com.luizqueiroz.pwndroid.ui.onboarding.OnboardingUiState
import br.com.luizqueiroz.pwndroid.ui.onboarding.RootDetector
import br.com.luizqueiroz.pwndroid.ui.wardrive.AccessPointDetailDialog
import br.com.luizqueiroz.pwndroid.ui.wardrive.WardriveScreen
import br.com.luizqueiroz.pwndroid.ui.wardrive.WardriveState
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val koin = GlobalContext.get()
        val registry = koin.get<SessionRegistry>()
        val state = WardriveState(repo = koin.get<WardriveRepository>(), whitelist = koin.get<WhitelistRepository>())
        val handshakesState = HandshakesState(repo = koin.get<HandshakeRepository>())
        val configStore = koin.get<ConfigStore>()
        val backends = koin.get<List<RadioBackend>>()
        val bus = koin.get<br.com.luizqueiroz.pwndroid.core.common.EventBus>()
        val home = HomeState(registry, bus, brain = koin.get())
        val rootDetector = RootDetector()

        setContent {
            MaterialTheme {
                AppRoot(
                    tabs = TabStates(home, state, handshakesState),
                    configStore = configStore,
                    backends = backends,
                    registry = registry,
                    activity = this,
                    rootDetector = rootDetector,
                )
            }
        }
    }

    /** Export via share sheet: arquivo em cache + FileProvider + ACTION_SEND. */
    internal fun shareExport(name: String, content: String, mime: String) {
        val file = File(cacheDir, name)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Exportar"))
    }

    /** Gera o CSV WiGLE do histórico e abre o share sheet. */
    internal fun exportCsv(wardrive: WardriveState, scope: CoroutineScope) {
        scope.launch {
            val (csv, _) = wardrive.exportAll()
            shareExport("pwndroid_wardrive.csv", csv, "text/csv")
        }
    }

    /** Gera o KML do histórico e abre o share sheet. */
    internal fun exportKml(wardrive: WardriveState, scope: CoroutineScope) {
        scope.launch {
            val (_, kml) = wardrive.exportAll()
            shareExport(
                "pwndroid_wardrive.kml",
                kml,
                "application/vnd.google-earth.kml+xml",
            )
        }
    }

    /**
     * Export do pcap do handshake (issue #24): arquivo binário real do
     * disco via FileProvider + ACTION_SEND (mime octet-stream; o hcxpcapngtool
     * do usuário recebe o pcap íntegro, hashcat-ready).
     */
    internal fun exportPcap(pcapPath: String, name: String) {
        val file = File(pcapPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Exportar $name"))
    }
}

/**
 * Estado de tela injetado da atividade (issue #24): agrupa os states das
 * abas para manter as composables de raiz dentro dos limites do detekt.
 */
private data class TabStates(
    val home: HomeState,
    val wardrive: WardriveState,
    val handshakes: HandshakesState,
)

/**
 * Raiz da UI: mostra o onboarding na primeira execução (issue #14 — flag
 * `onboarded` no DataStore) e as abas Home/Wardrive/Handshakes depois.
 */
@Composable
private fun AppRoot(
    tabs: TabStates,
    configStore: ConfigStore,
    backends: List<RadioBackend>,
    registry: SessionRegistry,
    activity: MainActivity,
    rootDetector: RootDetector,
) {
    val config by remember { configStore.config }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val cfg = config
    val showOnboarding = cfg?.let { OnboardingUiState.shouldShowOnboarding(it) } ?: true

    if (showOnboarding) {
        // Config ainda não carregada (initial = null): espera em branco
        // em vez de piscar o onboarding em quem já embarcou.
        OnboardingFlow(
            rootDetector = rootDetector,
            onPersist = {
                scope.launch {
                    configStore.setDisclaimerAccepted(true)
                    configStore.setOnboarded(true)
                }
            },
        )
    } else {
        AppTabs(tabs, configStore, backends, registry, activity)
    }
}

/**
 * Fluxo de onboarding (issue #14): dispara os pedidos reais do sistema
 * (permissão de localização e isenção de bateria), reflete o resultado
 * no [OnboardingUiState] e persiste `onboarded`/`disclaimerAccepted` no
 * ConfigStore ao sair do fluxo (botão Concluir ou "Pular para o app").
 */
@Composable
private fun OnboardingFlow(
    rootDetector: RootDetector,
    onPersist: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember {
        mutableStateOf(
            OnboardingUiState(
                locationGranted = OnboardingRequests.hasLocationPermission(context),
                batteryExempt = OnboardingRequests.isBatteryExempt(context),
                rootAvailable = runCatching { rootDetector.isRooted() }.getOrDefault(false),
            ),
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        state = state.withLocation(granted)
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RESULT_OK = usuário concedeu a isenção (o sistema devolve isso
        // para ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
        state = state.withBattery(result.resultCode == Activity.RESULT_OK)
    }

    OnboardingScreen(
        state = state,
        onRequestLocation = {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        },
        onRequestBattery = {
            runCatching { batteryLauncher.launch(OnboardingRequests.batteryIntent(context)) }
        },
        onAcceptDisclaimer = { state = state.acceptDisclaimer() },
        onAdvance = {
            val next = state.advance()
            state = next
            if (next.step == OnboardingStep.ROOT && !next.isLastStep) {
                // não deveria acontecer, mas mantém o fluxo íntegro
            }
        },
        onFinish = onPersist,
    )
}

/**
 * Abas Home/Wardrive/Handshakes com o estado mínimo por tab. O export é
 * escrito em cache e entregue ao share sheet do sistema (Files/Drive/WiGLE);
 * o pcap sai direto do disco via FileProvider.
 */
@Composable
private fun AppTabs(
    tabs: TabStates,
    configStore: ConfigStore,
    backends: List<RadioBackend>,
    registry: SessionRegistry,
    activity: MainActivity,
) {
    val (home, wardrive, handshakes) = tabs
    val ui by registry.state.collectAsState()
    val running = ui.backend != null
    val backendIds = remember(backends) { backends.map { it.id } }
    val apsFlow = remember { wardrive.aps() }
    val aps by apsFlow.collectAsState(initial = emptyList())
    val hsFlow = remember { handshakes.handshakes() }
    val hsList by hsFlow.collectAsState(initial = emptyList())

    FaceTicker(home)

    var selectedTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Home") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Wardrive") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Handshakes") })
            }
            when (selectedTab) {
                0 -> HomeTab(
                    home = home,
                    registry = registry,
                    backends = backendIds,
                    configStore = configStore,
                    running = running,
                    activity = activity,
                    modifier = Modifier.padding(padding),
                )
                2 -> HandshakesTab(
                    handshakes = handshakes,
                    list = hsList,
                    activity = activity,
                    modifier = Modifier.padding(padding),
                )
                else -> WardriveTab(
                    wardrive = wardrive,
                    aps = aps,
                    activity = activity,
                    scope = scope,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * Tab Handshakes da issue #24: lista de capturas com dialog de detalhe e
 * export do pcap (share sheet do sistema sobre o arquivo real do disco).
 */
@Composable
private fun HandshakesTab(
    handshakes: HandshakesState,
    list: List<HandshakeEntity>,
    activity: MainActivity,
    modifier: Modifier = Modifier,
) {
    var hsDetail by remember { mutableStateOf<HandshakeEntity?>(null) }
    HandshakesScreen(
        handshakes = list,
        onSelect = { hsDetail = it },
        modifier = modifier,
    )
    hsDetail?.let { hs ->
        HandshakeDetailDialog(
            handshake = hs,
            pcapExists = handshakes.pcapExists(hs.pcapPath),
            onExport = { activity.exportPcap(hs.pcapPath, hs.fileName()) },
            onDismiss = { hsDetail = null },
        )
    }
}

/**
 * Tab Wardrive (issue #18): lista de APs com dialog de detalhe e exports
 * CSV/KML via share sheet.
 */
@Composable
private fun WardriveTab(
    wardrive: WardriveState,
    aps: List<AccessPointWithMeta>,
    activity: MainActivity,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var detailMac by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<WardriveState.Detail?>(null) }
    val whitelistMacs by remember { wardrive.whitelistMacs() }.collectAsState(initial = emptySet())

    WardriveScreen(
        aps = aps,
        onSelectAp = { mac -> detailMac = mac },
        onExportCsv = { activity.exportCsv(wardrive, scope) },
        onExportKml = { activity.exportKml(wardrive, scope) },
        modifier = modifier,
    )
    detailMac?.let { mac ->
        AccessPointDetailHost(
            wardrive = wardrive,
            mac = mac,
            whitelistMacs = whitelistMacs,
            scope = scope,
            onClose = {
                detail = null
                detailMac = null
            },
            onLoaded = { detail = it },
            currentDetail = detail,
        )
    }
}

/**
 * Dialog do detalhe de um AP: carrega o snapshot do repositório
 * (LaunchedEffect) e mostra [AccessPointDetailDialog] com o estado
 * atual da whitelist.
 */
@Composable
private fun AccessPointDetailHost(
    wardrive: WardriveState,
    mac: String,
    whitelistMacs: Set<String>,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
    onLoaded: (WardriveState.Detail?) -> Unit,
    currentDetail: WardriveState.Detail?,
) {
    LaunchedEffect(mac) {
        val d = wardrive.detail(mac)
        onLoaded(d?.let { WardriveState.Detail(it.ap, it.sightings) })
    }
    currentDetail?.let { d ->
        val whitelisted = d.ap.mac in whitelistMacs
        AccessPointDetailDialog(
            ap = d.ap,
            sightings = d.sightings,
            whitelisted = whitelisted,
            onToggleWhitelist = {
                scope.launch { wardrive.toggleWhitelist(d.ap.mac, whitelisted) }
            },
            onDismiss = onClose,
        )
    }
}

/**
 * Tab Home da issue #13: coleciona o estado facial (HomeState), a config
 * (modo/backend) e monta as [HomeActions] — start/stop do serviço e
 * persistência das escolhas no ConfigStore.
 */
@Composable
private fun HomeTab(
    home: HomeState,
    registry: SessionRegistry,
    backends: List<BackendId>,
    configStore: ConfigStore,
    running: Boolean,
    activity: MainActivity,
    modifier: Modifier = Modifier,
) {
    val ui by registry.state.collectAsState()
    val face by home.face.collectAsState()
    val mood by home.mood.collectAsState()
    val config by remember { configStore.config }.collectAsState(initial = null)
    val brainState by home.brainState.collectAsState()
    val scope = rememberCoroutineScope()

    HomeScreen(
        face = face,
        mood = mood,
        ui = ui,
        running = running,
        config = HomeConfig(
            mode = config?.mode ?: PwnMode.AUTO,
            backendPreference = config?.backendPreference,
            availableBackends = backends,
            brain = brainState,
        ),
        actions = HomeActions(
            onStart = { PwnForegroundService.start(activity) },
            onStop = { PwnForegroundService.stop(activity) },
            onModeSelected = { mode ->
                scope.launch { configStore.setMode(mode) }
            },
            onBackendSelected = { backend ->
                scope.launch { configStore.setBackendPreference(backend) }
            },
        ),
        modifier = modifier,
    )
}

/**
 * Ticker de 1 FPS da face (issue #13): assina o bus via [HomeState.observe]
 * e marca o blink a cada segundo (faceTicker de feature/display).
 */
@Composable
private fun FaceTicker(home: HomeState) {
    LaunchedEffect(home) {
        val ticker = launch {
            while (true) {
                home.tick()
                kotlinx.coroutines.delay(1_000)
            }
        }
        home.observe(this)
        ticker.join()
    }
}
