package com.matoshree.shopmanager.ui.screens.customer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.firebase.FirestoreSettingsRepository
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.UdhariLedger
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeDatePickerField
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.OnePlusAlphabetScroller
import com.matoshree.shopmanager.ui.components.PaymentMethodPill
import com.matoshree.shopmanager.ui.components.PaymentReceiptDialog
import com.matoshree.shopmanager.ui.components.StatusBadge
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.AlphabetUtils
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class CustomerPaymentRecord(
    val id: String,
    val title: String,
    val subtitle: String,
    val date: String,
    val timeMillis: Long,
    val amount: Double,
    val paymentMethod: PaymentMethod,
    val billId: Long? = null,
    val billNumber: String? = null,
    val isUdhariSettlement: Boolean = false,
    val isReversed: Boolean = false,
    val udhariTx: UdhariTransaction? = null
)

class CustomerViewModel(
    private val customerRepository: CustomerRepository,
    private val billRepository: BillRepository,
    private val udhariRepository: UdhariRepository = UdhariRepository(),
    private val settingsRepository: FirestoreSettingsRepository = FirestoreSettingsRepository()
) : ViewModel() {
    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers

    val searchQuery = MutableStateFlow("")

    // Profile state
    val selectedCustomerProfile = MutableStateFlow<Customer?>(null)
    val customerBills = MutableStateFlow<List<Bill>>(emptyList())
    val customerTransactions = MutableStateFlow<List<UdhariTransaction>>(emptyList())
    val customerLedger = MutableStateFlow<List<UdhariLedgerEntry>>(emptyList())
    val customerAllPayments = MutableStateFlow<List<CustomerPaymentRecord>>(emptyList())
    val customerKhatavahi = MutableStateFlow<List<KhatavahiEntry>>(emptyList())
    val customerOutstandingDue = MutableStateFlow<Double>(0.0)
    val shopSettings = MutableStateFlow(PrintShareHelper.ShopSettingsSnapshot())
    private val syncedCustomerIds = mutableSetOf<Long>()

    private val errorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("CustomerViewModel", "CustomerViewModel error caught safely: ${throwable.message}", throwable)
    }

    init {
        observeCustomers()
        observeSettings()
    }

    private fun observeCustomers() {
        viewModelScope.launch(errorHandler) {
            customerRepository.getAllCustomers()
                .catch { e -> android.util.Log.e("CustomerViewModel", "observeCustomers flow error: ${e.message}", e) }
                .collectLatest {
                    _customers.value = it
                }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch(errorHandler) {
            settingsRepository.observeSettings()
                .catch { e -> android.util.Log.e("CustomerViewModel", "observeSettings flow error: ${e.message}", e) }
                .collectLatest {
                    shopSettings.value = it
                }
        }
    }

    private var profileJob: kotlinx.coroutines.Job? = null

    fun loadCustomerProfile(customerId: Long) {
        val cust = _customers.value.find { it.id == customerId }
        val gid = cust?.gid?.ifBlank { null } ?: "id_$customerId"
        loadCustomerProfile(gid)
    }

    fun loadCustomerProfile(customerGid: String) {
        val numericIdFallback = if (customerGid.startsWith("id_")) customerGid.removePrefix("id_").toLongOrNull() else null
        val cust = _customers.value.find {
            it.gid == customerGid || (numericIdFallback != null && it.id == numericIdFallback)
        }
        val targetMobile = cust?.mobile?.trim() ?: ""

        val isDifferentCustomer = selectedCustomerProfile.value != null &&
            selectedCustomerProfile.value?.gid != customerGid &&
            (numericIdFallback == null || selectedCustomerProfile.value?.id != numericIdFallback) &&
            (selectedCustomerProfile.value?.mobile.isNullOrBlank() || targetMobile.isBlank() || selectedCustomerProfile.value?.mobile != targetMobile)

        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            if (selectedCustomerProfile.value == null || (selectedCustomerProfile.value?.gid != customerGid && (numericIdFallback == null || selectedCustomerProfile.value?.id != numericIdFallback))) {
                if (cust != null) selectedCustomerProfile.value = cust
            }
            if (isDifferentCustomer) {
                // Reset state only when switching to an entirely DIFFERENT customer to avoid screen blinking
                customerBills.value = emptyList()
                customerTransactions.value = emptyList()
                customerLedger.value = emptyList()
                customerAllPayments.value = emptyList()
                customerKhatavahi.value = emptyList()
                customerOutstandingDue.value = cust?.outstandingBalance ?: 0.0
            }

            combine(
                billRepository.getAllBills(limit = 100000),
                udhariRepository.getAllTransactions()
            ) { allBills, txs ->
                val allCustomers = _customers.value
                val cleanTargetPhone = PhoneUtils.clean10DigitMobileInput(targetMobile)
                val currentCust = allCustomers.find { 
                    it.gid == customerGid ||
                    (numericIdFallback != null && it.id == numericIdFallback) ||
                    (cleanTargetPhone.length == 10 && PhoneUtils.clean10DigitMobileInput(it.mobile) == cleanTargetPhone)
                } ?: cust
                val keyResolver = UdhariLedger.buildCustomerKeyResolver(allCustomers, allBills, txs)
                val targetKey = keyResolver(currentCust?.gid ?: customerGid, currentCust?.mobile ?: targetMobile, currentCust?.name, currentCust?.id ?: numericIdFallback) ?: (currentCust?.gid ?: customerGid)

                val ledgerResult = UdhariLedger.computeCustomerLedger(
                    customerKey = targetKey,
                    allBills = allBills,
                    allTransactions = txs,
                    allCustomers = allCustomers
                )

                val customerSpecificBills = allBills.filter { b ->
                    !b.isVoided && b.paymentStatus != PaymentStatus.VOID &&
                    keyResolver(b.customerGid, b.customerMobile, b.customerName, b.customerId) == targetKey
                }.distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }

                val validTxs = txs.filter { tx ->
                    !tx.isReversed && tx.status != "TRASH" &&
                    keyResolver(tx.customerGid, tx.customerMobile, tx.customerName, tx.customerId) == targetKey
                }.distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else "${it.id}_${it.billId}" }

                if (customerBills.value != customerSpecificBills) {
                    customerBills.value = customerSpecificBills
                }
                if (customerTransactions.value != validTxs) {
                    customerTransactions.value = validTxs
                }

                // 2. Tab 2: Created Udhari Records (Authoritative per-bill breakdown with remaining outstanding + verified manual debits)
                val createdUdhariEntries = mutableListOf<UdhariLedgerEntry>()
                for (bDown in ledgerResult.billBreakdowns.filter { it.remainingOutstanding > 0.01 }) {
                    val matchingBill = customerSpecificBills.find { it.id == bDown.billId || (it.billNumber.isNotBlank() && it.billNumber == bDown.billNumber) }
                    val billSubtitle = "एकूण: ₹${String.format(java.util.Locale.US, "%.0f", bDown.finalAmount)} | जमा: ₹${String.format(java.util.Locale.US, "%.0f", bDown.checkoutPaid + bDown.directPayments + bDown.fifoPayments)} | बाकी: ₹${String.format(java.util.Locale.US, "%.0f", bDown.remainingOutstanding)}"
                    createdUdhariEntries.add(
                        UdhariLedgerEntry(
                            id = "bill_${bDown.billId}_${bDown.billNumber}",
                            date = bDown.billDate,
                            timeMillis = com.matoshree.shopmanager.utils.DateUtils.parseDateToMillis(bDown.billDate).takeIf { it > 0 } ?: (bDown.billId * 1000L),
                            title = "उधारी बिल #${bDown.billNumber}",
                            subtitle = billSubtitle,
                            billNumber = bDown.billNumber,
                            type = UdhariTransactionType.PURCHASE_CREDIT,
                            debitAmount = bDown.remainingOutstanding,
                            creditAmount = 0.0,
                            runningBalance = 0.0,
                            paymentMethod = matchingBill?.paymentMethod,
                            note = matchingBill?.note,
                            transaction = null
                        )
                    )
                }

                // Add only genuine manual debits from authoritative ledger events (never duplicate credit transactions already bound to bills)
                for (ev in ledgerResult.events.filter { !it.isCredit && it.billId == null && it.billNumber.isNullOrBlank() }) {
                    val matchingTx = validTxs.find { it.id == ev.id || (kotlin.math.abs(it.amount - ev.amount) < 0.001 && it.createdAt == ev.timeMillis) }
                    createdUdhariEntries.add(
                        UdhariLedgerEntry(
                            id = "manual_${ev.id}_${ev.timeMillis}",
                            date = ev.date,
                            timeMillis = ev.timeMillis,
                            title = "मॅन्युअल उधारी नोंद (+)",
                            subtitle = ev.description,
                            billNumber = null,
                            type = UdhariTransactionType.PURCHASE_CREDIT,
                            debitAmount = ev.amount,
                            creditAmount = 0.0,
                            runningBalance = ev.runningBalance,
                            paymentMethod = ev.paymentMethod,
                            note = ev.description,
                            isReversed = false,
                            transaction = matchingTx
                        )
                    )
                }

                val sortedLedger = createdUdhariEntries.sortedByDescending { it.timeMillis }
                if (customerLedger.value != sortedLedger) {
                    customerLedger.value = sortedLedger
                }

                // 3. Tab 3: Payments History (Real customer payments)
                val allPaymentsList = mutableListOf<CustomerPaymentRecord>()
                for (ev in ledgerResult.events.filter { it.isCredit }) {
                    allPaymentsList.add(
                        CustomerPaymentRecord(
                            id = ev.id,
                            title = ev.description,
                            subtitle = if (!ev.billNumber.isNullOrBlank()) "बिल: ${ev.billNumber}" else "जमा रक्कम",
                            date = ev.date,
                            timeMillis = ev.timeMillis,
                            amount = ev.amount,
                            paymentMethod = ev.paymentMethod ?: PaymentMethod.CASH,
                            billId = ev.billId,
                            billNumber = ev.billNumber,
                            isUdhariSettlement = true,
                            isReversed = false
                        )
                    )
                }

                val sortedPayments = allPaymentsList.sortedByDescending { it.timeMillis }
                if (customerAllPayments.value != sortedPayments) {
                    customerAllPayments.value = sortedPayments
                }

                // 4. Tab 4: Khatavahi (Exact chronological events with verified running balance)
                if (customerKhatavahi.value != ledgerResult.events) {
                    customerKhatavahi.value = ledgerResult.events
                }

                val finalDue = ledgerResult.balance
                if (kotlin.math.abs(customerOutstandingDue.value - finalDue) > 0.001) {
                    customerOutstandingDue.value = finalDue
                }
                val newProfile = currentCust?.copy(outstandingBalance = finalDue)
                if (newProfile != null && (selectedCustomerProfile.value?.outstandingBalance != finalDue || selectedCustomerProfile.value?.id != newProfile.id || selectedCustomerProfile.value?.name != newProfile.name)) {
                    selectedCustomerProfile.value = newProfile
                }
            }.collect()
        }
    }

    val selectedCustomerGids = MutableStateFlow<Set<String>>(emptySet())
    val selectedCustomerIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedListTag = MutableStateFlow<String>("All")
    val userCreatedLists = MutableStateFlow<Set<String>>(setOf("VIP", "Regular", "Kondharki"))

    fun createNewList(name: String) {
        val clean = name.trim()
        if (clean.isNotBlank()) {
            val cur = userCreatedLists.value.toMutableSet()
            cur.add(clean)
            userCreatedLists.value = cur
        }
    }

    fun toggleCustomerSelection(customerGid: String) {
        val currentGids = selectedCustomerGids.value.toMutableSet()
        if (currentGids.contains(customerGid)) currentGids.remove(customerGid) else currentGids.add(customerGid)
        selectedCustomerGids.value = currentGids

        val all = _customers.value
        selectedCustomerIds.value = all.filter { currentGids.contains(it.gid) || currentGids.contains("id_${it.id}") }.map { it.id }.toSet()
    }

    fun toggleCustomerSelection(customerId: Long) {
        val cust = _customers.value.find { it.id == customerId }
        val gid = cust?.gid?.ifBlank { null } ?: "id_$customerId"
        toggleCustomerSelection(gid)
    }

    fun selectAllCustomers(select: Boolean, filtered: List<Customer>) {
        if (select) {
            selectedCustomerGids.value = filtered.map { it.gid.ifBlank { "id_${it.id}" } }.toSet()
            selectedCustomerIds.value = filtered.map { it.id }.toSet()
        } else {
            selectedCustomerGids.value = emptySet()
            selectedCustomerIds.value = emptySet()
        }
    }

    suspend fun addTagToSelectedCustomers(tag: String) {
        val selected = selectedCustomerGids.value
        val all = _customers.value
        val curLists = userCreatedLists.value.toMutableSet()
        curLists.add(tag)
        userCreatedLists.value = curLists

        selected.forEach { gid ->
            val cust = all.find { it.gid == gid || "id_${it.id}" == gid }
            if (cust != null) {
                val currentTags = cust.listTags.toMutableSet()
                currentTags.add(tag)
                if (cust.gid.isNotBlank()) customerRepository.updateCustomerTags(cust.gid, currentTags.toList())
                if (cust.id > 0) customerRepository.updateCustomerTags(cust.id, currentTags.toList())
            }
        }
        selectedCustomerGids.value = emptySet()
        selectedCustomerIds.value = emptySet()
    }

    suspend fun removeTagFromCustomer(customerGid: String, tag: String) {
        val cust = _customers.value.find { it.gid == customerGid || "id_${it.id}" == customerGid }
        if (cust != null) {
            val updated = cust.listTags.filter { it != tag }
            if (cust.gid.isNotBlank()) customerRepository.updateCustomerTags(cust.gid, updated)
            if (cust.id > 0) customerRepository.updateCustomerTags(cust.id, updated)
        }
    }

    suspend fun removeTagFromCustomer(customerId: Long, tag: String) {
        val cust = _customers.value.find { it.id == customerId }
        val gid = cust?.gid?.ifBlank { null } ?: "id_$customerId"
        removeTagFromCustomer(gid, tag)
    }

    suspend fun removeTagFromSelectedCustomers(tag: String) {
        val selected = selectedCustomerGids.value
        val all = _customers.value
        selected.forEach { gid ->
            val cust = all.find { it.gid == gid || "id_${it.id}" == gid }
            if (cust != null) {
                val updated = cust.listTags.filter { it != tag }
                if (cust.gid.isNotBlank()) customerRepository.updateCustomerTags(cust.gid, updated)
                if (cust.id > 0) customerRepository.updateCustomerTags(cust.id, updated)
            }
        }
        selectedCustomerGids.value = emptySet()
        selectedCustomerIds.value = emptySet()
    }

    suspend fun deleteListTag(tag: String) {
        val all = _customers.value
        all.forEach { cust ->
            if (cust.listTags.contains(tag)) {
                val updated = cust.listTags.filter { it != tag }
                if (cust.gid.isNotBlank()) customerRepository.updateCustomerTags(cust.gid, updated)
                if (cust.id > 0) customerRepository.updateCustomerTags(cust.id, updated)
            }
        }
        val cur = userCreatedLists.value.toMutableSet()
        cur.remove(tag)
        userCreatedLists.value = cur
        if (selectedListTag.value == tag) {
            selectedListTag.value = "All"
        }
    }

    suspend fun deleteSelectedCustomers(): Pair<Int, Int> {
        val selected = selectedCustomerGids.value
        val all = _customers.value
        var deleted = 0
        var skipped = 0
        selected.forEach { gid ->
            val cust = all.find { it.gid == gid || "id_${it.id}" == gid }
            if (cust != null) {
                if (cust.outstandingBalance > 0.01) {
                    skipped++
                } else {
                    if (cust.gid.isNotBlank()) {
                        customerRepository.deleteOrArchiveCustomer(cust.gid, cust.mobile, cust.name)
                    } else {
                        customerRepository.deleteOrArchiveCustomer(cust.id, cust.mobile, cust.name)
                    }
                    deleted++
                }
            }
        }
        selectedCustomerGids.value = emptySet()
        selectedCustomerIds.value = emptySet()
        return Pair(deleted, skipped)
    }

    suspend fun recordManualUdhari(
        customer: Customer,
        amount: Double,
        note: String?,
        date: String?,
        dueDate: String?
    ): Result<UdhariTransaction> {
        val res = udhariRepository.recordManualDebit(customer, amount, note, date, dueDate)
        if (res.isSuccess) {
            val newBal = customerOutstandingDue.value + amount
            customerOutstandingDue.value = newBal
            selectedCustomerProfile.value = customer.copy(outstandingBalance = newBal)
            if (customer.gid.isNotBlank()) loadCustomerProfile(customer.gid) else loadCustomerProfile(customer.id)
        }
        return res
    }

    suspend fun findCustomerByMobile(mobile: String): Customer? {
        return customerRepository.findCustomerByMobile(mobile)
    }

    suspend fun saveCustomer(
        name: String,
        mobile: String,
        village: String,
        email: String? = null,
        existingId: Long = 0,
        allowOverwriteName: Boolean = false
    ): Result<Long> {
        val cleanName = name.trim()
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        val cleanVillage = village.trim().ifBlank { "कोंढारकी" }

        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("कृपया कस्टमरचे नाव टाका"))
        }
        if (cleanMobile.isNotBlank() && cleanMobile.length != 10) {
            return Result.failure(IllegalArgumentException("कृपया १० अंकी मोबाईल नंबर टाका किंवा रिकामा सोडा"))
        }

        // Duplicate check
        var targetId = existingId
        if (cleanMobile.isNotBlank()) {
            val existing = findCustomerByMobile(cleanMobile)
            if (existing != null && existing.id != existingId) {
                if (existing.name.trim().equals(cleanName, ignoreCase = true)) {
                    targetId = existing.id
                } else if (!allowOverwriteName) {
                    return Result.failure(IllegalStateException("EXISTS_DIFFERENT_NAME:${existing.id}:${existing.name}"))
                } else {
                    targetId = existing.id
                }
            } else if (existing == null) {
                // Check if customer was deleted or archived - automatically revive them with clean slate!
                val anyExisting = try {
                    customerRepository.findAnyCustomerByMobile(cleanMobile)
                } catch (e: Exception) {
                    null
                }
                if (anyExisting != null && (!anyExisting.isActive || anyExisting.id != existingId)) {
                    targetId = anyExisting.id
                    try {
                        customerRepository.purgeCustomerHistory(targetId, cleanMobile, cleanName)
                    } catch (e: Exception) {
                        android.util.Log.w("CustomerVM", "Purge history on revive note: ${e.message}")
                    }
                }
            }
        }

        val custToSave = Customer(
            id = targetId,
            shopId = 1,
            name = cleanName,
            mobile = cleanMobile,
            email = null,
            address = cleanVillage,
            isActive = true,
            outstandingBalance = 0.0,
            lifetimeSpend = 0.0,
            totalBills = 0
        )

        return try {
            val id = customerRepository.saveCustomer(custToSave)
            val updatedCust = custToSave.copy(id = id)
            val targetKey = updatedCust.canonicalKey()
            _customers.value = (_customers.value.filterNot { it.canonicalKey() == targetKey } + updatedCust).sortedBy { it.name }
            if (targetId > 0 && selectedCustomerProfile.value?.id == targetId) {
                selectedCustomerProfile.value = updatedCust
                customerBills.value = emptyList()
                customerTransactions.value = emptyList()
                customerAllPayments.value = emptyList()
                customerKhatavahi.value = emptyList()
                customerOutstandingDue.value = 0.0
            }
            Result.success(id)
        } catch (e: Exception) {
            android.util.Log.e("CustomerVM", "Error saving customer: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun recordCustomerPayment(
        customer: Customer,
        amount: Double,
        paymentMethod: PaymentMethod,
        note: String? = null
    ): Result<UdhariTransaction> {
        val bills = customerBills.value.filter { !it.isVoided && it.paymentStatus != PaymentStatus.PAID }.sortedBy { it.billDate }
        val targetBill = bills.firstOrNull()
        val safeAmount = if (customerOutstandingDue.value > 0.0) amount.coerceIn(0.01, customerOutstandingDue.value) else amount.coerceAtLeast(0.01)

        val billNumberSummary = when {
            bills.size > 1 -> "MULTI-BILL (${bills.size} बिले)"
            targetBill != null -> targetBill.billNumber
            else -> "DIRECT-PAY"
        }

        val transaction = UdhariTransaction(
            customerId = customer.id,
            customerGid = customer.gid,
            customerName = customer.name,
            customerMobile = customer.mobile,
            customerVillage = customer.address,
            billId = if (bills.size == 1) (targetBill?.id ?: 0L) else 0L,
            billGid = if (bills.size == 1) targetBill?.gid else null,
            billNumber = billNumberSummary,
            type = UdhariTransactionType.PAYMENT,
            amount = safeAmount,
            paymentMethod = paymentMethod,
            previousOutstanding = customerOutstandingDue.value,
            remainingOutstanding = (customerOutstandingDue.value - safeAmount).coerceAtLeast(0.0),
            transactionUuid = UUID.randomUUID().toString(),
            date = DateUtils.todayDate(),
            note = note,
            createdAt = System.currentTimeMillis()
        )

        val result = udhariRepository.recordPayment(transaction)
        return if (result.isSuccess) {
            val newBalance = (customerOutstandingDue.value - safeAmount).coerceAtLeast(0.0)
            customerOutstandingDue.value = newBalance
            try {
                customerRepository.saveCustomer(customer.copy(outstandingBalance = newBalance))
            } catch (_: Exception) { }
            val targetKey = customer.canonicalKey()
            _customers.value = _customers.value.map { if (it.canonicalKey() == targetKey) it.copy(outstandingBalance = newBalance) else it }
            if (customer.gid.isNotBlank()) loadCustomerProfile(customer.gid) else loadCustomerProfile(customer.id)
            Result.success(transaction)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Payment recording failed"))
        }
    }

    suspend fun reversePayment(transactionId: String, reason: String): Result<Unit> {
        val targetTx = customerTransactions.value.find { it.id == transactionId }
            ?: customerAllPayments.value.find { it.udhariTx?.id == transactionId }?.udhariTx
        val txAmount = targetTx?.amount ?: 0.0

        // Optimistically remove from state immediately so user sees it removed with zero delay
        customerAllPayments.value = customerAllPayments.value.filter { it.udhariTx?.id != transactionId && it.id != transactionId && it.id != "udhari_pay_$transactionId" }
        customerTransactions.value = customerTransactions.value.filter { it.id != transactionId }
        customerKhatavahi.value = customerKhatavahi.value.filter { it.id != transactionId }
        if (txAmount > 0.0) {
            val updatedDue = customerOutstandingDue.value + txAmount
            customerOutstandingDue.value = updatedDue
            selectedCustomerProfile.value = selectedCustomerProfile.value?.copy(outstandingBalance = updatedDue)
        }

        val res = udhariRepository.reversePayment(transactionId, reason)
        selectedCustomerProfile.value?.id?.let { loadCustomerProfile(it) }
        return res
    }

    suspend fun deleteOrReverseManualUdhari(transactionId: String, reason: String = "Deleted by user"): Result<Unit> {
        customerLedger.value = customerLedger.value.filter { it.id != transactionId && it.transaction?.id != transactionId }
        customerTransactions.value = customerTransactions.value.filter { it.id != transactionId }
        customerKhatavahi.value = customerKhatavahi.value.filter { it.id != transactionId }
        val res = udhariRepository.deleteOrReverseManualUdhari(transactionId, reason)
        selectedCustomerProfile.value?.id?.let { loadCustomerProfile(it) }
        return res
    }

    suspend fun updateUdhariDueDate(transactionId: String, newDueDate: String?): Result<Unit> {
        val res = udhariRepository.updateUdhariDueDate(transactionId, newDueDate)
        selectedCustomerProfile.value?.id?.let { loadCustomerProfile(it) }
        return res
    }

    suspend fun setCustomerCreditLimit(customerId: Long, limit: Double?): Result<Unit> {
        val res = udhariRepository.setCustomerCreditLimit(customerId, limit)
        loadCustomerProfile(customerId)
        return res
    }

    fun deleteOrArchiveCustomer(customerId: Long, hintMobile: String = "", hintName: String = "", onComplete: (String) -> Unit) {
        viewModelScope.launch {
            if (customerOutstandingDue.value > 0.01) {
                onComplete("Cannot delete customer with active outstanding balance of ${CurrencyFormatter.format(customerOutstandingDue.value)}.")
                return@launch
            }
            val profile = selectedCustomerProfile.value
            val mob = hintMobile.ifBlank { profile?.mobile ?: "" }
            val name = hintName.ifBlank { profile?.name ?: "" }
            val result = customerRepository.deleteOrArchiveCustomer(
                customerId = customerId,
                hintMobile = mob,
                hintName = name
            )
            _customers.value = _customers.value.filter { it.id != customerId }
            selectedCustomerProfile.value = null
            customerBills.value = emptyList()
            customerTransactions.value = emptyList()
            customerAllPayments.value = emptyList()
            customerKhatavahi.value = emptyList()
            customerOutstandingDue.value = 0.0
            onComplete(result)
        }
    }
}

@Composable
fun CustomersListScreen(
    viewModel: CustomerViewModel,
    onCustomerClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val customers by viewModel.customers.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val selectedIds by viewModel.selectedCustomerIds.collectAsState()
    val selectedGids by viewModel.selectedCustomerGids.collectAsState()
    val selectedTagFilter by viewModel.selectedListTag.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddToListDialog by remember { mutableStateOf(false) }
    var newListNameInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            viewModel.searchQuery.value = ""
        }
    }

    val allVillages = remember(customers) {
        customers.mapNotNull { it.address?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    var tagToDeleteList by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (tagToDeleteList != null) {
        val listTag = tagToDeleteList!!
        AlertDialog(
            onDismissRequest = { tagToDeleteList = null },
            title = { Text("Delete List '$listTag'?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Text("Are you sure you want to delete the list '$listTag'? The tag will be removed from all customers. No customer accounts or bills will be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.deleteListTag(listTag)
                            tagToDeleteList = null
                            Toast.makeText(context, "List '$listTag' deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("Delete List")
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDeleteList = null }) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Selected Customers?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Text("Customers with outstanding Udhari balance will NOT be deleted to prevent data loss. Only customers with ₹0 balance will be removed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val (deleted, skipped) = viewModel.deleteSelectedCustomers()
                            showDeleteConfirmDialog = false
                            if (skipped > 0) {
                                Toast.makeText(context, "Deleted $deleted customer(s). Skipped $skipped customer(s) due to pending Udhari balance.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Deleted $deleted customer(s)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("Confirm Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    val filteredCustomers = remember(customers, search, selectedTagFilter) {
        val cleanSearch = search.trim()
        val filtered = customers.filter { cust ->
            val matchesSearch = cleanSearch.isBlank() ||
                cust.name.contains(cleanSearch, ignoreCase = true) ||
                cust.mobile.contains(cleanSearch) ||
                (cust.address?.contains(cleanSearch, ignoreCase = true) == true)
            
            val matchesTag = when {
                selectedTagFilter == "All" || selectedTagFilter.isBlank() -> true
                else -> cust.address?.trim().equals(selectedTagFilter.trim(), ignoreCase = true)
            }
            matchesSearch && matchesTag
        }.distinctBy { it.canonicalKey() }
        filtered.sortedWith { c1, c2 ->
            AlphabetUtils.compareAlphabetically(c1.name, c2.name)
        }
    }

    val customerSectionIndices = remember(filteredCustomers) {
        val map = mutableMapOf<String, Int>()
        filteredCustomers.forEachIndexed { index, cust ->
            val key = AlphabetUtils.getAlphabetKey(cust.name)
            if (!map.containsKey(key)) {
                map[key] = index
            }
        }
        map
    }

    val customerListState = rememberLazyListState()

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                Surface(
                    color = DeepEmerald,
                    contentColor = WarmWhite,
                    shadowElevation = 4.dp,
                    modifier = Modifier.statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.selectAllCustomers(false, emptyList()) }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Selection", tint = WarmWhite)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${selectedIds.size} Selected",
                                style = MaterialTheme.typography.titleMedium.copy(color = WarmWhite, fontWeight = FontWeight.Bold)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { showAddToListDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = ChampagneGoldContainer),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("List", color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }

                            if (selectedTagFilter != "All") {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            viewModel.removeTagFromSelectedCustomers(selectedTagFilter)
                                            Toast.makeText(context, "Removed from list '$selectedTagFilter'", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmWhite)
                                ) {
                                    Text("Remove", color = WarmWhite, fontSize = 11.sp)
                                }
                            }

                            IconButton(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Customers", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }

                            TextButton(
                                onClick = {
                                    val allSelected = selectedIds.size == filteredCustomers.size
                                    viewModel.selectAllCustomers(!allSelected, filteredCustomers)
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = if (selectedIds.size == filteredCustomers.size) "Deselect" else "Select All",
                                    color = WarmWhite,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                MatoshreeTopAppBar(
                    title = "Customer Directory",
                    showBackButton = true,
                    onBackClick = onBackClick
                )
            }
        },
        floatingActionButton = {
            if (selectedIds.isEmpty()) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = DeepEmerald,
                    contentColor = WarmWhite,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Customer")
                }
            }
        }
    ) { padding ->
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 24.dp else 16.dp)
            ) {
            // Search field
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search by name, mobile, village…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = DeepEmerald) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = WarmWhite,
                    unfocusedContainerColor = WarmWhite
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // WhatsApp Business Style List Tag Filter Chips
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedTagFilter == "All" || selectedTagFilter.isBlank(),
                        onClick = { viewModel.selectedListTag.value = "All" },
                        label = { Text("सर्व गाव (${customers.size})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepEmerald,
                            selectedLabelColor = WarmWhite
                        )
                    )
                }
                items(allVillages) { village ->
                    val count = remember(customers, village) {
                        customers.count { it.address?.trim().equals(village.trim(), ignoreCase = true) }
                    }
                    FilterChip(
                        selected = selectedTagFilter.equals(village, ignoreCase = true),
                        onClick = {
                            viewModel.selectedListTag.value = if (selectedTagFilter == village) "All" else village
                        },
                        label = { Text("$village ($count)", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepEmerald,
                            selectedLabelColor = WarmWhite
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredCustomers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.People, contentDescription = "No Customers", modifier = Modifier.size(48.dp), tint = MutedCharcoal)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (search.isNotBlank()) "No customers match '$search'" else "No customers in '$selectedTagFilter'",
                            style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = customerListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = if (filteredCustomers.isNotEmpty()) 24.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filteredCustomers, key = { customer -> customer.gid.ifBlank { "cust_${customer.id}_${customer.canonicalKey()}" } }) { customer ->
                            val targetGid = customer.gid.ifBlank { "id_${customer.id}" }
                            val isSelected = selectedGids.contains(targetGid) || selectedIds.contains(customer.id)
                            CustomerListItemCard(
                                customer = customer,
                                isSelected = isSelected,
                                isSelectionMode = selectedGids.isNotEmpty() || selectedIds.isNotEmpty(),
                                onCardClick = {
                                    if (selectedGids.isNotEmpty() || selectedIds.isNotEmpty()) {
                                        viewModel.toggleCustomerSelection(targetGid)
                                    } else {
                                        onCustomerClick(targetGid)
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleCustomerSelection(targetGid)
                                }
                            )
                        }
                    }

                    if (filteredCustomers.isNotEmpty()) {
                        OnePlusAlphabetScroller(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(top = 4.dp, bottom = 80.dp, end = 2.dp),
                            activeLetters = customerSectionIndices.keys,
                            onLetterSelected = { letter ->
                                val target = AlphabetUtils.findBestTargetIndex(
                                    letter,
                                    customerSectionIndices,
                                    filteredCustomers.size
                                )
                                if (target != null) {
                                    coroutineScope.launch {
                                        customerListState.scrollToItem(target)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }

    if (showAddToListDialog) {
        AlertDialog(
            onDismissRequest = { showAddToListDialog = false },
            title = { Text(if (selectedIds.isNotEmpty()) "Add Selected to List / Tag" else "Create New Customer List", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selectedIds.isNotEmpty()) {
                        Text("Add ${selectedIds.size} customer(s) to list / tag:", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = newListNameInput,
                        onValueChange = { newListNameInput = it },
                        label = { Text("List Name / Tag") },
                        placeholder = { Text("उदा. VIP, साडी खरेदीदार, कोंढारकी") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    if (allVillages.isNotEmpty()) {
                        Text("Or select existing list:", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(allVillages.size) { idx ->
                                val village = allVillages[idx]
                                AssistChip(
                                    onClick = { newListNameInput = village },
                                    label = { Text(village) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tag = newListNameInput.trim()
                        if (tag.isNotBlank()) {
                            coroutineScope.launch {
                                if (selectedIds.isNotEmpty()) {
                                    viewModel.addTagToSelectedCustomers(tag)
                                    Toast.makeText(context, "Added to list '$tag'", Toast.LENGTH_SHORT).show()
                                }
                                viewModel.selectedListTag.value = tag
                                newListNameInput = ""
                                showAddToListDialog = false
                            }
                        }
                    },
                    enabled = newListNameInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Apply to List", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddToListDialog = false }) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    if (showAddDialog) {
        CustomerEditDialog(
            title = "Add New Customer",
            initialName = "",
            initialMobile = "",
            initialVillage = "Kondharki",
            customerId = 0,
            onDismiss = { showAddDialog = false },
            onSave = { name, mobile, village, email, existingId, allowOverwrite, onDone ->
                viewModel.viewModelScope.launch {
                    val result = viewModel.saveCustomer(name, mobile, village, email, existingId = existingId, allowOverwriteName = allowOverwrite)
                    onDone(result)
                    if (result.isSuccess) showAddDialog = false
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CustomerListItemCard(
    customer: Customer,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onCardClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = onLongClick
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ),
        color = if (isSelected) DeepEmeraldContainer.copy(alpha = 0.15f) else WarmWhite,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onCardClick() },
                        colors = CheckboxDefaults.colors(checkedColor = DeepEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(DeepEmeraldContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = customer.name.take(2).uppercase(),
                            color = WarmWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = customer.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (customer.tier == "VIP" || customer.listTags.contains("VIP")) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "VIP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ChampagneGold,
                                modifier = Modifier
                                    .background(ChampagneGoldContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = customer.mobile.ifBlank { "मोबाईल नाही" },
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal),
                            maxLines = 1
                        )
                        val addressText = customer.address?.trim()
                        if (!addressText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "📍 $addressText",
                                style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                    if (customer.listTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            customer.listTags.take(3).forEach { tag ->
                                Text(
                                    text = tag,
                                    fontSize = 9.sp,
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .background(DeepEmeraldContainer.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = CurrencyFormatter.format(customer.lifetimeSpend),
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${customer.totalBills} बिले",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun CustomerProfileScreen(
    customerGid: String,
    viewModel: CustomerViewModel,
    onBillClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(customerGid) {
        viewModel.loadCustomerProfile(customerGid)
    }

    val customer by viewModel.selectedCustomerProfile.collectAsState()
    val bills by viewModel.customerBills.collectAsState()
    val ledger by viewModel.customerLedger.collectAsState()
    val transactions by viewModel.customerTransactions.collectAsState()
    val allPayments by viewModel.customerAllPayments.collectAsState()
    val khatavahiEntries by viewModel.customerKhatavahi.collectAsState()
    val outstandingDue by viewModel.customerOutstandingDue.collectAsState()
    val shopSettings by viewModel.shopSettings.collectAsState()

    var showManualUdhariDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Bills, 1: Ledger, 2: Payments, 3: Khatavahi
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showCreditLimitDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var activeReceiptTx by remember { mutableStateOf<UdhariTransaction?>(null) }
    var reversingTx by remember { mutableStateOf<UdhariTransaction?>(null) }
    var deletingManualUdhariTx by remember { mutableStateOf<UdhariTransaction?>(null) }
    var selectedUdhariForAction by remember { mutableStateOf<UdhariTransaction?>(null) }

    val thermalPrinterManager = remember { com.matoshree.shopmanager.printer.ThermalPrinterManager(context) }
    var isPrintingStatement by remember { mutableStateOf(false) }
    var showPrintStatementDialog by remember { mutableStateOf(false) }
    var showUdhariReminderDialog by remember { mutableStateOf(false) }

    val nonVoidedBills = remember(bills) { bills.filter { !it.isVoided } }
    val effectiveLifetimeSpend = remember(nonVoidedBills) { nonVoidedBills.sumOf { it.finalAmount } }
    val effectiveTotalBills = nonVoidedBills.size
    val effectiveAvgBill = if (effectiveTotalBills > 0) effectiveLifetimeSpend / effectiveTotalBills else 0.0

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = customer?.name ?: "Customer Profile",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { showPrintStatementDialog = true }) {
                        Icon(Icons.Default.Print, contentDescription = "अहवाल प्रिंट करा", tint = DeepEmerald)
                    }
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Customer", tint = DeepEmerald)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Customer", tint = BoutiqueError)
                    }
                }
            )
        }
    ) { padding ->
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 24.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
            ) {
                // Banner for customer without valid 10-digit mobile
                if (customer != null && PhoneUtils.clean10DigitMobileInput(customer?.mobile ?: "").length != 10) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { showEditDialog = true },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                            border = BorderStroke(1.dp, Color(0xFFFFEEBA)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFF856404))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "या ग्राहकाचा मोबाईल नाही",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF856404)
                                    )
                                    Text(
                                        text = "नोंदी ओळखण्यासाठी व अचूक सिंक होण्यासाठी कृपया ग्राहकाचा १०-अंकी मोबाईल नंबर टाका (येथे स्पर्श करा).",
                                        fontSize = 12.sp,
                                        color = Color(0xFF856404)
                                    )
                                }
                            }
                        }
                    }
                }

                // TOP SECTION: RESPONSIVE FOR TABLET & PHONE
                item {
                    val onCallCustomer: () -> Unit = {
                        val mobile = customer?.mobile
                        if (!mobile.isNullOrBlank()) {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$mobile"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }

                    if (isTablet) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(modifier = Modifier.weight(1.15f)) {
                                CustomerHeaderProfileCard(
                                    customer = customer,
                                    lifetimeSpend = effectiveLifetimeSpend,
                                    totalBills = effectiveTotalBills,
                                    averageBill = effectiveAvgBill,
                                    outstandingDue = outstandingDue,
                                    onCallClick = onCallCustomer,
                                    onCreditLimitClick = { showCreditLimitDialog = true }
                                )
                            }
                            Box(modifier = Modifier.weight(0.85f)) {
                                CustomerActionsAndDueCard(
                                    customer = customer,
                                    outstandingDue = outstandingDue,
                                    onCreditLimitClick = { showCreditLimitDialog = true },
                                    onPaymentClick = { showPaymentDialog = true },
                                    onManualUdhariClick = { showManualUdhariDialog = true },
                                    onWhatsAppClick = { if (customer != null) showUdhariReminderDialog = true },
                                    onPrintStatementClick = { showPrintStatementDialog = true }
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CustomerHeaderProfileCard(
                                customer = customer,
                                lifetimeSpend = effectiveLifetimeSpend,
                                totalBills = effectiveTotalBills,
                                averageBill = effectiveAvgBill,
                                outstandingDue = outstandingDue,
                                onCallClick = onCallCustomer,
                                onCreditLimitClick = { showCreditLimitDialog = true }
                            )
                            CustomerActionsAndDueCard(
                                customer = customer,
                                outstandingDue = outstandingDue,
                                onCreditLimitClick = { showCreditLimitDialog = true },
                                onPaymentClick = { showPaymentDialog = true },
                                onManualUdhariClick = { showManualUdhariDialog = true },
                                onWhatsAppClick = { if (customer != null) showUdhariReminderDialog = true },
                                onPrintStatementClick = { showPrintStatementDialog = true }
                            )
                        }
                    }
                }

                // 3. TAB SELECTOR (Bills, Udhari, Payments)
                item {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = WarmWhite,
                        contentColor = DeepEmerald,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = DeepEmerald
                            )
                        },
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("बिल (${bills.size})", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("उधारी (${ledger.size})", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("पेमेंट (${allPayments.size})", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("खातेवही", fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }

                // TAB CONTENT
                when (selectedTab) {
                    0 -> {
                        // BILLS TAB
                        if (bills.isEmpty()) {
                            item {
                                MatoshreeCard {
                                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text("या कस्टमरसाठी कोणतेही बिल सापडले नाही.", color = MutedCharcoal)
                                    }
                                }
                            }
                        } else {
                            if (isTablet) {
                                items(bills.chunked(2), key = { pair -> "bill_pair_${pair[0].id}_${pair[0].transactionUuid}_${pair.getOrNull(1)?.id ?: "end"}" }) { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            CustomerBillCardItem(bill = pair[0], onBillClick = onBillClick)
                                        }
                                        if (pair.size > 1) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CustomerBillCardItem(bill = pair[1], onBillClick = onBillClick)
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                items(bills, key = { bill -> "bill_${bill.id}_${bill.transactionUuid}" }) { bill ->
                                    CustomerBillCardItem(bill = bill, onBillClick = onBillClick)
                                }
                            }
                        }
                    }
                    1 -> {
                        // UDHARI (ACTUAL CREDIT LEDGER) TAB
                        item {
                            MatoshreeCard(hasGoldTopBorder = outstandingDue > 0.01) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("चालू उधारी खाते बाकी", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                        Text(
                                            text = if (outstandingDue > 0.01) CurrencyFormatter.format(outstandingDue) else "₹0.00 (खाते निरंक)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = if (outstandingDue > 0.01) BoutiqueError else BoutiqueSuccess
                                        )
                                        Text(
                                            text = "एकूण ${ledger.size} उधारी नोंदी",
                                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                                        )
                                    }
                                    if (outstandingDue > 0.01) {
                                        Button(
                                            onClick = { showPaymentDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("उधारी जमा करा", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        if (ledger.isEmpty()) {
                            item {
                                MatoshreeCard {
                                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text("कोणतीही उधारी नोंद नाही (खाते पूर्ण निरंक आहे).", color = MutedCharcoal)
                                    }
                                }
                            }
                        } else {
                            if (isTablet) {
                                items(ledger.chunked(2), key = { pair -> "ledger_pair_${pair[0].id}_${pair.getOrNull(1)?.id ?: "end"}" }) { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            CustomerLedgerCardItem(entry = pair[0], onActionClick = { if (pair[0].transaction != null) selectedUdhariForAction = pair[0].transaction })
                                        }
                                        if (pair.size > 1) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CustomerLedgerCardItem(entry = pair[1], onActionClick = { if (pair[1].transaction != null) selectedUdhariForAction = pair[1].transaction })
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                items(ledger, key = { entry -> "ledger_${entry.id}" }) { entry ->
                                    CustomerLedgerCardItem(entry = entry, onActionClick = { if (entry.transaction != null) selectedUdhariForAction = entry.transaction })
                                }
                            }
                        }
                    }
                    2 -> {
                        // PAYMENTS TAB (Includes ALL payments: Bill checkout payments & Udhari settlements)
                        item {
                            val totalPaid = allPayments.sumOf { it.amount }
                            MatoshreeCard(hasGoldTopBorder = true) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("एकूण मिळालेली रक्कम", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                        Text(
                                            text = CurrencyFormatter.format(totalPaid),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = BoutiqueSuccess
                                        )
                                    }
                                    Text(
                                        text = "${allPayments.size} पेमेंट व्यवहार",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        }

                        if (allPayments.isEmpty()) {
                            item {
                                MatoshreeCard {
                                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text("या कस्टमरकडून अद्याप कोणतेही पेमेंट नोंदवले गेले नाही.", color = MutedCharcoal)
                                    }
                                }
                            }
                        } else {
                            if (isTablet) {
                                items(allPayments.chunked(2), key = { pair -> "pay_pair_${pair[0].id}_${pair.getOrNull(1)?.id ?: "end"}" }) { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            CustomerPaymentCardItem(
                                                record = pair[0],
                                                onBillClick = onBillClick,
                                                onReceiptClick = { activeReceiptTx = pair[0].udhariTx },
                                                onReverseClick = { reversingTx = pair[0].udhariTx }
                                            )
                                        }
                                        if (pair.size > 1) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                CustomerPaymentCardItem(
                                                    record = pair[1],
                                                    onBillClick = onBillClick,
                                                    onReceiptClick = { activeReceiptTx = pair[1].udhariTx },
                                                    onReverseClick = { reversingTx = pair[1].udhariTx }
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                items(allPayments, key = { record -> "pay_${record.id}" }) { record ->
                                    CustomerPaymentCardItem(
                                        record = record,
                                        onBillClick = onBillClick,
                                        onReceiptClick = { activeReceiptTx = record.udhariTx },
                                        onReverseClick = { reversingTx = record.udhariTx }
                                    )
                                }
                            }
                        }
                    }
                    3 -> {
                        // 4. TRADITIONAL KHATAVAHI TAB (पारंपारिक उधारी वही)
                        item {
                            KhatavahiNotebookView(
                                customer = customer,
                                entries = khatavahiEntries,
                                outstandingDue = outstandingDue
                            )
                        }
                    }
                }
            }
        }
    }

    // ADD PAYMENT DIALOG
    if (showPaymentDialog && customer != null) {
        var pAmountText by remember { mutableStateOf("") }
        var pMethod by remember { mutableStateOf(PaymentMethod.CASH) }
        var pNote by remember { mutableStateOf("") }
        var isSubmittingPay by remember { mutableStateOf(false) }

        val entered = pAmountText.toDoubleOrNull() ?: 0.0
        val isValidPay = entered > 0.0 && entered <= outstandingDue && !isSubmittingPay

        AlertDialog(
            onDismissRequest = { if (!isSubmittingPay) showPaymentDialog = false },
            title = { Text("Add Payment (पेमेंट जमा करा)", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Customer: ${customer!!.name} • Due: ${CurrencyFormatter.format(outstandingDue)}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))

                    // Quick Chips
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(500.0, 1000.0, 2000.0).forEach { amt ->
                            if (amt <= outstandingDue) {
                                FilterChip(
                                    selected = pAmountText == amt.toInt().toString(),
                                    onClick = { pAmountText = amt.toInt().toString() },
                                    label = { Text("₹${amt.toInt()}") }
                                )
                            }
                        }
                        FilterChip(
                            selected = pAmountText == String.format(java.util.Locale.US, "%.2f", outstandingDue),
                            onClick = { pAmountText = String.format(java.util.Locale.US, "%.2f", outstandingDue) },
                            label = { Text("Full Due (${CurrencyFormatter.format(outstandingDue)})") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DeepEmeraldContainer, selectedLabelColor = WarmWhite)
                        )
                    }

                    OutlinedTextField(
                        value = pAmountText,
                        onValueChange = { pAmountText = it },
                        label = { Text("Payment Amount (₹) *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = entered > outstandingDue && outstandingDue > 0.0
                    )
                    if (entered > outstandingDue && outstandingDue > 0.0) {
                        Text(
                            text = "भरणा रक्कम बाकीपेक्षा जास्त आहे. बाकी: ${CurrencyFormatter.format(outstandingDue)} (Payment exceeds due)",
                            color = BoutiqueError,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (entered <= 0.0 && pAmountText.isNotBlank()) {
                        Text(
                            text = "भरणा रक्कम ० पेक्षा जास्त असावी (Payment must be > 0)",
                            color = BoutiqueError,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text("Payment Method *", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PaymentMethod.CASH, PaymentMethod.UPI).forEach { method ->
                            Box(modifier = Modifier.weight(1f)) {
                                PaymentMethodPill(
                                    method = method,
                                    isSelected = pMethod == method,
                                    onClick = { pMethod = method }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = pNote,
                        onValueChange = { pNote = it },
                        label = { Text("Note (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isValidPay) {
                            isSubmittingPay = true
                            coroutineScope.launch {
                                val res = viewModel.recordCustomerPayment(customer!!, entered, pMethod, pNote.ifBlank { null })
                                isSubmittingPay = false
                                if (res.isSuccess) {
                                    showPaymentDialog = false
                                    activeReceiptTx = res.getOrNull()
                                    // Asynchronously dispatch WhatsApp Udhari Cleared / Payment confirmation
                                    coroutineScope.launch {
                                        try {
                                            val newDue = (outstandingDue - entered).coerceAtLeast(0.0)
                                            com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariClearedWhatsApp(
                                                context = context,
                                                customerName = customer!!.name,
                                                customerMobile = customer!!.mobile,
                                                amountPaid = entered,
                                                remainingBalance = newDue,
                                                paymentMethod = pMethod.name,
                                                settings = shopSettings
                                            )
                                        } catch (we: Exception) {
                                            android.util.Log.w("CustomerScreens", "WhatsApp cleared dispatch note: ${we.message}")
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, res.exceptionOrNull()?.message ?: "Payment error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = isValidPay,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    if (isSubmittingPay) {
                        CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Confirm Payment", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }, enabled = !isSubmittingPay) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // MANUAL UDHARI DIALOG (जुनी बाकी / Old Balance)
    if (showManualUdhariDialog && customer != null) {
        ManualUdhariDialog(
            customer = customer!!,
            onDismiss = { showManualUdhariDialog = false },
            onConfirm = { amount, note, date, dueDate ->
                coroutineScope.launch {
                    val res = viewModel.recordManualUdhari(customer!!, amount, note, date, dueDate)
                    if (res.isSuccess) {
                        showManualUdhariDialog = false
                        activeReceiptTx = res.getOrNull()
                        Toast.makeText(context, "Udhari of ${CurrencyFormatter.format(amount)} recorded successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "Recording error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // PAYMENT RECEIPT DIALOG
    if (activeReceiptTx != null && customer != null) {
        PaymentReceiptDialog(
            transaction = activeReceiptTx!!,
            customerName = customer!!.name,
            customerMobile = customer!!.mobile,
            settings = shopSettings,
            onDismiss = { activeReceiptTx = null }
        )
    }

    // PAYMENT REVERSAL CONFIRMATION DIALOG
    if (reversingTx != null) {
        var reversalReasonText by remember { mutableStateOf("") }
        var isReversing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isReversing) reversingTx = null },
            title = { Text("Reverse Payment", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to reverse payment of ${CurrencyFormatter.format(reversingTx!!.amount)}?")
                    Text("This will restore the outstanding balance on the customer's account.", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    OutlinedTextField(
                        value = reversalReasonText,
                        onValueChange = { reversalReasonText = it },
                        label = { Text("Reversal Reason *") },
                        placeholder = { Text("e.g. Wrong amount entered") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isReversing = true
                        coroutineScope.launch {
                            val res = viewModel.reversePayment(reversingTx!!.id, reversalReasonText.ifBlank { "Manager Reversal" })
                            isReversing = false
                            reversingTx = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "Payment reversed successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, res.exceptionOrNull()?.message ?: "Reversal error", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError),
                    enabled = !isReversing
                ) {
                    Text("Confirm Reversal", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { reversingTx = null }, enabled = !isReversing) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // DELETE MANUAL UDHARI CONFIRMATION DIALOG
    if (deletingManualUdhariTx != null) {
        var isDeletingUdhari by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isDeletingUdhari) deletingManualUdhariTx = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = BoutiqueError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("उधारी डिलीट करा?", fontWeight = FontWeight.Bold, color = BoutiqueError)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("रक्कम: ${CurrencyFormatter.format(deletingManualUdhariTx!!.amount)}", fontWeight = FontWeight.Bold)
                    Text("तारीख: ${deletingManualUdhariTx!!.date}")
                    Text("ही उधारी नोंद कायमची काढून टाकली जाईल आणि कस्टमरच्या खात्यातील शिल्लक उधारीतून ₹${deletingManualUdhariTx!!.amount.toInt()} कमी होतील.", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeletingUdhari = true
                        coroutineScope.launch {
                            val res = viewModel.deleteOrReverseManualUdhari(deletingManualUdhariTx!!.id)
                            isDeletingUdhari = false
                            deletingManualUdhariTx = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "उधारी नोंद यशस्वीरित्या डिलीट केली", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, res.exceptionOrNull()?.message ?: "डिलीट करताना त्रुटी आली", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError),
                    enabled = !isDeletingUdhari
                ) {
                    if (isDeletingUdhari) {
                        CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("होय, उधारी डिलीट करा", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingManualUdhariTx = null }, enabled = !isDeletingUdhari) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // UDHARI DETAILS & ACTION DIALOG (Opened on clicking Udhari in profile)
    if (selectedUdhariForAction != null && customer != null) {
        UdhariActionDialog(
            transaction = selectedUdhariForAction!!,
            customer = customer!!,
            settings = shopSettings,
            onDismiss = { selectedUdhariForAction = null },
            onViewBill = { bId ->
                selectedUdhariForAction = null
                onBillClick(bId)
            },
            onRecordPayment = {
                selectedUdhariForAction = null
                showPaymentDialog = true
            },
            onSaveDueDate = { newDueDate ->
                coroutineScope.launch {
                    val res = viewModel.updateUdhariDueDate(selectedUdhariForAction!!.id, newDueDate)
                    selectedUdhariForAction = null
                    if (res.isSuccess) {
                        Toast.makeText(context, "मुदत तारीख सेव्ह केली", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "अपडेट करताना त्रुटी आली", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDeleteUdhari = {
                val txToDelete = selectedUdhariForAction
                selectedUdhariForAction = null
                deletingManualUdhariTx = txToDelete
            }
        )
    }

    // CREDIT LIMIT DIALOG
    if (showCreditLimitDialog && customer != null) {
        var limitInput by remember { mutableStateOf(customer!!.creditLimit?.toInt()?.toString() ?: "") }
        var isSavingLimit by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSavingLimit) showCreditLimitDialog = false },
            title = { Text("Set Customer Credit Limit", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set maximum allowable outstanding balance for ${customer!!.name}.")
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it },
                        label = { Text("Credit Limit (₹) - Leave empty for No Limit") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSavingLimit = true
                        coroutineScope.launch {
                            val limitVal = limitInput.toDoubleOrNull()
                            viewModel.setCustomerCreditLimit(customer!!.id, limitVal)
                            isSavingLimit = false
                            showCreditLimitDialog = false
                            Toast.makeText(context, "Credit limit updated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("Save Limit", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreditLimitDialog = false }) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // DELETE / ARCHIVE CUSTOMER DIALOG
    if (showDeleteDialog && customer != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Customer", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                if (outstandingDue > 0.01) {
                    Text(
                        text = "Cannot delete '${customer!!.name}' because they have an active outstanding balance of ${CurrencyFormatter.format(outstandingDue)}. Please settle the balance before deleting.",
                        color = BoutiqueError,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text("Are you sure you want to delete '${customer!!.name}'? This will archive the customer record.")
                }
            },
            confirmButton = {
                if (outstandingDue <= 0.01) {
                    Button(
                        onClick = {
                            val targetId = customer?.id ?: (if (customerGid.startsWith("id_")) customerGid.removePrefix("id_").toLongOrNull() ?: 0L else 0L)
                            viewModel.deleteOrArchiveCustomer(targetId, customer?.mobile ?: "", customer?.name ?: "") { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                showDeleteDialog = false
                                onBackClick()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(if (outstandingDue > 0.01) "OK" else "Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // EDIT CUSTOMER DIALOG
    if (showEditDialog && customer != null) {
        val c = customer!!
        CustomerEditDialog(
            title = "Edit Customer Details",
            initialName = c.name,
            initialMobile = c.mobile,
            initialVillage = c.address?.ifBlank { "Kondharki" } ?: "Kondharki",
            initialEmail = c.email ?: "",
            customerId = c.id,
            onDismiss = { showEditDialog = false },
            onSave = { name, mobile, village, email, existingId, allowOverwrite, onDone ->
                coroutineScope.launch {
                    val res = viewModel.saveCustomer(name, mobile, village, email, existingId = existingId, allowOverwriteName = allowOverwrite)
                    onDone(res)
                    if (res.isSuccess) showEditDialog = false
                }
            }
        )
    }

    // CUSTOMER STATEMENT PRINT & SHARE DIALOG
    if (showPrintStatementDialog && customer != null) {
        val cust = customer!!
        AlertDialog(
            onDismissRequest = { if (!isPrintingStatement) showPrintStatementDialog = false },
            icon = {
                Icon(Icons.Default.Print, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("ग्राहक खाते अहवाल (Customer Statement)", fontWeight = FontWeight.Bold, color = DeepEmerald, textAlign = TextAlign.Center)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${cust.name} यांच्या खात्यातील बिले व जमा-खर्च व्यवहारांचा संपूर्ण अहवाल मिळवा:",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                    )

                    // Option 1: 58mm Thermal Bluetooth Print
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isPrintingStatement) {
                                isPrintingStatement = true
                                coroutineScope.launch {
                                    val res = thermalPrinterManager.printCustomerStatement(
                                        customer = cust,
                                        bills = bills,
                                        transactions = transactions,
                                        settings = shopSettings
                                    )
                                    isPrintingStatement = false
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "थर्मल प्रिंटिंग सुरू झाले!", Toast.LENGTH_SHORT).show()
                                        showPrintStatementDialog = false
                                    } else {
                                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "प्रिंट अयशस्वी", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = WarmWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepEmerald.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("५८ मिमी थर्मल प्रिंटरवर प्रिंट करा", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DeepEmerald)
                                Text("ब्लूटूथ पीओएस प्रिंटरवर पावती प्रिंट करा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                        }
                    }

                    // Option 2: System Print & Save as PDF
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isPrintingStatement) {
                                showPrintStatementDialog = false
                                PrintShareHelper.printCustomerStatementSystem(
                                    context = context,
                                    customer = cust,
                                    bills = bills,
                                    transactions = transactions,
                                    settings = shopSettings
                                )
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = WarmWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGoldContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = ChampagneGold, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("सिस्टीम प्रिंट / PDF सेव्ह करा", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DeepEmerald)
                                Text("रंगीत PDF डॉक्युमेंट तयार किंवा प्रिंट करा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                        }
                    }

                    // Option 3: Share Full-Color Report (WhatsApp / Image)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isPrintingStatement) {
                                showPrintStatementDialog = false
                                PrintShareHelper.shareColorCustomerStatement(
                                    context = context,
                                    customer = cust,
                                    bills = bills,
                                    transactions = transactions,
                                    settings = shopSettings
                                )
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = WarmWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BoutiqueSuccess.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("रंगीत खाते अहवाल शेअर करा", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DeepEmerald)
                                Text("व्हॉट्सॲपवर आकर्षक इमेज फॉरमॅटमध्ये पाठवा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                        }
                    }

                    if (isPrintingStatement) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = DeepEmerald)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("प्रिंट पाठवत आहे...", style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPrintStatementDialog = false }, enabled = !isPrintingStatement) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmIvory,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // UDHARI REMINDER DIALOG (WITH DUE DATE SELECTION & WHATSAPP WEBHOOK / DIRECT SHARE)
    if (showUdhariReminderDialog && customer != null) {
        val cust = customer!!
        var reminderDueDate by remember {
            mutableStateOf(
                transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PURCHASE_CREDIT && !it.dueDate.isNullOrBlank() }
                    .mapNotNull { it.dueDate }
                    .firstOrNull() ?: DateUtils.todayDate()
            )
        }
        var isSendingWebhookReminder by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSendingWebhookReminder) showUdhariReminderDialog = false },
            icon = {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("उधारी आठवण पाठवा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "ग्राहक: ${cust.name} (${cust.mobile.ifBlank { "नंबर नाही" }})\nबाकी उधारी रक्कम: ${CurrencyFormatter.format(outstandingDue)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                    )

                    OutlinedTextField(
                        value = reminderDueDate,
                        onValueChange = { reminderDueDate = it },
                        label = { Text("उधारी परत करण्याची मुदत तारीख (YYYY-MM-DD)") },
                        placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showUdhariReminderDialog = false
                            val lastPaymentTx = transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT }
                                .maxByOrNull { it.createdAt }
                            val pendingBillsCount = bills.count { !it.isVoided && it.pendingAmount > 0.01 }.coerceAtLeast(1)

                            openCustomerWhatsAppReminder(
                                context = context,
                                customer = cust,
                                outstandingDue = outstandingDue,
                                dueDate = reminderDueDate.takeIf { it.isNotBlank() },
                                lastPaymentAmount = lastPaymentTx?.amount,
                                lastPaymentDate = lastPaymentTx?.date,
                                pendingBillsCount = pendingBillsCount,
                                settings = shopSettings
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                        enabled = !isSendingWebhookReminder
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("नवीन खाते उतारा फोटो पाठवा (WhatsApp)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (cust.mobile.isBlank()) {
                                Toast.makeText(context, "कस्टमरचा मोबाईल नंबर उपलब्ध नाही", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isSendingWebhookReminder = true
                            coroutineScope.launch {
                                val dueTx = transactions.firstOrNull { !it.isReversed && it.type == UdhariTransactionType.PURCHASE_CREDIT }
                                if (dueTx != null && reminderDueDate.isNotBlank()) {
                                    viewModel.updateUdhariDueDate(dueTx.id, reminderDueDate)
                                }
                                val res = com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariReminderWhatsApp(
                                    context = context,
                                    customerName = cust.name,
                                    customerMobile = cust.mobile,
                                    outstandingDue = outstandingDue,
                                    dueDate = reminderDueDate.takeIf { it.isNotBlank() },
                                    settings = shopSettings
                                )
                                isSendingWebhookReminder = false
                                showUdhariReminderDialog = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "व्हॉट्सॲप Webhook द्वारे आठवण पाठवली!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, res.exceptionOrNull()?.message ?: "त्रुटी आली", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        enabled = !isSendingWebhookReminder
                    ) {
                        if (isSendingWebhookReminder) {
                            CircularProgressIndicator(color = DeepEmerald, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("पाठवत आहे...")
                        } else {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("व्हॉट्सॲप Webhook ऑटोमेशन पाठवा", fontSize = 13.sp)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUdhariReminderDialog = false }, enabled = !isSendingWebhookReminder) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun CustomerHeaderProfileCard(
    customer: Customer?,
    lifetimeSpend: Double,
    totalBills: Int,
    averageBill: Double,
    outstandingDue: Double,
    onCallClick: () -> Unit,
    onCreditLimitClick: () -> Unit
) {
    MatoshreeCard(hasGoldTopBorder = true) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(DeepEmeraldContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = customer?.name?.take(2)?.uppercase() ?: "MC",
                            color = WarmWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = customer?.name ?: "Customer",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = DeepEmerald),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (customer?.tier == "VIP" || customer?.listTags?.contains("VIP") == true) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StatusBadge(status = "VIP", isWarning = true)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = customer?.mobile?.ifBlank { "मोबाईल नंबर नाही" } ?: "मोबाईल नंबर नाही",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal),
                                maxLines = 1
                            )
                            if (!customer?.mobile.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = onCallClick,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Call,
                                        contentDescription = "कॉल करा",
                                        tint = BoutiqueSuccess,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = DeepEmeraldContainer.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📍 गाव / पत्ता: ${customer?.address?.ifBlank { "कोंढारकी" } ?: "कोंढारकी"}",
                        style = MaterialTheme.typography.bodyMedium.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ChampagneGoldContainer.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("एकूण खरेदी", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                    Text(CurrencyFormatter.format(lifetimeSpend), fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp, maxLines = 1, softWrap = false)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("एकूण बिले", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                    Text("$totalBills", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("सरासरी बिल", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                    Text(CurrencyFormatter.format(averageBill), fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
fun CustomerActionsAndDueCard(
    customer: Customer?,
    outstandingDue: Double,
    onCreditLimitClick: () -> Unit,
    onPaymentClick: () -> Unit,
    onManualUdhariClick: () -> Unit,
    onWhatsAppClick: () -> Unit,
    onPrintStatementClick: () -> Unit
) {
    MatoshreeCard(hasGoldTopBorder = outstandingDue > 0.01) {
        Column {
            // Outstanding Udhari Status Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (outstandingDue > 0.01) BoutiqueError.copy(alpha = 0.08f) else BoutiqueSuccess.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (outstandingDue > 0.01) BoutiqueError.copy(alpha = 0.3f) else BoutiqueSuccess.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (outstandingDue > 0.01) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (outstandingDue > 0.01) BoutiqueError else BoutiqueSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (outstandingDue > 0.01) "उधारी बाकी रक्कम" else "उधारी खाते निरंक",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (outstandingDue > 0.01) BoutiqueError else BoutiqueSuccess
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (outstandingDue > 0.01) CurrencyFormatter.format(outstandingDue) else "₹0.00",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = if (outstandingDue > 0.01) BoutiqueError else BoutiqueSuccess,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Surface(
                        color = ChampagneGold.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.6f)),
                        modifier = Modifier.clickable(onClick = onCreditLimitClick)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                val limitStr = customer?.creditLimit?.let { CurrencyFormatter.format(it) } ?: "मर्यादा सेट करा"
                                Text(limitStr, fontSize = 11.sp, color = DeepEmerald, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                if (customer?.creditLimit != null) {
                                    val avail = (customer.creditLimit!! - outstandingDue).coerceAtLeast(0.0)
                                    Text("उपलब्ध: ${CurrencyFormatter.format(avail)}", fontSize = 10.sp, color = MutedCharcoal, maxLines = 1, softWrap = false)
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Tune, contentDescription = "क्रेडिट मर्यादा", tint = DeepEmerald, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons (2x2 Grid)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPaymentClick,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmeraldContainer),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ पेमेंट जमा", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = onManualUdhariClick,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ उधारी नोंद", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onWhatsAppClick,
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.15f).height(44.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("व्हॉट्सॲप स्मरण", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onPrintStatementClick,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.85f).height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepEmerald),
                    border = BorderStroke(1.dp, DeepEmerald)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("खाते अहवाल", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun CustomerBillCardItem(bill: Bill, onBillClick: (Long) -> Unit) {
    MatoshreeCard(onClick = { onBillClick(bill.id) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bill.billNumber, fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                    if (bill.pendingAmount > 0.01) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(बाकी: ${CurrencyFormatter.format(bill.pendingAmount)})",
                            color = BoutiqueError,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${DateUtils.formatForDisplay(bill.billDate)} • ${bill.paymentMethod.name}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(CurrencyFormatter.format(bill.finalAmount), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(2.dp))
                StatusBadge(
                    status = if (bill.isVoided) "रद्द (Void)" else if (bill.pendingAmount > 0.01) "अंशतः उधारी" else "पूर्ण भरणा",
                    isSuccess = !bill.isVoided && bill.pendingAmount <= 0.01,
                    isError = bill.isVoided || bill.pendingAmount > 0.01
                )
            }
        }
    }
}

@Composable
fun CustomerLedgerCardItem(entry: UdhariLedgerEntry, onActionClick: () -> Unit) {
    MatoshreeCard(
        modifier = Modifier.clickable { onActionClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (!entry.billNumber.isNullOrBlank()) "उधारी नोंद" else "थेट उधारी नोंद (+)",
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.isReversed) MutedCharcoal else DeepEmerald
                    )
                    if (!entry.billNumber.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ChampagneGoldContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "#${entry.billNumber}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${DateUtils.formatForDisplay(entry.date)} • ${entry.subtitle}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (entry.isReversed) {
                    Text("व्यवहार रद्द (REVERSED)", color = BoutiqueError, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyFormatter.format(entry.debitAmount),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (entry.isReversed) MutedCharcoal else BoutiqueError
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "तपशील व कृती",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = DeepEmerald,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = DeepEmerald
                    )
                }
            }
        }
    }
}

@Composable
fun CustomerPaymentCardItem(
    record: CustomerPaymentRecord,
    onBillClick: (Long) -> Unit,
    onReceiptClick: () -> Unit,
    onReverseClick: () -> Unit
) {
    MatoshreeCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        fontWeight = FontWeight.Bold,
                        color = if (record.isReversed) MutedCharcoal else BoutiqueSuccess
                    )
                    Text(
                        text = "${DateUtils.formatForDisplay(record.date)} • ${record.paymentMethod.name} • ${record.subtitle}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CurrencyFormatter.format(record.amount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (record.isReversed) MutedCharcoal else BoutiqueSuccess
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (record.isReversed) {
                        StatusBadge(status = "रद्द (REVERSED)", isError = true)
                    } else if (record.isUdhariSettlement) {
                        StatusBadge(status = "उधारी जमा", isSuccess = true)
                    } else {
                        StatusBadge(status = "बिल भरणा", isSuccess = true)
                    }
                }
            }

            if (!record.isReversed) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (record.billId != null) {
                        TextButton(onClick = { onBillClick(record.billId) }) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = "बिल पहा", modifier = Modifier.size(16.dp), tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("बिल पहा", color = DeepEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (record.isUdhariSettlement) {
                        TextButton(onClick = onReceiptClick) {
                            Icon(Icons.Default.Receipt, contentDescription = "पावती", modifier = Modifier.size(16.dp), tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("पावती पहा", color = DeepEmerald, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = onReverseClick) {
                            Text("रद्द करा", color = BoutiqueError, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KhatavahiNotebookView(
    customer: Customer?,
    entries: List<KhatavahiEntry>,
    outstandingDue: Double
) {
    if (customer == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFDFBF7), // Authentic Notebook Paper Cream Tone
        border = BorderStroke(1.dp, Color(0xFFE2D8C3)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // --- 1. NOTEBOOK PAGE HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Number in traditional notebook stamp circle
                val pageNo = ((customer.id xor 0x1F) % 150) + 1
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(1.5.dp, Color(0xFFC62828)),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$pageNo",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828),
                            fontSize = 13.sp
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = customer.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color(0xFF1A237E), // Deep Ink Blue
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "${customer.address?.ifBlank { "कोंढारकी" } ?: "कोंढारकी"} • ${customer.mobile}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp),
                        textAlign = TextAlign.Center
                    )
                }

                // Traditional "खातेवही पान" stamp badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = ChampagneGoldContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, ChampagneGold)
                ) {
                    Text(
                        text = "खातेवही",
                        fontWeight = FontWeight.Bold,
                        color = DeepEmerald,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Red double ruled header line (traditional notebook style)
            HorizontalDivider(thickness = 1.5.dp, color = Color(0xFFC62828))
            Spacer(modifier = Modifier.height(2.dp))
            HorizontalDivider(thickness = 0.8.dp, color = Color(0xFFC62828).copy(alpha = 0.6f))

            Spacer(modifier = Modifier.height(6.dp))

            // --- 2. NOTEBOOK ENTRIES OR EMPTY STATE ---
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "या खातेवहीत अद्याप कोणत्याही नोंदी नाहीत.",
                        color = MutedCharcoal,
                        fontSize = 14.sp
                    )
                }
            } else {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: Date
                        Column(
                            modifier = Modifier.width(62.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = DateUtils.formatForDisplay(entry.date),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = MutedCharcoal
                            )
                        }

                        // Vertical Red Margin Line
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .height(44.dp)
                                .background(Color(0xFFE57373).copy(alpha = 0.7f))
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Middle Column: Description / Items
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.description,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color(0xFF263238),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (entry.billNumber?.isNotBlank() == true) {
                                Text(
                                    text = "बिल क्र: #${entry.billNumber}",
                                    fontSize = 10.sp,
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Right Column: Amount (+ or -) and Running Balance (येणे =)
                        Column(horizontalAlignment = Alignment.End) {
                            if (entry.isCredit) {
                                Text(
                                    text = "- ${CurrencyFormatter.format(entry.amount)} जमा",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = BoutiqueSuccess
                                )
                            } else {
                                Text(
                                    text = "+ ${CurrencyFormatter.format(entry.amount)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1A237E) // Deep Ink Blue
                                )
                            }
                            Text(
                                text = if (entry.runningBalance > 0.01) "येणे = ${CurrencyFormatter.format(entry.runningBalance)}" else "येणे = निरंक",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (entry.runningBalance > 0.01) BoutiqueError else BoutiqueSuccess
                            )
                        }
                    }

                    // Light Blue Horizontal Rule (notebook lines)
                    HorizontalDivider(
                        thickness = 0.8.dp,
                        color = Color(0xFFD6E4F0)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- 3. NOTEBOOK FOOTER (DOUBLE ACCOUNTING RULE) ---
            HorizontalDivider(thickness = 1.dp, color = Color(0xFF1A237E).copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(2.dp))
            HorizontalDivider(thickness = 1.5.dp, color = Color(0xFF1A237E))

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "खातेवही एकंदर स्थिती:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MutedCharcoal
                )
                val vahiFinalBalance = entries.lastOrNull()?.runningBalance ?: outstandingDue
                if (vahiFinalBalance > 0.01) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = BoutiqueError.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, BoutiqueError.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "★ चालू येणे बाकी: ${CurrencyFormatter.format(vahiFinalBalance)} ★",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = BoutiqueError,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = BoutiqueSuccess.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, BoutiqueSuccess.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "★ हिशोब पूर्ण चुकता (खाते निरंक) ★",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = BoutiqueSuccess,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerEditDialog(
    title: String,
    initialName: String,
    initialMobile: String,
    initialVillage: String = "Kondharki",
    initialEmail: String = "",
    customerId: Long = 0,
    onDismiss: () -> Unit,
    onSave: (name: String, mobile: String, village: String, email: String?, existingId: Long, allowOverwrite: Boolean, onDone: (Result<Long>) -> Unit) -> Unit
) {
    var editName by remember { mutableStateOf(initialName) }
    var editMobile by remember { mutableStateOf(initialMobile) }
    var editVillage by remember { mutableStateOf(initialVillage) }

    var editNameError by remember { mutableStateOf<String?>(null) }
    var editMobileError by remember { mutableStateOf<String?>(null) }
    var editGeneralError by remember { mutableStateOf<String?>(null) }
    var isSavingEdit by remember { mutableStateOf(false) }

    var showDuplicateResolutionDialog by remember { mutableStateOf<Pair<Long, String>?>(null) }

    if (showDuplicateResolutionDialog != null) {
        val (existingCustId, existingCustName) = showDuplicateResolutionDialog!!
        AlertDialog(
            onDismissRequest = { showDuplicateResolutionDialog = null },
            title = { Text("Customer Already Exists", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Text(
                    text = "A customer with mobile $editMobile already exists as '$existingCustName'. Do you want to update this customer's name to '${editName.trim()}' or keep existing?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameTrimmed = editName.trim()
                        val mobileClean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(editMobile)
                        val villageTrimmed = editVillage.trim().ifBlank { "कोंढारकी" }
                        showDuplicateResolutionDialog = null
                        isSavingEdit = true
                        onSave(nameTrimmed, mobileClean, villageTrimmed, null, existingCustId, true) { res ->
                            isSavingEdit = false
                            if (res.isSuccess) {
                                onDismiss()
                            } else {
                                editGeneralError = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("नाव '${editName.trim()}' असे अपडेट करा", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDuplicateResolutionDialog = null
                    onDismiss()
                }) {
                    Text("जुने नाव '$existingCustName' ठेवा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = DeepEmerald) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editGeneralError != null) {
                    Surface(
                        color = BoutiqueError.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = editGeneralError!!,
                            color = BoutiqueError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = editName,
                    onValueChange = {
                        editName = it
                        editNameError = if (it.isBlank()) "कृपया कस्टमरचे नाव टाका" else null
                        editGeneralError = null
                    },
                    label = { Text("कस्टमरचे नाव *") },
                    placeholder = { Text("उदा. राहुल पाटील", color = MutedCharcoal.copy(alpha = 0.5f)) },
                    singleLine = true,
                    isError = editNameError != null,
                    supportingText = editNameError?.let { { Text(it, color = BoutiqueError) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = editMobile,
                    onValueChange = { input ->
                        val cleaned = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(input)
                        editMobile = cleaned
                        editMobileError = when {
                            cleaned.isNotBlank() && cleaned.length != 10 -> "कृपया १० अंकी मोबाईल नंबर टाका"
                            else -> null
                        }
                        editGeneralError = null
                    },
                    label = { Text("मोबाईल नंबर") },
                    placeholder = { Text("१० अंकी मोबाईल नंबर टाका", color = MutedCharcoal.copy(alpha = 0.5f)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                    ),
                    isError = editMobileError != null,
                    supportingText = editMobileError?.let { { Text(it, color = BoutiqueError) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = editVillage,
                    onValueChange = {
                        editVillage = it
                        editGeneralError = null
                    },
                    label = { Text("गाव / पत्ता") },
                    placeholder = { Text("कोंढारकी", color = MutedCharcoal.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val nameTrimmed = editName.trim()
                    val mobileClean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(editMobile)
                    val villageTrimmed = editVillage.trim().ifBlank { "कोंढारकी" }

                    if (nameTrimmed.isBlank()) {
                        editNameError = "कृपया कस्टमरचे नाव टाका"
                        return@Button
                    }
                    if (mobileClean.isNotBlank() && mobileClean.length != 10) {
                        editMobileError = "कृपया १० अंकी मोबाईल नंबर टाका किंवा रिकामा सोडा"
                        return@Button
                    }

                    isSavingEdit = true
                    onSave(nameTrimmed, mobileClean, villageTrimmed, null, customerId, false) { res ->
                        isSavingEdit = false
                        if (res.isFailure) {
                            val msg = res.exceptionOrNull()?.message ?: "कस्टमर सेव्ह करताना त्रुटी आली"
                            if (msg.startsWith("EXISTS_DIFFERENT_NAME:")) {
                                val parts = msg.split(":")
                                val existingId = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                                val existingName = parts.getOrNull(2) ?: "Existing"
                                showDuplicateResolutionDialog = Pair(existingId, existingName)
                            } else {
                                editGeneralError = msg
                            }
                        } else {
                            onDismiss()
                        }
                    }
                },
                enabled = !isSavingEdit && editName.isNotBlank() && (editMobile.isBlank() || editMobile.length == 10),
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSavingEdit) {
                    CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save Customer", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

@Composable
fun ManualUdhariDialog(
    customer: Customer,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, note: String, date: String, dueDate: String?) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("जुनी बाकी") }
    var dateText by remember { mutableStateOf(DateUtils.todayDate()) }
    var dueDateText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PostAdd, contentDescription = null, tint = DeepEmerald)
                Spacer(modifier = Modifier.width(8.dp))
                Text("नवीन उधारी ॲड करा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("कस्टमर: ${customer.name} (${customer.address ?: "कोंढारकी"})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("उधारी रक्कम (₹) *") },
                    placeholder = { Text("उदा. 2500") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("तपशील / Note") },
                    placeholder = { Text("उदा. साडी उधारी / जुनी बाकी") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                MatoshreeDatePickerField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = "तारीख",
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.fillMaxWidth()
                )

                MatoshreeDatePickerField(
                    value = dueDateText,
                    onValueChange = { dueDateText = it },
                    label = "मुदत तारीख (पर्यायी)",
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toDoubleOrNull() ?: 0.0
                    if (amt > 0.0) {
                        isSubmitting = true
                        onConfirm(amt, noteText.trim(), dateText.trim(), dueDateText.trim().ifBlank { null })
                    }
                },
                enabled = !isSubmitting && (amountText.toDoubleOrNull() ?: 0.0) > 0.0,
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("उधारी सेव्ह करा", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("रद्द करा", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

@Composable
fun UdhariActionDialog(
    transaction: UdhariTransaction,
    customer: Customer,
    settings: PrintShareHelper.ShopSettingsSnapshot,
    onDismiss: () -> Unit,
    onViewBill: ((Long) -> Unit)? = null,
    onRecordPayment: () -> Unit = {},
    onSaveDueDate: (String?) -> Unit,
    onDeleteUdhari: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dueDateText by remember { mutableStateOf(transaction.dueDate ?: "") }
    var isSavingDueDate by remember { mutableStateOf(false) }

    val isManualUdhari = transaction.type == UdhariTransactionType.PURCHASE_CREDIT && transaction.billId == 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = DeepEmerald)
                Spacer(modifier = Modifier.width(8.dp))
                Text("उधारी तपशील व कृती", fontWeight = FontWeight.Bold, color = DeepEmerald)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WarmIvory,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGoldContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("कस्टमर:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(customer.name, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("उधारी रक्कम:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(CurrencyFormatter.format(transaction.amount), fontWeight = FontWeight.Bold, color = BoutiqueError, fontSize = 16.sp)
                        }
                        if (transaction.billNumber.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("मूळ बिल संदर्भ:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text("#${transaction.billNumber}", fontWeight = FontWeight.Bold, color = DeepEmerald)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("नोंद तारीख:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(transaction.date, fontWeight = FontWeight.SemiBold)
                        }
                        if (!transaction.dueDate.isNullOrBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("सध्याची मुदत तारीख:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text(transaction.dueDate!!, fontWeight = FontWeight.Bold, color = BoutiqueError)
                            }
                        }
                        if (transaction.note?.isNotBlank() == true) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("तपशील:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text(transaction.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Action 1: View Source Bill (if linked to a bill)
                if (transaction.billId != 0L && onViewBill != null) {
                    Button(
                        onClick = { onViewBill(transaction.billId) },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("मूळ बिल पहा (#${transaction.billNumber})", fontWeight = FontWeight.Bold)
                    }
                }

                // Action 2: Record Payment (जमा उधारी)
                Button(
                    onClick = onRecordPayment,
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("या उधारीची जमा नोंद करा", fontWeight = FontWeight.Bold)
                }

                Text("मुदत तारीख बदला / सेट करा:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                MatoshreeDatePickerField(
                    value = dueDateText,
                    onValueChange = { dueDateText = it },
                    label = "मुदत तारीख",
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = {
                        isSavingDueDate = true
                        onSaveDueDate(dueDateText.trim().ifBlank { null })
                    },
                    enabled = !isSavingDueDate,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    if (isSavingDueDate) {
                        CircularProgressIndicator(color = DeepEmerald, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = DeepEmerald)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("मुदत तारीख सेव्ह करा", fontWeight = FontWeight.Bold, color = DeepEmerald)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = OutlineVariantGrey.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            PrintShareHelper.sharePaymentReceiptJpg(
                                context = context,
                                transaction = transaction,
                                customerName = customer.name,
                                customerMobile = customer.mobile,
                                customerVillage = customer.address,
                                settings = settings
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp), tint = DeepEmerald)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("व्हॉट्सॲप", color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    if (isManualUdhari) {
                        Button(
                            onClick = onDeleteUdhari,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("डिलीट करा", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("बंद करा", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

@Composable
fun CustomerProfileScreen(
    customerId: Long,
    viewModel: CustomerViewModel,
    onBillClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    CustomerProfileScreen(
        customerGid = "id_$customerId",
        viewModel = viewModel,
        onBillClick = onBillClick,
        onBackClick = onBackClick
    )
}

fun openCustomerWhatsAppReminder(
    context: Context,
    customer: Customer,
    outstandingDue: Double,
    dueDate: String? = null,
    lastPaymentAmount: Double? = null,
    lastPaymentDate: String? = null,
    pendingBillsCount: Int = 1,
    settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
) {
    PrintShareHelper.shareCustomerUdhariStatementJpg(
        context = context,
        customerName = customer.name,
        customerMobile = customer.mobile,
        customerVillage = customer.address,
        totalOutstanding = outstandingDue,
        lastPaymentAmount = lastPaymentAmount,
        lastPaymentDate = lastPaymentDate,
        pendingBillsCount = pendingBillsCount,
        dueDate = dueDate,
        settings = settings
    )
}
