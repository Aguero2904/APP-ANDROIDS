package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.HistorialPago
import kotlinx.coroutines.flow.Flow

@Dao
interface HistorialPagoDao {
    @Query("SELECT * FROM historial_pagos ORDER BY fecha DESC")
    fun getAllPagos(): Flow<List<HistorialPago>>

    @Query("SELECT * FROM historial_pagos WHERE clienteId = :clienteId ORDER BY fecha DESC")
    fun getPagosForCliente(clienteId: Int): Flow<List<HistorialPago>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPago(pago: HistorialPago): Long

    @Delete
    suspend fun deletePago(pago: HistorialPago)

    @Query("SELECT * FROM historial_pagos WHERE clienteId = :clienteId AND fecha >= :startOfMilis AND fecha <= :endOfMilis")
    suspend fun getPagosForClienteInPeriod(clienteId: Int, startOfMilis: Long, endOfMilis: Long): List<HistorialPago>
}
