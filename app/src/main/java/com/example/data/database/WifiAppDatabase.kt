package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.BcvRateDao
import com.example.data.dao.ClienteDao
import com.example.data.dao.HistorialPagoDao
import com.example.data.model.BcvRate
import com.example.data.model.Cliente
import com.example.data.model.HistorialPago

@Database(
    entities = [Cliente::class, HistorialPago::class, BcvRate::class],
    version = 2,
    exportSchema = false
)
abstract class WifiAppDatabase : RoomDatabase() {

    abstract fun clienteDao(): ClienteDao
    abstract fun historialPagoDao(): HistorialPagoDao
    abstract fun bcvRateDao(): BcvRateDao

    companion object {
        @Volatile
        private var INSTANCE: WifiAppDatabase? = null

        fun getDatabase(context: Context): WifiAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WifiAppDatabase::class.java,
                    "wifi_cobranza_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
