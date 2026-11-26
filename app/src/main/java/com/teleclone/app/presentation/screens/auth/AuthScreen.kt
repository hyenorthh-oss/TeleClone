package com.teleclone.app.presentation.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleclone.app.domain.model.AuthState
import com.teleclone.app.ui.theme.AccentRed
import com.teleclone.app.ui.theme.BackgroundDeep
import com.teleclone.app.ui.theme.BorderSubtle
import com.teleclone.app.ui.theme.SurfaceElevated
import com.teleclone.app.ui.theme.TextPrimary
import com.teleclone.app.ui.theme.TextSecondary

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = viewModel(),
) {
    val authState by viewModel.authState.collectAsState()
    var inputValue by remember { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState is AuthState.Ready) onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = AccentRed,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "TeleClone",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (authState) {
                is AuthState.WaitingForPhoneNumber -> "Enter your phone number"
                is AuthState.WaitingForCode -> "Enter the verification code"
                is AuthState.WaitingForPassword -> "Enter your 2FA password"
                else -> "Connecting..."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(32.dp))

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentRed,
            focusedBorderColor = AccentRed,
            unfocusedBorderColor = BorderSubtle,
            focusedContainerColor = SurfaceElevated,
            unfocusedContainerColor = SurfaceElevated,
            focusedLabelColor = AccentRed,
            unfocusedLabelColor = TextSecondary,
        )

        when (authState) {
            is AuthState.WaitingForPhoneNumber -> {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = { Text("Phone number") },
                    placeholder = { Text("+1234567890", color = TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.sendPhoneNumber(inputValue)
                        inputValue = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Continue", color = TextPrimary)
                }
            }
            is AuthState.WaitingForCode -> {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = { Text("Verification code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.sendCode(inputValue)
                        inputValue = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Verify", color = TextPrimary)
                }
            }
            is AuthState.WaitingForPassword -> {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = { Text("2FA Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.sendPassword(inputValue)
                        inputValue = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Submit", color = TextPrimary)
                }
            }
            else -> {
                CircularProgressIndicator(color = AccentRed)
            }
        }
    }
}
