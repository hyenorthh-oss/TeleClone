package com.teleclone.app.presentation.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teleclone.app.data.local.datastore.PreferencesManager
import com.teleclone.app.data.tdlib.TdLibManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    val userPhone: StateFlow<String> = prefs.userPhone
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun logout() {
        viewModelScope.launch {
            TdLibManager.logout()
            prefs.clear()
        }
    }
}
