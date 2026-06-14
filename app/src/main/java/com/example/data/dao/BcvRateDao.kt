package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.BcvRate
import kotlinx.coroutines.flow.Flow

@Dao
interface BcvRateDao {
    @Query("SELECT * FROM bcv_rate WHERE id = 1 LIMIT 1")
    fun getBcvRateFlow(): Flow<BcvRate?>

    @Query("SELECT * FROM bcv_rate WHERE id = 1 LIMIT 1")
    suspend fun getBcvRate(): BcvRate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBcvRate(bcvRate: BcvRate)
}
