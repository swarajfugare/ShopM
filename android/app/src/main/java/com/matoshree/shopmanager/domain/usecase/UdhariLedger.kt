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
    val events: List<KhatavahiEntry>
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
    val customerResults: Map<String, CustomerLedgerResult>
)

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

    fun resolveCustomerKey(mobile: String?, name: String?, localId: Long?): String? {
        val clean = PhoneUtils.clean10DigitMobileInput(mobile ?: "")
        return when {
            clean.length == 10 -> "mob_$clean"
            localId != null && localId > 0L -> "cust_$localId"
            !name.isNullOrBlank() -> "name_${name.trim().lowercase()}"
            else -> null
        }
    }

    /**
     * Builds a unified resolver that links customer entities, bills, and transactions
     * so that records sharing mobile, name, or customer ID resolve accurately to the canonical key,
     * while guaranteeing that distinct 10-digit mobile numbers are NEVER merged,
     * and customers without mobile are NEVER conflated by name alone if their IDs differ.
     */
    fun buildCustomerKeyResolver(
        customers: List<Customer> = emptyList(),
        bills: List<Bill> = emptyList(),
        transactions: List<UdhariTransaction> = emptyList()
    ): (mobile: String?, name: String?, localId: Long?) -> String? {
        val aliasToCanonical = mutableMapOf<String, String>()

        // Seed from known customer entities (only map name if it is unique among customers)
        val nameCount = customers.map { it.name.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

        for (c in customers) {
            val cleanMob = PhoneUtils.clean10DigitMobileInput(c.mobile)
            val cleanName = c.name.trim().lowercase()
            if (cleanMob.length == 10) {
                val canonical = "mob_$cleanMob"
                if (cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1) {
                    aliasToCanonical["name_$cleanName"] = canonical
                }
                if (c.id > 0L) {
                    aliasToCanonical["id_${c.id}"] = canonical
                    aliasToCanonical["cust_${c.id}"] = canonical
                }
            } else if (cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1) {
                val canonical = "name_$cleanName"
                aliasToCanonical["name_$cleanName"] = canonical
                if (c.id > 0L) {
                    aliasToCanonical["id_${c.id}"] = canonical
                    aliasToCanonical["cust_${c.id}"] = canonical
                }
            } else if (c.id > 0L) {
                val canonical = "cust_${c.id}"
                aliasToCanonical["id_${c.id}"] = canonical
                aliasToCanonical["cust_${c.id}"] = canonical
            }
        }

        return { mobile, name, localId ->
            val cleanMob = PhoneUtils.clean10DigitMobileInput(mobile ?: "")
            if (cleanMob.length == 10) {
                "mob_$cleanMob"
            } else {
                val cleanName = name?.trim()?.lowercase() ?: ""
                (if (localId != null && localId > 0L) (aliasToCanonical["id_$localId"] ?: aliasToCanonical["cust_$localId"]) else null)
                    ?: (if (cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1) aliasToCanonical["name_$cleanName"] else null)
                    ?: resolveCustomerKey(mobile, name, localId)
            }
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

        val cust = allCustomers.find { keyResolver(it.mobile, it.name, it.id) == customerKey }

        val rawCustBills = nonVoidBills.filter { b ->
            keyResolver(b.customerMobile, b.customerName, b.customerId) == customerKey
        }.sortedWith(compareBy({ it.billDate }, { it.id }))

        // Deduplicate bills within customer (e.g. local room bill + cloud bill with same billNumber or transactionUuid)
        val seenBillNumbers = mutableSetOf<String>()
        val seenBillUuids = mutableSetOf<String>()
        val custBills = mutableListOf<Bill>()

        for (b in rawCustBills) {
            val cleanNum = b.billNumber.trim().uppercase()
            val uuid = b.transactionUuid.trim()
            if (uuid.isNotBlank() && seenBillUuids.contains(uuid)) continue
            if (cleanNum.isNotBlank() && seenBillNumbers.contains(cleanNum)) continue

            if (uuid.isNotBlank()) seenBillUuids.add(uuid)
            if (cleanNum.isNotBlank()) seenBillNumbers.add(cleanNum)
            custBills.add(b)
        }

        val rawCustTxs = validTxs.filter { tx ->
            keyResolver(tx.customerMobile, tx.customerName, tx.customerId) == customerKey
        }

        // Deduplicate transactions within customer (prevent duplicate payment/credit events from sync retries)
        val seenTxIds = mutableSetOf<String>()
        val seenTxUuids = mutableSetOf<String>()
        val custTxs = mutableListOf<UdhariTransaction>()

        for (tx in rawCustTxs) {
            if (tx.id.isNotBlank() && seenTxIds.contains(tx.id)) continue
            if (tx.transactionUuid.isNotBlank() && seenTxUuids.contains(tx.transactionUuid)) continue

            // Semantic duplicate check: same type, amount, bill, date, and close timestamp (< 2 min)
            val isSemanticDup = custTxs.any { existing ->
                existing.type == tx.type &&
                kotlin.math.abs(existing.amount - tx.amount) < 0.001 &&
                ((existing.billId != 0L && existing.billId == tx.billId) ||
                 (existing.billNumber.isNotBlank() && existing.billNumber.trim().equals(tx.billNumber.trim(), ignoreCase = true))) &&
                existing.date == tx.date &&
                kotlin.math.abs(existing.createdAt - tx.createdAt) < 120_000L
            }
            if (isSemanticDup) continue

            if (tx.id.isNotBlank()) seenTxIds.add(tx.id)
            if (tx.transactionUuid.isNotBlank()) seenTxUuids.add(tx.transactionUuid)
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
                    id = "bill_${b.id}",
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
                it.type == UdhariTransactionType.PAYMENT &&
                ((it.billId != 0L && it.billId == b.id) || (it.billNumber.isNotBlank() && it.billNumber == b.billNumber))
            }

            val hasCheckoutTx = directTxsForBill.any {
                it.note?.contains("checkout", ignoreCase = true) == true ||
                it.note?.contains("initial", ignoreCase = true) == true
            }

            val checkoutPaid = when {
                hasCheckoutTx -> 0.0 // Checkout payment is already inside directTxsForBill
                b.paymentStatus == PaymentStatus.PAID && directTxsForBill.isEmpty() -> b.finalAmount
                b.paidAmount > 0.0 -> b.paidAmount
                else -> 0.0
            }

            if (checkoutPaid > 0.01) {
                totalCheckoutPaid += checkoutPaid
                vahiEvents.add(
                    KhatavahiEntry(
                        id = "checkout_pay_${b.id}",
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
            }

            val directPaid = directTxsForBill.sumOf { it.amount }
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
        val knownCustBillIds = custBills.map { it.id }.filter { it != 0L }.toSet()
        val knownCustBillNumbers = custBills.map { it.billNumber.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val knownCustBillUuids = custBills.map { it.transactionUuid.trim() }.filter { it.isNotBlank() }.toSet()

        val unlinkedCreditTxs = custTxs.filter { tx ->
            val cleanNum = tx.billNumber.trim().uppercase()
            val uuid = tx.transactionUuid.trim()
            val note = tx.note?.trim()?.uppercase() ?: ""

            val matchesKnownBill =
                (tx.billId != 0L && knownCustBillIds.contains(tx.billId)) ||
                (cleanNum.isNotBlank() && knownCustBillNumbers.contains(cleanNum)) ||
                (uuid.isNotBlank() && knownCustBillUuids.contains(uuid)) ||
                custBills.any { b ->
                    (cleanNum.isNotBlank() && b.billNumber.trim().equals(cleanNum, ignoreCase = true)) ||
                    (note.isNotBlank() && b.billNumber.isNotBlank() && note.contains(b.billNumber.trim().uppercase())) ||
                    (tx.billId == 0L && cleanNum.isBlank() && kotlin.math.abs(b.finalAmount - tx.amount) < 0.001 && b.billDate.startsWith(tx.date))
                }

            tx.type == UdhariTransactionType.PURCHASE_CREDIT && !matchesKnownBill
        }

        val unlinkedBillTxs = unlinkedCreditTxs.filter { it.billId != 0L || it.billNumber.isNotBlank() }
            .groupBy { if (it.billId != 0L) "id_${it.billId}" else "num_${it.billNumber}" }

        var totalUnlinkedBillAmount = 0.0
        for ((_, group) in unlinkedBillTxs) {
            val firstTx = group.first()
            val totalBillAmount = group.sumOf { it.amount }
            totalUnlinkedBillAmount += totalBillAmount
            val billId = firstTx.billId
            val billNumber = firstTx.billNumber

            val directTxsForBill = custTxs.filter {
                it.type == UdhariTransactionType.PAYMENT &&
                ((billId != 0L && it.billId == billId) || (billNumber.isNotBlank() && it.billNumber == billNumber))
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
        val manualDebits = unlinkedCreditTxs.filter { it.billId == 0L && it.billNumber.isBlank() }
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

        // 3. Add all payment transactions to vahiEvents
        val allPaymentTxs = custTxs.filter { it.type == UdhariTransactionType.PAYMENT }
        for (p in allPaymentTxs) {
            val payDesc = when {
                !p.note.isNullOrBlank() -> "जमा (${p.note})"
                p.billNumber.isNotBlank() -> "जमा (${p.billNumber} • ${p.paymentMethod.name})"
                else -> "जमा (${p.paymentMethod.name})"
            }
            vahiEvents.add(
                KhatavahiEntry(
                    id = "pay_${p.id}",
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

        // 4. Unallocated payments (payments not directly allocated to any known bill)
        val knownStateBillIds = billStates.map { it.billId }.filter { it != 0L }.toSet()
        val knownStateBillNumbers = billStates.map { it.billNumber }.filter { it.isNotBlank() }.toSet()

        val unallocatedTxs = allPaymentTxs.filter {
            !knownStateBillIds.contains(it.billId) &&
            (it.billNumber.isBlank() || !knownStateBillNumbers.contains(it.billNumber))
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
        val finalBillDueSum = billStates.sumOf { it.netRemaining.coerceAtLeast(0.0) }
        val customerBalance = finalBillDueSum + remainingManualDue - unallocatedPool

        // Build breakdowns
        for (st in billStates) {
            val remaining = st.netRemaining.coerceAtLeast(0.0)
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
        val totalPurchases = custBills.sumOf { it.finalAmount } + totalUnlinkedBillAmount + totalManualDebits
        val totalPaid = totalCheckoutPaid + allPaymentTxs.sumOf { it.amount }
        val pendingBillsCount = billBreakdowns.count { it.remainingOutstanding > 0.01 } + (if (remainingManualDue > 0.01) 1 else 0)

        val overdueBills = billBreakdowns.filter { it.isOverdue }
        val overdueAmount = overdueBills.sumOf { it.remainingOutstanding }
        val oldestDueDate = billBreakdowns.filter { it.remainingOutstanding > 0.01 }.mapNotNull { it.dueDate }.minOrNull()
        val isOverdue = overdueAmount > 0.01

        val lastPayment = allPaymentTxs.maxByOrNull { it.createdAt }

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
            events = finalEvents
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
            keyResolver(c.mobile, c.name, c.id)?.let { allKeys.add(it) }
        }
        nonVoidBills.forEach { b ->
            keyResolver(b.customerMobile, b.customerName, b.customerId)?.let { allKeys.add(it) }
        }
        validTxs.forEach { tx ->
            keyResolver(tx.customerMobile, tx.customerName, tx.customerId)?.let { allKeys.add(it) }
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
            customerResults = customerResults
        )
    }
}
