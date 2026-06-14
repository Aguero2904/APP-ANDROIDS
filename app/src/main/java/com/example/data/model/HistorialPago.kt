package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "historial_pagos")
data class HistorialPago(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val clienteId: Int,
    val fecha: Long = System.currentTimeMillis(),
    val montoUsd: Double,
    val tasaBcv: Double,
    val montoBs: Double, // Calculado automáticamente: montoUsd * tasaBcv
    val metodoPago: String // "Pago Móvil", "Efectivo USD", "Efectivo Bs"
)
