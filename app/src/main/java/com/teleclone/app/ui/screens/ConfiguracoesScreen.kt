package com.teleclone.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teleclone.app.data.model.CloneSettings
import com.teleclone.app.ui.theme.AccentRed
import com.teleclone.app.ui.theme.AccentRedDark
import com.teleclone.app.ui.theme.BackgroundDeep
import com.teleclone.app.ui.theme.BorderSubtle
import com.teleclone.app.ui.theme.SurfaceCard
import com.teleclone.app.ui.theme.SurfaceElevated
import com.teleclone.app.ui.theme.TextPrimary
import com.teleclone.app.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun ConfiguracoesScreen(
    settings: CloneSettings,
    cacheSizeMb: Double,
    onDelayChange: (Float) -> Unit,
    onIncludeTextChange: (Boolean) -> Unit,
    onIncludePhotosChange: (Boolean) -> Unit,
    onIncludeVideosChange: (Boolean) -> Unit,
    onIncludeAudioChange: (Boolean) -> Unit,
    onIncludeDocumentsChange: (Boolean) -> Unit,
    onStripMentionsChange: (Boolean) -> Unit,
    onSignatureChange: (String) -> Unit,
    onClearCache: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Configurações",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
        )

        // Cadência Anti-Ban
        SettingsSectionCard(
            title = "Cadência Anti-Ban",
            icon = Icons.Rounded.Speed,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Intervalo entre envios",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = String.format(Locale.US, "%.1fs", settings.delaySeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed,
                )
            }
            Slider(
                value = settings.delaySeconds.coerceIn(1.0f, 10.0f),
                onValueChange = onDelayChange,
                valueRange = 1.0f..10.0f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentRed,
                    activeTrackColor = AccentRed,
                    inactiveTrackColor = SurfaceElevated,
                ),
            )
            Text(
                text = "Aguarda entre 1.0s e 10.0s após cada postagem para evitar FloodWait.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        // Filtros de Mídia
        SettingsSectionCard(
            title = "Filtros de Mídia",
            icon = Icons.Rounded.FilterList,
        ) {
            SettingsSwitchRow("Texto", settings.includeText, onIncludeTextChange)
            SettingsSwitchRow("Fotos", settings.includePhotos, onIncludePhotosChange)
            SettingsSwitchRow("Vídeos", settings.includeVideos, onIncludeVideosChange)
            SettingsSwitchRow("Áudios", settings.includeAudio, onIncludeAudioChange)
            SettingsSwitchRow("Documentos", settings.includeDocuments, onIncludeDocumentsChange)
        }

        // Tratamento de Legenda
        SettingsSectionCard(
            title = "Tratamento de Legenda",
            icon = Icons.Rounded.TextFields,
        ) {
            SettingsSwitchRow(
                label = "Remover @menções e links",
                checked = settings.stripMentionsAndLinks,
                onCheckedChange = onStripMentionsChange,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.customSignature,
                onValueChange = onSignatureChange,
                label = { Text("Assinatura ao final do post") },
                placeholder = { Text("Opcional", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentRed,
                    focusedBorderColor = AccentRed,
                    unfocusedBorderColor = BorderSubtle,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedLabelColor = AccentRed,
                    unfocusedLabelColor = TextSecondary,
                ),
                shape = RoundedCornerShape(12.dp),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Armazenamento
        SettingsSectionCard(
            title = "Armazenamento",
            icon = Icons.Rounded.Storage,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cache temporário em uso",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = String.format(Locale.US, "%.2f MB", cacheSizeMb),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClearCache,
                border = BorderStroke(1.dp, AccentRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Limpar Cache Temporário",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentRed,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
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
