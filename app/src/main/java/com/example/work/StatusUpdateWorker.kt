package com.example.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.database.WifiAppDatabase
import com.example.data.repository.WifiRepository

class StatusUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("StatusUpdateWorker", "Starting background client status evaluation...")
        return try {
            val database = WifiAppDatabase.getDatabase(applicationContext)
            val repository = WifiRepository(
                database.clienteDao(),
                database.historialPagoDao(),
                database.bcvRateDao()
            )
            
            // Background rate synchronization
            repository.syncBcvRate()
            
            // Re-evaluate client credit and payment status based on calendar rules
            repository.updateAllClientStatuses()
            
            Log.d("StatusUpdateWorker", "Successfully finished client status updates.")
            Result.success()
        } catch (e: Exception) {
            Log.e("StatusUpdateWorker", "Error evaluating client statuses in background", e)
            Result.retry()
        }
    }
}
