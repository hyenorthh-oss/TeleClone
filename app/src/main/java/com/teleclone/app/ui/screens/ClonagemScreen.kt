package com.teleclone.app.ui.screens

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.teleclone.app.data.model.BatchModeConfig
import com.teleclone.app.data.model.BatchModeValidator
import com.teleclone.app.data.model.BatchValidationResult
import com.teleclone.app.data.model.CloneProgressState
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import com.teleclone.app.data.model.TelegramChatItem
import com.teleclone.app.ui.theme.AccentRed
import com.teleclone.app.ui.theme.AccentRedDark
import com.teleclone.app.ui.theme.BackgroundDeep
import com.teleclone.app.ui.theme.BorderSubtle
import com.teleclone.app.ui.theme.StatusSuccess
import com.teleclone.app.ui.theme.StatusWarning
import com.teleclone.app.ui.theme.SurfaceCard
import com.teleclone.app.ui.theme.SurfaceElevated
import com.teleclone.app.ui.theme.TextPrimary
import com.teleclone.app.ui.theme.TextSecondary

private enum class SelectionMode {
    SOURCE,
    TARGET,
}

@Composable
fun ClonagemScreen(
    chats: List<TelegramChatItem>,
    isLoading: Boolean,
    errorMessage: String?,
    serviceProgress: CloneProgressState,
    activeDbTask: CloneTaskEntity?,
    selectedSourceId: Long?,
    selectedTargetId: Long?,
    batchConfig: BatchModeConfig,
    batchValidation: BatchValidationResult,
    onSelectSource: (Long) -> Unit,
    onSelectTarget: (Long) -> Unit,
    onToggleBatchMode: () -> Unit,
    onBatchBlockSizeChange: (String) -> Unit,
    onBatchJitterMinChange: (String) -> Unit,
    onBatchJitterMaxChange: (String) -> Unit,
    onBatchAutoPauseChange: (Boolean) -> Unit,
    onBatchVariableJitterChange: (Boolean) -> Unit,
    onBatchPreserveAlbumChange: (Boolean) -> Unit,
    onBatchRiskAcceptedChange: (Boolean) -> Unit,
    onRefreshChats: () -> Unit,
    onStartCloning: (Context, TelegramChatItem, TelegramChatItem) -> Unit,
    onPauseCloning: (Context) -> Unit,
    onResumeCloning: (Context, Long) -> Unit,
    onCancelCloning: (Context) -> Unit,
) {
    val context = LocalContext.current

    var selectionMode by rememberSaveable { mutableStateOf(SelectionMode.SOURCE) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val selectedSource = remember(chats, selectedSourceId) {
        selectedSourceId?.let { id -> chats.firstOrNull { it.id == id } }
    }
    val selectedTarget = remember(chats, selectedTargetId) {
        selectedTargetId?.let { id -> chats.firstOrNull { it.id == id } }
    }

    val displayedTask = serviceProgress.activeTask ?: activeDbTask
    val isTaskRunning = displayedTask?.status == CloneTaskStatus.RUNNING

    val visibleChats = remember(chats, selectionMode, searchQuery) {
        val byPermission = if (selectionMode == SelectionMode.TARGET) {
            chats.filter { it.canSendMessages }
        } else {
            chats
        }
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            byPermission
        } else {
            byPermission.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    val modeIconRotation by animateFloatAsState(
        targetValue = if (batchConfig.isBatchMode) 180f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "TrocarModoIconRotation",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        // Header with title, "Trocar Modo" pill button, and Refresh icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nova Clonagem",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (batchConfig.isBatchMode) AccentRedDark else SurfaceCard)
                        .border(1.dp, AccentRed, RoundedCornerShape(50))
                        .clickable(onClick = onToggleBatchMode)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Trocar Modo",
                        tint = AccentRed,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(modeIconRotation),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Trocar Modo",
                        style = MaterialTheme.typography.labelLarge,
                        color = AccentRed,
                        maxLines = 1,
                    )
                }

                IconButton(onClick = onRefreshChats) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Atualizar canais",
                        tint = TextSecondary,
                    )
                }
            }
        }

        if (!batchConfig.isBatchMode) {
            // ==================== MODO PADRÃO ====================
            if (displayedTask != null &&
                (displayedTask.status == CloneTaskStatus.RUNNING ||
                    displayedTask.status == CloneTaskStatus.PAUSED ||
                    displayedTask.status == CloneTaskStatus.ERROR)
            ) {
                ActiveClonePanel(
                    task = displayedTask,
                    statusText = serviceProgress.statusMessage.ifBlank {
                        when (displayedTask.status) {
                            CloneTaskStatus.PAUSED -> "Clonagem pausada"
                            CloneTaskStatus.ERROR -> "Erro durante a clonagem"
                            else -> "Sincronizando..."
                        }
                    },
                    isFloodWait = serviceProgress.floodWaitSeconds != null,
                    onPauseResume = {
                        if (displayedTask.status == CloneTaskStatus.RUNNING) {
                            onPauseCloning(context)
                        } else {
                            onResumeCloning(context, displayedTask.id)
                        }
                    },
                    onCancel = { onCancelCloning(context) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            SourceTargetSelectorRow(
                selectionMode = selectionMode,
                onSelectMode = { selectionMode = it },
            )

            SelectedChatsConfirmationCard(
                selectedSource = selectedSource,
                selectedTarget = selectedTarget,
            )

            Spacer(modifier = Modifier.height(10.dp))

            ChatSearchField(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ChatListContent(
                    chats = chats,
                    visibleChats = visibleChats,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    selectionMode = selectionMode,
                    selectedSourceId = selectedSourceId,
                    selectedTargetId = selectedTargetId,
                    onRefreshChats = onRefreshChats,
                    onChatClick = { item ->
                        if (selectionMode == SelectionMode.SOURCE) {
                            onSelectSource(item.id)
                        } else {
                            onSelectTarget(item.id)
                        }
                    },
                )
            }
        } else {
            // ==================== MODO LOTE AVANÇADO ====================
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (displayedTask != null &&
                    (displayedTask.status == CloneTaskStatus.RUNNING ||
                        displayedTask.status == CloneTaskStatus.PAUSED ||
                        displayedTask.status == CloneTaskStatus.ERROR)
                ) {
                    item(key = "active_clone_panel", contentType = "panel") {
                        ActiveClonePanel(
                            task = displayedTask,
                            statusText = serviceProgress.statusMessage.ifBlank {
                                when (displayedTask.status) {
                                    CloneTaskStatus.PAUSED -> "Clonagem pausada"
                                    CloneTaskStatus.ERROR -> "Erro durante a clonagem"
                                    else -> "Sincronizando..."
                                }
                            },
                            isFloodWait = serviceProgress.floodWaitSeconds != null,
                            onPauseResume = {
                                if (displayedTask.status == CloneTaskStatus.RUNNING) {
                                    onPauseCloning(context)
                                } else {
                                    onResumeCloning(context, displayedTask.id)
                                }
                            },
                            onCancel = { onCancelCloning(context) },
                        )
                    }
                }

                item(key = "batch_risk_banner", contentType = "risk_banner") {
                    BatchRiskBannerCard()
                }

                item(key = "batch_config_card", contentType = "batch_config") {
                    BatchBlockJitterCard(
                        config = batchConfig,
                        validation = batchValidation,
                        onBlockSizeChange = onBatchBlockSizeChange,
                        onJitterMinChange = onBatchJitterMinChange,
                        onJitterMaxChange = onBatchJitterMaxChange,
                    )
                }

                item(key = "batch_protections_card", contentType = "batch_protections") {
                    BatchProtectionsCard(
                        config = batchConfig,
                        onAutoPauseChange = onBatchAutoPauseChange,
                        onVariableJitterChange = onBatchVariableJitterChange,
                        onPreserveAlbumChange = onBatchPreserveAlbumChange,
                        onRiskAcceptedChange = onBatchRiskAcceptedChange,
                    )
                }

                item(key = "batch_source_target_selector", contentType = "selector") {
                    SourceTargetSelectorRow(
                        selectionMode = selectionMode,
                        onSelectMode = { selectionMode = it },
                    )
                }

                if (selectedSource != null || selectedTarget != null) {
                    item(key = "batch_selected_confirmation", contentType = "confirmation") {
                        SelectedChatsConfirmationCard(
                            selectedSource = selectedSource,
                            selectedTarget = selectedTarget,
                            addTopSpacer = false,
                        )
                    }
                }

                item(key = "batch_search_field", contentType = "search") {
                    ChatSearchField(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                    )
                }

                batchChatListItems(
                    chats = chats,
                    visibleChats = visibleChats,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    selectionMode = selectionMode,
                    selectedSourceId = selectedSourceId,
                    selectedTargetId = selectedTargetId,
                    onRefreshChats = onRefreshChats,
                    onChatClick = { item ->
                        if (selectionMode == SelectionMode.SOURCE) {
                            onSelectSource(item.id)
                        } else {
                            onSelectTarget(item.id)
                        }
                    },
                )
            }
        }

        // Fixed full-width red CTA button above bottom navigation bar
        val hasValidPair = selectedSource != null &&
            selectedTarget != null &&
            selectedSource.id != selectedTarget.id

        val canStart = BatchModeValidator.canStartCloning(
            hasValidSourceAndTarget = hasValidPair,
            isTaskRunning = isTaskRunning,
            config = batchConfig,
            validation = batchValidation,
        )

        Button(
            onClick = {
                val src = selectedSource
                val tgt = selectedTarget
                if (src != null && tgt != null && src.id != tgt.id) {
                    onStartCloning(context, src, tgt)
                }
            },
            enabled = canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentRed,
                disabledContainerColor = SurfaceElevated,
                contentColor = TextPrimary,
                disabledContentColor = TextSecondary,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(52.dp),
        ) {
            Text(
                text = if (isTaskRunning) "Clonagem em andamento" else "Começar Clonagem",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SourceTargetSelectorRow(
    selectionMode: SelectionMode,
    onSelectMode: (SelectionMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeSelectorCard(
            modifier = Modifier.weight(1f),
            title = "ORIGEM",
            subtitle = "De onde copiar",
            icon = Icons.Rounded.Download,
            isActive = selectionMode == SelectionMode.SOURCE,
            onClick = { onSelectMode(SelectionMode.SOURCE) },
        )
        ModeSelectorCard(
            modifier = Modifier.weight(1f),
            title = "DESTINO",
            subtitle = "Para onde enviar",
            icon = Icons.Rounded.Upload,
            isActive = selectionMode == SelectionMode.TARGET,
            onClick = { onSelectMode(SelectionMode.TARGET) },
        )
    }
}

@Composable
private fun SelectedChatsConfirmationCard(
    selectedSource: TelegramChatItem?,
    selectedTarget: TelegramChatItem?,
    addTopSpacer: Boolean = true,
) {
    if (selectedSource == null && selectedTarget == null) return
    if (addTopSpacer) {
        Spacer(modifier = Modifier.height(10.dp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        selectedSource?.let { src ->
            SelectionConfirmationRow(
                label = "${src.title} (Selecionado)",
                role = "ORIGEM",
            )
        }
        selectedTarget?.let { tgt ->
            SelectionConfirmationRow(
                label = "${tgt.title} (Selecionado)",
                role = "DESTINO",
            )
        }
    }
}

@Composable
private fun ChatSearchField(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = { Text("Buscar...", color = TextSecondary) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary)
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Limpar", tint = TextSecondary)
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentRed,
            focusedBorderColor = AccentRed,
            unfocusedBorderColor = BorderSubtle,
            focusedContainerColor = SurfaceElevated,
            unfocusedContainerColor = SurfaceElevated,
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChatListContent(
    chats: List<TelegramChatItem>,
    visibleChats: List<TelegramChatItem>,
    isLoading: Boolean,
    errorMessage: String?,
    selectionMode: SelectionMode,
    selectedSourceId: Long?,
    selectedTargetId: Long?,
    onRefreshChats: () -> Unit,
    onChatClick: (TelegramChatItem) -> Unit,
) {
    when {
        isLoading && chats.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
        }
        !errorMessage.isNullOrBlank() && chats.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentRed,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onRefreshChats) {
                    Text("Tentar novamente", color = TextPrimary)
                }
            }
        }
        visibleChats.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectionMode == SelectionMode.TARGET) {
                        "Nenhum canal ou grupo com permissão de envio"
                    } else {
                        "Nenhum chat encontrado"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = visibleChats,
                    key = { it.id },
                    contentType = { "chat_row" },
                ) { item ->
                    val isSelected = if (selectionMode == SelectionMode.SOURCE) {
                        selectedSourceId == item.id
                    } else {
                        selectedTargetId == item.id
                    }
                    VisualChatRow(
                        item = item,
                        isSelected = isSelected,
                        onClick = { onChatClick(item) },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.batchChatListItems(
    chats: List<TelegramChatItem>,
    visibleChats: List<TelegramChatItem>,
    isLoading: Boolean,
    errorMessage: String?,
    selectionMode: SelectionMode,
    selectedSourceId: Long?,
    selectedTargetId: Long?,
    onRefreshChats: () -> Unit,
    onChatClick: (TelegramChatItem) -> Unit,
) {
    when {
        isLoading && chats.isEmpty() -> {
            item(key = "batch_chats_loading", contentType = "state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentRed)
                }
            }
        }
        !errorMessage.isNullOrBlank() && chats.isEmpty() -> {
            item(key = "batch_chats_error", contentType = "state") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentRed,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = onRefreshChats) {
                        Text("Tentar novamente", color = TextPrimary)
                    }
                }
            }
        }
        visibleChats.isEmpty() -> {
            item(key = "batch_chats_empty", contentType = "state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectionMode == SelectionMode.TARGET) {
                            "Nenhum canal ou grupo com permissão de envio"
                        } else {
                            "Nenhum chat encontrado"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
        else -> {
            items(
                items = visibleChats,
                key = { it.id },
                contentType = { "chat_row" },
            ) { item ->
                val isSelected = if (selectionMode == SelectionMode.SOURCE) {
                    selectedSourceId == item.id
                } else {
                    selectedTargetId == item.id
                }
                VisualChatRow(
                    item = item,
                    isSelected = isSelected,
                    onClick = { onChatClick(item) },
                )
            }
        }
    }
}

@Composable
private fun BatchRiskBannerCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, AccentRed.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = StatusWarning,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Atenção: O envio em grandes blocos aumenta o risco de FloodWait longo, PeerFlood ou restrição temporária na conta. Recomendado apenas para contas secundárias.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

@Composable
private fun BatchBlockJitterCard(
    config: BatchModeConfig,
    validation: BatchValidationResult,
    onBlockSizeChange: (String) -> Unit,
    onJitterMinChange: (String) -> Unit,
    onJitterMaxChange: (String) -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = AccentRed,
        focusedBorderColor = AccentRed,
        unfocusedBorderColor = BorderSubtle,
        focusedContainerColor = SurfaceElevated,
        unfocusedContainerColor = SurfaceElevated,
        focusedLabelColor = AccentRed,
        unfocusedLabelColor = TextSecondary,
        errorBorderColor = AccentRed,
        errorLabelColor = AccentRed,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Modo Lote Avançado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }

        OutlinedTextField(
            value = config.blockSizeText,
            onValueChange = onBlockSizeChange,
            label = { Text("Mensagens por bloco") },
            placeholder = { Text("Ex: 250", color = TextSecondary) },
            isError = !validation.isBlockSizeValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (validation.blockSizeError != null) {
            Text(
                text = validation.blockSizeError,
                style = MaterialTheme.typography.bodySmall,
                color = AccentRed,
            )
        }

        Text(
            text = "Intervalo Jitter (s)",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = config.jitterMinText,
                onValueChange = onJitterMinChange,
                label = { Text("Mínimo (s)") },
                placeholder = { Text("5", color = TextSecondary) },
                isError = !validation.isJitterMinValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.jitterMaxText,
                onValueChange = onJitterMaxChange,
                label = { Text("Máximo (s)") },
                placeholder = { Text("8", color = TextSecondary) },
                isError = !validation.isJitterMaxValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (validation.jitterError != null) {
            Text(
                text = validation.jitterError,
                style = MaterialTheme.typography.bodySmall,
                color = AccentRed,
            )
        }
    }
}

@Composable
private fun BatchProtectionsCard(
    config: BatchModeConfig,
    onAutoPauseChange: (Boolean) -> Unit,
    onVariableJitterChange: (Boolean) -> Unit,
    onPreserveAlbumChange: (Boolean) -> Unit,
    onRiskAcceptedChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Proteções Obrigatórias",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }

        BatchProtectionSwitchRow(
            title = "Pausa automática em FloodWait (429)",
            subtitle = "Aguarda automaticamente o tempo exigido pelo Telegram se houver limite de taxa.",
            checked = config.autoPauseFloodWait,
            onCheckedChange = onAutoPauseChange,
        )

        BatchProtectionSwitchRow(
            title = "Atraso aleatório variável (Jitter)",
            subtitle = "Sorteia um intervalo diferente dentro da faixa [min, max] após cada bloco enviado.",
            checked = config.variableJitterEnabled,
            onCheckedChange = onVariableJitterChange,
        )

        BatchProtectionSwitchRow(
            title = "Preservar integridade de álbuns (mediaAlbumId)",
            subtitle = "Ajusta a fronteira do bloco para nunca partir um álbum ao meio.",
            checked = config.preserveAlbumIntegrity,
            onCheckedChange = onPreserveAlbumChange,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceElevated)
                .border(
                    width = 1.dp,
                    color = if (config.riskAccepted) AccentRed else BorderSubtle,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { onRiskAcceptedChange(!config.riskAccepted) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = config.riskAccepted,
                onCheckedChange = onRiskAcceptedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentRed,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = TextPrimary,
                ),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ciência de risco",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "Entendo os riscos de limitação na conta e assumo a responsabilidade pelo envio em lote.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun BatchProtectionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = AccentRed,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceElevated,
                uncheckedBorderColor = BorderSubtle,
            ),
        )
    }
}

@Composable
private fun ModeSelectorCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isActive) AccentRedDark else SurfaceCard
    val borderColor = if (isActive) AccentRed else BorderSubtle
    val borderWidth = if (isActive) 1.5.dp else 1.dp

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) AccentRed else TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionConfirmationRow(
    label: String,
    role: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = StatusSuccess,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$role: $label",
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VisualChatRow(
    item: TelegramChatItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) AccentRedDark else SurfaceCard
    val borderColor = if (isSelected) AccentRed else BorderSubtle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarPath = item.avatarLocalPath?.takeIf { it.isNotBlank() }
        if (avatarPath != null) {
            AsyncImage(
                model = avatarPath,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(SurfaceElevated)
                    .border(1.dp, BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            val subtitleText = when {
                item.memberCount > 2 -> "${item.memberCount} membros"
                item.isChannel -> "Canal"
                else -> "Conversa"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Selecionado",
                tint = AccentRed,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, BorderSubtle, CircleShape),
            )
        }
    }
}

@Composable
private fun ActiveClonePanel(
    task: CloneTaskEntity,
    statusText: String,
    isFloodWait: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val isRunning = task.status == CloneTaskStatus.RUNNING
    val progress = if (task.totalMessagesEstimate > 0) {
        (task.clonedCount.toFloat() / task.totalMessagesEstimate.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, AccentRed, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = task.sourceTitle,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = task.targetTitle,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (task.totalMessagesEstimate > 0) {
            LinearProgressIndicator(
                progress = { progress },
                color = AccentRed,
                trackColor = SurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        } else {
            LinearProgressIndicator(
                color = AccentRed,
                trackColor = SurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val counterLabel = if (task.totalMessagesEstimate > 0) {
                "${task.clonedCount} / ${task.totalMessagesEstimate} mensagens"
            } else {
                "${task.clonedCount} mensagens"
            }
            Text(
                text = counterLabel,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFloodWait) StatusWarning else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onPauseResume,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "Pausar" else "Retomar",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
            }

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AccentRed),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentRed,
                )
            }
        }
    }
}
