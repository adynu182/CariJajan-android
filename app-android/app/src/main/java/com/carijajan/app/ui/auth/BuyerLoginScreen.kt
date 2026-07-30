package com.carijajan.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carijajan.app.data.remote.AuthApi
import com.carijajan.app.data.remote.RegisterOutcome
import com.carijajan.app.data.remote.toFriendlyAuthMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BuyerAuthViewModel(
    private val authApi: AuthApi = AuthApi()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun loginBuyer(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            runCatching {
                authApi.loginBuyer(email, pass)
            }.onSuccess {
                _uiState.value = AuthUiState.Success("Login berhasil")
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(error.toFriendlyAuthMessage())
            }
        }
    }

    fun registerBuyer(email: String, pass: String, name: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            runCatching {
                authApi.registerBuyer(email, pass, name)
            }.onSuccess { outcome ->
                _uiState.value = when (outcome) {
                    RegisterOutcome.SIGNED_IN -> AuthUiState.Success("Registrasi berhasil")
                    RegisterOutcome.CONFIRMATION_REQUIRED -> AuthUiState.Info(
                        "Registrasi berhasil! Silakan cek email Anda untuk konfirmasi akun, lalu masuk."
                    )
                }
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(error.toFriendlyAuthMessage())
            }
        }
    }

    fun resetUiState() {
        _uiState.value = AuthUiState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuyerLoginScreen(
    viewModel: BuyerAuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        when (uiState) {
            is AuthUiState.Success -> {
                onSuccess()
                viewModel.resetUiState()
            }
            is AuthUiState.Info -> {
                // Registrasi berhasil tapi butuh konfirmasi email — belum ada session.
                isRegisterMode = false
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login Pembeli") },
                actions = {
                    TextButton(onClick = onCancel) {
                        Text("Batal")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Login Diperlukan",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Untuk memberikan rating, ulasan, atau menyimpan favorit, kamu perlu masuk terlebih dahulu.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            if (isRegisterMode) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Nama Lengkap") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (uiState is AuthUiState.Error) {
                Text(
                    text = (uiState as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (uiState is AuthUiState.Info) {
                Text(
                    text = (uiState as AuthUiState.Info).message,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    if (isRegisterMode) {
                        viewModel.registerBuyer(email, password, fullName)
                    } else {
                        viewModel.loginBuyer(email, password)
                    }
                },
                enabled = uiState !is AuthUiState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState is AuthUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isRegisterMode) "Daftar" else "Masuk")
                }
            }

            TextButton(onClick = { isRegisterMode = !isRegisterMode }) {
                Text(
                    if (isRegisterMode) "Sudah punya akun? Masuk"
                    else "Belum punya akun? Daftar"
                )
            }
        }
    }
}
