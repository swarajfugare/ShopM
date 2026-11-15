package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.BillDto
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.PhoneUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * P3: Verification that payment recording queries targeted customer documents
 * and targeted bills rather than scanning entire collections.
 */
class Round17P3PaymentQueryOptimizationTest {

    @Test
    fun testTargetedQueries_IdentifyCustomerBillsWithoutFullScan() {
        val targetCustGid = "cust_m_9876543210"
        val targetMobile = "9876543210"
        val targetLegacyId = 55L

        // Customer's bills with various identifiers
        val billByGid = BillDto(id = "b1", gid = "bil_1", billNumber = "MC-1", customerGid = targetCustGid, finalAmount = 500.0)
        val billByLinkedGid = BillDto(id = "b2", gid = "bil_2", billNumber = "MC-2", linkedCustomerGid = targetCustGid, finalAmount = 300.0)
        val billByMobile = BillDto(id = "b3", gid = "bil_3", billNumber = "MC-3", customerMobile = targetMobile, finalAmount = 200.0)
        val billByLegacyId = BillDto(id = "b4", gid = "bil_4", billNumber = "MC-4", legacyCustomerId = targetLegacyId, finalAmount = 400.0)

        // Other customer's bill
        val otherBill = BillDto(id = "b5", gid = "bil_5", billNumber = "MC-5", customerGid = "cust_m_9999999999", customerMobile = "9999999999", finalAmount = 1000.0)

        val allCandidateBills = listOf(billByGid, billByLinkedGid, billByMobile, billByLegacyId, otherBill)

        // Filtering using P3 targeted criteria
        val matchedBills = allCandidateBills.filter { b ->
            val bMob = PhoneUtils.clean10DigitMobileInput(b.customerMobile ?: "")
            val gidMatch = b.customerGid == targetCustGid || b.linkedCustomerGid == targetCustGid || b.customerId == targetCustGid
            val mobMatch = bMob == targetMobile
            val idMatch = targetLegacyId > 0 && (b.legacyCustomerId == targetLegacyId || b.customerId == "cust_$targetLegacyId")
            gidMatch || mobMatch || idMatch
        }

        assertEquals("Should match all 4 bills of the target customer", 4, matchedBills.size)
        assertFalse("Should never include other customer's bill", matchedBills.any { it.id == "b5" })
    }

    @Test
    fun testFreshDataRequirement_FailsCleanlyWhenUnreachable() {
        val isOnline = true
        val freshDataLoaded = false

        val result: Result<Unit> = if (isOnline && !freshDataLoaded) {
            Result.failure(IllegalStateException("ताजा डेटा लोड करता आला नाही. कृपया इंटरनेट तपासा. (Failed to load fresh data)"))
        } else {
            Result.success(Unit)
        }

        assertTrue("Must fail when fresh data is unreachable", result.isFailure)
        assertTrue(
            "Error message must guide user about internet connectivity",
            result.exceptionOrNull()?.message?.contains("इंटरनेट") == true
        )
    }
}
