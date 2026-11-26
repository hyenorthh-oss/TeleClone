package com.teleclone.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleclone.app.data.model.AuthState
import com.teleclone.app.ui.screens.LoginScreen
import com.teleclone.app.ui.screens.MainScaffold
import com.teleclone.app.ui.screens.MainViewModel
import com.teleclone.app.ui.theme.BackgroundDeep
import com.teleclone.app.ui.theme.TeleCloneTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled gracefully by Android notification system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            TeleCloneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundDeep,
                ) {
                    val mainViewModel: MainViewModel = viewModel()
                    val authState by mainViewModel.authState.collectAsState()
                    val lastStep by mainViewModel.lastKnownAuthStep.collectAsState()
                    val authError by mainViewModel.authError.collectAsState()

                    if (authState is AuthState.Ready) {
                        MainScaffold(viewModel = mainViewModel)
                    } else {
                        LoginScreen(
                            authState = authState,
                            lastStep = lastStep,
                            errorMessage = authError,
                            onSubmitApiCredentials = { id, hash ->
                                mainViewModel.submitApiCredentials(id, hash)
                            },
                            onSubmitPhone = { phone ->
                                mainViewModel.submitPhoneNumber(phone)
                            },
                            onSubmitCode = { code ->
                                mainViewModel.submitCode(code)
                            },
                            onSubmitPassword = { password ->
                                mainViewModel.submitPassword(password)
                            },
                            onResetApiCredentials = {
                                mainViewModel.backToApiCredentials()
                            },
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
