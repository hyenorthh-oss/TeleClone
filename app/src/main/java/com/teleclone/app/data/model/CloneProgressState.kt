package com.teleclone.app.data.model

data class CloneProgressState(
    val activeTask: CloneTaskEntity? = null,
    val statusMessage: String = "",
    val floodWaitSeconds: Int? = null,
)
