package com.example.data.repository

import android.util.Log
import com.example.data.dao.BcvRateDao
import com.example.data.dao.ClienteDao
import com.example.data.dao.HistorialPagoDao
import com.example.data.model.BcvRate
import com.example.data.model.Cliente
import com.example.data.model.HistorialPago
import com.example.data.network.BcvRateFetcher
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class WifiRepository(
    private val clienteDao: ClienteDao,
    private val historialPagoDao: HistorialPagoDao,
    private val bcvRateDao: BcvRateDao
) {
    private val TAG = "WifiRepository"

    val allClientes: Flow<List<Cliente>> = clienteDao.getAllClientes()
    val allPagos: Flow<List<HistorialPago>> = historialPagoDao.getAllPagos()
    val bcvRateFlow: Flow<BcvRate?> = bcvRateDao.getBcvRateFlow()

    // Get current rate synchronous/suspend
    suspend fun getLatestBcvRate(): BcvRate? {
        return bcvRateDao.getBcvRate()
    }

    /**
     * Performs rate synchronization via scraping and API backup.
     * Returns true if online fetch worked, false if it had to use DB fallback.
     */
    suspend fun syncBcvRate(): Boolean {
        val onlineRate = BcvRateFetcher.fetchLatestRate()
        if (onlineRate != null && onlineRate > 0) {
            val existing = bcvRateDao.getBcvRate()
            val existingWeekendRate = existing?.weekendRate ?: 0.0
            val bcvRateEntity = BcvRate(
                id = 1,
                rate = onlineRate,
                weekendRate = existingWeekendRate,
                lastUpdated = System.currentTimeMillis()
            )
            bcvRateDao.insertBcvRate(bcvRateEntity)
            Log.d(TAG, "Successfully synced online BCV rate: $onlineRate")
            return true
        } else {
            Log.w(TAG, "Online BCV rate fetch failed. Fallback to existing database value.")
            return false
        }
    }

    suspend fun saveManualRate(rate: Double, weekendRate: Double = 0.0) {
        val bcvRateEntity = BcvRate(
            id = 1,
            rate = rate,
            weekendRate = weekendRate,
            lastUpdated = System.currentTimeMillis()
        )
        bcvRateDao.insertBcvRate(bcvRateEntity)
    }

    suspend fun insertCliente(cliente: Cliente): Long {
        val clientId = clienteDao.insertCliente(cliente)
        updateSingleClientStatus(clientId.toInt())
        return clientId
    }

    suspend fun updateCliente(cliente: Cliente) {
        clienteDao.updateCliente(cliente)
        updateSingleClientStatus(cliente.id)
    }

    suspend fun deleteCliente(cliente: Cliente) = clienteDao.deleteCliente(cliente)
    
    suspend fun insertPago(pago: HistorialPago): Long {
        val cleanPagoId = historialPagoDao.insertPago(pago)
        // Set client status to "Al día" because a payment was registered this month.
        clienteDao.updateClienteEstado(pago.clienteId, "Al día")
        return cleanPagoId
    }

    suspend fun deletePago(pago: HistorialPago) {
        historialPagoDao.deletePago(pago)
        // Re-evaluate client status when a payment is deleted
        updateSingleClientStatus(pago.clienteId)
    }

    /**
     * Loops through all clients and updates statuses based on date rules:
     * - Payment in current month -> "Al día"
     * - No payment:
     *   - Day 15-20 -> "Por pagar"
     *   - Day 21+ -> "Moroso/Pendiente"
     *   - Day 1-14 -> "Al día" (or "Moroso/Pendiente" if they missed previous month's payment)
     */
    suspend fun updateAllClientStatuses() {
        val currentCalendar = Calendar.getInstance()
        val dayOfMonth = currentCalendar.get(Calendar.DAY_OF_MONTH)
        
        // Boundaries of current calendar month
        val (startOfMilis, endOfMilis) = getCurrentMonthBoundaries()

        val clientes = clienteDao.getAllClientesSuspend()
        for (cliente in clientes) {
            // Find payments in current month
            val paymentsInMonth = historialPagoDao.getPagosForClienteInPeriod(cliente.id, startOfMilis, endOfMilis)
            val totalPaidThisMonth = paymentsInMonth.sumOf { it.montoUsd }
            
            val targetEstado = if (totalPaidThisMonth >= 2.0) {
                "Al día"
            } else {
                when {
                    dayOfMonth in 15..20 -> "Por pagar"
                    dayOfMonth >= 21 -> "Moroso/Pendiente"
                    else -> {
                        // Days 1-14. Check if they had a payment in the previous calendar month.
                        val (prevStart, prevEnd) = getPreviousMonthBoundaries()
                        val paymentsPrevMonth = historialPagoDao.getPagosForClienteInPeriod(cliente.id, prevStart, prevEnd)
                        val totalPaidPrevMonth = paymentsPrevMonth.sumOf { it.montoUsd }
                        
                        if (totalPaidPrevMonth >= 2.0) {
                            "Al día" // Paid previous month, so they are Up To Date during first half of month
                        } else {
                            "Moroso/Pendiente" // Missed previous month, they remain Moroso/Pendiente
                        }
                    }
                }
            }
            
            if (cliente.estado != targetEstado) {
                clienteDao.updateClienteEstado(cliente.id, targetEstado)
            }
        }
    }

    suspend fun updateSingleClientStatus(clienteId: Int) {
        val cliente = clienteDao.getClienteByIdSuspend(clienteId) ?: return
        val currentCalendar = Calendar.getInstance()
        val dayOfMonth = currentCalendar.get(Calendar.DAY_OF_MONTH)
        val (startOfMilis, endOfMilis) = getCurrentMonthBoundaries()
        
        val paymentsInMonth = historialPagoDao.getPagosForClienteInPeriod(cliente.id, startOfMilis, endOfMilis)
        val totalPaidThisMonth = paymentsInMonth.sumOf { it.montoUsd }
        
        val targetEstado = if (totalPaidThisMonth >= 2.0) {
            "Al día"
        } else {
            when {
                dayOfMonth in 15..20 -> "Por pagar"
                dayOfMonth >= 21 -> "Moroso/Pendiente"
                else -> {
                    val (prevStart, prevEnd) = getPreviousMonthBoundaries()
                    val paymentsPrevMonth = historialPagoDao.getPagosForClienteInPeriod(cliente.id, prevStart, prevEnd)
                    val totalPaidPrevMonth = paymentsPrevMonth.sumOf { it.montoUsd }
                    
                    if (totalPaidPrevMonth >= 2.0) "Al día" else "Moroso/Pendiente"
                }
            }
        }
        
        if (cliente.estado != targetEstado) {
            clienteDao.updateClienteEstado(cliente.id, targetEstado)
        }
    }

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

    private fun getPreviousMonthBoundaries(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
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
}
