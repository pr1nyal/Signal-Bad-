package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.model.GeminiModelInfo
import com.example.model.LiveSettings
import com.example.model.SystemPreset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsModalSheet(
    settings: LiveSettings,
    availableModels: List<GeminiModelInfo>,
    isScanningModels: Boolean,
    scanStatusMessage: String?,
    onUpdateSettings: ((LiveSettings) -> LiveSettings) -> Unit,
    onSelectPreset: (SystemPreset) -> Unit,
    onScanModels: () -> Unit,
    onTriggerHighThinking: () -> Unit,
    onTriggerSearchGrounding: () -> Unit,
    onTriggerVisualHud: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme
    var showApiKey by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.testTag("settings_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Настройки Gemini Live",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "WebSocket BidiGenerateContent • 16kHz / 24kHz",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("settings_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть настройки",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outline)

            // Theme Switcher Row (Dark / Light)
            SettingToggleRow(
                title = "Темная тема (Dark Mode)",
                subtitle = if (settings.isDarkTheme) "Графитовый фон #121212 и карточки #1E1E1E" else "Мягкий светлый фон #F5F5F7",
                checked = settings.isDarkTheme,
                tag = "switch_dark_theme",
                onCheckedChange = { dark -> onUpdateSettings { it.copy(isDarkTheme = dark) } }
            )

            HorizontalDivider(color = colorScheme.outline)

            // 1. API Key (Strictly from SharedPreferences)
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { newKey -> onUpdateSettings { it.copy(apiKey = newKey) } },
                label = { Text("Gemini API Key (SharedPreferences)") },
                placeholder = { Text("Введите ваш Gemini API Key") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Показать ключ",
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outline,
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface,
                    focusedLabelColor = colorScheme.primary,
                    unfocusedLabelColor = colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_key_input")
            )

            // API Version + Auto-Scan Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("v1beta", "v1alpha").forEach { ver ->
                        FilterChip(
                            selected = settings.apiVersion == ver,
                            onClick = { onUpdateSettings { it.copy(apiVersion = ver) } },
                            label = { Text(ver) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorScheme.primary.copy(alpha = 0.16f),
                                selectedLabelColor = colorScheme.primary
                            ),
                            modifier = Modifier.testTag("chip_api_$ver")
                        )
                    }
                }

                Button(
                    onClick = onScanModels,
                    enabled = !isScanningModels,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("btn_scan_models")
                ) {
                    if (isScanningModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Автоскан моделей",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Автоскан моделей", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (!scanStatusMessage.isNullOrBlank()) {
                Text(
                    text = scanStatusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.primary
                )
            }

            // 2. Dynamic Live Model Dropdown
            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = !modelDropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = settings.selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Модель Live / BidiGenerateContent") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary,
                        unfocusedBorderColor = colorScheme.outline,
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .testTag("model_dropdown_field")
                )

                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                    modifier = Modifier.background(colorScheme.surfaceVariant)
                ) {
                    availableModels.forEach { modelInfo ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = modelInfo.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.primary
                                    )
                                    Text(
                                        text = modelInfo.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurface
                                    )
                                }
                            },
                            onClick = {
                                onUpdateSettings { it.copy(selectedModel = modelInfo.name) }
                                modelDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Voice Selection
            Text(
                text = "Голосовой профиль (PrebuiltVoiceConfig)",
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir").forEach { voice ->
                    FilterChip(
                        selected = settings.voiceName == voice,
                        onClick = { onUpdateSettings { it.copy(voiceName = voice) } },
                        label = { Text(voice) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = colorScheme.primary
                        )
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outline)

            // 3. System Prompt Presets («Прямой без цензуры», «Технарь», «Сброс»)
            Text(
                text = "Пресеты системного промпта",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SystemPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.activePreset == preset,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = colorScheme.primary
                        ),
                        modifier = Modifier.testTag("preset_${preset.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { txt -> onUpdateSettings { it.copy(systemPrompt = txt) } },
                label = { Text("Системная инструкция") },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outline,
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("system_prompt_input")
            )

            HorizontalDivider(color = colorScheme.outline)

            // 4. Sliders (Temperature, Top-P, VAD порог шума, FPS камеры)
            SettingSliderItem(
                label = "Temperature",
                valueText = String.format(Locale.US, "%.2f", settings.temperature),
                value = settings.temperature,
                valueRange = 0.0f..2.0f,
                onValueChange = { v -> onUpdateSettings { it.copy(temperature = v) } }
            )

            SettingSliderItem(
                label = "Top-P",
                valueText = String.format(Locale.US, "%.2f", settings.topP),
                value = settings.topP,
                valueRange = 0.05f..1.0f,
                onValueChange = { v -> onUpdateSettings { it.copy(topP = v) } }
            )

            SettingSliderItem(
                label = "VAD порог шума (Barge-In RMS)",
                valueText = String.format(Locale.US, "%.3f", settings.vadThreshold),
                value = settings.vadThreshold,
                valueRange = 0.005f..0.120f,
                onValueChange = { v -> onUpdateSettings { it.copy(vadThreshold = v) } }
            )

            SettingSliderItem(
                label = "FPS камеры (Realtime Video)",
                valueText = String.format(Locale.US, "%.1f FPS", settings.cameraFps),
                value = settings.cameraFps,
                valueRange = 0.2f..5.0f,
                onValueChange = { v -> onUpdateSettings { it.copy(cameraFps = v) } }
            )

            HorizontalDivider(color = colorScheme.outline)

            // 5. Toggles (Switches)
            Text(
                text = "Параметры сессии",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )

            SettingToggleRow(
                title = "outputAudioTranscription (Живые субтитры)",
                subtitle = "Стриминг текстовой транскрипции текущей реплики модели",
                checked = settings.outputAudioTranscription,
                tag = "switch_transcription",
                onCheckedChange = { c -> onUpdateSettings { it.copy(outputAudioTranscription = c) } }
            )

            SettingToggleRow(
                title = "Affective Dialog",
                subtitle = "Адаптация интонации к эмоциям голоса (v1alpha)",
                checked = settings.affectiveDialog,
                tag = "switch_affective",
                onCheckedChange = { c -> onUpdateSettings { it.copy(affectiveDialog = c) } }
            )

            SettingToggleRow(
                title = "BLOCK_NONE",
                subtitle = "Отключение цензуры фильтров безопасности (SafetySettings)",
                checked = settings.blockNone,
                tag = "switch_block_none",
                onCheckedChange = { c -> onUpdateSettings { it.copy(blockNone = c) } }
            )

            SettingToggleRow(
                title = "Google Search Grounding",
                subtitle = "Поиск актуальных данных в реальном времени",
                checked = settings.googleSearch,
                tag = "switch_google_search",
                onCheckedChange = { c -> onUpdateSettings { it.copy(googleSearch = c) } }
            )

            SettingToggleRow(
                title = "Авто-реконнект WebSocket",
                subtitle = "Автоматическое переподключение при обрыве связи",
                checked = settings.autoReconnect,
                tag = "switch_auto_reconnect",
                onCheckedChange = { c -> onUpdateSettings { it.copy(autoReconnect = c) } }
            )

            SettingToggleRow(
                title = "Wake Lock",
                subtitle = "Удержание процессора и экрана во время звонка",
                checked = settings.wakeLock,
                tag = "switch_wake_lock",
                onCheckedChange = { c -> onUpdateSettings { it.copy(wakeLock = c) } }
            )

            HorizontalDivider(color = colorScheme.outline)

            // Auxiliary HUD Tools (High Thinking, Search Grounding, 1K/2K/4K Image Generation)
            Text(
                text = "Инструменты HUD-оверлея",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onTriggerHighThinking()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("hud_btn_thinking")
                ) {
                    Text("Thinking HIGH (Argon)", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        onTriggerSearchGrounding()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("hud_btn_search")
                ) {
                    Text("Search Grounding", style = MaterialTheme.typography.labelSmall)
                }
                listOf("1K", "2K", "4K").forEach { size ->
                    OutlinedButton(
                        onClick = {
                            onTriggerVisualHud(size)
                            onDismiss()
                        },
                        modifier = Modifier.testTag("hud_btn_img_$size")
                    ) {
                        Text("Схема $size", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.background, RoundedCornerShape(10.dp))
                    .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Примечание безопасности: ключи API в APK/SharedPreferences предназначены для прототипирования. Не публикуйте APK с личным ключом.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSliderItem(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurface)
            Text(text = valueText, style = MaterialTheme.typography.labelMedium, color = colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = colorScheme.primary,
                activeTrackColor = colorScheme.primary,
                inactiveTrackColor = colorScheme.outline
            )
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.onPrimary,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                uncheckedTrackColor = colorScheme.surfaceVariant
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
