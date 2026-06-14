package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.database.WifiAppDatabase
import com.example.data.repository.WifiRepository
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.WifiViewModel
import com.example.ui.viewmodel.WifiViewModelFactory
import com.example.work.StatusUpdateWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val database by lazy { WifiAppDatabase.getDatabase(this) }
    private val repository by lazy {
        WifiRepository(
            database.clienteDao(),
            database.historialPagoDao(),
            database.bcvRateDao()
        )
    }

    private val viewModel: WifiViewModel by viewModels {
        WifiViewModelFactory(application, repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()

        // Configure the daily automated checks via Android WorkManager
        scheduleDailyBillingCycleChecks()

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun scheduleDailyBillingCycleChecks() {
        try {
            val billingWorkRequest = PeriodicWorkRequestBuilder<StatusUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS) // Delay initial run so it doesn't collide with app startup checks
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "AutomatedWifiBillingCycle",
                ExistingPeriodicWorkPolicy.KEEP, // Maintain current sequence to avoid double resets
                billingWorkRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
