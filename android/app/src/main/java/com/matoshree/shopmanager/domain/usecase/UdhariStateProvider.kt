package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

data class UdhariLedgerState(
    val isLoading: Boolean = false,
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
    val pendingBillsCount: Int = 0
)

class UdhariStateProvider(
    private val customerRepository: CustomerRepository,
    private val billRepository: BillRepository,
    private val udhariRepository: UdhariRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    val state: StateFlow<UdhariLedgerState> = combine(
        customerRepository.getAllCustomers().distinctUntilChanged(),
        billRepository.getAllBills(limit = 10000).distinctUntilChanged(),
        udhariRepository.getAllTransactions().distinctUntilChanged()
    ) { customers, bills, transactions ->
        Triple(customers, bills, transactions)
    }.debounce(150).map { (customers, bills, transactions) ->
        val activeBills = bills.filter { !it.isVoided }
            .distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }
        val validTxs = transactions.filter { !it.isReversed }
            .distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else "${it.id}_${it.billId}" }
        val uniqueCustomers = customers.distinctBy { it.canonicalKey() }

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(activeBills, validTxs, uniqueCustomers)
        val totalOutstanding = balances.values.sum()
        val pendingCustomersCount = balances.count { it.value > 0.01 }
        val pendingBills = activeBills.filter { it.pendingAmount > 0.01 }

        val todayIso = DateUtils.todayDate()
        val currentMonthIso = todayIso.take(7)

        val todayPayments = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.date == todayIso }
        val todayCollections = todayPayments.sumOf { it.amount }
        val todayCashCollections = todayPayments.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
        val todayUpiCollections = todayPayments.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.amount }

        val monthPayments = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.date.startsWith(currentMonthIso) }
        val monthCollections = monthPayments.sumOf { it.amount }

        val overdueAmount = pendingBills.filter { it.dueDate != null && DateUtils.isDatePassed(it.dueDate) }.sumOf { it.pendingAmount }

        UdhariLedgerState(
            isLoading = false,
            totalOutstanding = totalOutstanding,
            pendingCustomersCount = pendingCustomersCount,
            customerBalances = balances,
            customers = uniqueCustomers,
            activeBills = activeBills,
            validTransactions = validTxs,
            todayCollections = todayCollections,
            todayCashCollections = todayCashCollections,
            todayUpiCollections = todayUpiCollections,
            thisMonthCollections = monthCollections,
            overdueAmount = overdueAmount,
            pendingBillsCount = pendingBills.size
        )
    }.catch { e ->
        android.util.Log.e("UdhariStateProvider", "Error computing state: ${e.message}", e)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        UdhariLedgerState(isLoading = false)
    )
}
