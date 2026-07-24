package com.carijajan.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carijajan.app.data.remote.AuthApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val message: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(
    private val authApi: AuthApi = AuthApi()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun loginSeller(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.value = AuthUiState.Error("Email dan password wajib diisi")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            runCatching {
                authApi.loginSeller(email, pass)
            }.onSuccess {
                _uiState.value = AuthUiState.Success("Login berhasil")
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(error.localizedMessage ?: "Login gagal")
            }
        }
    }

    fun registerSeller(email: String, pass: String, name: String, phone: String) {
        if (email.isBlank() || pass.isBlank() || name.isBlank() || phone.isBlank()) {
            _uiState.value = AuthUiState.Error("Semua bidang wajib diisi")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            runCatching {
                authApi.registerSeller(email, pass, name, phone)
            }.onSuccess {
                _uiState.value = AuthUiState.Success("Registrasi berhasil! Silakan login.")
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(error.localizedMessage ?: "Registrasi gagal")
            }
        }
    }

    fun resetUiState() {
        _uiState.value = AuthUiState.Idle
    }
}

@Composable
fun LoginScreen(
    viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onLoginSuccess: () -> Unit,
    onContinueAsBuyer: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Success) {
            onLoginSuccess()
            viewModel.resetUiState()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🍡 CariJajan",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Aplikasi Pencari & Penjual Kaki Lima",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRegisterMode) "Daftar Sebagai Penjual" else "Masuk Penjual",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (isRegisterMode) {
                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Nama Lengkap") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            label = { Text("Nomor HP / WhatsApp") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

                    Button(
                        onClick = {
                            if (isRegisterMode) {
                                viewModel.registerSeller(email, password, fullName, phoneNumber)
                            } else {
                                viewModel.loginSeller(email, password)
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
                            else "Belum punya akun? Daftar sebagai Penjual"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onContinueAsBuyer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lanjutkan Sebagai Pembeli (Tanpa Login)")
            }
        }
    }
}
