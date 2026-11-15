package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction & regression tests for Round 16 Bug Q4:
 * - Restoring manual old udhari from Trash brings it back to customer profile.
 */
class Round16Q4ManualUdhariTrashTest {

    private fun createCustomer(
        id: Long,
        name: String,
        mobile: String,
        totalBills: Int = 0,
        gid: String = IdGenerator.customerGid("cust_m_$mobile")
    ): Customer {
        return Customer(
            id = id,
            gid = gid,
            shopId = 1,
            name = name,
            mobile = mobile,
            totalBills = totalBills,
            address = "कोंढारकी",
            isActive = true
        )
    }

    private fun createTx(
        id: String,
        customerId: Long,
        customerGid: String,
        name: String,
        mobile: String,
        amount: Double,
        type: UdhariTransactionType,
        kind: String = "",
        billId: Long = 0L,
        billNumber: String = "",
        billGid: String? = null,
        note: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        status: String = "ACTIVE",
        isDeleted: Boolean = false,
        isReversed: Boolean = false,
        cascadedBy: String? = null
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = if (id.startsWith("udh_") || id.startsWith("pay_")) id else IdGenerator.legacyGid(if (type == UdhariTransactionType.PURCHASE_CREDIT) IdType.UDHARI else IdType.PAYMENT, id),
            customerId = customerId,
            customerGid = customerGid,
            customerName = name,
            customerMobile = mobile,
            billId = billId,
            billNumber = billNumber,
            billGid = billGid,
            type = type,
            kind = kind,
            amount = amount,
            paymentMethod = PaymentMethod.CASH,
            transactionUuid = "uuid-$id",
            date = "2026-11-15",
            note = note,
            status = status,
            isDeleted = isDeleted,
            isReversed = isReversed,
            cascadedBy = cascadedBy,
            createdAt = createdAt
        )
    }

    @Test
    fun testQ4_ManualOldUdhari_RestoredFromTrash_ReactivatesInLedger() {
        val cust = createCustomer(401L, "TEST Mahesh", "9876543211")
        // Manual old udhari entry: 1500
        val manualUdhari = createTx(
            id = "udh_manual_401",
            customerId = cust.id,
            customerGid = cust.gid,
            name = cust.name,
            mobile = cust.mobile,
            amount = 1500.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            kind = "MANUAL",
            note = "दिवाळी खरेदी जुनी बाकी"
        )

        // Baseline: Due is 1500
        val baseLedger = UdhariLedger.computeCustomerLedger(cust.gid, emptyList<Bill>(), listOf(manualUdhari), listOf(cust))
        assertEquals(1500.0, baseLedger.balance, 0.001)

        // Soft-delete to Trash
        val trashedUdhari = manualUdhari.copy(
            status = "TRASH",
            isDeleted = true
        )
        val trashedLedger = UdhariLedger.computeCustomerLedger(cust.gid, emptyList<Bill>(), listOf(trashedUdhari), listOf(cust))
        assertEquals("Trashed manual udhari must not count toward due", 0.0, trashedLedger.balance, 0.001)

        // Restore from Trash
        val restoredUdhari = trashedUdhari.copy(
            status = "ACTIVE",
            isDeleted = false,
            kind = "MANUAL"
        )
        val restoredLedger = UdhariLedger.computeCustomerLedger(cust.gid, emptyList<Bill>(), listOf(restoredUdhari), listOf(cust))
        assertEquals("Restored manual udhari must return due to 1500", 1500.0, restoredLedger.balance, 0.001)
        assertEquals("Kind must remain MANUAL", "MANUAL", restoredUdhari.kind)
        assertEquals("Note must remain intact", "दिवाळी खरेदी जुनी बाकी", restoredUdhari.note)
    }
}
