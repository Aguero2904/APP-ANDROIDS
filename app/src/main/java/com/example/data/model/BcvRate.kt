package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bcv_rate")
data class BcvRate(
    @PrimaryKey val id: Int = 1, // We only need a single row to represent the last successful rate
    val rate: Double,
    val weekendRate: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis()
)
