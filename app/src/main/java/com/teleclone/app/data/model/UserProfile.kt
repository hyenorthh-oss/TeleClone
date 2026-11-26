package com.teleclone.app.data.model

data class UserProfile(
    val id: Long,
    val fullName: String,
    val username: String?,
    val phoneNumber: String,
    val avatarLocalPath: String?,
)
