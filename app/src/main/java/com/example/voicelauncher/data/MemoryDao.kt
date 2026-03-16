package com.example.voicelauncher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertMemory(entry: MemoryEntry)

    @Query("SELECT * FROM memory_entries ORDER BY timestampMs ASC")
    suspend fun getAllMemories(): List<MemoryEntry>

    @Query("DELETE FROM memory_entries")
    suspend fun clearAll()

    @androidx.room.Delete
    suspend fun deleteMemory(entry: MemoryEntry)
}
