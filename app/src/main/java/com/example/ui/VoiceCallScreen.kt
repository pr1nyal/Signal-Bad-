package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraEnhance
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CallConnectionState
import com.example.ui.components.AudioSphereVisualizer
import com.example.ui.components.CameraLivePipOverlay
import com.example.ui.components.FloatingHudOverlay
import com.example.ui.components.SettingsModalSheet
import com.example.ui.components.createTelemetryHdSnapshot
import com.example.ui.theme.CallActiveGreen
import com.example.ui.theme.CallEndRed
import com.example.ui.theme.CallStandbyAmber
import com.example.viewmodel.LiveCallViewModel
import java.util.Locale

@Composable
fun VoiceCallScreen(
    viewModel: LiveCallViewModel
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val isScanningModels by viewModel.isScanningModels.collectAsStateWithLifecycle()
    val scanStatusMessage by viewModel.scanStatusMessage.collectAsStateWithLifecycle()
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBanner.collectAsStateWithLifecycle()
    val liveCaption by viewModel.liveCaption.collectAsStateWithLifecycle()
    val hudPayload by viewModel.hudPayload.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isPttMode by viewModel.isPttMode.collectAsStateWithLifecycle()
    val isPttPressed by viewModel.isPttPressed.collectAsStateWithLifecycle()
    val isCameraStreaming by viewModel.isCameraStreaming.collectAsStateWithLifecycle()
    val callDurationSeconds by viewModel.callDurationSeconds.collectAsStateWithLifecycle()

    val inputRms by viewModel.audioEngine.inputRms.collectAsStateWithLifecycle()
    val outputRms by viewModel.audioEngine.outputRms.collectAsStateWithLifecycle()
    val spectrumBands by viewModel.audioEngine.spectrumBands.collectAsStateWithLifecycle()

    var showSettingsModal by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.startCall()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        viewModel.toggleCameraStreaming()
    }

    if (showSettingsModal) {
        BackHandler { showSettingsModal = false }
        SettingsModalSheet(
            settings = settings,
            availableModels = availableModels,
            isScanningModels = isScanningModels,
            scanStatusMessage = scanStatusMessage,
            onUpdateSettings = viewModel::updateSettings,
            onSelectPreset = viewModel::applySystemPreset,
            onScanModels = viewModel::triggerModelAutoScan,
            onTriggerHighThinking = viewModel::runHighThinkingHudAnalysis,
            onTriggerSearchGrounding = viewModel::runGroundedSearchHud,
            onTriggerVisualHud = viewModel::generateVisualHudArtifact,
            onDismiss = { showSettingsModal = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .testTag("voice_call_root")
    ) {
        // 1. Center Sphere Visualizer
        AudioSphereVisualizer(
            callState = callState,
            inputRms = inputRms,
            outputRms = outputRms,
            spectrumBands = spectrumBands,
            vadThreshold = settings.vadThreshold,
            isPttPressed = isPttPressed,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 96.dp)
        )

        // 2. Minimalist Top Bar + Conditional Floating HUD Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MinimalistTopHeader(
                callState = callState,
                modelName = settings.selectedModel.removePrefix("models/"),
                durationSeconds = callDurationSeconds,
                isDarkTheme = settings.isDarkTheme,
                onToggleTheme = {
                    viewModel.updateSettings { it.copy(isDarkTheme = !it.isDarkTheme) }
                },
                onOpenSettings = { showSettingsModal = true }
            )

            AnimatedVisibility(
                visible = !errorBanner.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .border(1.dp, CallEndRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                    color = colorScheme.surface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = errorBanner.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CallEndRed,
                        modifier = Modifier.padding(10.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Floating HUD appears only when code/commands are received
            FloatingHudOverlay(
                payload = hudPayload,
                onCopyClick = { viewModel.copyHudCodeToClipboard(context) },
                onDismiss = viewModel::dismissHud
            )
        }

        // 3. Optional Camera PiP
        AnimatedVisibility(
            visible = isCameraStreaming,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
        ) {
            CameraLivePipOverlay(
                hasCameraPermission = hasCameraPermission,
                onPeriodicFrame = viewModel::onLiveCameraFrame,
                onSendHdSnapshot = viewModel::sendHdSnapshot
            )
        }

        // 4. Clean Bottom Area: Neat Live Captions Line + Strict Row of Call Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Neat Live Captions String
            CleanLiveCaptionsLine(
                captionText = liveCaption,
                callState = callState,
                isPttMode = isPttMode,
                isPttPressed = isPttPressed
            )

            // Strict Row of Call Control Buttons (No clutter or outer container slab)
            StrictCallControlRow(
                isCallActive = viewModel.isCallActive(),
                isMuted = isMuted,
                isPttMode = isPttMode,
                isPttPressed = isPttPressed,
                isCameraStreaming = isCameraStreaming,
                onToggleMute = viewModel::toggleMute,
                onTogglePttMode = viewModel::togglePttMode,
                onPttPressChanged = viewModel::setPttPressed,
                onToggleCall = {
                    if (viewModel.isCallActive()) {
                        viewModel.endCall()
                    } else {
                        val grantedNow = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        hasAudioPermission = grantedNow
                        if (grantedNow) {
                            viewModel.startCall()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onToggleCamera = {
                    val grantedNow = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    hasCameraPermission = grantedNow
                    if (grantedNow) {
                        viewModel.toggleCameraStreaming()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onSendInstantHdSnapshot = {
                    viewModel.sendHdSnapshot(createTelemetryHdSnapshot())
                }
            )
        }
    }
}

@Composable
private fun MinimalistTopHeader(
    callState: CallConnectionState,
    modelName: String,
    durationSeconds: Long,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val (statusLabel, dotColor) = when (callState) {
        CallConnectionState.DISCONNECTED -> modelName to colorScheme.onSurfaceVariant
        CallConnectionState.CONNECTING -> "Подключение…" to CallStandbyAmber
        CallConnectionState.LISTENING -> "В эфире" to CallActiveGreen
        CallConnectionState.MODEL_SPEAKING -> "Gemini говорит" to colorScheme.primary
        CallConnectionState.BARGE_IN -> "Прерывание" to colorScheme.onBackground
        CallConnectionState.RECONNECTING -> "Реконнект…" to CallStandbyAmber
        CallConnectionState.ERROR -> "Ошибка" to CallEndRed
    }

    val mins = durationSeconds / 60
    val secs = durationSeconds % 60
    val timerText = if (durationSeconds > 0) String.format(Locale.US, " • %02d:%02d", mins, secs) else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = "$statusLabel$timerText",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("call_status_label")
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface)
                    .border(1.dp, colorScheme.outline, CircleShape)
                    .testTag("theme_toggle_button")
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Переключить тему Dark/Light",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface)
                    .border(1.dp, colorScheme.outline, CircleShape)
                    .testTag("open_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Открыть настройки",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CleanLiveCaptionsLine(
    captionText: String,
    callState: CallConnectionState,
    isPttMode: Boolean,
    isPttPressed: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayText = when {
        isPttMode && isPttPressed -> "Передача PTT…"
        captionText.isNotBlank() -> captionText
        callState == CallConnectionState.LISTENING -> "Слушаю…"
        callState == CallConnectionState.MODEL_SPEAKING -> "…"
        else -> "Нажмите кнопку вызова для начала разговора"
    }

    Text(
        text = displayText,
        style = MaterialTheme.typography.bodyLarge,
        color = if (captionText.isNotBlank()) colorScheme.onBackground else colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag("live_captions_panel")
    )
}

@Composable
private fun StrictCallControlRow(
    isCallActive: Boolean,
    isMuted: Boolean,
    isPttMode: Boolean,
    isPttPressed: Boolean,
    isCameraStreaming: Boolean,
    onToggleMute: () -> Unit,
    onTogglePttMode: () -> Unit,
    onPttPressChanged: (Boolean) -> Unit,
    onToggleCall: () -> Unit,
    onToggleCamera: () -> Unit,
    onSendInstantHdSnapshot: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Mute
        MinimalRoundCallButton(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = "Микрофон",
            active = isMuted,
            activeColor = CallEndRed,
            tag = "btn_mute_toggle",
            onClick = onToggleMute
        )

        // 2. PTT (Walkie-Talkie)
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isPttPressed -> colorScheme.primary
                        isPttMode -> colorScheme.primary.copy(alpha = 0.16f)
                        else -> colorScheme.surface
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (isPttPressed || isPttMode) colorScheme.primary else colorScheme.outline,
                    shape = CircleShape
                )
                .pointerInput(isPttMode) {
                    detectTapGestures(
                        onTap = { onTogglePttMode() },
                        onPress = {
                            if (!isPttMode) onTogglePttMode()
                            onPttPressChanged(true)
                            tryAwaitRelease()
                            onPttPressChanged(false)
                        }
                    )
                }
                .testTag("btn_ptt_control"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = "Рация PTT",
                tint = when {
                    isPttPressed -> colorScheme.onPrimary
                    isPttMode -> colorScheme.primary
                    else -> colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp)
            )
        }

        // 3. Center Start / End Call Button
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(if (isCallActive) CallEndRed else colorScheme.primary)
                .clickable(onClick = onToggleCall)
                .testTag("btn_call_main"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isCallActive) Icons.Default.CallEnd else Icons.Default.Call,
                contentDescription = if (isCallActive) "Сбросить звонок" else "Начать звонок",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // 4. Camera Stream
        MinimalRoundCallButton(
            icon = if (isCameraStreaming) Icons.Default.Videocam else Icons.Default.VideocamOff,
            contentDescription = "Камера",
            active = isCameraStreaming,
            activeColor = colorScheme.primary,
            tag = "btn_camera_toggle",
            onClick = onToggleCamera
        )

        // 5. Instant HD Snapshot
        MinimalRoundCallButton(
            icon = Icons.Default.CameraEnhance,
            contentDescription = "HD-Снимок",
            active = false,
            activeColor = colorScheme.primary,
            tag = "btn_hd_snap_quick",
            onClick = onSendInstantHdSnapshot
        )
    }
}

@Composable
private fun MinimalRoundCallButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    tag: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.16f) else colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (active) activeColor else colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) activeColor else colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}
