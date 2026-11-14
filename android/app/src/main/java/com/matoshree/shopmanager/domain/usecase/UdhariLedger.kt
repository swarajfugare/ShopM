package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PhoneUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class BillLedgerBreakdown(
    val billId: Long,
    val billNumber: String,
    val billDate: String,
    val finalAmount: Double,
    val checkoutPaid: Double,
    val directPayments: Double,
    val fifoPayments: Double,
    val remainingOutstanding: Double,
    val isOverdue: Boolean,
    val dueDate: String?
)

data class CustomerCalculationBreakdown(
    val totalBillAmount: Double = 0.0,
    val checkoutPaidAmount: Double = 0.0,
    val directSettlementAmount: Double = 0.0,
    val manualDebitAmount: Double = 0.0,
    val manualPaidAmount: Double = 0.0,
    val unallocatedPaidAmount: Double = 0.0,
    val orphanAmount: Double = 0.0,
    val orphanCount: Int = 0,
    val netDue: Double = 0.0
)

data class CustomerLedgerResult(
    val customerKey: String,
    val customer: Customer?,
    val balance: Double, // Outstanding balance (> 0 means due, < 0 means advance)
    val totalPurchases: Double, // Total bill amounts + manual debits
    val totalPaid: Double, // Real total payments (checkout + direct + fifo + unallocated)
    val billBreakdowns: List<BillLedgerBreakdown>,
    val pendingBillsCount: Int,
    val oldestDueDate: String?,
    val isOverdue: Boolean,
    val overdueAmount: Double,
    val lastPaymentDate: String?,
    val lastPaymentAmount: Double?,
    val events: List<KhatavahiEntry>,
    val orphanPayments: List<UdhariTransaction> = emptyList(),
    val orphanTransactions: List<UdhariTransaction> = emptyList(),
    val calculationBreakdown: CustomerCalculationBreakdown = CustomerCalculationBreakdown()
)

data class ShopUdhariSummary(
    val totalOutstanding: Double,
    val pendingCustomersCount: Int,
    val todayCollections: Double,
    val todayCashCollections: Double,
    val todayUpiCollections: Double,
    val thisMonthCollections: Double,
    val overdueAmount: Double,
    val pendingBillsCount: Int,
    val customerResults: Map<String, CustomerLedgerResult>,
    val allOrphanPayments: List<UdhariTransaction> = emptyList(),
    val allOrphanTransactions: List<UdhariTransaction> = emptyList()
)

fun interface CustomerKeyResolver {
    fun resolve(gid: String?, mobile: String?, name: String?, localId: Long?): String?

    operator fun invoke(gid: String?, mobile: String?, name: String?, localId: Long?): String? =
        resolve(gid, mobile, name, localId)

    operator fun invoke(mobile: String?, name: String?, localId: Long?): String? =
        resolve(null, mobile, name, localId)
}

/**
 * Authoritative Single Udhari Ledger Calculation Engine.
 * Used identically across:
 * - Khate Page (UdhariScreen)
 * - Customer Profile (Header, Khatavahi Tab, All Payments Tab, Payment Dialog)
 * - Home Screen (Dashboard Metrics)
 * - Automated WhatsApp Reminder Workers
 * - Customer Diagnostic & Repair Tool
 */
object UdhariLedger {

    fun resolveCustomerKey(gid: String?, mobile: String?, name: String?, localId: Long?): String? {
        if (!gid.isNullOrBlank()) return gid
        val clean = PhoneUtils.clean10DigitMobileInput(mobile ?: "")
        return when {
            clean.length == 10 -> "mob_$clean"
            localId != null && localId > 0L -> "cust_$localId"
            !name.isNullOrBlank() -> "name_${name.trim().lowercase()}"
            else -> null
        }
    }

    fun resolveCustomerKey(mobile: String?, name: String?, localId: Long?): String? =
        resolveCustomerKey(null, mobile, name, localId)

    /**
     * Builds a unified resolver that links customer entities, bills, and transactions
     * strictly by customerGid and normalized 10-digit mobile number.
     * Numeric ID aliases and name-only fallbacks are strictly excluded to prevent
     * cross-customer bill/payment contamination.
     */
    fun buildCustomerKeyResolver(
        customers: List<Customer> = emptyList(),
        bills: List<Bill> = emptyList(),
        transactions: List<UdhariTransaction> = emptyList(),
        aliases: Map<String, String> = emptyMap()
    ): CustomerKeyResolver {
        val aliasToCanonical = mutableMapOf<String, String>()
        aliasToCanonical.putAll(aliases)

        val nameCount = mutableMapOf<String, Int>()
        for (c in customers) {
            val cleanName = c.name.trim().lowercase()
            if (cleanName.isNotBlank()) {
                nameCount[cleanName] = (nameCount[cleanName] ?: 0) + 1
            }
        }

        for (c in customers) {
            val cleanMob = PhoneUtils.clean10DigitMobileInput(c.mobile)
            val cleanName = c.name.trim().lowercase()
            val canonical = when {
                c.gid.isNotBlank() -> c.gid
                cleanMob.length == 10 -> "mob_$cleanMob"
                cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1 -> "name_$cleanName"
                c.id > 0L -> "cust_${c.id}"
                else -> "cust_${c.name.trim().lowercase()}"
            }

            if (c.gid.isNotBlank()) {
                aliasToCanonical[c.gid] = canonical
            }
            if (cleanMob.length == 10) {
                aliasToCanonical["mob_$cleanMob"] = canonical
                aliasToCanonical[IdGenerator.customerGid("cust_m_$cleanMob")] = canonical
            }
            if (cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1) {
                aliasToCanonical["name_$cleanName"] = canonical
            }
            if (c.id > 0L && cleanMob.length != 10) {
                aliasToCanonical["cust_${c.id}"] = canonical
                aliasToCanonical["id_${c.id}"] = canonical
            }
        }

        return CustomerKeyResolver { gid, mobile, name, localId ->
            // 1. Primary: match by customerGid
            if (!gid.isNullOrBlank()) {
                val byGid = aliasToCanonical[gid]
                if (byGid != null) return@CustomerKeyResolver byGid
            }

            // 2. Secondary: match by clean 10-digit mobile number
            val cleanMob = PhoneUtils.clean10DigitMobileInput(mobile ?: "")
            if (cleanMob.length == 10) {
                val byMob = aliasToCanonical["mob_$cleanMob"]
                if (byMob != null) return@CustomerKeyResolver byMob
                return@CustomerKeyResolver "mob_$cleanMob"
            }

            // 3. Fallback:
            if (!gid.isNullOrBlank()) {
                return@CustomerKeyResolver gid
            }

            val cleanName = name?.trim()?.lowercase() ?: ""
            if (cleanName.isNotBlank()) {
                val byName = aliasToCanonical["name_$cleanName"]
                if (byName != null) return@CustomerKeyResolver byName
                if ((nameCount[cleanName] ?: 0) == 1) return@CustomerKeyResolver "name_$cleanName"
            }

            if (localId != null && localId > 0L) {
                val byId = aliasToCanonical["cust_$localId"] ?: aliasToCanonical["id_$localId"]
                if (byId != null) return@CustomerKeyResolver byId
            }

            resolveCustomerKey(gid, mobile, name, localId)
        }
    }

    private fun parseBillMillis(dateStr: String, idFallback: Long): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = DateUtils.kolkataTimeZone
            }
            sdf.parse(dateStr)?.time ?: (idFallback * 1000L)
        } catch (_: Exception) {
            try {
                val sdf2 = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
                    timeZone = DateUtils.kolkataTimeZone
                }
                sdf2.parse(dateStr)?.time ?: (idFallback * 1000L)
            } catch (_: Exception) {
                idFallback * 1000L
            }
        }
    }

    /**
     * Computes the complete ledger result for a single customer key.
     */
    fun computeCustomerLedger(
        customerKey: String,
        allBills: List<Bill>,
        allTransactions: List<UdhariTransaction>,
        allCustomers: List<Customer>
    ): CustomerLedgerResult {
        val nonVoidBills = allBills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.VOID }
        val voidedBillIds = allBills.filter { it.isVoided || it.paymentStatus == PaymentStatus.VOID }.map { it.id }.toSet()
        val voidedBillNumbers = allBills.filter { (it.isVoided || it.paymentStatus == PaymentStatus.VOID) && it.billNumber.isNotBlank() }
            .map { it.billNumber }.toSet()

        val validTxs = allTransactions.filter {
            !it.isReversed && it.status != "TRASH" &&
            !voidedBillIds.contains(it.billId) &&
            !(it.billNumber.isNotBlank() && voidedBillNumbers.contains(it.billNumber))
        }

        val keyResolver = buildCustomerKeyResolver(allCustomers, nonVoidBills, validTxs)

        val cust = allCustomers.find {
            (it.gid.isNotBlank() && it.gid == customerKey) ||
            keyResolver(it.gid, it.mobile, it.name, it.id) == customerKey
        }

        val rawCustBills = nonVoidBills.filter { b ->
            (!b.customerGid.isNullOrBlank() && b.customerGid == customerKey) ||
            keyResolver(b.customerGid, b.customerMobile, b.customerName, b.customerId) == customerKey
        }.sortedWith(compareBy({ it.billDate }, { it.id }))

        // Deduplicate bills within customer by GID or transactionUuid (never by billNumber alone)
        val seenBillGids = mutableSetOf<String>()
        val seenBillUuids = mutableSetOf<String>()
        val custBills = mutableListOf<Bill>()

        for (b in rawCustBills) {
            if (b.gid.isNotBlank() && seenBillGids.contains(b.gid)) continue
            val uuid = b.transactionUuid.trim()
            if (uuid.isNotBlank() && seenBillUuids.contains(uuid)) continue

            if (b.gid.isNotBlank()) seenBillGids.add(b.gid)
            if (uuid.isNotBlank()) seenBillUuids.add(uuid)
            custBills.add(b)
        }

        val rawCustTxs = validTxs.filter { tx ->
            (tx.customerGid.isNotBlank() && tx.customerGid == customerKey) ||
            keyResolver(tx.customerGid, tx.customerMobile, tx.customerName, tx.customerId) == customerKey
        }

        // Deduplicate transactions within customer by GID, ID, or transactionUuid only
        val seenTxGids = mutableSetOf<String>()
        val seenTxIds = mutableSetOf<String>()
        val seenTxUuids = mutableSetOf<String>()
        val custTxs = mutableListOf<UdhariTransaction>()

        for (tx in rawCustTxs) {
            if (tx.gid.isNotBlank() && seenTxGids.contains(tx.gid)) continue
            if (tx.id.isNotBlank() && seenTxIds.contains(tx.id)) continue
            val uuid = tx.transactionUuid.trim()
            if (uuid.isNotBlank() && seenTxUuids.contains(uuid)) continue

            if (tx.gid.isNotBlank()) seenTxGids.add(tx.gid)
            if (tx.id.isNotBlank()) seenTxIds.add(tx.id)
            if (uuid.isNotBlank()) seenTxUuids.add(uuid)
            custTxs.add(tx)
        }

        // 1. Process each bill's direct checkout payment & direct payment transactions
        val billBreakdowns = mutableListOf<BillLedgerBreakdown>()
        val vahiEvents = mutableListOf<KhatavahiEntry>()

        var totalCheckoutPaid = 0.0
        var totalDirectBillPaid = 0.0

        class MutableBillState(
            val billId: Long,
            val billNumber: String,
            val billDate: String,
            val finalAmount: Double,
            val dueDate: String?,
            val checkoutPaid: Double,
            val directPayments: Double,
            var fifoPayments: Double = 0.0
        ) {
            val netRemaining: Double get() = finalAmount - (checkoutPaid + directPayments + fifoPayments)
        }

        val billStates = mutableListOf<MutableBillState>()
        val allCheckoutTxIds = mutableSetOf<String>()
        val allCheckoutTxGids = mutableSetOf<String>()

        for (b in custBills) {
            val billMillis = parseBillMillis(b.billDate, b.id)

            // Charge event (+)
            val itemsDesc = if (b.items.isNotEmpty()) {
                b.items.joinToString(", ") { "${it.productName}${if (it.quantity > 1) " (${it.quantity})" else ""}" }
            } else {
                if (!b.note.isNullOrBlank()) b.note!! else "बिल खरेदी (#${b.billNumber})"
            }

            vahiEvents.add(
                KhatavahiEntry(
                    id = if (b.gid.isNotBlank()) b.gid else "bill_${b.id}",
                    date = b.billDate,
                    timeMillis = billMillis,
                    description = itemsDesc,
                    isCredit = false,
                    amount = b.finalAmount,
                    runningBalance = 0.0,
                    billId = b.id,
                    billNumber = b.billNumber,
                    paymentMethod = b.paymentMethod
                )
            )

            // Direct payment transactions allocated to this bill
            val directTxsForBill = custTxs.filter {
                it.type == UdhariTransactionType.PAYMENT && (
                    (!it.billGid.isNullOrBlank() && b.gid.isNotBlank() && it.billGid == b.gid) ||
                    (it.billId != 0L && it.billId == b.id) ||
                    (it.billNumber.isNotBlank() && it.billNumber.trim().equals(b.billNumber.trim(), ignoreCase = true))
                )
            }

            val checkoutTx = directTxsForBill.firstOrNull { tx ->
                val noteMatch = tx.note?.contains("checkout", ignoreCase = true) == true ||
                                tx.note?.contains("initial", ignoreCase = true) == true
                val amountMatch = b.paidAmount > 0.01 && kotlin.math.abs(tx.amount - b.paidAmount) < 0.001
                noteMatch || amountMatch
            }
            if (checkoutTx != null) {
                if (checkoutTx.id.isNotBlank()) allCheckoutTxIds.add(checkoutTx.id)
                if (checkoutTx.gid.isNotBlank()) allCheckoutTxGids.add(checkoutTx.gid)
            }

            val checkoutPaid = when {
                checkoutTx != null -> b.paidAmount
                b.paymentStatus == PaymentStatus.PAID && directTxsForBill.isEmpty() -> b.finalAmount
                b.paidAmount > 0.0 -> b.paidAmount
                else -> 0.0
            }

            if (checkoutPaid > 0.01 && checkoutTx == null) {
                totalCheckoutPaid += checkoutPaid
                vahiEvents.add(
                    KhatavahiEntry(
                        id = "checkout_pay_${if (b.gid.isNotBlank()) b.gid else b.id}",
                        date = b.billDate,
                        timeMillis = billMillis + 100L,
                        description = "बिल भरणा (${b.paymentMethod.name})",
                        isCredit = true,
                        amount = checkoutPaid,
                        runningBalance = 0.0,
                        billId = b.id,
                        billNumber = b.billNumber,
                        paymentMethod = b.paymentMethod
                    )
                )
            } else if (checkoutTx != null) {
                totalCheckoutPaid += checkoutPaid
            }

            val nonCheckoutDirectTxs = if (checkoutTx != null) {
                directTxsForBill.filter { it.id != checkoutTx.id && it.gid != checkoutTx.gid }
            } else {
                directTxsForBill
            }
            val directPaid = nonCheckoutDirectTxs.sumOf { it.amount }
            totalDirectBillPaid += directPaid

            billStates.add(
                MutableBillState(
                    billId = b.id,
                    billNumber = b.billNumber,
                    billDate = b.billDate,
                    finalAmount = b.finalAmount,
                    dueDate = b.dueDate,
                    checkoutPaid = checkoutPaid,
                    directPayments = directPaid
                )
            )
        }

        // 1b. Process unlinked credit transactions (PURCHASE_CREDIT transactions for bills absent from allBills)
        val knownCustBillGids = custBills.map { it.gid }.filter { it.isNotBlank() }.toSet()
        val knownCustBillIds = custBills.map { it.id }.filter { it != 0L }.toSet()
        val knownCustBillNumbers = custBills.map { it.billNumber.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val knownCustBillUuids = custBills.map { it.transactionUuid.trim() }.filter { it.isNotBlank() }.toSet()

        val unlinkedCreditTxs = custTxs.filter { tx ->
            val cleanNum = tx.billNumber.trim().uppercase()
            val uuid = tx.transactionUuid.trim()
            val note = tx.note?.trim()?.uppercase() ?: ""

            val matchesKnownBill =
                (!tx.billGid.isNullOrBlank() && knownCustBillGids.contains(tx.billGid)) ||
                (tx.billId != 0L && knownCustBillIds.contains(tx.billId)) ||
                (cleanNum.isNotBlank() && knownCustBillNumbers.contains(cleanNum)) ||
                (uuid.isNotBlank() && knownCustBillUuids.contains(uuid))

            tx.type == UdhariTransactionType.PURCHASE_CREDIT && !matchesKnownBill
        }

        // 1b. Process unlinked credit transactions (separate pure manual debits from orphan credits referencing deleted/missing bills vs legacy bills)
        val orphanCreditTxs = mutableListOf<UdhariTransaction>()
        val manualDebits = mutableListOf<UdhariTransaction>()
        val legacyCreditTxs = mutableListOf<UdhariTransaction>()

        for (tx in unlinkedCreditTxs) {
            val cleanNum = tx.billNumber.trim().uppercase()
            val hasBillRef = !tx.billGid.isNullOrBlank() || tx.billId != 0L || cleanNum.isNotBlank()
            if (hasBillRef) {
                val isDeletedAppBill = cleanNum.startsWith("MC-") ||
                                       allBills.any { b -> (b.isVoided || b.paymentStatus == PaymentStatus.VOID) &&
                                           ((!tx.billGid.isNullOrBlank() && b.gid == tx.billGid) ||
                                            (cleanNum.isNotBlank() && b.billNumber.trim().equals(cleanNum, ignoreCase = true)) ||
                                            (tx.billId != 0L && b.id == tx.billId)) } ||
                                       (cust != null && cust.totalBills == 0 && custBills.isEmpty())

                if (isDeletedAppBill) {
                    orphanCreditTxs.add(tx)
                } else {
                    legacyCreditTxs.add(tx)
                }
            } else {
                manualDebits.add(tx)
            }
        }

        val unlinkedBillTxs = legacyCreditTxs.groupBy { if (it.billId != 0L) "id_${it.billId}" else "num_${it.billNumber}" }
        var totalLegacyBillAmount = 0.0
        for ((_, group) in unlinkedBillTxs) {
            val firstTx = group.first()
            val totalBillAmount = group.sumOf { it.amount }
            totalLegacyBillAmount += totalBillAmount
            val billId = firstTx.billId
            val billNumber = firstTx.billNumber

            val directTxsForBill = custTxs.filter {
                it.type == UdhariTransactionType.PAYMENT &&
                ((billId != 0L && it.billId == billId) || (billNumber.isNotBlank() && it.billNumber.trim().equals(billNumber.trim(), ignoreCase = true)))
            }
            val directPaid = directTxsForBill.sumOf { it.amount }
            totalDirectBillPaid += directPaid

            for (tx in group) {
                vahiEvents.add(
                    KhatavahiEntry(
                        id = "credit_${tx.id}",
                        date = tx.date,
                        timeMillis = tx.createdAt,
                        description = tx.note?.ifBlank { "बिल खरेदी (${tx.billNumber})" } ?: "बिल खरेदी (${tx.billNumber})",
                        isCredit = false,
                        amount = tx.amount,
                        runningBalance = 0.0,
                        billId = if (tx.billId != 0L) tx.billId else null,
                        billNumber = tx.billNumber.ifBlank { null },
                        paymentMethod = tx.paymentMethod
                    )
                )
            }

            billStates.add(
                MutableBillState(
                    billId = billId,
                    billNumber = billNumber,
                    billDate = firstTx.date,
                    finalAmount = totalBillAmount,
                    dueDate = firstTx.dueDate,
                    checkoutPaid = 0.0,
                    directPayments = directPaid
                )
            )
        }

        // 2. Add manual debits (PURCHASE_CREDIT with billId == 0L and billNumber.isBlank())
        var totalManualDebits = manualDebits.sumOf { it.amount }

        for (m in manualDebits) {
            vahiEvents.add(
                KhatavahiEntry(
                    id = "manual_${m.id}",
                    date = m.date,
                    timeMillis = m.createdAt,
                    description = m.note?.ifBlank { "मॅन्युअल उधारी नोंद" } ?: "मॅन्युअल उधारी नोंद",
                    isCredit = false,
                    amount = m.amount,
                    runningBalance = 0.0,
                    billId = null,
                    billNumber = null,
                    paymentMethod = m.paymentMethod
                )
            )
        }

        // 2b. Fallback opening balance if customer has no transactions and no bills
        if (custBills.isEmpty() && custTxs.isEmpty() && cust != null && cust.outstandingBalance > 0.01) {
            val openingBal = cust.outstandingBalance
            totalManualDebits += openingBal
            val dateStr = cust.firstPurchaseAt?.take(10) ?: DateUtils.todayDate()
            vahiEvents.add(
                KhatavahiEntry(
                    id = "opening_${cust.id}",
                    date = dateStr,
                    timeMillis = 1L,
                    description = "मागील शिल्लक (Opening Balance)",
                    isCredit = false,
                    amount = openingBal,
                    runningBalance = 0.0,
                    billId = null,
                    billNumber = null,
                    paymentMethod = PaymentMethod.OTHER
                )
            )
        }

        // 3. Process payment transactions (separate genuine customer payments from foreign/orphan payments)
        val allPaymentTxs = custTxs.filter { it.type == UdhariTransactionType.PAYMENT }

        val knownStateBillGids = custBills.map { it.gid }.filter { it.isNotBlank() }.toSet()
        val knownStateBillIds = billStates.map { it.billId }.filter { it != 0L }.toSet()
        val knownStateBillNumbers = billStates.map { it.billNumber.trim().uppercase() }.filter { it.isNotBlank() }.toSet()

        val orphanTxs = mutableListOf<UdhariTransaction>()
        val validPaymentTxs = mutableListOf<UdhariTransaction>()

        for (p in allPaymentTxs) {
            val cleanNum = p.billNumber.trim().uppercase()
            val isGeneral = cleanNum.isBlank() ||
                            cleanNum == "DIRECT-PAY" ||
                            cleanNum.startsWith("MULTI-BILL") ||
                            cleanNum == "GENERAL" ||
                            cleanNum == "ADVANCE" ||
                            cleanNum == "KHATA-PAY" ||
                            p.note?.contains("DIRECT-PAY", ignoreCase = true) == true

            val hasBillRef = !isGeneral && (!p.billGid.isNullOrBlank() || p.billId != 0L || cleanNum.isNotBlank())
            val matchesKnownBill = (!p.billGid.isNullOrBlank() && knownStateBillGids.contains(p.billGid)) ||
                                   (p.billId != 0L && knownStateBillIds.contains(p.billId)) ||
                                   (cleanNum.isNotBlank() && knownStateBillNumbers.contains(cleanNum))

            if (hasBillRef && !matchesKnownBill) {
                // Orphan payment: names a specific bill, but that bill does NOT exist in this customer's profile!
                orphanTxs.add(p)
            } else {
                validPaymentTxs.add(p)
            }
        }

        // Add ONLY valid payment transactions to vahiEvents
        for (p in validPaymentTxs) {
            val payDesc = when {
                !p.note.isNullOrBlank() -> "जमा (${p.note})"
                p.billNumber.isNotBlank() -> "जमा (${p.billNumber} • ${p.paymentMethod.name})"
                else -> "जमा (${p.paymentMethod.name})"
            }
            vahiEvents.add(
                KhatavahiEntry(
                    id = if (p.gid.isNotBlank()) p.gid else "pay_${p.id}",
                    date = p.date,
                    timeMillis = p.createdAt,
                    description = payDesc,
                    isCredit = true,
                    amount = p.amount,
                    runningBalance = 0.0,
                    billId = if (p.billId != 0L) p.billId else null,
                    billNumber = p.billNumber.ifBlank { null },
                    paymentMethod = p.paymentMethod
                )
            )
        }

        // 4. Unallocated payments (valid payments that do NOT reference any bill)
        val unallocatedTxs = validPaymentTxs.filter {
            (it.billGid.isNullOrBlank() || !knownStateBillGids.contains(it.billGid)) &&
            !knownStateBillIds.contains(it.billId) &&
            (it.billNumber.isBlank() || !knownStateBillNumbers.contains(it.billNumber.trim().uppercase()))
        }
        var unallocatedPool = unallocatedTxs.sumOf { it.amount }

        // FIFO apply unallocated payments to oldest bills first
        for (st in billStates) {
            val pendingOnBill = st.netRemaining
            if (pendingOnBill > 0.001 && unallocatedPool > 0.001) {
                val toApply = minOf(pendingOnBill, unallocatedPool)
                st.fifoPayments += toApply
                unallocatedPool -= toApply
            }
        }

        // Any remaining unallocated payments reduce manual debits
        val manualPaid = minOf(totalManualDebits, unallocatedPool)
        val remainingManualDue = totalManualDebits - manualPaid
        unallocatedPool -= manualPaid

        // Final customer balance = sum of remaining bill outstandings + remaining manual due - unallocated advance
        val finalBillDueSum = billStates.sumOf { it.netRemaining }
        val customerBalance = finalBillDueSum + remainingManualDue - unallocatedPool

        // Build breakdowns
        for (st in billStates) {
            val remaining = st.netRemaining
            val isOverdue = remaining > 0.01 && st.dueDate != null && DateUtils.isDatePassed(st.dueDate)
            billBreakdowns.add(
                BillLedgerBreakdown(
                    billId = st.billId,
                    billNumber = st.billNumber,
                    billDate = st.billDate,
                    finalAmount = st.finalAmount,
                    checkoutPaid = st.checkoutPaid,
                    directPayments = st.directPayments,
                    fifoPayments = st.fifoPayments,
                    remainingOutstanding = remaining,
                    isOverdue = isOverdue,
                    dueDate = st.dueDate
                )
            )
        }

        // Sort events chronologically and compute running balance
        val sortedEvents = vahiEvents.sortedBy { it.timeMillis }
        var runningBal = 0.0
        val finalEvents = sortedEvents.map { ev ->
            if (ev.isCredit) {
                runningBal -= ev.amount
            } else {
                runningBal += ev.amount
            }
            ev.copy(runningBalance = runningBal)
        }

        // Lifetime metrics
        val totalPurchases = custBills.sumOf { it.finalAmount } + totalLegacyBillAmount + totalManualDebits
        val nonCheckoutPaymentTxs = validPaymentTxs.filter {
            it.id !in allCheckoutTxIds && (it.gid.isBlank() || it.gid !in allCheckoutTxGids)
        }
        val totalPaid = totalCheckoutPaid + nonCheckoutPaymentTxs.sumOf { it.amount }
        val pendingBillsCount = billBreakdowns.count { it.remainingOutstanding > 0.01 } + (if (remainingManualDue > 0.01) 1 else 0)

        val overdueBills = billBreakdowns.filter { it.isOverdue }
        val overdueAmount = overdueBills.sumOf { it.remainingOutstanding }
        val oldestDueDate = billBreakdowns.filter { it.remainingOutstanding > 0.01 }.mapNotNull { it.dueDate }.minOrNull()
        val isOverdue = overdueAmount > 0.01

        val lastPayment = allPaymentTxs.maxByOrNull { it.createdAt }

        val allOrphanTxs = orphanCreditTxs + orphanTxs
        val calculationBreakdown = CustomerCalculationBreakdown(
            totalBillAmount = custBills.sumOf { it.finalAmount } + totalLegacyBillAmount,
            checkoutPaidAmount = totalCheckoutPaid,
            directSettlementAmount = totalDirectBillPaid,
            manualDebitAmount = totalManualDebits,
            manualPaidAmount = manualPaid,
            unallocatedPaidAmount = unallocatedPool,
            orphanAmount = allOrphanTxs.sumOf { it.amount },
            orphanCount = allOrphanTxs.size,
            netDue = customerBalance
        )

        return CustomerLedgerResult(
            customerKey = customerKey,
            customer = cust,
            balance = customerBalance,
            totalPurchases = totalPurchases,
            totalPaid = totalPaid,
            billBreakdowns = billBreakdowns,
            pendingBillsCount = pendingBillsCount,
            oldestDueDate = oldestDueDate,
            isOverdue = isOverdue,
            overdueAmount = overdueAmount,
            lastPaymentDate = lastPayment?.date,
            lastPaymentAmount = lastPayment?.amount,
            events = finalEvents,
            orphanPayments = orphanTxs,
            orphanTransactions = allOrphanTxs,
            calculationBreakdown = calculationBreakdown
        )
    }

    /**
     * Computes the complete shop-wide Udhari summary and per-customer results.
     */
    fun computeShopLedger(
        allBills: List<Bill>,
        allTransactions: List<UdhariTransaction>,
        allCustomers: List<Customer>,
        todayDate: String = DateUtils.todayDate()
    ): ShopUdhariSummary {
        val nonVoidBills = allBills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.VOID }
        val voidedBillIds = allBills.filter { it.isVoided || it.paymentStatus == PaymentStatus.VOID }.map { it.id }.toSet()
        val voidedBillNumbers = allBills.filter { (it.isVoided || it.paymentStatus == PaymentStatus.VOID) && it.billNumber.isNotBlank() }
            .map { it.billNumber }.toSet()

        val validTxs = allTransactions.filter {
            !it.isReversed && it.status != "TRASH" &&
            !voidedBillIds.contains(it.billId) &&
            !(it.billNumber.isNotBlank() && voidedBillNumbers.contains(it.billNumber))
        }

        val keyResolver = buildCustomerKeyResolver(allCustomers, nonVoidBills, validTxs)

        val allKeys = mutableSetOf<String>()
        allCustomers.forEach { c ->
            keyResolver(c.gid, c.mobile, c.name, c.id)?.let { allKeys.add(it) }
        }
        nonVoidBills.forEach { b ->
            keyResolver(b.customerGid, b.customerMobile, b.customerName, b.customerId)?.let { allKeys.add(it) }
        }
        validTxs.forEach { tx ->
            keyResolver(tx.customerGid, tx.customerMobile, tx.customerName, tx.customerId)?.let { allKeys.add(it) }
        }

        val customerResults = mutableMapOf<String, CustomerLedgerResult>()
        for (key in allKeys) {
            customerResults[key] = computeCustomerLedger(key, nonVoidBills, validTxs, allCustomers)
        }

        val totalOutstanding = customerResults.values.filter { it.balance > 0.01 }.sumOf { it.balance }
        val pendingCustomersCount = customerResults.values.count { it.balance > 0.01 }
        val totalOverdue = customerResults.values.sumOf { it.overdueAmount }
        val totalPendingBills = customerResults.values.sumOf { it.pendingBillsCount }

        // Today collections: PAYMENT transactions created today + checkout partial payments on credit bills created today
        val todayPaymentTxs = validTxs.filter {
            it.type == UdhariTransactionType.PAYMENT &&
            (DateUtils.isToday(it.date) || it.date.startsWith(todayDate))
        }

        val todayCollections = todayPaymentTxs.sumOf { it.amount }
        val todayCashCollections = todayPaymentTxs.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
        val todayUpiCollections = todayPaymentTxs.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.amount }

        val currentMonthPrefix = todayDate.take(7)
        val monthPaymentTxs = validTxs.filter {
            it.type == UdhariTransactionType.PAYMENT &&
            (DateUtils.isThisMonth(it.date) || it.date.startsWith(currentMonthPrefix))
        }
        val thisMonthCollections = monthPaymentTxs.sumOf { it.amount }

        return ShopUdhariSummary(
            totalOutstanding = totalOutstanding,
            pendingCustomersCount = pendingCustomersCount,
            todayCollections = todayCollections,
            todayCashCollections = todayCashCollections,
            todayUpiCollections = todayUpiCollections,
            thisMonthCollections = thisMonthCollections,
            overdueAmount = totalOverdue,
            pendingBillsCount = totalPendingBills,
            customerResults = customerResults,
            allOrphanPayments = customerResults.values.flatMap { it.orphanPayments },
            allOrphanTransactions = customerResults.values.flatMap { it.orphanTransactions }
        )
    }
}
