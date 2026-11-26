package com.teleclone.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teleclone.app.data.model.CloneSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "teleclone_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val API_ID = intPreferencesKey("api_id")
        val API_HASH = stringPreferencesKey("api_hash")
        val PHONE_NUMBER = stringPreferencesKey("phone_number")
        val DELAY_SECONDS = floatPreferencesKey("delay_seconds")
        val INCLUDE_TEXT = booleanPreferencesKey("include_text")
        val INCLUDE_PHOTOS = booleanPreferencesKey("include_photos")
        val INCLUDE_VIDEOS = booleanPreferencesKey("include_videos")
        val INCLUDE_AUDIO = booleanPreferencesKey("include_audio")
        val INCLUDE_DOCUMENTS = booleanPreferencesKey("include_documents")
        val STRIP_MENTIONS_AND_LINKS = booleanPreferencesKey("strip_mentions_and_links")
        val CUSTOM_SIGNATURE = stringPreferencesKey("custom_signature")
    }

    val apiIdFlow: Flow<Int> = context.settingsDataStore.data.map { it[Keys.API_ID] ?: 0 }
    val apiHashFlow: Flow<String> = context.settingsDataStore.data.map { it[Keys.API_HASH] ?: "" }
    val phoneNumberFlow: Flow<String> = context.settingsDataStore.data.map { it[Keys.PHONE_NUMBER] ?: "" }

    val cloneSettingsFlow: Flow<CloneSettings> = context.settingsDataStore.data.map { prefs ->
        CloneSettings(
            delaySeconds = prefs[Keys.DELAY_SECONDS] ?: 2.5f,
            includeText = prefs[Keys.INCLUDE_TEXT] ?: true,
            includePhotos = prefs[Keys.INCLUDE_PHOTOS] ?: true,
            includeVideos = prefs[Keys.INCLUDE_VIDEOS] ?: true,
            includeAudio = prefs[Keys.INCLUDE_AUDIO] ?: true,
            includeDocuments = prefs[Keys.INCLUDE_DOCUMENTS] ?: true,
            stripMentionsAndLinks = prefs[Keys.STRIP_MENTIONS_AND_LINKS] ?: false,
            customSignature = prefs[Keys.CUSTOM_SIGNATURE] ?: "",
        )
    }

    suspend fun getApiCredentials(): Pair<Int, String> {
        val prefs = context.settingsDataStore.data.first()
        return (prefs[Keys.API_ID] ?: 0) to (prefs[Keys.API_HASH] ?: "")
    }

    suspend fun getCloneSettings(): CloneSettings = cloneSettingsFlow.first()

    suspend fun saveApiCredentials(apiId: Int, apiHash: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.API_ID] = apiId
            prefs[Keys.API_HASH] = apiHash
        }
    }

    suspend fun savePhoneNumber(phoneNumber: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PHONE_NUMBER] = phoneNumber
        }
    }

    suspend fun updateDelaySeconds(value: Float) {
        context.settingsDataStore.edit { it[Keys.DELAY_SECONDS] = value }
    }

    suspend fun updateIncludeText(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCLUDE_TEXT] = value }
    }

    suspend fun updateIncludePhotos(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCLUDE_PHOTOS] = value }
    }

    suspend fun updateIncludeVideos(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCLUDE_VIDEOS] = value }
    }

    suspend fun updateIncludeAudio(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCLUDE_AUDIO] = value }
    }

    suspend fun updateIncludeDocuments(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.INCLUDE_DOCUMENTS] = value }
    }

    suspend fun updateStripMentionsAndLinks(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.STRIP_MENTIONS_AND_LINKS] = value }
    }

    suspend fun updateCustomSignature(value: String) {
        context.settingsDataStore.edit { it[Keys.CUSTOM_SIGNATURE] = value }
    }

    suspend fun clearSessionCredentials() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.PHONE_NUMBER)
        }
    }
}
