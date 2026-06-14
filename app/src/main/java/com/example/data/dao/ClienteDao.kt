package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Cliente
import kotlinx.coroutines.flow.Flow

@Dao
interface ClienteDao {
    @Query("SELECT * FROM clientes ORDER BY nombre ASC")
    fun getAllClientes(): Flow<List<Cliente>>

    @Query("SELECT * FROM clientes WHERE id = :id")
    fun getClienteById(id: Int): Flow<Cliente?>

    @Query("SELECT * FROM clientes WHERE id = :id")
    suspend fun getClienteByIdSuspend(id: Int): Cliente?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCliente(cliente: Cliente): Long

    @Update
    suspend fun updateCliente(cliente: Cliente)

    @Delete
    suspend fun deleteCliente(cliente: Cliente)

    @Query("UPDATE clientes SET estado = :estado WHERE id = :clienteId")
    suspend fun updateClienteEstado(clienteId: Int, estado: String)

    @Query("SELECT * FROM clientes")
    suspend fun getAllClientesSuspend(): List<Cliente>
}
