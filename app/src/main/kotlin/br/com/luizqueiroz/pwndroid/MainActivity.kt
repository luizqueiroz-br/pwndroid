package br.com.luizqueiroz.pwndroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import br.com.luizqueiroz.pwndroid.data.WhitelistRepository
import br.com.luizqueiroz.pwndroid.data.WardriveRepository
import br.com.luizqueiroz.pwndroid.service.PwnForegroundService
import br.com.luizqueiroz.pwndroid.ui.handshakes.HandshakesScreen
import br.com.luizqueiroz.pwndroid.ui.home.HomeActions
import br.com.luizqueiroz.pwndroid.ui.home.HomeConfig
import br.com.luizqueiroz.pwndroid.ui.home.HomeScreen
import br.com.luizqueiroz.pwndroid.ui.home.HomeState
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
        val configStore = koin.get<ConfigStore>()
        val backends = koin.get<List<RadioBackend>>()
        val bus = koin.get<br.com.luizqueiroz.pwndroid.core.common.EventBus>()
        val home = HomeState(registry, bus)

        setContent {
            MaterialTheme {
                AppTabs(home, configStore, backends, registry, state, this)
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
}

/**
 * Abas Home/Wardrive com o estado mínimo por tab. O export é escrito em
 * cache e entregue ao share sheet do sistema (Files/Drive/WiGLE).
 */
@Composable
private fun AppTabs(
    home: HomeState,
    configStore: ConfigStore,
    backends: List<RadioBackend>,
    registry: SessionRegistry,
    wardrive: WardriveState,
    activity: MainActivity,
) {
    val ui by registry.state.collectAsState()
    val running = ui.backend != null
    val backendIds = remember(backends) { backends.map { it.id } }
    val apsFlow = remember { wardrive.aps() }
    val aps by apsFlow.collectAsState(initial = emptyList())
    val face by home.face.collectAsState()
    val mood by home.mood.collectAsState()
    val config by remember { configStore.config }.collectAsState(initial = null)

    FaceTicker(home)

    var selectedTab by remember { mutableStateOf(0) }
    var detailMac by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<WardriveState.Detail?>(null) }
    val whitelistMacs by remember { wardrive.whitelistMacs() }.collectAsState(initial = emptySet())

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
                2 -> HandshakesScreen(modifier = Modifier.padding(padding))
                else -> WardriveScreen(
                    aps = aps,
                    onSelectAp = { mac -> detailMac = mac },
                    onExportCsv = { activity.exportCsv(wardrive, scope) },
                    onExportKml = { activity.exportKml(wardrive, scope) },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

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

