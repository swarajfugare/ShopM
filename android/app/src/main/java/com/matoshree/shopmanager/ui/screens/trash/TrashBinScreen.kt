package com.matoshree.shopmanager.ui.screens.trash

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.firebase.FirestoreTrashRepository
import com.matoshree.shopmanager.data.firebase.TrashedItem
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TrashBinViewModel(
    private val trashRepository: FirestoreTrashRepository = FirestoreTrashRepository()
) : ViewModel() {

    private val _products = MutableStateFlow<List<TrashedItem>>(emptyList())
    val products: StateFlow<List<TrashedItem>> = _products

    private val _customers = MutableStateFlow<List<TrashedItem>>(emptyList())
    val customers: StateFlow<List<TrashedItem>> = _customers

    private val _bills = MutableStateFlow<List<TrashedItem>>(emptyList())
    val bills: StateFlow<List<TrashedItem>> = _bills

    private val _payments = MutableStateFlow<List<TrashedItem>>(emptyList())
    val payments: StateFlow<List<TrashedItem>> = _payments

    val selectedTab = MutableStateFlow(0)
    val selectedDocIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        observeTrash()
        autoPurge()
    }

    private fun observeTrash() {
        viewModelScope.launch {
            trashRepository.observeTrashedProducts().collectLatest {
                _products.value = it
            }
        }
        viewModelScope.launch {
            trashRepository.observeTrashedCustomers().collectLatest {
                _customers.value = it
            }
        }
        viewModelScope.launch {
            trashRepository.observeTrashedBills().collectLatest {
                _bills.value = it
            }
        }
        viewModelScope.launch {
            trashRepository.observeTrashedPayments().collectLatest {
                _payments.value = it
            }
        }
    }

    private fun autoPurge() {
        viewModelScope.launch {
            trashRepository.autoPurgeOldTrash()
        }
    }

    fun toggleSelection(docId: String) {
        val current = selectedDocIds.value.toMutableSet()
        if (current.contains(docId)) current.remove(docId) else current.add(docId)
        selectedDocIds.value = current
    }

    fun selectAll(docIds: List<String>) {
        selectedDocIds.value = docIds.toSet()
    }

    fun clearSelection() {
        selectedDocIds.value = emptySet()
    }

    fun restoreItem(item: TrashedItem, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                trashRepository.restoreItem(item)
                onComplete()
            } catch (e: Exception) {
                Log.w("TrashViewModel", "Restore error: ${e.message}")
            }
        }
    }

    fun permanentDeleteItem(item: TrashedItem, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                trashRepository.permanentDeleteItem(item)
                onComplete()
            } catch (e: Exception) {
                Log.w("TrashViewModel", "Permanent delete error: ${e.message}")
            }
        }
    }

    fun restoreSelected(currentList: List<TrashedItem>, onComplete: (Int) -> Unit) {
        val targetDocs = selectedDocIds.value
        val itemsToRestore = currentList.filter { targetDocs.contains(it.docId) }
        viewModelScope.launch {
            var count = 0
            itemsToRestore.forEach { item ->
                try {
                    trashRepository.restoreItem(item)
                    count++
                } catch (e: Exception) {}
            }
            clearSelection()
            onComplete(count)
        }
    }

    fun deleteSelectedPermanently(currentList: List<TrashedItem>, onComplete: (Int) -> Unit) {
        val targetDocs = selectedDocIds.value
        val itemsToDelete = currentList.filter { targetDocs.contains(it.docId) }
        viewModelScope.launch {
            var count = 0
            itemsToDelete.forEach { item ->
                try {
                    trashRepository.permanentDeleteItem(item)
                    count++
                } catch (e: Exception) {}
            }
            clearSelection()
            onComplete(count)
        }
    }

    fun emptyAllTrash(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                trashRepository.emptyTrash()
                clearSelection()
                onComplete()
            } catch (e: Exception) {}
        }
    }
}

@Composable
fun TrashBinScreen(
    viewModel: TrashBinViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val products by viewModel.products.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val bills by viewModel.bills.collectAsState()
    val payments by viewModel.payments.collectAsState()

    val currentTab by viewModel.selectedTab.collectAsState()
    val selectedDocIds by viewModel.selectedDocIds.collectAsState()

    var showEmptyAllDialog by remember { mutableStateOf(false) }
    var itemToPermanentDelete by remember { mutableStateOf<TrashedItem?>(null) }
    var itemToRestore by remember { mutableStateOf<TrashedItem?>(null) }

    val currentList = when (currentTab) {
        0 -> products
        1 -> customers
        2 -> bills
        else -> payments
    }

    val tabTitles = listOf(
        "प्रॉडक्ट्स (${products.size})",
        "कस्टमर्स (${customers.size})",
        "बिले (${bills.size})",
        "पेमेंट्स (${payments.size})"
    )

    if (showEmptyAllDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyAllDialog = false },
            title = { Text("कचरा पेटी (Trash) रिकामी करायची का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = { Text("आपण कचरा पेटीतील सर्व ${products.size + customers.size + bills.size + payments.size} नोंदी कायमच्या डिलीट करू इच्छिता का? हे पूर्ववत करता येणार नाही.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyAllTrash {
                            Toast.makeText(context, "कचरा पेटी पूर्णपणे रिकामी करण्यात आली", Toast.LENGTH_SHORT).show()
                            showEmptyAllDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("सर्व काढून टाका", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyAllDialog = false }) { Text("रद्द करा", color = MutedCharcoal) }
            },
            containerColor = WarmWhite
        )
    }

    itemToPermanentDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToPermanentDelete = null },
            title = { Text("कायमचे डिलीट करायचे का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = { Text("आपण '${item.title}' कायमचे डिलीट करू इच्छिता का? ही क्रिया पूर्ववत करता येणार नाही.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentDeleteItem(item) {
                            Toast.makeText(context, "'${item.title}' कायमचे डिलीट करण्यात आले", Toast.LENGTH_SHORT).show()
                            itemToPermanentDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("कायमचे डिलीट करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToPermanentDelete = null }) { Text("रद्द करा", color = MutedCharcoal) }
            },
            containerColor = WarmWhite
        )
    }

    itemToRestore?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToRestore = null },
            title = { Text("नोंद पूर्ववत (Restore) करायची का?", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = { Text("'${item.title}' पुन्हा ॲक्टिव्ह यादीमध्ये पूर्ववत करायचे का?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreItem(item) {
                            Toast.makeText(context, "'${item.title}' पूर्ववत करण्यात आले", Toast.LENGTH_SHORT).show()
                            itemToRestore = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("पूर्ववत करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRestore = null }) { Text("रद्द करा", color = MutedCharcoal) }
            },
            containerColor = WarmWhite
        )
    }

    Scaffold(
        topBar = {
            if (selectedDocIds.isNotEmpty()) {
                Surface(
                    color = DeepEmerald,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "निवड साफ करा", tint = WarmWhite)
                            }
                            Text(
                                text = "${selectedDocIds.size} निवडले",
                                color = WarmWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (selectedDocIds.size == currentList.size) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll(currentList.map { it.docId })
                                }
                            }) {
                                Text(
                                    text = if (selectedDocIds.size == currentList.size) "निवड रद्द करा" else "सर्व निवडा",
                                    color = ChampagneGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = {
                                viewModel.restoreSelected(currentList) { count ->
                                    Toast.makeText(context, "$count नोंद(ी) पूर्ववत करण्यात आल्या", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = "निवडलेले पूर्ववत करा", tint = WarmWhite)
                            }
                            IconButton(onClick = {
                                viewModel.deleteSelectedPermanently(currentList) { count ->
                                    Toast.makeText(context, "$count नोंद(ी) कायमच्या डिलीट करण्यात आल्या", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "कायमचे डिलीट करा", tint = Color(0xFFFF8A80))
                            }
                        }
                    }
                }
            } else {
                MatoshreeTopAppBar(
                    title = "कचरा पेटी (Trash Bin)",
                    showBackButton = true,
                    onBackClick = onBackClick,
                    actions = {
                        if (products.isNotEmpty() || customers.isNotEmpty() || bills.isNotEmpty()) {
                            IconButton(onClick = { showEmptyAllDialog = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "कचरा पेटी रिकामी करा", tint = BoutiqueError)
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 7 Days Retention Notice Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = DeepEmerald.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepEmerald.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.HourglassTop, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "कचरा पेटीतील नोंदी ७ दिवसांनंतर आपोआप कायमच्या डिलीट होतात. आपण त्या कधीही पूर्ववत (Restore) करू शकता.",
                        style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Selector
            TabRow(
                selectedTabIndex = currentTab,
                containerColor = WarmWhite,
                contentColor = DeepEmerald,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = currentTab == index,
                        onClick = {
                            viewModel.selectedTab.value = index
                            viewModel.clearSelection()
                        },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (currentTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (currentTab == index) DeepEmerald else MutedCharcoal,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = OutlineVariantGrey,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "कचरा पेटीमध्ये नोंदी नाहीत",
                            style = MaterialTheme.typography.titleMedium.copy(color = MutedCharcoal, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "डिलीट केलेल्या नोंदी येथे ७ दिवसांसाठी राहतील",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(currentList, key = { item -> "trash_${item.entityType}_${item.docId}" }) { item ->
                        val isSelected = selectedDocIds.contains(item.docId)
                        MatoshreeCard(
                            onClick = {
                                if (selectedDocIds.isNotEmpty()) {
                                    viewModel.toggleSelection(item.docId)
                                }
                            },
                            onLongClick = {
                                viewModel.toggleSelection(item.docId)
                            }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selectedDocIds.isNotEmpty()) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { viewModel.toggleSelection(item.docId) },
                                                colors = CheckboxDefaults.colors(checkedColor = DeepEmerald)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }

                                        Column {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepCharcoal)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.subtitle,
                                                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                            )
                                        }
                                    }

                                    // Countdown Tag
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = BoutiqueWarning.copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BoutiqueWarning.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "⏳ ${item.daysLeft} दिवस शिल्लक",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(color = BoutiqueWarning, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.3f))
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { itemToPermanentDelete = item },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BoutiqueError),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("कायमचे डिलीट", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = { itemToRestore = item },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("पूर्ववत करा", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
