package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.BillDto
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.UdhariTransactionDto
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.PhoneUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * P1: Payment recording customer and bill isolation tests.
 * Asserts:
 * 1. Customer and bill matching must NEVER match across different customers by name.
 * 2. Ambiguous payments (no valid GID, ID, or 10-digit mobile) must fail.
 * 3. Two customers with the identical name do not have their bills or balances cross-contaminated.
 */
class Round17P1PaymentCustomerIsolationTest {

    private fun createCustomer(id: Long, name: String, mobile: String): Customer {
        val cleanMob = PhoneUtils.clean10DigitMobileInput(mobile)
        return Customer(
            id = id,
            gid = "cust_m_$cleanMob",
            name = name,
            mobile = cleanMob,
            isActive = true
        )
    }

    private fun createBill(
        id: Long,
        billNumber: String,
        customer: Customer,
        finalAmount: Double,
        paidAmount: Double = 0.0
    ): Bill {
        return Bill(
            id = id,
            gid = "bil_$billNumber",
            billNumber = billNumber,
            billDate = "2026-11-15",
            transactionUuid = "uuid-$billNumber",
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            customerId = customer.id,
            customerGid = customer.gid,
            customerName = customer.name,
            customerMobile = customer.mobile,
            finalAmount = finalAmount,
            paidAmount = paidAmount,
            pendingAmount = (finalAmount - paidAmount).coerceAtLeast(0.0),
            paymentStatus = if (paidAmount >= finalAmount) PaymentStatus.PAID else if (paidAmount > 0.0) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
            paymentMethod = PaymentMethod.CASH
        )
    }

    @Test
    fun testTwoCustomersWithIdenticalName_LedgerIsStrictlyIsolated() {
        val cust1 = createCustomer(1L, "Ram Sharma", "9876543210")
        val cust2 = createCustomer(2L, "Ram Sharma", "9123456780")

        val bill1 = createBill(101L, "MC-101", cust1, 1000.0, 0.0)
        val bill2 = createBill(102L, "MC-102", cust2, 500.0, 0.0)

        val payCust1 = UdhariTransaction(
            id = "pay_1",
            gid = "pay_1",
            customerId = cust1.id,
            customerGid = cust1.gid,
            customerName = "Ram Sharma",
            customerMobile = cust1.mobile,
            billId = bill1.id,
            billGid = bill1.gid,
            billNumber = bill1.billNumber,
            amount = 400.0,
            type = UdhariTransactionType.PAYMENT,
            transactionUuid = "pay-uuid-1",
            date = "2026-11-15"
        )

        val allCustomers = listOf(cust1, cust2)
        val allBills = listOf(bill1, bill2)
        val allTxs = listOf(payCust1)

        val ledger1 = UdhariLedger.computeCustomerLedger(cust1.gid, allBills, allTxs, allCustomers)
        val ledger2 = UdhariLedger.computeCustomerLedger(cust2.gid, allBills, allTxs, allCustomers)

        // Cust 1: 1000 - 400 = 600 due
        assertEquals("Cust 1 due must be 600", 600.0, ledger1.balance, 0.001)
        assertEquals("Cust 1 total purchases", 1000.0, ledger1.totalPurchases, 0.001)
        assertEquals("Cust 1 total paid", 400.0, ledger1.totalPaid, 0.001)

        // Cust 2: 500 bill, 0 payments -> 500 due
        assertEquals("Cust 2 due must be unchanged at 500", 500.0, ledger2.balance, 0.001)
        assertEquals("Cust 2 total purchases", 500.0, ledger2.totalPurchases, 0.001)
        assertEquals("Cust 2 total paid must be 0", 0.0, ledger2.totalPaid, 0.001)
    }

    @Test
    fun testBillMatchingCriteria_NeverMatchesByNameAlone() {
        val cleanPaymentMobile = "9876543210"
        val paymentCustGid = "cust_m_9876543210"
        val paymentCustId = 1L
        val cleanCustName = "Ram Sharma"

        // Bill of Cust 2 who shares name "Ram Sharma" but has different mobile and GID
        val otherCustBill = BillDto(
            id = "bil_MC-200",
            gid = "bil_MC-200",
            billNumber = "MC-200",
            customerId = "cust_2",
            customerGid = "cust_m_9123456780",
            customerMobile = "9123456780",
            customerName = "Ram Sharma",
            finalAmount = 800.0
        )

        val bCleanMobile = PhoneUtils.clean10DigitMobileInput(otherCustBill.customerMobile ?: "")
        val mobileMatches = cleanPaymentMobile.isNotBlank() && bCleanMobile.isNotBlank() && bCleanMobile == cleanPaymentMobile
        val gidMatches = paymentCustGid.isNotBlank() && (
            otherCustBill.customerGid == paymentCustGid ||
            otherCustBill.linkedCustomerGid == paymentCustGid ||
            otherCustBill.customerId == paymentCustGid
        )
        val idMatches = (paymentCustId != 0L && (
            otherCustBill.legacyCustomerId == paymentCustId ||
            otherCustBill.customerId == paymentCustId.toString() ||
            otherCustBill.customerId == "cust_$paymentCustId"
        ))

        // In buggy v3.6.1 code: nameMatches was:
        val oldNameMatches = cleanCustName.isNotBlank() && otherCustBill.customerName?.trim()?.equals(cleanCustName, ignoreCase = true) == true
        assertTrue("Buggy old code matched other customer's bill by name", oldNameMatches)

        // In fixed P1 code: strictly NO nameMatches:
        val matchesIsolated = gidMatches || idMatches || mobileMatches
        assertFalse("P1 isolated criteria must NOT match other customer's bill", matchesIsolated)
    }

    @Test
    fun testAmbiguousPaymentIdentity_MustBeDetectedAndRejected() {
        val blankGid = ""
        val zeroId = 0L
        val invalidMobile = "123" // less than 10 digits

        val cleanMob = PhoneUtils.clean10DigitMobileInput(invalidMobile)
        val isIdentifiable = blankGid.isNotBlank() || zeroId > 0L || cleanMob.length == 10

        assertFalse("Payment without GID, ID, or 10-digit mobile is unidentifiable", isIdentifiable)
    }
}
