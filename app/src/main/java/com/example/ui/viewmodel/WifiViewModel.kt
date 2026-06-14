package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.WifiAppDatabase
import com.example.data.model.BcvRate
import com.example.data.model.Cliente
import com.example.data.model.HistorialPago
import com.example.data.repository.WifiRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class WifiViewModel(
    application: Application,
    private val repository: WifiRepository
) : AndroidViewModel(application) {

    private val TAG = "WifiViewModel"

    // Search & Filter state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // Active filter: "Todos", "Al día", "Por pagar", "Moroso/Pendiente", "Por Cobrar" (for day 15 to 20 filter)
    private val _selectedFilter = MutableStateFlow("Todos")
    val selectedFilter = _selectedFilter.asStateFlow()

    // Loading states
    private val _isRefreshingRate = MutableStateFlow(false)
    val isRefreshingRate = _isRefreshingRate.asStateFlow()

    private val _rateSyncResult = MutableSharedFlow<SyncResult>()
    val rateSyncResult: SharedFlow<SyncResult> = _rateSyncResult.asSharedFlow()

    // Base client list, payments list, and rate from repo
    val bcvRate: StateFlow<BcvRate?> = repository.bcvRateFlow.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Combined filtered clients state for standard listing and search
    val filteredClientes: StateFlow<List<Cliente>> = combine(
        repository.allClientes,
        _searchQuery,
        _selectedFilter
    ) { clientes, query, filter ->
        var list = clientes

        // 1. Apply Search Query (matches name or phone)
        if (query.isNotBlank()) {
            list = list.filter {
                it.nombre.contains(query, ignoreCase = true) ||
                        it.telefono.contains(query)
            }
        }

        // 2. Apply Period Status filter
        val todayDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        list = when (filter) {
            "Al día" -> list.filter { it.estado == "Al día" }
            "Por pagar" -> list.filter { it.estado == "Por pagar" }
            "Moroso/Pendiente" -> list.filter { it.estado == "Moroso/Pendiente" }
            "Por Cobrar" -> {
                // "Por Cobrar" lists all clients pending payment ("Por pagar" and "Moroso/Pendiente")
                list.filter { it.estado == "Por pagar" || it.estado == "Moroso/Pendiente" }
            }
            else -> list
        }

        list
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val pagos: StateFlow<List<HistorialPago>> = repository.allPagos.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val pendingAmounts: StateFlow<Map<Int, Double>> = combine(
        repository.allClientes,
        repository.allPagos
    ) { clientes, pagos ->
        val (start, end) = getCurrentMonthBoundaries()
        val pagosThisMonthByClient = pagos
            .filter { it.fecha in start..end }
            .groupBy { it.clienteId }
        
        clientes.associate { cliente ->
            val totalPaid = pagosThisMonthByClient[cliente.id]?.sumOf { it.montoUsd } ?: 0.0
            val pending = if (totalPaid >= 2.0) 0.0 else 2.0 - totalPaid
            cliente.id to pending
        }
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    val pendingCuotas: StateFlow<Map<Int, Double>> = combine(
        repository.allClientes,
        repository.allPagos
    ) { clientes, pagos ->
        val currentCalendar = Calendar.getInstance()
        val currentYear = currentCalendar.get(Calendar.YEAR)
        val currentMonth = currentCalendar.get(Calendar.MONTH)
        val currentDay = currentCalendar.get(Calendar.DAY_OF_MONTH)

        clientes.associate { cliente ->
            val regCalendar = Calendar.getInstance().apply { timeInMillis = cliente.fechaRegistro }
            val regYear = regCalendar.get(Calendar.YEAR)
            val regMonth = regCalendar.get(Calendar.MONTH)

            var totalExpected = 0.0
            val tempCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, regYear)
                set(Calendar.MONTH, regMonth)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            while (tempCal.get(Calendar.YEAR) < currentYear || 
                   (tempCal.get(Calendar.YEAR) == currentYear && tempCal.get(Calendar.MONTH) <= currentMonth)) {
                
                val mYear = tempCal.get(Calendar.YEAR)
                val mMonth = tempCal.get(Calendar.MONTH)
                
                val expectedForThisMonth = if (mYear == currentYear && mMonth == currentMonth) {
                    if (currentDay >= 15) 2.0 else 0.0
                } else {
                    2.0
                }
                
                totalExpected += expectedForThisMonth
                tempCal.add(Calendar.MONTH, 1)
            }

            val clientPagos = pagos.filter { it.clienteId == cliente.id }
            val totalPaid = clientPagos.sumOf { it.montoUsd }

            val totalOwed = maxOf(0.0, totalExpected - totalPaid)
            val cuotasOwed = totalOwed / 2.0
            
            cliente.id to cuotasOwed
        }
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    val totalPendingAmountUsd: StateFlow<Double> = combine(
        filteredClientes,
        pendingAmounts
    ) { list, pendingMap ->
        list.sumOf { pendingMap[it.id] ?: 0.0 }
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = 0.0
    )

    private fun getCurrentMonthBoundaries(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        
        return Pair(start, end)
    }

    init {
        // Run initial status sync and rate checks
        evaluateStatusesAndFetchRate()
    }

    /**
     * Initial startup function. Performs status transitions and pulls online exchange rate.
     */
    fun evaluateStatusesAndFetchRate() {
        viewModelScope.launch {
            _isRefreshingRate.value = true
            try {
                // 1. Recalibrate status of all subscribers
                repository.updateAllClientStatuses()
                
                // 2. Refresh exchange rate from BCV
                val didFetchOnline = repository.syncBcvRate()
                val latestRate = repository.getLatestBcvRate()
                
                if (didFetchOnline && latestRate != null) {
                    _rateSyncResult.emit(SyncResult.Success(latestRate.rate, false))
                } else {
                    _rateSyncResult.emit(
                        SyncResult.Fallback(
                            rate = latestRate?.rate ?: 0.0,
                            lastUpdated = latestRate?.lastUpdated ?: 0L
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in evaluateStatusesAndFetchRate", e)
                val latestRate = repository.getLatestBcvRate()
                _rateSyncResult.emit(
                    SyncResult.Error(
                        message = e.localizedMessage ?: "Error desconocido de red",
                        rate = latestRate?.rate ?: 0.0,
                        lastUpdated = latestRate?.lastUpdated ?: 0L
                    )
                )
            } finally {
                _isRefreshingRate.value = false
            }
        }
    }

    fun syncRateManually() {
        viewModelScope.launch {
            _isRefreshingRate.value = true
            try {
                val didSync = repository.syncBcvRate()
                val latestRate = repository.getLatestBcvRate()
                if (didSync && latestRate != null) {
                    _rateSyncResult.emit(SyncResult.Success(latestRate.rate, true))
                } else {
                    _rateSyncResult.emit(
                        SyncResult.Fallback(
                            rate = latestRate?.rate ?: 0.0,
                            lastUpdated = latestRate?.lastUpdated ?: 0L
                        )
                    )
                }
            } catch (e: Exception) {
                val latestRate = repository.getLatestBcvRate()
                _rateSyncResult.emit(
                    SyncResult.Error(
                        message = e.localizedMessage ?: "Error de red",
                        rate = latestRate?.rate ?: 0.0,
                        lastUpdated = latestRate?.lastUpdated ?: 0L
                    )
                )
            } finally {
                _isRefreshingRate.value = false
            }
        }
    }

    fun isWeekendOrMonday(): Boolean {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.FRIDAY ||
               dayOfWeek == Calendar.SATURDAY ||
               dayOfWeek == Calendar.SUNDAY ||
               dayOfWeek == Calendar.MONDAY
    }

    fun saveManualExchangeRate(rateValue: Double, weekendRateValue: Double) {
        viewModelScope.launch {
            repository.saveManualRate(rateValue, weekendRateValue)
            val latestRate = repository.getLatestBcvRate()
            if (latestRate != null) {
                _rateSyncResult.emit(SyncResult.Success(latestRate.rate, true))
            }
        }
    }

    // Filters and search controls
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectFilter(filter: String) {
        _selectedFilter.value = filter
    }

    // Phone parsing and insertion logic
    fun addNewCliente(nombre: String, telefonoRaw: String) {
        viewModelScope.launch {
            val cleanPhone = sanitizePhoneNumber(telefonoRaw)
            val nuovoCliente = Cliente(
                nombre = nombre.trim(),
                telefono = cleanPhone,
                estado = "Al día" // Initial state, will be evaluated by business logic
            )
            repository.insertCliente(nuovoCliente)
            repository.updateAllClientStatuses()
        }
    }

    fun deleteCliente(cliente: Cliente) {
        viewModelScope.launch {
            repository.deleteCliente(cliente)
        }
    }

    // Payment registers
    fun registerClientPayment(clienteId: Int, usdAmount: Double, metodoPago: String) {
        viewModelScope.launch {
            val rateEntity = repository.getLatestBcvRate()
            val currentExchangeRate = if (isWeekendOrMonday() && rateEntity != null && rateEntity.weekendRate > 0.0) {
                rateEntity.weekendRate
            } else {
                rateEntity?.rate ?: 0.0
            }
            val calculatedBsAmount = usdAmount * currentExchangeRate
            
            val payment = HistorialPago(
                clienteId = clienteId,
                montoUsd = usdAmount,
                tasaBcv = currentExchangeRate,
                montoBs = calculatedBsAmount,
                metodoPago = metodoPago
            )
            repository.insertPago(payment)
        }
    }

    fun deletePaymentLog(pago: HistorialPago) {
        viewModelScope.launch {
            repository.deletePago(pago)
        }
    }

    /**
     * Cleans phone strings by keeping only numbers and appending country codes.
     */
    fun sanitizePhoneNumber(rawPhone: String): String {
        var clean = rawPhone.replace(Regex("[^0-9+]"), "")
        if (clean.startsWith("0")) {
            // Convert Venezuelan 0414... to +58414...
            clean = "+58" + clean.substring(1)
        } else if (clean.startsWith("58") && clean.length == 12 && !clean.startsWith("+")) {
            clean = "+$clean"
        } else if (!clean.startsWith("+")) {
            // Apply standard fallback for local 10 digit values (e.g. 4141234567)
            if (clean.length == 10 && (clean.startsWith("414") || clean.startsWith("424") ||
                        clean.startsWith("412") || clean.startsWith("416") || clean.startsWith("212"))) {
                clean = "+58$clean"
            } else {
                clean = "+$clean"
            }
        }
        return clean
    }

    // Sealed class for UI sync results
    sealed class SyncResult {
        data class Success(val rate: Double, val manual: Boolean) : SyncResult()
        data class Fallback(val rate: Double, val lastUpdated: Long) : SyncResult()
        data class Error(val message: String, val rate: Double, val lastUpdated: Long) : SyncResult()
    }
}

class WifiViewModelFactory(
    private val application: Application,
    private val repository: WifiRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WifiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WifiViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
