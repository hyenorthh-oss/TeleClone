package com.teleclone.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class CloneProgressState(
    val activeTask: CloneTaskEntity? = null,
    val statusMessage: String = "",
    val floodWaitSeconds: Int? = null,
)
