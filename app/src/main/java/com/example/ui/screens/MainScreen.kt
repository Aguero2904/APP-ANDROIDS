package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BcvRate
import com.example.data.model.Cliente
import com.example.data.model.HistorialPago
import com.example.ui.viewmodel.WifiViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: WifiViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clientes by viewModel.filteredClientes.collectAsStateWithLifecycle()
    val pagos by viewModel.pagos.collectAsStateWithLifecycle()
    val rateState by viewModel.bcvRate.collectAsStateWithLifecycle()
    val isSyncingRate by viewModel.isRefreshingRate.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val activeFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()

    // Dialog trigger states
    var showAddClientDialog by remember { mutableStateOf(false) }
    var showRecordPaymentDialog by remember { mutableStateOf(false) }
    var showRateOverrideDialog by remember { mutableStateOf(false) }
    var selectedClientForPayment by remember { mutableStateOf<Cliente?>(null) }
    var showMassNotificationDialog by remember { mutableStateOf(false) }

    var currentTab by remember { mutableStateOf(0) } // 0 = Clientes, 1 = Historial de Pagos

    val calendar = Calendar.getInstance()
    val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
    val isCobranzaPeriod = dayOfMonth in 15..20

    // Receive sync results feedback
    LaunchedEffect(Unit) {
        viewModel.rateSyncResult.collect { result ->
            when (result) {
                is WifiViewModel.SyncResult.Success -> {
                    if (result.manual) {
                        Toast.makeText(context, "Tasa BCV guardada: ${result.rate} Bs/USD", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Tasa BCV sincronizada online: ${result.rate} Bs/USD", Toast.LENGTH_SHORT).show()
                    }
                }
                is WifiViewModel.SyncResult.Fallback -> {
                    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(result.lastUpdated))
                    Toast.makeText(context, "Modo Contingencia: Tasa cargada de BD (${result.rate} Bs) de fecha $dateStr", Toast.LENGTH_LONG).show()
                }
                is WifiViewModel.SyncResult.Error -> {
                    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(result.lastUpdated))
                    Toast.makeText(context, "Error de Red: ${result.message}\nUsando última tasa registrada: ${result.rate} Bs (${dateStr})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Permission launcher for READ_CONTACTS
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let {
            try {
                val cursor = context.contentResolver.query(
                    it,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER
                    ),
                    null, null, null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameCol = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val hasPhoneCol = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                    val contactId = cursor.getString(idCol)
                    val contactName = cursor.getString(nameCol)
                    val hasPhone = cursor.getString(hasPhoneCol).toInt()

                    if (hasPhone > 0) {
                        val phoneCursor = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(contactId),
                            null
                        )
                        if (phoneCursor != null && phoneCursor.moveToFirst()) {
                            val phoneCol = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val phoneNumber = phoneCursor.getString(phoneCol)
                            viewModel.addNewCliente(contactName, phoneNumber)
                            Toast.makeText(context, "Cliente importado: $contactName", Toast.LENGTH_SHORT).show()
                            phoneCursor.close()
                        }
                    } else {
                        Toast.makeText(context, "El contacto seleccionado no tiene teléfono.", Toast.LENGTH_SHORT).show()
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al importar el contacto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            Toast.makeText(context, "Se denegó el permiso de contactos. Puede ingresar el cliente manualmente.", Toast.LENGTH_LONG).show()
            showAddClientDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Cobranza WiFi",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncRateManually() },
                        modifier = Modifier.testTag("force_sync_button")
                    ) {
                        if (isSyncingRate) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sincronizar BCV")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (currentTab == 0) {
                FloatingActionButton(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            contactPickerLauncher.launch(null)
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_client_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Importar Cliente")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // BCV Exchange Rate Card (Always visible at top)
            BcvIndicatorCard(
                rateState = rateState,
                onManualOverrideClick = { showRateOverrideDialog = true }
            )

            // Dynamic Period Warning (Cobranza Activa)
            AnimatedVisibility(visible = isCobranzaPeriod) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF4DF)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Cobranza activa",
                            tint = Color(0xFFD67F00)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Periodo de Cobranza (Días 15-20)",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7A4A00),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Los clientes sin pago han cambiado a 'Por pagar'. Utilice el filtro rápido 'Por Cobrar' para enviar notificaciones masivas.",
                                color = Color(0xFF7A4A00),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Main Tab selector
            TabRow(selectedTabIndex = currentTab, modifier = Modifier.fillMaxWidth()) {
                Tab(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    text = { Text("Clientes", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) }
                )
                Tab(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    text = { Text("Historial de Pagos", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
            }

            SearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) }
            )

            if (currentTab == 0) {
                // Clients view
                FilterChipsRow(
                    selectedFilter = activeFilter,
                    isCobranzaPeriod = isCobranzaPeriod,
                    onFilterSelected = { viewModel.selectFilter(it) }
                )

                if (clientes.isEmpty()) {
                    EmptyStatePlaceholder(
                        title = "Sin Clientes",
                        subtitle = "Importe clientes desde sus contactos presionando el botón + para comenzar la facturación."
                    )
                } else {
                    val pendingMap by viewModel.pendingAmounts.collectAsStateWithLifecycle()
                    val pendingCuotasMap by viewModel.pendingCuotas.collectAsStateWithLifecycle()
                    val totalPendingUsd by viewModel.totalPendingAmountUsd.collectAsStateWithLifecycle()
                    val pendingClientsCount = remember(clientes, pendingMap) {
                        clientes.count { (pendingMap[it.id] ?: 0.0) > 0.0 }
                    }
                    val totalCuotasOwed = remember(clientes, pendingCuotasMap) {
                        clientes.sumOf { pendingCuotasMap[it.id] ?: 0.0 }
                    }

                    val isWeekend = isWeekendOrMonday()
                    val rateVal = rateState
                    val activeRate = if (isWeekend && rateVal != null && rateVal.weekendRate > 0.0) {
                        rateVal.weekendRate
                    } else {
                        rateVal?.rate ?: 0.0
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            PendingMoneyCard(
                                totalPendingUsd = totalPendingUsd,
                                rate = activeRate,
                                pendingClientsCount = pendingClientsCount,
                                totalClientsCount = clientes.size,
                                totalCuotasOwed = totalCuotasOwed,
                                onMassSendClick = { showMassNotificationDialog = true }
                            )
                        }
                        items(clientes) { cliente ->
                            val cuotasOwed = pendingCuotasMap[cliente.id] ?: 0.0
                            ClienteCard(
                                cliente = cliente,
                                latestRate = activeRate,
                                cuotasOwed = cuotasOwed,
                                onRegisterPaymentClick = {
                                    selectedClientForPayment = cliente
                                    showRecordPaymentDialog = true
                                },
                                onSendWhatsAppClick = {
                                    val text = formatWhatsAppMessage(cliente, activeRate, dayOfMonth)
                                    launchWhatsApp(context, cliente.telefono, text)
                                },
                                onDeleteClientClick = {
                                    viewModel.deleteCliente(cliente)
                                    Toast.makeText(context, "Cliente eliminado", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            } else {
                // Payments View
                if (pagos.isEmpty()) {
                    EmptyStatePlaceholder(
                        title = "Sin Pagos Registrados",
                        subtitle = "Cualquier abono registrado aparecerá aquí detallando el método, tasa de cambio y fecha."
                    )
                } else {
                    val df = DecimalFormat("#.##")
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pagos) { pago ->
                            val clientName = remember(pago.clienteId) {
                                viewModel.filteredClientes.value.firstOrNull { it.id == pago.clienteId }?.nombre ?: "Cliente #${pago.clienteId}"
                            }

                            HistorialPagoCard(
                                pago = pago,
                                ownerName = clientName,
                                onDeleteClick = {
                                    viewModel.deletePaymentLog(pago)
                                    Toast.makeText(context, "Pago eliminado y estatus re-evaluado", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet or dialog for MANUAL subscriber adding
    if (showAddClientDialog) {
        AddClientDialog(
            onDismiss = { showAddClientDialog = false },
            onSave = { name, phone ->
                viewModel.addNewCliente(name, phone)
                showAddClientDialog = false
            }
        )
    }

    // Modal Dialog for RECORD PAYMENT
    if (showRecordPaymentDialog && selectedClientForPayment != null) {
        val client = selectedClientForPayment!!
        val isWeekend = isWeekendOrMonday()
        val rateVal = rateState
        val activeRate = if (isWeekend && rateVal != null && rateVal.weekendRate > 0.0) {
            rateVal.weekendRate
        } else {
            rateVal?.rate ?: 0.0
        }
        RecordPaymentDialog(
            cliente = client,
            rate = activeRate,
            onDismiss = {
                showRecordPaymentDialog = false
                selectedClientForPayment = null
            },
            onSave = { usdAmount, method ->
                viewModel.registerClientPayment(client.id, usdAmount, method)
                showRecordPaymentDialog = false
                selectedClientForPayment = null
            }
        )
    }

    // Manual Exchange Rate Override Dialog
    if (showRateOverrideDialog) {
        ManualRateDialog(
            currentRate = rateState?.rate ?: 0.0,
            currentWeekendRate = rateState?.weekendRate ?: 0.0,
            onDismiss = { showRateOverrideDialog = false },
            onSave = { manualRate, manualWeekendRate ->
                viewModel.saveManualExchangeRate(manualRate, manualWeekendRate)
                showRateOverrideDialog = false
            }
        )
    }

    // Modal Dialog for MASS NOTIFICATION
    if (showMassNotificationDialog) {
        val pendingMap by viewModel.pendingAmounts.collectAsStateWithLifecycle()
        val isWeekend = isWeekendOrMonday()
        val rateVal = rateState
        val activeRate = if (isWeekend && rateVal != null && rateVal.weekendRate > 0.0) {
            rateVal.weekendRate
        } else {
            rateVal?.rate ?: 0.0
        }
        MassNotificationDialog(
            clientes = clientes,
            pendingMap = pendingMap,
            latestRate = activeRate,
            dayOfMonth = dayOfMonth,
            onDismiss = { showMassNotificationDialog = false },
            onLaunchWhatsApp = { activeClient, text ->
                launchWhatsApp(context, activeClient.telefono, text)
            }
        )
    }
}

@Composable
fun BcvIndicatorCard(
    rateState: BcvRate?,
    onManualOverrideClick: () -> Unit
) {
    val df = DecimalFormat("#.##")
    val isWeekend = isWeekendOrMonday()
    val activeRate = if (isWeekend && rateState != null && rateState.weekendRate > 0.0) {
        rateState.weekendRate
    } else {
        rateState?.rate ?: 0.0
    }
    
    val rateText = if (activeRate > 0) "${df.format(activeRate)}" else "0.00"
    
    val dateText = if (rateState != null) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault())
        "Última tasa: " + dateFormat.format(Date(rateState.lastUpdated))
    } else {
        "Sincronice la tasa para comenzar"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 12.dp, 16.dp, 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWeekend) Color(0xFF0F172A) else MaterialTheme.colorScheme.primary // Slate-slate or Primary
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp)
            ) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.04f),
                    radius = 180f,
                    center = androidx.compose.ui.geometry.Offset(size.width + 50f, -50f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = 260f,
                    center = androidx.compose.ui.geometry.Offset(size.width - 20f, size.height + 70f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = if (isWeekend) "🟢 TASA FIN DE SEMANA Y LUNES ACTIVA" else "📊 TASA BCV OFICIAL HOY",
                            color = if (isWeekend) Color(0xFF34D399) else Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = rateText,
                                color = Color.White,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Bs/$",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    
                    Surface(
                        color = if (isWeekend) Color(0xFF1E293B) else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = if (isWeekend) "Vrg y FDS" else (if (rateState != null) "AL DÍA" else "SIN CONEXIÓN"),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Tarifa Mensual: $2.00",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val totalBs = if (activeRate > 0) df.format(2.0 * activeRate) else "0.00"
                        Text(
                            text = "Cuota: $totalBs Bs.",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onManualOverrideClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = if (isWeekend) Color(0xFF0F172A) else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AJUSTAR",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                
                if (rateState != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tasa BCV: ${df.format(rateState.rate)} Bs",
                            color = if (!isWeekend) Color.White else Color.White.copy(alpha = 0.6f),
                            fontWeight = if (!isWeekend) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "FDS y Lunes: ${df.format(if (rateState.weekendRate > 0.0) rateState.weekendRate else rateState.rate)} Bs",
                            color = if (isWeekend) Color(0xFF34D399) else Color.White.copy(alpha = 0.6f),
                            fontWeight = if (isWeekend) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// Client filter choices
@Composable
fun FilterChipsRow(
    selectedFilter: String,
    isCobranzaPeriod: Boolean,
    onFilterSelected: (String) -> Unit
) {
    val filters = remember(isCobranzaPeriod) {
        if (isCobranzaPeriod) {
            listOf("Todos", "Al día", "Por pagar", "Moroso/Pendiente", "Por Cobrar")
        } else {
            listOf("Todos", "Al día", "Por pagar", "Moroso/Pendiente")
        }
    }

    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters) { filter ->
            val isSelected = filter == selectedFilter
            val color = if (filter == "Por Cobrar") {
                if (isSelected) Color(0xFFD67F00) else Color(0xFFFFB03A).copy(alpha = 0.15f)
            } else {
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            }

            val textOnColor = if (filter == "Por Cobrar") {
                if (isSelected) Color.White else Color(0xFFD67F00)
            } else {
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                modifier = Modifier
                    .clickable { onFilterSelected(filter) }
                    .testTag("filter_chip_$filter"),
                shape = RoundedCornerShape(16.dp),
                color = color,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Text(
                    text = filter,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = textOnColor
                )
            }
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("search_bar"),
        placeholder = { Text("Buscar por nombre o teléfono...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                }
            }
        },
        maxLines = 1,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    )
}

// Client visual items
@Composable
fun ClienteCard(
    cliente: Cliente,
    latestRate: Double,
    cuotasOwed: Double,
    onRegisterPaymentClick: () -> Unit,
    onSendWhatsAppClick: () -> Unit,
    onDeleteClientClick: () -> Unit
) {
    val badgeColors = when (cliente.estado) {
        "Al día" -> Pair(Color(0xFFDCFCE7), Color(0xFF16A34A))      // Green100, Green600
        "Por pagar" -> Pair(Color(0xFFFEF3C7), Color(0xFFB45309))    // Amber100, Amber700
        "Moroso/Pendiente" -> Pair(Color(0xFFFEE2E2), Color(0xFFB91C1C)) // Red100, Red700
        else -> Pair(Color(0xFFF1F5F9), Color(0xFF475569)) // Slate100, Slate600
    }

    val initials = remember(cliente.nombre) {
        val parts = cliente.nombre.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) ""
        else if (parts.size == 1) parts[0].take(2).uppercase()
        else (parts[0].take(1) + parts[1].take(1)).uppercase()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("client_card_${cliente.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(badgeColors.first, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = badgeColors.second,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = cliente.nombre,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = cliente.telefono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        val dfCuotas = DecimalFormat("#.#")
                        val cuotasText = dfCuotas.format(cuotasOwed)
                        val cuotasColor = if (cuotasOwed > 0) MaterialTheme.colorScheme.error else Color(0xFF16A34A)
                        val cuotasLabel = if (cuotasOwed == 1.0) "cuota" else "cuotas"

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (cuotasOwed > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = cuotasColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                    text = "Cuotas pendientes: $cuotasText $cuotasLabel",
                                    color = cuotasColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Surface(
                    color = badgeColors.first,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = cliente.estado,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = badgeColors.second
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSendWhatsAppClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("whatsapp_button_${cliente.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF16A34A),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cobrar", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
                }

                OutlinedButton(
                    onClick = onRegisterPaymentClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("pay_button_${cliente.id}"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pagar", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onDeleteClientClick,
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), androidx.compose.foundation.shape.CircleShape)
                        .testTag("delete_button_${cliente.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar Cliente",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// Payment visual items
@Composable
fun HistorialPagoCard(
    pago: HistorialPago,
    ownerName: String,
    onDeleteClick: () -> Unit
) {
    val df = DecimalFormat("#.##")
    val dfBs = DecimalFormat("#,##0.00")
    val dateText = SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault()).format(Date(pago.fecha))

    val methodBadgeColors = when (pago.metodoPago) {
        "Pago Móvil" -> Pair(Color(0xFFDBEAFE), Color(0xFF1E40AF))     // Slate blue theme
        "Efectivo USD" -> Pair(Color(0xFFDCFCE7), Color(0xFF16A34A))   // Green theme
        "Efectivo Bs" -> Pair(Color(0xFFFEF3C7), Color(0xFFB45309))    // Gold theme
        else -> Pair(Color(0xFFF1F5F9), Color(0xFF475569))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pago_card_${pago.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ownerName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = methodBadgeColors.first,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = pago.metodoPago,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = methodBadgeColors.second,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "• $dateText",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tasa aplicada: ${df.format(pago.tasaBcv)} Bs/USD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$${df.format(pago.montoUsd)} USD",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "${dfBs.format(pago.montoBs)} Bs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar Registro",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Dialog definitions
@Composable
fun AddClientDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar Suscriptor", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre Completo") },
                    modifier = Modifier.fillMaxWidth().testTag("add_client_name"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Número WhatsApp (ej. +58...)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().testTag("add_client_phone"),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        onSave(name, phone)
                    }
                },
                modifier = Modifier.testTag("save_client_button")
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun RecordPaymentDialog(
    cliente: Cliente,
    rate: Double,
    onDismiss: () -> Unit,
    onSave: (usdAmount: Double, method: String) -> Unit
) {
    var usdAmount by remember { mutableStateOf("2.0") }
    var selectedMethod by remember { mutableStateOf("Pago Móvil") }
    val methods = listOf("Pago Móvil", "Efectivo USD", "Efectivo Bs")

    val parsedUsd = usdAmount.toDoubleOrNull() ?: 0.0
    val calculatedBsText = if (rate > 0) {
        DecimalFormat("#,##0.00").format(parsedUsd * rate) + " Bs."
    } else {
        "0.00 Bs."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar Abono", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Registrar cobro para ${cliente.nombre}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = usdAmount,
                    onValueChange = { usdAmount = it },
                    label = { Text("Monto USD") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("payment_usd_input"),
                    singleLine = true
                )
                
                // Automatic Bs dynamic conversion label
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Equivalencia en Bolívares (Calculado)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = calculatedBsText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Tasa oficial: $rate Bs/USD",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                Column {
                    Text("Método de Pago", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        methods.forEach { method ->
                            val isSelected = method == selectedMethod
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedMethod = method }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = method,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val usd = usdAmount.toDoubleOrNull()
                    if (usd != null && usd > 0) {
                        onSave(usd, selectedMethod)
                    }
                },
                modifier = Modifier.testTag("save_payment_button")
            ) {
                Text("Confirmar Pago")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ManualRateDialog(
    currentRate: Double,
    currentWeekendRate: Double,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var rateStr by remember { mutableStateOf(if (currentRate > 0) currentRate.toString() else "") }
    var weekendRateStr by remember { mutableStateOf(if (currentWeekendRate > 0) currentWeekendRate.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustar Tasas de Cambio", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Ingrese los valores en Bolívares por dólar estadounidense para calcular cuotas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = rateStr,
                    onValueChange = { rateStr = it },
                    label = { Text("Tasa Oficial BCV (Bs/USD)") },
                    placeholder = { Text("Ej. 36.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("manual_rate_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = weekendRateStr,
                    onValueChange = { weekendRateStr = it },
                    label = { Text("Tasa Fin de Semana / Lunes (Bs/USD)") },
                    placeholder = { Text("Ej. 38.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("manual_weekend_rate_input"),
                    singleLine = true
                )
                
                Text(
                    text = "Nota: El sistema aplicará la tasa de fin de semana de forma automática los días Viernes, Sábado, Domingo y Lunes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bcvVal = rateStr.toDoubleOrNull() ?: 0.0
                    val weekendVal = weekendRateStr.toDoubleOrNull() ?: bcvVal
                    if (bcvVal > 0) {
                        onSave(bcvVal, weekendVal)
                    }
                },
                modifier = Modifier.testTag("save_manual_rate_button")
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// Styling components
@Composable
fun EmptyStatePlaceholder(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color.LightGray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
    }
}

// WhatsApp template formatter logic
fun formatWhatsAppMessage(cliente: Cliente, rate: Double, dayOfMonth: Int): String {
    val df = DecimalFormat("#.##")
    val dfBs = DecimalFormat("#,##0.00")
    val calculatedBsStr = dfBs.format(2.0 * rate)
    val isWeekend = isWeekendOrMonday()
    val rateLabel = if (isWeekend) "Tasa Especial Fin de Semana (Viernes-Lunes)" else "Tasa BCV del día"

    return if (dayOfMonth in 15..20) {
        // Mensaje Estándar (Días 15 al 20)
        """📡 CONTROL DE SERVICIO WIFI 📡
Estimado(a) ${cliente.nombre}, le recordamos que nos encontramos en el período de cobranza correspondiente a este mes (del 15 al 20).

Monto del Servicio: $2.00

$rateLabel: ${df.format(rate)} Bs.

Total a pagar: $calculatedBsStr Bs.
Por favor, una vez realizado su pago, envíe el comprobante de transferencia o pago móvil por esta vía para registrarlo en el sistema."""
    } else {
        // Mensaje de Advertencia (Día 21 en adelante)
        """📡 AVISO DE COBRO - SERVICIO WIFI 📡
Estimado(a) ${cliente.nombre}, le informamos que presenta pagos atrasados con el servicio de Internet. El monto mensual es de $2.00 anclados a la tasa aplicada del día ($rateLabel: ${df.format(rate)} Bs.).
⚠️ NOTA IMPORTANTE: Agradecemos consultar su deuda a la brevedad posible para ponerse al día y evitar la suspensión del servicio.
Por favor, comuníquese por este chat para verificar su estado de cuenta."""
    }
}

// Launching official/web intents
@SuppressLint("QueryPermissionsNeeded")
fun launchWhatsApp(context: Context, phone: String, messageText: String) {
    // Strip "+" or any symbol except digits for standard WhatsApp links compatibility
    val rawNumber = phone.replace("+", "").replace("-", "").replace(" ", "")
    val intentUri = "whatsapp://send?phone=$rawNumber&text=${Uri.encode(messageText)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUri))
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to official web API which triggers Whatsapp Web/App flawlesssly
        val webUri = "https://api.whatsapp.com/send?phone=$rawNumber&text=${Uri.encode(messageText)}"
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUri))
        try {
            context.startActivity(webIntent)
        } catch (ex: Exception) {
            Toast.makeText(context, "WhatsApp no está instalado o no se puede abrir.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun PendingMoneyCard(
    totalPendingUsd: Double,
    rate: Double,
    pendingClientsCount: Int,
    totalClientsCount: Int,
    totalCuotasOwed: Double,
    onMassSendClick: () -> Unit
) {
    val df = DecimalFormat("#.##")
    val dfBs = DecimalFormat("#,##0.00")
    val totalBsText = dfBs.format(totalPendingUsd * rate)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_money_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TOTAL DINERO PENDIENTE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$${df.format(totalPendingUsd)}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "USD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = "Equivalente: $totalBsText Bs.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), thickness = 1.dp)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clientes con Deuda",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$pendingClientsCount de $totalClientsCount en esta lista",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (totalCuotasOwed > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        val dfCuotasTotal = DecimalFormat("#.#")
                        Text(
                            text = "Cuotas totales pendientes: ${dfCuotasTotal.format(totalCuotasOwed)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (pendingClientsCount > 0) {
                    Button(
                        onClick = onMassSendClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cobro Masivo",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MassNotificationDialog(
    clientes: List<Cliente>,
    pendingMap: Map<Int, Double>,
    latestRate: Double,
    dayOfMonth: Int,
    onDismiss: () -> Unit,
    onLaunchWhatsApp: (Cliente, String) -> Unit
) {
    val pendingClients = remember(clientes, pendingMap) {
        clientes.filter { (pendingMap[it.id] ?: 0.0) > 0.0 }
    }

    val sentSet = remember { mutableStateListOf<Int>() }
    var wizardIndex by remember { mutableStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Mensajería de Cobranza WiFi",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pendingClients.isEmpty()) {
                    Text(
                        text = "No hay clientes pendientes de pago en la lista actual.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        color = Color.Gray
                    )
                } else if (wizardIndex == -1) {
                    Text(
                        text = "Envíe notificaciones a todos los deudores. Puede hacerlo individualmente o de manera secuencial (por lote).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { wizardIndex = 0 },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Iniciar Envío en Lote (${pendingClients.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Lista de Deudores (${pendingClients.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(pendingClients) { cliente ->
                            val pendingUsd = pendingMap[cliente.id] ?: 2.0
                            val isSent = sentSet.contains(cliente.id)
                            
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cliente.nombre,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "Debe: $${DecimalFormat("#.##").format(pendingUsd)} USD",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val text = formatWhatsAppMessage(cliente, latestRate, dayOfMonth)
                                            onLaunchWhatsApp(cliente, text)
                                            if (!sentSet.contains(cliente.id)) {
                                                sentSet.add(cliente.id)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSent) Color(0xFF16A34A) else MaterialTheme.colorScheme.secondary,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        if (isSent) {
                                            Icon(Icons.Default.Check, contentDescription = "Enviado", modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Listo", style = MaterialTheme.typography.labelSmall)
                                        } else {
                                            Icon(Icons.Default.Send, contentDescription = "Cobrar", modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Cobrar", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val activeClient = pendingClients.getOrNull(wizardIndex)
                    if (activeClient != null) {
                        val pendingUsd = pendingMap[activeClient.id] ?: 2.0
                        val isSent = sentSet.contains(activeClient.id)

                        Text(
                            text = "Asistente de Envío Masivo",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        LinearProgressIndicator(
                            progress = { (wizardIndex + 1).toFloat() / pendingClients.size.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "CLIENTE ${wizardIndex + 1} de ${pendingClients.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = activeClient.nombre,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Teléfono: ${activeClient.telefono}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Deuda Estimada: $${DecimalFormat("#.##").format(pendingUsd)} USD",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val text = formatWhatsAppMessage(activeClient, latestRate, dayOfMonth)
                                onLaunchWhatsApp(activeClient, text)
                                if (!sentSet.contains(activeClient.id)) {
                                    sentSet.add(activeClient.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSent) Color(0xFF16A34A) else Color(0xFF25D366)
                            )
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSent) "Re-enviar por WhatsApp" else "Abrir WhatsApp de ${activeClient.nombre}",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { wizardIndex = -1 }
                            ) {
                                Text("Salir del Asistente")
                            }

                            Button(
                                onClick = {
                                    if (wizardIndex < pendingClients.size - 1) {
                                        wizardIndex++
                                    } else {
                                        wizardIndex = -1
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (wizardIndex < pendingClients.size - 1) "Siguiente" else "Finalizar"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        wizardIndex = -1
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Listo")
            }
        }
    )
}

fun isWeekendOrMonday(): Boolean {
    val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return dayOfWeek == Calendar.FRIDAY ||
           dayOfWeek == Calendar.SATURDAY ||
           dayOfWeek == Calendar.SUNDAY ||
           dayOfWeek == Calendar.MONDAY
}
