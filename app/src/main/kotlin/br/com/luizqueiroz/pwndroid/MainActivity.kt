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
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.data.WhitelistRepository
import br.com.luizqueiroz.pwndroid.data.WardriveRepository
import br.com.luizqueiroz.pwndroid.service.PwnForegroundService
import br.com.luizqueiroz.pwndroid.ui.HomeScreen
import br.com.luizqueiroz.pwndroid.ui.handshakes.HandshakesScreen
import br.com.luizqueiroz.pwndroid.ui.wardrive.AccessPointDetailDialog
import br.com.luizqueiroz.pwndroid.ui.wardrive.WardriveScreen
import br.com.luizqueiroz.pwndroid.ui.wardrive.WardriveState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val koin = GlobalContext.get()
        val registry = koin.get<SessionRegistry>()
        val repo = koin.get<WardriveRepository>()
        val whitelist = koin.get<WhitelistRepository>()
        val state = WardriveState(repo, whitelist)

        setContent {
            MaterialTheme {
                AppTabs(registry, state, this)
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
    registry: SessionRegistry,
    wardrive: WardriveState,
    activity: MainActivity,
) {
    val ui by registry.state.collectAsState()
    val running = ui.backend != null
    val apsFlow = remember { wardrive.aps() }
    val aps by apsFlow.collectAsState(initial = emptyList())

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
                0 -> HomeScreen(
                    ui = ui,
                    running = running,
                    onStart = { PwnForegroundService.start(activity) },
                    onStop = { PwnForegroundService.stop(activity) },
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
