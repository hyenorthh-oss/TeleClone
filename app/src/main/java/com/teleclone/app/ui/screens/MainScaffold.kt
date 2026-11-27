package com.teleclone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.teleclone.app.ui.theme.AccentRed
import com.teleclone.app.ui.theme.BackgroundDeep
import com.teleclone.app.ui.theme.BorderSubtle
import com.teleclone.app.ui.theme.SurfaceCard
import com.teleclone.app.ui.theme.TextSecondary

private data class BottomNavTab(
    val index: Int,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun MainScaffold(viewModel: MainViewModel) {
    // Default initial tab: Clonagem (index 1)
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }

    val chats by viewModel.chats.collectAsState()
    val isLoadingChats by viewModel.isLoadingChats.collectAsState()
    val chatsError by viewModel.chatsError.collectAsState()
    val serviceProgress by viewModel.serviceProgress.collectAsState()
    val activeDbTask by viewModel.activeDbTask.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val totalClonedMessages by viewModel.totalClonedMessages.collectAsState()
    val cloneSettings by viewModel.cloneSettings.collectAsState()
    val cacheSizeMb by viewModel.cacheSizeMb.collectAsState()
    val batchModeConfig by viewModel.batchModeConfig.collectAsState()
    val batchValidation by viewModel.batchValidation.collectAsState()
    val selectedSourceId by viewModel.selectedSourceId.collectAsState()
    val selectedTargetId by viewModel.selectedTargetId.collectAsState()

    val tabs = remember {
        listOf(
            BottomNavTab(0, "Perfil", Icons.Rounded.Person),
            BottomNavTab(1, "Clonagem", Icons.Rounded.Sync),
            BottomNavTab(2, "Configurações", Icons.Rounded.Settings),
        )
    }

    Scaffold(
        containerColor = BackgroundDeep,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 1.dp, color = BorderSubtle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val isSelected = selectedTab == tab.index
                        val tint = if (isSelected) AccentRed else TextSecondary
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    selectedTab = tab.index
                                    if (tab.index == 0) {
                                        viewModel.refreshUserProfile()
                                    } else if (tab.index == 2) {
                                        viewModel.refreshCacheSize()
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isSelected) AccentRed else SurfaceCard),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> PerfilScreen(
                    userProfile = userProfile,
                    totalClonedMessages = totalClonedMessages,
                    onLogout = { viewModel.logout() },
                )
                1 -> ClonagemScreen(
                    chats = chats,
                    isLoading = isLoadingChats,
                    errorMessage = chatsError,
                    serviceProgress = serviceProgress,
                    activeDbTask = activeDbTask,
                    selectedSourceId = selectedSourceId,
                    selectedTargetId = selectedTargetId,
                    batchConfig = batchModeConfig,
                    batchValidation = batchValidation,
                    onSelectSource = { viewModel.selectSourceChat(it) },
                    onSelectTarget = { viewModel.selectTargetChat(it) },
                    onToggleBatchMode = { viewModel.toggleBatchMode() },
                    onBatchBlockSizeChange = { viewModel.updateBatchBlockSize(it) },
                    onBatchJitterMinChange = { viewModel.updateBatchJitterMin(it) },
                    onBatchJitterMaxChange = { viewModel.updateBatchJitterMax(it) },
                    onBatchAutoPauseChange = { viewModel.updateBatchAutoPauseFloodWait(it) },
                    onBatchVariableJitterChange = { viewModel.updateBatchVariableJitter(it) },
                    onBatchPreserveAlbumChange = { viewModel.updateBatchPreserveAlbumIntegrity(it) },
                    onBatchRiskAcceptedChange = { viewModel.updateBatchRiskAccepted(it) },
                    onRefreshChats = { viewModel.refreshChats() },
                    onStartCloning = { context, source, target ->
                        viewModel.startCloning(context, source, target)
                    },
                    onPauseCloning = { context -> viewModel.pauseCloning(context) },
                    onResumeCloning = { context, taskId -> viewModel.resumeCloning(context, taskId) },
                    onCancelCloning = { context -> viewModel.cancelCloning(context) },
                )
                2 -> ConfiguracoesScreen(
                    settings = cloneSettings,
                    cacheSizeMb = cacheSizeMb,
                    onDelayChange = { viewModel.updateDelaySeconds(it) },
                    onIncludeTextChange = { viewModel.updateIncludeText(it) },
                    onIncludePhotosChange = { viewModel.updateIncludePhotos(it) },
                    onIncludeVideosChange = { viewModel.updateIncludeVideos(it) },
                    onIncludeAudioChange = { viewModel.updateIncludeAudio(it) },
                    onIncludeDocumentsChange = { viewModel.updateIncludeDocuments(it) },
                    onStripMentionsChange = { viewModel.updateStripMentionsAndLinks(it) },
                    onSignatureChange = { viewModel.updateCustomSignature(it) },
                    onClearCache = { viewModel.clearCache() },
                )
            }
        }
    }
}
