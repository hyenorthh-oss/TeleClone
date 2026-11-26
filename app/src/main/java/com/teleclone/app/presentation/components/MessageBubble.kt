package com.teleclone.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teleclone.app.domain.model.Message
import com.teleclone.app.ui.theme.AccentRed
import com.teleclone.app.ui.theme.AccentRedDark
import com.teleclone.app.ui.theme.SurfaceCard
import com.teleclone.app.ui.theme.TextPrimary
import com.teleclone.app.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(message: Message) {
    val shape = if (message.isOutgoing) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (message.isOutgoing) AccentRedDark else SurfaceCard,
                    shape = shape,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!message.isOutgoing) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentRed,
                )
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun formatTimestamp(epochSeconds: Long): String {
    return try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
    } catch (_: Exception) { "" }
}
