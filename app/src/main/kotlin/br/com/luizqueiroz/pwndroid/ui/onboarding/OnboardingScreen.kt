package br.com.luizqueiroz.pwndroid.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Fluxo de onboarding (issue #14): um [OnboardingStep] por página, com
 * progresso (1/4..4/4). Só o disclaimer é bloqueante — "Pular para o
 * app" aparece em todas as páginas seguintes. Permissões podem ser
 * concedidas de novo nas telas onde faltam (o onboarding nunca as
 * exige).
 *
 * O host (MainActivity) grava `onboarded = true` em [onFinish].
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onRequestLocation: () -> Unit,
    onRequestBattery: () -> Unit,
    onAcceptDisclaimer: () -> Unit,
    onAdvance: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Configuração inicial (${state.step.ordinal + 1}/4)",
            style = MaterialTheme.typography.labelLarge,
        )
        when (state.step) {
            OnboardingStep.DISCLAIMER -> DisclaimerPage(
                accepted = state.disclaimerAccepted,
                onAccept = onAcceptDisclaimer,
            )
            OnboardingStep.LOCATION -> LocationPage(
                granted = state.locationGranted,
                onRequest = onRequestLocation,
            )
            OnboardingStep.BATTERY -> BatteryPage(
                exempt = state.batteryExempt,
                onRequest = onRequestBattery,
            )
            OnboardingStep.ROOT -> RootPage(rootAvailable = state.rootAvailable)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.canSkip) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Pular para o app")
                }
            }
            Button(
                onClick = onAdvance,
                enabled = state.canAdvance,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isLastStep) "Concluir" else "Continuar")
            }
        }
    }
}

@Composable
private fun DisclaimerPage(accepted: Boolean, onAccept: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aviso legal", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Este app é uma ferramenta de auditoria de redes Wi-Fi, " +
                "inspirada no pwnagotchi. Use-o SOMENTE em redes que lhe " +
                "pertencem ou para as quais você tem autorização explícita " +
                "para testar. Capturar tráfego ou autenticações de redes de " +
                "terceiros sem autorização é ilegal na maioria das " +
                "jurisdições (no Brasil, art. 154-A do Código Penal; leis " +
                "como o ECPA/Computer Fraud and Abuse Act nos EUA). Os " +
                "autores não se responsabilizam pelo uso indevido.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = { if (it) onAccept() })
            Text("Eu entendo e vou usar apenas em redes autorizadas")
        }
    }
}

@Composable
private fun LocationPage(granted: Boolean, onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Localização", style = MaterialTheme.typography.headlineSmall)
        Text(
            "O Android exige permissão de localização para escanear redes " +
                "Wi-Fi próximas — é assim que o pwndroid descobre os APs " +
                "ao redor. A permissão fica no seu aparelho: os dados de " +
                "scan não saem dele (o export para WiGLE/wpa-sec é manual " +
                "e sua escolha).",
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusLine(
            ok = granted,
            okText = "Permissão concedida.",
            pendingText = "Permissão não concedida — o scan Wi-Fi não vai funcionar.",
        )
        if (!granted) {
            Button(onClick = {
                onRequest()
            }) {
                Text("Conceder permissão de localização")
            }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) {
                Text("Abrir configurações de localização")
            }
        }
    }
}

@Composable
private fun BatteryPage(exempt: Boolean, onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bateria", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Para sessões longas (o app fica rodando em segundo plano), " +
                "peça ao Android para não restringir o pwndroid. Sem isso, " +
                "o sistema pode pausar o app e interromper as épocas.",
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusLine(
            ok = exempt,
            okText = "Otimização de bateria ignorada.",
            pendingText = "Otimização de bateria ativa — sessões podem ser interrompidas.",
        )
        if (!exempt) {
            Button(onClick = { onRequest() }) {
                Text("Pedir isenção de bateria")
            }
        }
    }
}

@Composable
private fun RootPage(rootAvailable: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Root (opcional)", style = MaterialTheme.typography.headlineSmall)
        if (rootAvailable) {
            Text(
                "Root detectado neste device. Nas próximas versões (v0.2) " +
                    "você poderá ativar backends com injeção de pacotes " +
                    "(bettercap) — por ora, o app roda em modo passivo.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Nenhum root detectado. Sem problema: o pwndroid roda em " +
                    "modo passivo (scan de redes, BLE, wardriving). Para " +
                    "capturar handshakes com injeção de pacotes é preciso " +
                    "root (Magisk) — veja a documentação do projeto.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, pendingText: String) {
    Text(
        text = if (ok) "✔ $okText" else "⚠ $pendingText",
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

/**
 * Pedidos reais do sistema (issue #14), montados no [MainActivity]:
 * - Localização: `requestPermissions` com ACCESS_FINE_LOCATION.
 * - Bateria: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` com o
 *   package URI (exige a permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *   no manifest).
 */
object OnboardingRequests {

    /** true se a localização fina já está concedida. */
    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** true se o app já está na lista de isenção de bateria. */
    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /** Intent que pede a isenção de bateria direto do sistema. */
    fun batteryIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    /** true se o pedido de isenção é permitido neste device. */
    fun canRequestBatteryExemption(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
}
