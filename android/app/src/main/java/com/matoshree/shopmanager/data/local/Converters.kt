package com.matoshree.shopmanager.data.local

import androidx.room.TypeConverter
import com.matoshree.shopmanager.domain.model.*

class Converters {
    @TypeConverter
    fun fromSaleType(value: SaleType): String = value.name
    @TypeConverter
    fun toSaleType(value: String): SaleType = try { SaleType.valueOf(value) } catch (e: Exception) { SaleType.DETAILED }

    @TypeConverter
    fun fromProfitType(value: ProfitType): String = value.name
    @TypeConverter
    fun toProfitType(value: String): ProfitType = try { ProfitType.valueOf(value) } catch (e: Exception) { ProfitType.ESTIMATED }

    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String = value.name
    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod = try { PaymentMethod.valueOf(value) } catch (e: Exception) { PaymentMethod.CASH }

    @TypeConverter
    fun fromPaymentStatus(value: PaymentStatus): String = value.name
    @TypeConverter
    fun toPaymentStatus(value: String): PaymentStatus = try { PaymentStatus.valueOf(value) } catch (e: Exception) { PaymentStatus.PAID }

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = try { SyncStatus.valueOf(value) } catch (e: Exception) { SyncStatus.PENDING }
}
