package com.matoshree.shopmanager.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminder_logs",
    indices = [
        Index("timestamp"),
        Index("date"),
        Index("customerMobile"),
        Index("customerGid")
    ]
)
data class ReminderLogEntity(
    @PrimaryKey val id: String,
    val customerGid: String? = null,
    val customerName: String,
    val customerMobile: String,
    val amount: Double,
    val type: String, // "DUE_DATE", "MONDAY", "MANUAL"
    val status: String, // "SENT", "FAILED"
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val date: String // "yyyy-MM-dd"
)
