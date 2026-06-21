package com.shotsense.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shotsense.app.ui.theme.Palette

/** Titled instrument panel with an accent header tab. */
@Composable
fun Panel(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 4.dp, height = 14.dp)) {
                    drawRoundRect(color = accent, cornerRadius = CornerRadius(2f, 2f))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Label on the left (dim), bold monospace value on the right. */
@Composable
fun ReadoutRow(label: String, value: String, valueColor: Color = Palette.TextPrimary) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Horizontal level meter. [fraction] is the filled portion (0..1); a red line
 * marks the trigger threshold at [thresholdFraction].
 */
@Composable
fun LevelMeter(
    fraction: Float,
    thresholdFraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Palette.SurfaceAlt, RoundedCornerShape(4.dp)),
    ) {
        val w = size.width
        val h = size.height
        val fillW = w * fraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            size = Size(fillW, h),
            cornerRadius = CornerRadius(6f, 6f),
        )
        val x = w * thresholdFraction.coerceIn(0f, 1f)
        drawLine(
            color = Palette.Red,
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 3f,
        )
    }
}
