package com.teleclone.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teleclone_prefs")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val USER_PHONE = stringPreferencesKey("user_phone")
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IS_LOGGED_IN] ?: false
    }

    val userPhone: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_PHONE] ?: ""
    }

    suspend fun setLoggedIn(value: Boolean) {
        context.dataStore.edit { it[Keys.IS_LOGGED_IN] = value }
    }

    suspend fun setUserPhone(phone: String) {
        context.dataStore.edit { it[Keys.USER_PHONE] = phone }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
