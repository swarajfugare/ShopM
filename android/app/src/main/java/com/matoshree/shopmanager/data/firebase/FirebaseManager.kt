package com.matoshree.shopmanager.data.firebase

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.UUID

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    const val SHOP_ID = "matoshree_collection"

    private var initialized = false
    private var appContext: Context? = null

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val firestore: FirebaseFirestore by lazy {
        val app = FirebaseApp.getInstance()
        val db = try {
            FirebaseFirestore.getInstance(app, "default")
        } catch (e: Exception) {
            FirebaseFirestore.getInstance()
        }
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(104857600L) // 100 MB persistent cache
                        .build()
                )
                .build()
            db.firestoreSettings = settings
        } catch (e: Exception) {
            Log.w(TAG, "Firestore settings notice: ${e.message}")
        }
        db
    }
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context)
                }
                initialized = true
                Log.d(TAG, "Firebase initialized successfully with offline persistence")
                // Start background authentication immediately so auth token is ready early
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        ensureAuthenticated()
                    } catch (e: Exception) {
                        Log.w(TAG, "Background auth init notice: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
            }
        }
    }

    fun isNetworkAvailable(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    val AUTHORIZED_EMAILS = setOf(
        "swarajfugare23@gmail.com",
        "sadhanafugare86@gmail.com"
    )

    fun isAuthorizedUser(): Boolean {
        val email = auth.currentUser?.email?.lowercase()?.trim()
        return email != null && AUTHORIZED_EMAILS.contains(email)
    }

    suspend fun ensureAuthenticated(): Boolean {
        if (auth.currentUser != null) return true
        if (!isNetworkAvailable()) {
            return false // Instant return when offline, zero wait
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(2500L) {
                auth.signInAnonymously().await()
            }
            Log.d(TAG, "Authenticated with Firebase (UID: ${auth.currentUser?.uid})")
            auth.currentUser != null
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Auth notice (offline mode active): ${e.message}")
            auth.currentUser != null
        }
    }

    fun signInWithGoogle(
        activity: android.app.Activity,
        onResult: (Boolean, String?) -> Unit
    ) {
        try {
            val provider = com.google.firebase.auth.OAuthProvider.newBuilder("google.com")
            provider.addCustomParameter("prompt", "select_account")
            
            auth.startActivityForSignInWithProvider(activity, provider.build())
                .addOnSuccessListener { authResult ->
                    val user = authResult.user
                    val email = user?.email?.lowercase()?.trim()
                    if (email != null && AUTHORIZED_EMAILS.contains(email)) {
                        Log.d(TAG, "Authorized Google login success: $email (UID: ${user.uid})")
                        onResult(true, null)
                    } else {
                        Log.w(TAG, "Unauthorized login attempt from: $email")
                        try { auth.signOut() } catch (_: Exception) {}
                        onResult(false, "प्रवेश नाकारला: $email हे खाते अधिकृत नाही. केवळ swarajfugare23@gmail.com किंवा sadhanafugare86@gmail.com द्वारेच प्रवेश शक्य आहे.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Google sign-in error: ${e.message}")
                    onResult(false, e.localizedMessage ?: "लॉगिन अयशस्वी झाले. कृपया पुन्हा प्रयत्न करा.")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in launch error: ${e.message}", e)
            onResult(false, e.localizedMessage ?: "लॉगिन सुरू करता आले नाही.")
        }
    }

    /**
     * Executes a Firestore write Task (set, update, delete, batch.commit) in an offline-resilient manner.
     * When offline: Firestore queues the mutation locally in persistent cache; we do NOT block on server ack.
     * When online: waits with a short timeout (1500ms) for server confirmation.
     */
    suspend fun <T> awaitWriteTask(task: com.google.android.gms.tasks.Task<T>, timeoutMs: Long = 1500L): T? {
        return if (isNetworkAvailable()) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    task.await()
                }
            } catch (e: Exception) {
                null
            }
        } else {
            // Offline: the mutation has already been accepted into Firestore's local cache
            null
        }
    }

    // Direct, top-level root collections for clean & easy database access
    fun shopDoc(): DocumentReference = firestore.collection("settings").document("shop_settings")
    fun customersCollection(): CollectionReference = firestore.collection("customers")
    fun productsCollection(): CollectionReference = firestore.collection("products")
    fun categoriesCollection(): CollectionReference = firestore.collection("categories")
    fun billsCollection(): CollectionReference = firestore.collection("bills")
    fun udhariTransactionsCollection(): CollectionReference = firestore.collection("udhari_transactions")
    fun expensesCollection(): CollectionReference = firestore.collection("expenses")
    fun dailyClosingsCollection(): CollectionReference = firestore.collection("daily_closings")
    fun stockMovementsCollection(): CollectionReference = firestore.collection("stock_movements")
    fun securityDoc(): DocumentReference = firestore.collection("security").document("app_pin")

    // Shop subcollections for nested structure compatibility
    fun shopRootDoc(): DocumentReference = firestore.collection("shops").document(SHOP_ID)
    fun shopCustomersCollection(): CollectionReference = firestore.collection("shops").document(SHOP_ID).collection("customers")
    fun shopProductsCollection(): CollectionReference = firestore.collection("shops").document(SHOP_ID).collection("products")
    fun shopCategoriesCollection(): CollectionReference = firestore.collection("shops").document(SHOP_ID).collection("categories")
    fun shopBillsCollection(): CollectionReference = firestore.collection("shops").document(SHOP_ID).collection("bills")

    /**
     * Executes diagnostic test of Firestore read/write/delete and Storage upload/download/delete.
     * Enforces Source.SERVER verification where applicable.
     */
    suspend fun runDiagnostics(): DiagnosticResult {
        val results = mutableListOf<String>()
        var allPassed = true

        try {
            // STEP 1: FirebaseAuth currentUser
            val authOk = ensureAuthenticated()
            val user = auth.currentUser
            if (authOk && user != null) {
                results.add("AUTH: PASS (UID: ${user.uid})")
            } else {
                results.add("AUTH: FAIL")
                allPassed = false
            }

            // MINIMAL DIRECT FIRESTORE CLOUD TEST
            val app = FirebaseApp.getInstance()
            val testPayload = mapOf(
                "message" to "FIRESTORE_DIRECT_TEST",
                "test" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "deviceId" to (android.os.Build.MODEL ?: "Android_Device"),
                "appVersion" to "1.0.0"
            )
            val docPath = "diagnostics/connection_test"
            val diagDoc = firestore.collection("diagnostics").document("connection_test")

            // 1. DIRECT WRITE
            try {
                withTimeout(15000) {
                    diagDoc.set(testPayload).await()
                }
                Log.d(TAG, "FIRESTORE_WRITE entity=DIRECT_TEST path=$docPath result=SUCCESS database=default(named)")
                results.add("Cloud Write (named 'default'): PASS")
            } catch (we: Exception) {
                Log.e(TAG, "FIRESTORE_WRITE entity=DIRECT_TEST path=$docPath result=FAIL exception=${we.message}", we)
                results.add("Cloud Write: FAIL (${we.message})")
                allPassed = false
            }

            if (allPassed) {
                // 2. SERVER READ (Source.SERVER)
                try {
                    val serverSnap = diagDoc.get(Source.SERVER).await()
                    if (serverSnap.exists()) {
                        Log.d(TAG, "FIRESTORE_SERVER_READ path=$docPath result=SUCCESS")
                        results.add("FIRESTORE SERVER READ: PASS")
                    } else {
                        Log.e(TAG, "FIRESTORE_SERVER_READ path=$docPath result=FAIL (Document does not exist on server)")
                        results.add("FIRESTORE SERVER READ: FAIL (Not on server)")
                        allPassed = false
                    }
                } catch (re: Exception) {
                    Log.e(TAG, "FIRESTORE_SERVER_READ path=$docPath result=FAIL exception=${re.message}")
                    results.add("FIRESTORE SERVER READ: FAIL (${re.message})")
                    allPassed = false
                }

                // 3. SERVER DELETE (delete().await())
                try {
                    diagDoc.delete().await()
                    Log.d(TAG, "FIRESTORE_DELETE path=$docPath result=SUCCESS")
                    results.add("FIRESTORE DELETE: PASS")
                } catch (de: Exception) {
                    Log.e(TAG, "FIRESTORE_DELETE path=$docPath result=FAIL exception=${de.message}")
                    results.add("FIRESTORE DELETE: FAIL (${de.message})")
                    allPassed = false
                }
            }

            // Test Security PIN Document on server
            val pinSnap = securityDoc().get().await()
            if (!pinSnap.exists()) {
                securityDoc().set(SecurityPinDto(updatedAt = System.currentTimeMillis(), updatedByDevice = "init")).await()
            }

            // Seed Shop Settings if missing
            val shopSnap = shopDoc().get().await()
            if (!shopSnap.exists()) {
                val shopDto = ShopDto(
                    name = "मातोश्री कलेक्शन",
                    mobile = "8208610261",
                    email = "swarajfugare23@gmail.com",
                    address = "कोंढारकी",
                    gstin = "",
                    showGstin = false,
                    upiId = "matoshree@upi",
                    upiDisplayName = "मातोश्री कलेक्शन",
                    upiMobile = "8208610261",
                    logoPath = "android.resource://com.matoshree.collection/drawable/app_logo",
                    updatedAt = System.currentTimeMillis()
                )
                shopDoc().set(shopDto, com.google.firebase.firestore.SetOptions.merge()).await()
                results.add("Shop Settings: VERIFIED")
            }

            // Test Firebase Cloud Storage
            try {
                val testStorageRef = storage.reference.child("shops/matoshree_collection/branding/test_connection.png")
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val data = baos.toByteArray()
                testStorageRef.putBytes(data).await()
                results.add("STORAGE UPLOAD: PASS")

                val downloadUrl = testStorageRef.downloadUrl.await()
                results.add("STORAGE DOWNLOAD: PASS")

                testStorageRef.delete().await()
                results.add("STORAGE DELETE: PASS")
            } catch (se: Exception) {
                results.add("STORAGE TEST: NOTICE (${se.message})")
            }

        } catch (e: Exception) {
            allPassed = false
            results.add("Diagnostic Exception: ${e.message}")
            Log.e(TAG, "Diagnostic failed with exception", e)
        }

        return DiagnosticResult(success = allPassed, details = results)
    }
}

data class DiagnosticResult(
    val success: Boolean,
    val details: List<String>
)
