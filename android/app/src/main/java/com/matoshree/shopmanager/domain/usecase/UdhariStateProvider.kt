package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

data class UdhariLedgerState(
    val isLoading: Boolean = true,
    val totalOutstanding: Double = 0.0,
    val pendingCustomersCount: Int = 0,
    val customerBalances: Map<String, Double> = emptyMap(),
    val customers: List<Customer> = emptyList(),
    val activeBills: List<Bill> = emptyList(),
    val validTransactions: List<UdhariTransaction> = emptyList(),
    val todayCollections: Double = 0.0,
    val todayCashCollections: Double = 0.0,
    val todayUpiCollections: Double = 0.0,
    val thisMonthCollections: Double = 0.0,
    val overdueAmount: Double = 0.0,
    val pendingBillsCount: Int = 0,
    val errorMessage: String? = null
)

class UdhariStateProvider(
    private val customerRepository: CustomerRepository,
    private val billRepository: BillRepository,
    private val udhariRepository: UdhariRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    val state: StateFlow<UdhariLedgerState> = combine(
        customerRepository.getAllCustomers().distinctUntilChanged(),
        billRepository.getAllBills(limit = 100000).distinctUntilChanged(),
        udhariRepository.getAllTransactions().distinctUntilChanged()
    ) { customers, bills, transactions ->
        Triple(customers, bills, transactions)
    }.debounce(150).map { (customers, bills, transactions) ->
        val activeBills = bills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.VOID }
            .distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }
        val validTxs = transactions.filter { !it.isReversed && it.status != "TRASH" }
            .distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else "${it.id}_${it.billId}" }
        val uniqueCustomers = customers.distinctBy { it.canonicalKey() }

        val summary = UdhariLedger.computeShopLedger(activeBills, validTxs, uniqueCustomers)
        val balances = summary.customerResults.mapValues { it.value.balance }

        UdhariLedgerState(
            isLoading = false,
            totalOutstanding = summary.totalOutstanding,
            pendingCustomersCount = summary.pendingCustomersCount,
            customerBalances = balances,
            customers = uniqueCustomers,
            activeBills = activeBills,
            validTransactions = validTxs,
            todayCollections = summary.todayCollections,
            todayCashCollections = summary.todayCashCollections,
            todayUpiCollections = summary.todayUpiCollections,
            thisMonthCollections = summary.thisMonthCollections,
            overdueAmount = summary.overdueAmount,
            pendingBillsCount = summary.pendingBillsCount,
            errorMessage = null
        )
    }.retryWhen { cause, attempt ->
        android.util.Log.e("UdhariStateProvider", "Error in state flow (attempt $attempt): ${cause.message}", cause)
        delay(minOf(1000L * (attempt + 1), 5000L))
        true
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        UdhariLedgerState(isLoading = true)
    )
}
