package com.teleclone.app.data.model

data class CloneSettings(
    val delaySeconds: Float = 2.5f,
    val includeText: Boolean = true,
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = true,
    val includeAudio: Boolean = true,
    val includeDocuments: Boolean = true,
    val stripMentionsAndLinks: Boolean = false,
    val customSignature: String = "",
)
