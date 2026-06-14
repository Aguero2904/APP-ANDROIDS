package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clientes")
data class Cliente(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val telefono: String, // Formato internacional (+58...)
    val estado: String = "Al día", // "Al día", "Por pagar", "Moroso/Pendiente"
    val fechaRegistro: Long = System.currentTimeMillis()
)
