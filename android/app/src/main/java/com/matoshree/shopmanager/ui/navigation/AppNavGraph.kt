package com.matoshree.shopmanager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import com.matoshree.shopmanager.data.remote.ApiClient
import com.matoshree.shopmanager.data.repository.*
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.ui.screens.analytics.AnalyticsScreen
import com.matoshree.shopmanager.ui.screens.analytics.AnalyticsViewModel
import com.matoshree.shopmanager.ui.screens.bill.BillPreviewScreen
import com.matoshree.shopmanager.ui.screens.bill.BillViewModel
import com.matoshree.shopmanager.ui.screens.bill.BillsHistoryScreen
import com.matoshree.shopmanager.ui.screens.closing.DailyClosingScreen
import com.matoshree.shopmanager.ui.screens.closing.DailyClosingViewModel
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
    pinManager: PinManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apiService = remember { ApiClient.create(context) }

    val billRepository = remember { BillRepository(database, apiService) }
    val customerRepository = remember { CustomerRepository(database) }
    val productRepository = remember { ProductRepository(database) }
    val expenseRepository = remember { ExpenseRepository(database) }
    val dailyClosingRepository = remember { DailyClosingRepository(database) }
    val syncRepository = remember { SyncRepository(database, apiService) }

    val dashboardViewModel = remember { DashboardViewModel(billRepository) }
    val saleViewModel = remember { SaleViewModel(billRepository, customerRepository, productRepository) }
    val billViewModel = remember { BillViewModel(billRepository) }
    val customerViewModel = remember { CustomerViewModel(customerRepository) }
    val productViewModel = remember { ProductViewModel(productRepository) }
    val expenseViewModel = remember { ExpenseViewModel(expenseRepository) }
    val closingViewModel = remember { DailyClosingViewModel(billRepository, dailyClosingRepository) }
    val analyticsViewModel = remember { AnalyticsViewModel(billRepository) }
    val settingsViewModel = remember { SettingsViewModel(syncRepository, pinManager) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(
        BottomNavItem("Home", Screen.Dashboard.route, Icons.Filled.Home, Icons.Outlined.Home),
        BottomNavItem("Bills", Screen.BillsHistory.route, Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
        BottomNavItem("Customers", Screen.Customers.route, Icons.Filled.Group, Icons.Outlined.Group),
        BottomNavItem("Analytics", Screen.Analytics.route, Icons.Filled.Leaderboard, Icons.Outlined.Leaderboard),
        BottomNavItem("Settings", Screen.Settings.route, Icons.Filled.Settings, Icons.Outlined.Settings)
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
            startDestination = Screen.AppLock.route,
            modifier = Modifier.padding(padding)
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
                    }
                )
            }

            composable(Screen.NewSale.route) {
                val products by productViewModel.products.collectAsState()
                NewSaleScreen(
                    viewModel = saleViewModel,
                    onAddProductClick = { showAddProductSheet = true },
                    onCheckoutClick = {
                        navController.navigate(Screen.SaleSummary.route)
                    },
                    onBackClick = { navController.popBackStack() }
                )

                if (showAddProductSheet) {
                    AddProductSheet(
                        products = products,
                        onProductSelected = { prod, qty, disc ->
                            saleViewModel.addItemToCart(prod, qty, disc)
                            showAddProductSheet = false
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
                            val bill = saleViewModel.completeSale(com.matoshree.shopmanager.domain.model.SaleType.QUICK)
                            navController.navigate(Screen.BillPreview.createRoute(bill.id)) {
                                popUpTo(Screen.Dashboard.route)
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
                            val bill = saleViewModel.completeSale(com.matoshree.shopmanager.domain.model.SaleType.DETAILED)
                            navController.navigate(Screen.BillPreview.createRoute(bill.id)) {
                                popUpTo(Screen.Dashboard.route)
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
                    viewModel = billViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.BillsHistory.route) {
                BillsHistoryScreen(
                    viewModel = billViewModel,
                    onBillClick = { billId ->
                        navController.navigate(Screen.BillPreview.createRoute(billId))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Customers.route) {
                CustomersListScreen(
                    viewModel = customerViewModel,
                    onCustomerClick = { custId ->
                        // Can show customer purchase profile
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Analytics.route) {
                AnalyticsScreen(
                    viewModel = analyticsViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
