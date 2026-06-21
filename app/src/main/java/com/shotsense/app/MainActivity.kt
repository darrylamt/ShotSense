package com.shotsense.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shotsense.app.data.CalibrationLogger
import com.shotsense.app.data.Settings
import com.shotsense.app.data.SettingsStore
import com.shotsense.app.service.DetectionService
import com.shotsense.app.ui.MainScreen
import com.shotsense.app.ui.SettingsScreen
import com.shotsense.app.ui.components.Panel
import com.shotsense.app.ui.theme.Palette
import com.shotsense.app.ui.theme.ShotSenseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(applicationContext)
        setContent {
            ShotSenseTheme {
                AppRoot(settingsStore)
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS }

@Composable
private fun AppRoot(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DetectionService.state.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = Settings())

    var permsGranted by remember { mutableStateOf(hasRequiredPerms(context)) }
    var screen by remember { mutableStateOf(Screen.MAIN) }

    // Re-check permissions when returning from the system permission UI.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permsGranted = hasRequiredPerms(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        permsGranted = hasRequiredPerms(context)
    }

    if (!permsGranted) {
        PermissionGate(onRequest = { launcher.launch(requiredPermissions()) })
        return
    }

    when (screen) {
        Screen.MAIN -> MainScreen(
            state = state,
            onStart = { DetectionService.start(context) },
            onStop = { DetectionService.stop(context) },
            onSimulateShot = { DetectionService.simulateShot(context) },
            onSimulateRecoil = { DetectionService.simulateRecoil(context) },
            onSendTestAlert = { DetectionService.sendTestAlert(context) },
            onExportCsv = { exportCsv(context) },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onBack = { screen = Screen.MAIN },
            onUpdate = { scope.launch { settingsStore.saveAll(it) } },
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    val scroll = rememberScrollState()
    val items = listOf(
        "Microphone" to "Required. Listens for the loud sound impulse of a discharge.",
        "Location (precise)" to "Required. Tags each confirmed shot with GPS coordinates for the alert.",
        "Notifications" to "Shows the persistent 'detection active' notice so the OS keeps detection alive.",
        "SMS" to "Optional. Only needed if you enable the SMS alert channel.",
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("SHOTSENSE", style = MaterialTheme.typography.titleLarge, color = Palette.Amber)
        Text(
            "Permissions needed before detection can start.",
            color = Palette.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Panel(title = "WHY EACH PERMISSION", accent = Palette.Teal) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { (name, why) ->
                    Column {
                        Text(name, color = Palette.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Text(why, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Surface(color = Palette.SurfaceAlt, shape = RoundedCornerShape(8.dp)) {
            Text(
                "Microphone + Location must be granted to run. Detection stays in Test mode by default — no alerts are sent until you arm it.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("GRANT PERMISSIONS", fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}

private fun hasRequiredPerms(context: Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val mic = granted(Manifest.permission.RECORD_AUDIO)
    val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    return mic && loc
}

private fun exportCsv(context: Context) {
    val file = CalibrationLogger(context).file
    if (!file.exists() || file.length() == 0L) {
        Toast.makeText(context, "No calibration data yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export calibration CSV"))
}
