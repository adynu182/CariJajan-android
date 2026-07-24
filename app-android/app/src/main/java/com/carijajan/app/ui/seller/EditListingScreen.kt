package com.carijajan.app.ui.seller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carijajan.app.data.remote.AuthApi
import com.carijajan.app.data.repository.ListingRepository
import com.carijajan.app.domain.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditListingViewModel(
    private val repository: ListingRepository,
    private val authApi: AuthApi = AuthApi()
) : ViewModel() {

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun saveListing(
        listingId: String,
        name: String,
        category: Category,
        priceMinStr: String,
        priceMaxStr: String,
        description: String,
        onSuccess: () -> Unit
    ) {
        if (name.isBlank()) {
            _errorMessage.value = "Nama lapak wajib diisi"
            return
        }

        val priceMin = priceMinStr.toIntOrNull()
        val priceMax = priceMaxStr.toIntOrNull()

        viewModelScope.launch {
            _isSaving.value = true
            runCatching {
                if (listingId == "new") {
                    val userId = authApi.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
                    repository.createListing(
                        sellerId = userId,
                        name = name,
                        category = category,
                        priceMin = priceMin,
                        priceMax = priceMax,
                        description = description
                    )
                } else {
                    repository.updateListing(
                        listingId = listingId,
                        name = name,
                        category = category,
                        priceMin = priceMin,
                        priceMax = priceMax,
                        description = description
                    )
                }
            }.onSuccess {
                _isSaving.value = false
                onSuccess()
            }.onFailure { error ->
                _isSaving.value = false
                _errorMessage.value = error.localizedMessage ?: "Gagal menyimpan lapak"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditListingScreen(
    listingId: String,
    repository: ListingRepository,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val viewModel = remember { EditListingViewModel(repository) }

    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(Category.CILOK) }
    var priceMinStr by remember { mutableStateOf("") }
    var priceMaxStr by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }

    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (listingId == "new") "Buat Lapak Baru" else "Edit Info Lapak") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama Lapak (misal: Cilok Mang Ujang)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Category Dropdown
            Text("Kategori Dagangan", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            ExposedDropdownMenuBox(
                expanded = isCategoryDropdownExpanded,
                onExpandedChange = { isCategoryDropdownExpanded = !isCategoryDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = "${selectedCategory.displayName} ${selectedCategory.emoji}",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isCategoryDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = isCategoryDropdownExpanded,
                    onDismissRequest = { isCategoryDropdownExpanded = false }
                ) {
                    Category.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text("${cat.displayName} ${cat.emoji}") },
                            onClick = {
                                selectedCategory = cat
                                isCategoryDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = priceMinStr,
                    onValueChange = { priceMinStr = it },
                    label = { Text("Harga Min (Rp)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedTextField(
                    value = priceMaxStr,
                    onValueChange = { priceMaxStr = it },
                    label = { Text("Harga Max (Rp)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Deskripsi Singkat / Menu Unggulan") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    viewModel.saveListing(
                        listingId = listingId,
                        name = name,
                        category = selectedCategory,
                        priceMinStr = priceMinStr,
                        priceMaxStr = priceMaxStr,
                        description = description,
                        onSuccess = onSuccess
                    )
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Simpan Lapak", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
