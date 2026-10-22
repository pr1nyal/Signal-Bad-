package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import com.example.model.CallConnectionState
import com.example.ui.theme.CallActiveGreen
import com.example.ui.theme.CallEndRed
import com.example.ui.theme.CallStandbyAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AudioSphereVisualizer(
    callState: CallConnectionState,
    inputRms: Float,
    outputRms: Float,
    spectrumBands: FloatArray,
    vadThreshold: Float,
    isPttPressed: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "sphere_transition")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sphere_phase"
    )

    val secondaryPhase by infiniteTransition.animateFloat(
        initialValue = (2f * PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sphere_secondary_phase"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sphere_breath"
    )

    val avgSpectrum = if (spectrumBands.isNotEmpty()) {
        spectrumBands.average().toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    val targetEnergy = when (callState) {
        CallConnectionState.MODEL_SPEAKING -> (outputRms * 1.8f + avgSpectrum * 0.35f).coerceIn(0.05f, 0.85f)
        CallConnectionState.LISTENING -> (inputRms * 2.0f + avgSpectrum * 0.25f).coerceIn(0.02f, 0.85f)
        CallConnectionState.BARGE_IN -> 0.55f
        CallConnectionState.CONNECTING, CallConnectionState.RECONNECTING -> 0.14f
        else -> 0.01f
    }

    // Smooth low-stiffness spring eliminates sudden jumps and jitter during speech
    val smoothedEnergy by animateFloatAsState(
        targetValue = targetEnergy,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "smoothed_energy"
    )

    val primaryContour = when (callState) {
        CallConnectionState.DISCONNECTED -> colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        CallConnectionState.CONNECTING, CallConnectionState.RECONNECTING -> CallStandbyAmber
        CallConnectionState.LISTENING -> if (isPttPressed) CallActiveGreen else colorScheme.primary
        CallConnectionState.MODEL_SPEAKING -> colorScheme.primary
        CallConnectionState.BARGE_IN -> colorScheme.onBackground
        CallConnectionState.ERROR -> CallEndRed
    }

    val secondaryContour = when (callState) {
        CallConnectionState.DISCONNECTED -> colorScheme.outline
        CallConnectionState.MODEL_SPEAKING -> colorScheme.onBackground.copy(alpha = 0.85f)
        else -> colorScheme.onBackground.copy(alpha = 0.65f)
    }

    val surfaceCore = colorScheme.surface

    Box(
        modifier = modifier.testTag("audio_sphere_visualizer"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minDim = min(size.width, size.height)
            val baseRadius = minDim * 0.25f * breathScale * (1f + smoothedEnergy * 0.20f)

            // 1. Soft Ambient Halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryContour.copy(alpha = (0.15f + smoothedEnergy * 0.16f).coerceAtMost(0.38f)),
                        primaryContour.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 2.15f
                ),
                radius = baseRadius * 2.15f,
                center = center
            )

            // 2. Smooth Continuous Acoustic Contours (no dashed ticks around the sphere)
            for (layer in 0..2) {
                val path = Path()
                val steps = 120
                val layerScale = 1f - layer * 0.075f
                val layerOffset = layer * 1.15f

                for (p in 0..steps) {
                    val theta = (p.toFloat() / steps) * (2f * PI).toFloat()
                    val waveA = sin(theta * (2 + layer) + phase + layerOffset)
                    val waveB = cos(theta * (3 - layer) + secondaryPhase - layerOffset)
                    val waveC = sin(theta * 4 - phase * 0.7f)

                    val amplitude = 0.018f + smoothedEnergy * (0.065f - layer * 0.012f)
                    val displacement = 1f + (waveA * 0.45f + waveB * 0.35f + waveC * 0.20f) * amplitude
                    val r = baseRadius * layerScale * displacement

                    val x = center.x + cos(theta) * r
                    val y = center.y + sin(theta) * r
                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                val contourColor = if (layer == 0) {
                    secondaryContour.copy(alpha = 0.82f)
                } else {
                    primaryContour.copy(alpha = 0.72f - layer * 0.18f)
                }

                drawPath(
                    path = path,
                    color = contourColor,
                    style = Stroke(
                        width = (3.8f - layer * 0.8f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // 3. Smooth Inner Core Sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        surfaceCore,
                        surfaceCore.copy(alpha = 0.94f),
                        primaryContour.copy(alpha = (0.18f + smoothedEnergy * 0.14f).coerceAtMost(0.36f))
                    ),
                    center = center,
                    radius = baseRadius * 0.78f
                ),
                radius = baseRadius * 0.78f,
                center = center
            )

            // 4. Clean Minimalist Inner Ring
            drawCircle(
                color = secondaryContour.copy(alpha = 0.85f),
                radius = baseRadius * 0.78f,
                center = center,
                style = Stroke(width = 1.8f)
            )
        }
    }
}
