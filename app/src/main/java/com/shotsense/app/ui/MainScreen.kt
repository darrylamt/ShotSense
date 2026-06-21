package com.shotsense.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shotsense.app.model.DetectorState
import com.shotsense.app.model.FusionStatus
import com.shotsense.app.model.RunState
import com.shotsense.app.model.ShotRecord
import com.shotsense.app.ui.components.LevelMeter
import com.shotsense.app.ui.components.Panel
import com.shotsense.app.ui.components.ReadoutRow
import com.shotsense.app.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    state: DetectorState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSimulateShot: () -> Unit,
    onSimulateRecoil: () -> Unit,
    onSendTestAlert: () -> Unit,
    onExportCsv: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
    val running = state.runState != RunState.STOPPED

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- Header ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SHOTSENSE", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            StatusChip(state.runState)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.TextDim)
            }
        }

        // --- Start / Stop ---
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!running) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.Black),
                ) { Text("START", fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color.Black),
                ) { Text("STOP", fontWeight = FontWeight.Bold) }
            }
        }

        // --- Sound branch ---
        Panel(title = "SOUND BRANCH", accent = Palette.Amber) {
            ReadoutRow("peak", fmt(state.soundPeak, 3), Palette.Amber)
            ReadoutRow("peak hold", fmt(state.soundPeakHold, 3), Palette.Amber)
            ReadoutRow("over-ambient ratio", fmt(state.soundRatio, 1) + "x", Palette.TextPrimary)
            ReadoutRow("audio source", state.audioSource, Palette.TextDim)
            Spacer(Modifier.height(8.dp))
            LevelMeter(
                fraction = state.soundPeak,
                thresholdFraction = state.saturationThreshold,
                color = Palette.Amber,
            )
            Text(
                "saturation line @ ${fmt(state.saturationThreshold, 2)}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // --- Recoil branch ---
        Panel(title = "RECOIL BRANCH", accent = Palette.Teal) {
            val maxG = (state.recoilThresholdG * 2f).coerceAtLeast(4f)
            ReadoutRow("g-force", fmt(state.recoilG, 2) + " g", Palette.Teal)
            ReadoutRow("recoil hold", fmt(state.recoilHold, 2) + " g", Palette.Teal)
            ReadoutRow(
                "sensor",
                if (state.hasRecoilSensor) state.recoilSensorName else "NONE",
                if (state.hasRecoilSensor) Palette.TextPrimary else Palette.Red,
            )
            ReadoutRow("sample rate", fmt(state.recoilSampleRateHz, 0) + " Hz", Palette.TextPrimary)
            Spacer(Modifier.height(8.dp))
            LevelMeter(
                fraction = state.recoilG / maxG,
                thresholdFraction = state.recoilThresholdG / maxG,
                color = Palette.Teal,
            )
            Text(
                "recoil line @ ${fmt(state.recoilThresholdG, 1)} g",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // --- Fusion ---
        FusionPanel(state)

        // --- Simulation / test buttons ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSimulateRecoil, enabled = running, modifier = Modifier.weight(1f)) {
                Text("SIM RECOIL", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onSimulateShot, enabled = running, modifier = Modifier.weight(1f)) {
                Text("SIM SHOT", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSendTestAlert, modifier = Modifier.weight(1f)) {
                Text("SEND TEST ALERT", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
                Text("EXPORT CSV", style = MaterialTheme.typography.labelSmall)
            }
        }

        // --- Confirmed shots log ---
        Panel(title = "CONFIRMED SHOTS LOG", accent = Palette.Red) {
            if (state.shots.isEmpty()) {
                Text("no events yet", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.shots.take(25).forEach { ShotRow(it) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FusionPanel(state: DetectorState) {
    val (label, color) = when (state.fusionStatus) {
        FusionStatus.WAITING -> "WAITING" to Palette.TextDim
        FusionStatus.SOUND_ONLY -> "SOUND ONLY" to Palette.Amber
        FusionStatus.RECOIL_ONLY -> "RECOIL ONLY" to Palette.Teal
        FusionStatus.CONFIRMED -> "CONFIRMED SHOT" to Palette.Red
    }
    Panel(title = "FUSION", accent = Palette.TextPrimary) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        ReadoutRow("confirmed count", state.confirmedCount.toString(), Palette.Red)
    }
}

@Composable
private fun ShotRow(shot: ShotRecord) {
    val time = remember(shot.epochMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(shot.epochMillis))
    }
    val confidence = if (shot.confirmedByRecoil) "sound+recoil" else "sound only"
    val loc = if (shot.lat != null && shot.lng != null) {
        "${fmt(shot.lat, 5)}, ${fmt(shot.lng, 5)}" + if (shot.approximateLocation) " ~" else ""
    } else {
        "loc unavailable"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceAlt, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time, color = Palette.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(confidence, color = if (shot.confirmedByRecoil) Palette.Red else Palette.Amber, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "peak ${fmt(shot.soundPeak, 3)}   recoil ${fmt(shot.recoilG, 2)}g",
            color = Palette.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(loc, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(shot.alertStatus, color = Palette.Green, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StatusChip(runState: RunState) {
    val (label, color) = when (runState) {
        RunState.STOPPED -> "STOPPED" to Palette.TextDim
        RunState.TEST -> "TEST" to Palette.Amber
        RunState.ARMED -> "ARMED" to Palette.Red
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

private fun fmt(v: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
