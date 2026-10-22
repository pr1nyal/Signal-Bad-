package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.HudOverlayPayload
import com.example.ui.theme.CallActiveGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

@Composable
fun FloatingHudOverlay(
    payload: HudOverlayPayload?,
    onCopyClick: () -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var copiedFeedback by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = payload != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        if (payload != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("floating_hud_overlay")
                    .border(1.dp, colorScheme.outline, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "HUD Terminal",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = payload.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (payload.codeOrCommand.isNotBlank()) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (onCopyClick()) {
                                                copiedFeedback = true
                                                scope.launch {
                                                    delay(1800L)
                                                    copiedFeedback = false
                                                }
                                            }
                                        }
                                        .testTag("hud_copy_button"),
                                    color = if (copiedFeedback) CallActiveGreen.copy(alpha = 0.16f) else colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (copiedFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Copy HUD Code",
                                            tint = if (copiedFeedback) CallActiveGreen else colorScheme.onSurface,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (copiedFeedback) "Скопировано" else "Копировать",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (copiedFeedback) CallActiveGreen else colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("hud_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть HUD",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (payload.generatedBitmap != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Image(
                            bitmap = payload.generatedBitmap.asImageBitmap(),
                            contentDescription = "HUD Image ${payload.imageResolution}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    if (payload.codeOrCommand.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 42.dp, max = 160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.background)
                                .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = payload.codeOrCommand,
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onBackground
                            )
                        }
                    }

                    if (payload.groundingSources.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        payload.groundingSources.take(3).forEach { src ->
                            Text(
                                text = src,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraLivePipOverlay(
    hasCameraPermission: Boolean,
    onPeriodicFrame: (Bitmap) -> Unit,
    onSendHdSnapshot: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorScheme = MaterialTheme.colorScheme
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var useFrontCamera by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(hasCameraPermission, useFrontCamera) {
        if (!hasCameraPermission) return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            runCatching {
                val provider = cameraProviderFuture.get()
                val preferredSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val fallbackSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val activeSelector = when {
                    provider.hasCamera(preferredSelector) -> preferredSelector
                    provider.hasCamera(fallbackSelector) -> fallbackSelector
                    else -> return@runCatching
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                val rotatedBmp = imageProxy.toRotatedScaledBitmap(maxDimension = 1024)
                                if (rotatedBmp != null) {
                                    latestBitmap = rotatedBmp
                                    onPeriodicFrame(rotatedBmp)
                                }
                            } catch (_: Exception) {
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    activeSelector,
                    preview,
                    imageAnalysis
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val provider = ProcessCameraProvider.getInstance(context).get()
                provider.unbindAll()
            }
            analysisExecutor.shutdown()
        }
    }

    Surface(
        modifier = modifier
            .width(136.dp)
            .border(1.dp, colorScheme.outline, RoundedCornerShape(14.dp))
            .testTag("camera_pip_overlay"),
        shape = RoundedCornerShape(14.dp),
        color = colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp),
                        factory = { previewView }
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surface.copy(alpha = 0.78f))
                            .clickable { useFrontCamera = !useFrontCamera }
                            .testTag("btn_flip_camera"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Переключить камеру",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Камера",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val bmp = latestBitmap ?: createTelemetryHdSnapshot()
                        onSendHdSnapshot(bmp)
                    }
                    .testTag("btn_send_hd_snapshot"),
                color = colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "HD Snapshot",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HD-Снимок",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun ImageProxy.toRotatedScaledBitmap(maxDimension: Int): Bitmap? {
    val rawBitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    val srcWidth = rawBitmap.width
    val srcHeight = rawBitmap.height
    if (srcWidth <= 0 || srcHeight <= 0) return null

    val maxSide = max(srcWidth, srcHeight)
    val scale = if (maxSide > maxDimension) maxDimension.toFloat() / maxSide.toFloat() else 1f

    if (rotation == 0 && scale == 1f) {
        return rawBitmap
    }

    val matrix = Matrix().apply {
        if (scale != 1f) {
            postScale(scale, scale)
        }
        if (rotation != 0) {
            postRotate(rotation.toFloat())
        }
    }
    return Bitmap.createBitmap(rawBitmap, 0, 0, srcWidth, srcHeight, matrix, true)
}

fun createTelemetryHdSnapshot(): Bitmap {
    val width = 1280
    val height = 720
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bgPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            AndroidColor.parseColor("#121212"),
            AndroidColor.parseColor("#1E1E1E"),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#3B82F6")
        textSize = 42f
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#F5F5F7")
        textSize = 28f
    }
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    canvas.drawText("GEMINI MULTIMODAL LIVE • HD SNAPSHOT", 80f, 160f, textPaint)
    canvas.drawText("Timestamp: $ts | Stream: BidiGenerateContent", 80f, 230f, subPaint)
    canvas.drawText("Command: $ adb logcat -s GeminiLiveAudio:V", 80f, 310f, subPaint)
    return bmp
}
