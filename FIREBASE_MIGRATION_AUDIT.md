# MATOSHREE COLLECTION — FIREBASE MIGRATION AUDIT REPORT

**Date**: August 30, 2026  
**Target Architecture**: Android ↔ Firebase Authentication ↔ Cloud Firestore ↔ Firebase Cloud Storage  
**Primary Objective**: Replace fragile custom delta polling sync and Hostinger MySQL REST bridge with native Google Cloud Firestore Realtime Listeners & Offline Persistence.

---

## 1. Project & Build Configuration

| Component | Current State | Firebase Migration Plan |
|---|---|---|
| **Root Gradle** | `android/build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28) | Add `com.google.gms.google-services` plugin (v4.4.2) |
| **Version Catalog** | `gradle/libs.versions.toml` | Add `firebaseBom = "33.7.0"`, `googleServices = "4.4.2"`, Firebase Auth, Firestore KTX, Storage KTX |
| **App Gradle** | `android/app/build.gradle.kts` (`applicationId = "com.matoshree.shopmanager"`) | Apply `com.google.gms.google-services` plugin. Update `applicationId = "com.matoshree.collection"` to match `google-services.json`. Import `platform(libs.firebase.bom)`, `firebase-auth-ktx`, `firebase-firestore-ktx`, `firebase-storage-ktx`. |
| **`google-services.json`** | Verified at `android/app/google-services.json` | Project ID: `matoshree-collection`, Client Package: `com.matoshree.collection`. Ready for direct initialization. |

---

## 2. Room & Local Database Audit

| Entity / Table | Current Purpose | Firebase Migration Action |
|---|---|---|
| `CustomerEntity` (`customers`) | Local Room cache + offline queue | Replace as primary store. Firestore `shops/matoshree_collection/customers/{id}` is authoritative. Room retained only as UI cache if needed. |
| `ProductEntity` (`products`) | Local Room cache | Firestore `shops/matoshree_collection/products/{id}` is authoritative. |
| `CategoryEntity` (`categories`) | Local Room cache | Firestore `shops/matoshree_collection/categories/{id}` is authoritative. |
| `BillEntity` / `BillItemEntity` | Local Room sales records | Firestore `shops/matoshree_collection/bills/{id}` + subcollections/embedded items. Atomic batched writes. |
| `PaymentEntity` | Local Room payment ledger | Firestore `shops/matoshree_collection/bills/{id}/payments`. |
| `ExpenseEntity` | Local Room expenses | Firestore `shops/matoshree_collection/expenses/{id}`. |
| `DailyClosingEntity` | Local Room daily ledger | Firestore `shops/matoshree_collection/dailyClosings/{date}`. |
| `SettingsEntity` (`app_settings`) | Room Key-Value store | Firestore `shops/matoshree_collection` document fields (`name`, `address`, `mobile`, `upiId`, `logoPath`, etc.). |
| `SyncQueueEntity` (`sync_queue`) | Custom offline upload queue | **DEPRECATE**. Firestore Android SDK native offline persistent disk cache (`persistentDiskCacheSettings`) handles offline queuing and auto-resumption automatically. |
| Hardcoded Mock Seeding in `MatoshreeApp` | Injected 4 mock customers & 5 products into Room on empty DB | **REMOVE**. Fresh devices will hydrate authentic live records directly from Cloud Firestore snapshot listeners. |

---

## 3. Repositories & Data Flow Audit

| Repository | Current Implementation | Firebase Architecture Target |
|---|---|---|
| `BillRepository` | Room DAO insert + immediate Hostinger Retrofit API + SyncQueue fallback | Writes directly to Firestore `shops/{shopId}/bills` using atomic batch/transaction. Firestore offline persistence guarantees delivery. Realtime listener emits to Compose UI. |
| `CustomerRepository` | Room DAO insert + Retrofit API + SyncQueue fallback | Writes to Firestore `shops/{shopId}/customers`. Realtime snapshot listener emits `Flow<List<Customer>>`. Archiving updates `status = "ARCHIVED"`. |
| `ProductRepository` | Room DAO insert + Retrofit API + SyncQueue fallback | Writes to Firestore `shops/{shopId}/products`. Realtime snapshot listener emits `Flow<List<Product>>`. Archiving updates `status = "ARCHIVED"`. |
| `SettingsRepository` / `SettingsDao` | Local Room Key-Value + Hostinger `PUT /settings` | Reads/writes authoritative document `shops/{shopId}` in Firestore. Emits realtime `StateFlow<ShopSettingsSnapshot>`. |
| `ExpenseRepository` | Room DAO + Retrofit | Writes to Firestore `shops/{shopId}/expenses`. Realtime listener. |
| `DailyClosingRepository`| Room DAO | Writes to Firestore `shops/{shopId}/dailyClosings/{date}`. |

---

## 4. Authentication, Security & PIN Audit

| Feature | Current Implementation | Firebase Migration Design |
|---|---|---|
| **Shop Account** | Single boutique shop (`shopId = "matoshree_collection"`) | Firebase Auth (Anonymous / Email-Password / Custom Token). Authenticated user binds to `shops/matoshree_collection`. |
| **Account PIN** | MySQL `users.pin` + BCrypt / Shared PIN | Account-level PIN stored in Firestore `shops/matoshree_collection/security/pin` (salted SHA-256 hash). Changing PIN on Phone A updates Firestore document; Phone B listener validates against new hash instantly. Master recovery secret `MATOSHREE2026`. |
| **Biometrics** | `BiometricPrompt` + EncryptedSharedPreferences | Device-local biometric authentication retained for hardware unlock. |

---

## 5. Storage & Logo Audit

| Asset | Current Implementation | Firebase Storage Architecture |
|---|---|---|
| **Shop Logo** | Base64 in MySQL `shops.logo_data` + local `filesDir/shop_logo.png` | Uploaded to Firebase Storage at `shops/matoshree_collection/branding/logo.png`. Download URL / Storage reference + `logoVersion` stored in Firestore shop document. |
| **Multi-Device Logo Sync** | Polled Base64 string | Phone B listener receives updated `logoPath`/`logoVersion`, downloads image from Firebase Storage, and caches locally. If local cache deleted, re-downloads from Firebase Storage seamlessly. |

---

## 6. Deprecation & Cleanup Plan

1. **Remove Custom Polling & WorkManager Sync**:
   - `SyncManager.kt`, `SyncWorker`, `SyncEngine.kt`, `GET /api/v1/sync/changes` custom cursor polling can be safely retired once Firestore realtime listeners are established.
2. **Decouple Hostinger REST API from Critical Path**:
   - Android will no longer communicate with PHP/Node REST endpoints for shared business data.
   - Hostinger MySQL serves as archival data source. A one-time migration script imports historical records into Cloud Firestore.
