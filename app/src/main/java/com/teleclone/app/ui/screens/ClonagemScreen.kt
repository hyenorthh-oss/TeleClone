package com.teleclone.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
import java.io.File

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
    onRefreshChats: () -> Unit,
    onStartCloning: (Context, TelegramChatItem, TelegramChatItem) -> Unit,
    onPauseCloning: (Context) -> Unit,
    onResumeCloning: (Context, Long) -> Unit,
    onCancelCloning: (Context) -> Unit,
) {
    val context = LocalContext.current

    var selectionMode by remember { mutableStateOf(SelectionMode.SOURCE) }
    var selectedSource by remember { mutableStateOf<TelegramChatItem?>(null) }
    var selectedTarget by remember { mutableStateOf<TelegramChatItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val displayedTask = serviceProgress.activeTask ?: activeDbTask
    val isTaskRunning = displayedTask?.status == CloneTaskStatus.RUNNING

    val visibleChats = remember(chats, selectionMode, searchQuery) {
        val byPermission = if (selectionMode == SelectionMode.TARGET) {
            chats.filter { it.canSendMessages }
        } else {
            chats
        }
        if (searchQuery.isBlank()) {
            byPermission
        } else {
            byPermission.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        // Clean header
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
            )
            IconButton(onClick = onRefreshChats) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Atualizar canais",
                    tint = TextSecondary,
                )
            }
        }

        // Active Progress Panel when RUNNING / PAUSED / ERROR
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

        // ORIGEM & DESTINO side-by-side buttons
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
                onClick = { selectionMode = SelectionMode.SOURCE },
            )
            ModeSelectorCard(
                modifier = Modifier.weight(1f),
                title = "DESTINO",
                subtitle = "Para onde enviar",
                icon = Icons.Rounded.Upload,
                isActive = selectionMode == SelectionMode.TARGET,
                onClick = { selectionMode = SelectionMode.TARGET },
            )
        }

        // Visual confirmations below buttons
        if (selectedSource != null || selectedTarget != null) {
            Spacer(modifier = Modifier.height(10.dp))
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

        Spacer(modifier = Modifier.height(10.dp))

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar...", color = TextSecondary) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
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

        Spacer(modifier = Modifier.height(8.dp))

        // Chat / Channel list
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                        items(visibleChats, key = { it.id }) { item ->
                            val isSelected = if (selectionMode == SelectionMode.SOURCE) {
                                selectedSource?.id == item.id
                            } else {
                                selectedTarget?.id == item.id
                            }
                            VisualChatRow(
                                item = item,
                                isSelected = isSelected,
                                onClick = {
                                    if (selectionMode == SelectionMode.SOURCE) {
                                        selectedSource = item
                                    } else {
                                        selectedTarget = item
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Fixed full-width red CTA button above bottom navigation bar
        val canStart = selectedSource != null &&
            selectedTarget != null &&
            selectedSource?.id != selectedTarget?.id &&
            !isTaskRunning

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
private fun ModeSelectorCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        val avatarFile = item.avatarLocalPath?.let { File(it) }?.takeIf { it.exists() }
        if (avatarFile != null) {
            AsyncImage(
                model = avatarFile,
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
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFloodWait) StatusWarning else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed),
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
