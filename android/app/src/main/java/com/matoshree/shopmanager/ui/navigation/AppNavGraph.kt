package com.matoshree.shopmanager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.repository.*
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.ui.screens.analytics.AnalyticsScreen
import com.matoshree.shopmanager.ui.screens.analytics.AnalyticsViewModel
import com.matoshree.shopmanager.ui.screens.bill.BillPreviewScreen
import com.matoshree.shopmanager.ui.screens.bill.BillViewModel
import com.matoshree.shopmanager.ui.screens.bill.BillsHistoryScreen
import com.matoshree.shopmanager.ui.screens.closing.DailyClosingScreen
import com.matoshree.shopmanager.ui.screens.closing.DailyClosingViewModel
import com.matoshree.shopmanager.ui.screens.customer.CustomerProfileScreen
import com.matoshree.shopmanager.ui.screens.customer.CustomerViewModel
import com.matoshree.shopmanager.ui.screens.customer.CustomersListScreen
import com.matoshree.shopmanager.ui.screens.dashboard.DashboardScreen
import com.matoshree.shopmanager.ui.screens.dashboard.DashboardViewModel
import com.matoshree.shopmanager.ui.screens.expense.ExpenseViewModel
import com.matoshree.shopmanager.ui.screens.expense.ExpensesListScreen
import com.matoshree.shopmanager.ui.screens.lock.AppLockScreen
import com.matoshree.shopmanager.ui.screens.product.ProductViewModel
import com.matoshree.shopmanager.ui.screens.product.ProductsListScreen
import com.matoshree.shopmanager.ui.screens.sale.*
import com.matoshree.shopmanager.ui.screens.settings.SettingsScreen
import com.matoshree.shopmanager.ui.screens.settings.SettingsViewModel
import com.matoshree.shopmanager.ui.screens.trash.TrashBinScreen
import com.matoshree.shopmanager.ui.screens.trash.TrashBinViewModel
import com.matoshree.shopmanager.ui.theme.*
import kotlinx.coroutines.launch

data class BottomNavItem(
    val title: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun AppNavGraph(
    navController: NavHostController,
    database: AppDatabase,
    pinManager: PinManager,
    syncManager: com.matoshree.shopmanager.sync.SyncManager? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val billRepository = remember { BillRepository(database) }
    val customerRepository = remember { CustomerRepository(database) }
    val productRepository = remember { ProductRepository(database) }
    val expenseRepository = remember { ExpenseRepository(database) }
    val dailyClosingRepository = remember { DailyClosingRepository(database) }
    val syncRepository = remember { SyncRepository(database) }
    val settingsDao = remember { database.settingsDao() }
    val udhariRepository = remember { UdhariRepository(syncManager = syncManager) }

    val dashboardViewModel = remember { DashboardViewModel(billRepository, udhariRepository, productRepository) }
    val saleViewModel = remember { SaleViewModel(billRepository, customerRepository, productRepository) }

    // Lazy on-demand ViewModels: Prevent 8 heavy ViewModels from initializing simultaneously on unlock,
    // using Kotlin lazy delegates inside remember so no snapshot state mutations occur during composition.
    val lazyProductViewModel = remember { lazy { ProductViewModel(productRepository) } }
    fun getProductViewModel(): ProductViewModel = lazyProductViewModel.value

    val lazyBillViewModel = remember { lazy { BillViewModel(billRepository, settingsDao, udhariRepository) } }
    fun getBillViewModel(): BillViewModel = lazyBillViewModel.value

    val lazyCustomerViewModel = remember { lazy { CustomerViewModel(customerRepository, billRepository, udhariRepository) } }
    fun getCustomerViewModel(): CustomerViewModel = lazyCustomerViewModel.value

    val lazyUdhariViewModel = remember { lazy { com.matoshree.shopmanager.ui.screens.udhari.UdhariViewModel(udhariRepository, customerRepository, billRepository) } }
    fun getUdhariViewModel(): com.matoshree.shopmanager.ui.screens.udhari.UdhariViewModel = lazyUdhariViewModel.value

    val lazyClosingViewModel = remember { lazy { DailyClosingViewModel(billRepository, dailyClosingRepository, udhariRepository, expenseRepository) } }
    fun getClosingViewModel(): DailyClosingViewModel = lazyClosingViewModel.value

    val lazyAnalyticsViewModel = remember { lazy { AnalyticsViewModel(billRepository, customerRepository, productRepository, expenseRepository, dailyClosingRepository) } }
    fun getAnalyticsViewModel(): AnalyticsViewModel = lazyAnalyticsViewModel.value

    val lazyInventoryViewModel = remember { lazy { com.matoshree.shopmanager.ui.screens.inventory.InventoryViewModel(productRepository) } }
    fun getInventoryViewModel(): com.matoshree.shopmanager.ui.screens.inventory.InventoryViewModel = lazyInventoryViewModel.value

    val lazyExpenseViewModel = remember { lazy { ExpenseViewModel(expenseRepository) } }
    fun getExpenseViewModel(): ExpenseViewModel = lazyExpenseViewModel.value

    val lazySettingsViewModel = remember { lazy { SettingsViewModel(syncRepository, pinManager, settingsDao) } }
    fun getSettingsViewModel(): SettingsViewModel = lazySettingsViewModel.value

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(
        BottomNavItem("मुख्य", Screen.Dashboard.route, Icons.Filled.Home, Icons.Outlined.Home),
        BottomNavItem("प्रॉडक्ट्स", Screen.Products.route, Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
        BottomNavItem("कस्टमर्स", Screen.Customers.route, Icons.Filled.Group, Icons.Outlined.Group),
        BottomNavItem("ॲनालिटिक्स", Screen.Analytics.route, Icons.Filled.Leaderboard, Icons.Outlined.Leaderboard),
        BottomNavItem("सेटिंग", Screen.Settings.route, Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }
    val coroutineScope = rememberCoroutineScope()
    var showAddProductSheet by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = WarmWhite,
                    contentColor = DeepEmerald
                ) {
                    bottomNavItems.forEach { item ->
                        val isSelected = currentRoute == item.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Screen.Dashboard.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.title,
                                    tint = if (isSelected) DeepEmerald else MutedCharcoal
                                )
                            },
                            label = {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isSelected) DeepEmerald else MutedCharcoal,
                                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = ChampagneGoldContainer.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(Screen.AppLock.route) {
                AppLockScreen(
                    onUnlockSuccess = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.AppLock.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    syncManager = syncManager,
                    onNewSaleClick = {
                        saleViewModel.reset()
                        navController.navigate(Screen.NewSale.route)
                    },
                    onQuickSaleClick = {
                        saleViewModel.reset()
                        navController.navigate(Screen.QuickSale.route)
                    },
                    onBillClick = { billId ->
                        navController.navigate(Screen.BillPreview.createRoute(billId))
                    },
                    onViewAllBillsClick = {
                        navController.navigate(Screen.BillsHistory.route)
                    },
                    onProductsClick = {
                        navController.navigate(Screen.Products.route)
                    },
                    onExpensesClick = {
                        navController.navigate(Screen.Expenses.route)
                    },
                    onClosingClick = {
                        navController.navigate(Screen.DailyClosing.route)
                    },
                    onUdhariClick = {
                        navController.navigate(Screen.Udhari.route)
                    },
                    onInventoryClick = {
                        navController.navigate(Screen.Inventory.route)
                    }
                )
            }

            composable(Screen.Udhari.route) {
                com.matoshree.shopmanager.ui.screens.udhari.UdhariScreen(
                    viewModel = getUdhariViewModel(),
                    onCustomerClick = { custId ->
                        navController.navigate(Screen.CustomerProfile.createRoute(custId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Products.route) {
                ProductsListScreen(
                    viewModel = getProductViewModel(),
                    onNavigateToLabels = { navController.navigate(Screen.ProductLabels.route) },
                    onNavigateToInventory = { navController.navigate(Screen.Inventory.route) },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Inventory.route) {
                com.matoshree.shopmanager.ui.screens.inventory.InventoryScreen(
                    viewModel = getInventoryViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.ProductLabels.route) {
                com.matoshree.shopmanager.ui.screens.product.ProductLabelsScreen(
                    viewModel = remember { com.matoshree.shopmanager.ui.screens.product.ProductLabelsViewModel(productRepository) },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Expenses.route) {
                ExpensesListScreen(
                    viewModel = getExpenseViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.DailyClosing.route) {
                DailyClosingScreen(
                    viewModel = getClosingViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.NewSale.route) {
                val prodVm = getProductViewModel()
                val products by prodVm.products.collectAsState()
                val categories by prodVm.categories.collectAsState()

                NewSaleScreen(
                    viewModel = saleViewModel,
                    products = products,
                    onAddProductClick = { showAddProductSheet = true },
                    onCheckoutClick = {
                        navController.navigate(Screen.SaleSummary.route)
                    },
                    onBackClick = { navController.popBackStack() }
                )

                if (showAddProductSheet) {
                    AddProductSheet(
                        products = products,
                        categories = categories,
                        onProductSelected = { prod, qty, disc ->
                            saleViewModel.addItemToCart(prod, qty, disc)
                            showAddProductSheet = false
                        },
                        onAddNewProduct = { newProd ->
                            coroutineScope.launch {
                                try {
                                    val id = productRepository.saveProduct(newProd)
                                    saleViewModel.addItemToCart(newProd.copy(id = id), 1)
                                    showAddProductSheet = false
                                } catch (e: Exception) {
                                    android.util.Log.e("AppNavGraph", "Error adding product to cart: ${e.message}", e)
                                }
                            }
                        },
                        onDismiss = { showAddProductSheet = false }
                    )
                }
            }

            composable(Screen.QuickSale.route) {
                QuickSaleScreen(
                    viewModel = saleViewModel,
                    onCompleteClick = {
                        coroutineScope.launch {
                            try {
                                val bill = saleViewModel.completeSale(com.matoshree.shopmanager.domain.model.SaleType.QUICK)
                                saleViewModel.reset()
                                navController.navigate(Screen.BillPreview.createRoute(bill.id)) {
                                    popUpTo(Screen.Dashboard.route)
                                }
                                // Asynchronously dispatch automatic WhatsApp bill in background without delaying navigation
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendBillAutoWhatsApp(context, bill)
                                    } catch (we: Exception) {
                                        android.util.Log.w("AppNavGraph", "Background WhatsApp dispatch note: ${we.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("AppNavGraph", "Error completing quick sale: ${e.message}", e)
                            }
                        }
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.SaleSummary.route) {
                SaleSummaryScreen(
                    viewModel = saleViewModel,
                    onCompleteSale = {
                        coroutineScope.launch {
                            try {
                                val chosenTemplateType = saleViewModel.selectedWhatsAppTemplateType.value
                                val shouldSendWa = saleViewModel.sendWhatsAppOnComplete.value
                                val bill = saleViewModel.completeSale(com.matoshree.shopmanager.domain.model.SaleType.DETAILED)
                                saleViewModel.reset()
                                navController.navigate(Screen.BillPreview.createRoute(bill.id)) {
                                    popUpTo(Screen.Dashboard.route)
                                }
                                if (shouldSendWa) {
                                    // Asynchronously dispatch automatic WhatsApp bill in background without delaying navigation
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        try {
                                            com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendBillAutoWhatsApp(
                                                context = context,
                                                bill = bill,
                                                templateTypeOverride = chosenTemplateType
                                            )
                                        } catch (we: Exception) {
                                            android.util.Log.w("AppNavGraph", "Background WhatsApp dispatch note: ${we.message}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("AppNavGraph", "Error completing detailed sale: ${e.message}", e)
                            }
                        }
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.BillPreview.route,
                arguments = listOf(navArgument("billId") { type = NavType.LongType })
            ) { backStackEntry ->
                val billId = backStackEntry.arguments?.getLong("billId") ?: 0L
                BillPreviewScreen(
                    billId = billId,
                    viewModel = getBillViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.BillsHistory.route) {
                BillsHistoryScreen(
                    viewModel = getBillViewModel(),
                    onBillClick = { billId ->
                        navController.navigate(Screen.BillPreview.createRoute(billId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Customers.route) {
                CustomersListScreen(
                    viewModel = getCustomerViewModel(),
                    onCustomerClick = { custId ->
                        navController.navigate(Screen.CustomerProfile.createRoute(custId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.CustomerProfile.route,
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { backStackEntry ->
                val customerId = backStackEntry.arguments?.getLong("customerId") ?: 0L
                CustomerProfileScreen(
                    customerId = customerId,
                    viewModel = getCustomerViewModel(),
                    onBillClick = { billId ->
                        navController.navigate(Screen.BillPreview.createRoute(billId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Analytics.route) {
                AnalyticsScreen(
                    viewModel = getAnalyticsViewModel(),
                    onCustomerClick = { custId ->
                        navController.navigate(Screen.CustomerProfile.createRoute(custId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = getSettingsViewModel(),
                    syncManager = syncManager,
                    onNavigateToTrash = {
                        navController.navigate(Screen.TrashBin.route)
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.TrashBin.route) {
                TrashBinScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
