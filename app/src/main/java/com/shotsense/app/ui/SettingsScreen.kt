package com.shotsense.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import com.shotsense.app.data.ArmingMode
import com.shotsense.app.data.Settings
import com.shotsense.app.ui.components.Panel
import com.shotsense.app.ui.theme.Palette
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Settings screen. The incoming [settings] is the source of truth; every edit
 * calls [onUpdate] with a fully-formed snapshot, which the host persists to
 * DataStore. Live values are shown next to each slider.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onUpdate: (Settings) -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.TextPrimary)
            }
            Spacer(Modifier.padding(end = 4.dp))
            Text("SETTINGS", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
        }

        // --- Arming mode ---
        Panel(title = "ARMING MODE", accent = Palette.Red) {
            Text(
                "Test = detections shown but nothing sent. Armed = confirmed shots send alerts.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeButton("TEST", settings.armingMode == ArmingMode.TEST, Palette.Amber, Modifier.weight(1f)) {
                    onUpdate(settings.copy(armingMode = ArmingMode.TEST))
                }
                ModeButton("ARMED", settings.armingMode == ArmingMode.ARMED, Palette.Red, Modifier.weight(1f)) {
                    onUpdate(settings.copy(armingMode = ArmingMode.ARMED))
                }
            }
        }

        // --- Detection tunables ---
        Panel(title = "DETECTION TUNABLES", accent = Palette.Amber) {
            SliderRow(
                label = "Saturation threshold",
                value = settings.saturationThreshold,
                range = 0.5f..1.0f,
                display = fmtF(settings.saturationThreshold, 2),
            ) { onUpdate(settings.copy(saturationThreshold = it)) }

            SliderRow(
                label = "Impulse ratio K",
                value = settings.impulseRatioK,
                range = 2f..40f,
                display = fmtF(settings.impulseRatioK, 0) + "x",
            ) { onUpdate(settings.copy(impulseRatioK = it)) }

            SliderRow(
                label = "Recoil threshold",
                value = settings.recoilThresholdG,
                range = 0.5f..8f,
                display = fmtF(settings.recoilThresholdG, 1) + " g",
            ) { onUpdate(settings.copy(recoilThresholdG = it)) }

            SliderRow(
                label = "Fusion window",
                value = settings.fusionWindowMs.toFloat(),
                range = 20f..300f,
                display = "${settings.fusionWindowMs} ms",
            ) { onUpdate(settings.copy(fusionWindowMs = it.roundToLong())) }

            SliderRow(
                label = "Debounce",
                value = settings.debounceMs.toFloat(),
                range = 50f..1000f,
                display = "${settings.debounceMs} ms",
            ) { onUpdate(settings.copy(debounceMs = it.roundToLong())) }

            Spacer(Modifier.height(6.dp))
            ToggleRow(
                label = "Require recoil (two-sensor)",
                checked = settings.requireRecoil,
                accent = Palette.Teal,
            ) { onUpdate(settings.copy(requireRecoil = it)) }
            Text(
                "Off = a loud sound alone confirms. Keep ON to avoid false alarms.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // --- Operator profile ---
        Panel(title = "OPERATOR PROFILE", accent = Palette.Green) {
            Text(
                "Sent with every alert so the console can identify who raised it.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            TextRow("Full name", settings.operatorName, KeyboardType.Text) {
                onUpdate(settings.copy(operatorName = it))
            }
            Spacer(Modifier.height(6.dp))
            TextRow("Phone number", settings.operatorPhone, KeyboardType.Phone) {
                onUpdate(settings.copy(operatorPhone = it))
            }
            Spacer(Modifier.height(6.dp))
            TextRow("Firearm type", settings.firearmType, KeyboardType.Text) {
                onUpdate(settings.copy(firearmType = it))
            }
            Spacer(Modifier.height(6.dp))
            TextRow("Photo URL (optional)", settings.photoUrl, KeyboardType.Uri) {
                onUpdate(settings.copy(photoUrl = it))
            }
            Text(
                "Photo URL: a link to a profile image shown in the response console.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // --- Identity ---
        Panel(title = "DEVICE", accent = Palette.Teal) {
            TextRow("Device ID", settings.deviceId, KeyboardType.Text) {
                onUpdate(settings.copy(deviceId = it))
            }
        }

        // --- SMS channel ---
        Panel(title = "SMS CHANNEL", accent = Palette.Amber) {
            ToggleRow("SMS enabled", settings.smsEnabled, Palette.Amber) {
                onUpdate(settings.copy(smsEnabled = it))
            }
            Spacer(Modifier.height(6.dp))
            TextRow("Emergency number", settings.smsNumber, KeyboardType.Phone) {
                onUpdate(settings.copy(smsNumber = it))
            }
            Text(
                "Requires SEND_SMS permission. Offline fallback channel.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // --- HTTP channel ---
        Panel(title = "HTTP CHANNEL", accent = Palette.Teal) {
            ToggleRow("HTTP enabled", settings.httpEnabled, Palette.Teal) {
                onUpdate(settings.copy(httpEnabled = it))
            }
            Spacer(Modifier.height(6.dp))
            TextRow("Endpoint URL", settings.httpUrl, KeyboardType.Uri) {
                onUpdate(settings.copy(httpUrl = it))
            }
            Text(
                "Primary scalable channel; feeds the response console.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(display, color = Palette.Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Palette.Amber,
                activeTrackColor = Palette.Amber,
                inactiveTrackColor = Palette.SurfaceAlt,
            ),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = accent,
                uncheckedTrackColor = Palette.SurfaceAlt,
            ),
        )
    }
}

@Composable
private fun TextRow(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            autoCorrect = false,
        ),
    )
}

@Composable
private fun ModeButton(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent else Palette.SurfaceAlt,
            contentColor = if (selected) Color.Black else Palette.TextDim,
        ),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private fun fmtF(v: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
