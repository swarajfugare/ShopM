# MATOSHREE COLLECTION — COMPLETE DATA FLOW TRACE

This document traces the complete end-to-end data flow for sales, synchronization, and persistence between **Android (Jetpack Compose + Room)**, **Hostinger Node.js REST API**, and **Hostinger MySQL Database**.

---

## 1. End-to-End Data Flow Architecture

```mermaid
sequenceDiagram
    autonumber
    actor Cashier as Boutique Cashier (Android)
    participant UI as Compose UI (SaleScreens)
    participant VM as SaleViewModel
    participant UC as CalculateProfitUseCase
    participant Repo as BillRepository
    participant Room as Room SQLite Database
    participant Queue as Room SyncQueueDao
    participant Worker as WorkManager (SyncWorker)
    participant Retrofit as Retrofit / OkHttpClient
    participant API as Hostinger Express API
    participant Controller as syncController / salesController
    participant MySQL as Hostinger MySQL Database

    %% Step 1: Sale Creation in Android
    Cashier->>UI: Selects Items / Flat Amount & Discount -> Taps "Generate Bill"
    UI->>VM: completeSale(detailed/quick)
    VM->>UC: calculateProfit(items, sellingPrice, costPrice, margin=25%)
    UC-->>VM: Financials (subtotal, finalAmount, cost, profit)
    VM->>Repo: createSale(saleType, customer, items, discount, paymentMethod, snapshots)
    
    %% Step 2: Room Local Persistence
    Repo->>Room: INSERT INTO bills + bill_items (SyncStatus=PENDING)
    Repo->>Queue: INSERT INTO sync_queue (txUuid, "SALE", "CREATE", payloadJson)
    Room-->>Repo: localBillId
    Repo-->>VM: Bill Domain Object
    VM-->>UI: Display BillPreviewScreen (Instant offline receipt)

    %% Step 3: WorkManager Background Synchronization
    Note over Worker: Triggered automatically or via Periodic/One-time Sync
    Worker->>Queue: getPendingItems()
    Queue-->>Worker: List<SyncQueueEntity>
    Worker->>Retrofit: syncBatch(BatchSyncRequest)
    Retrofit->>API: HTTPS POST /api/v1/sync (Bearer JWT)
    
    %% Step 4: Hostinger Backend & MySQL Transaction
    API->>Controller: syncController.syncBatch(req, res)
    Controller->>MySQL: START TRANSACTION
    Controller->>MySQL: SELECT id FROM bills WHERE transaction_uuid = ? (Idempotency Check)
    alt New Transaction
        Controller->>MySQL: INSERT INTO bills (shop_id, bill_number, transaction_uuid, final_amount, snapshots...)
        Controller->>MySQL: INSERT INTO payments (bill_id, payment_method, amount, status='PAID')
        Controller->>MySQL: INSERT INTO sync_logs (device_id, entity_type='SALE', status='SUCCESS')
        Controller->>MySQL: UPDATE customers SET total_bills += 1, lifetime_spend += ?
        Controller->>MySQL: COMMIT
        MySQL-->>Controller: insertId, affectedRows
        Controller-->>API: 200 OK (status='SUCCESS', server_id=insertId)
    else Duplicate Transaction
        Controller->>MySQL: ROLLBACK
        Controller-->>API: 200 OK (status='DUPLICATE', server_id=existingId)
    end

    %% Step 5: Android Synchronization Confirmation
    API-->>Retrofit: HTTP 200 JSON Response (BatchSyncResponseData)
    Retrofit-->>Worker: Response<ApiResponse<BatchSyncResponseData>>
    Worker->>Room: UPDATE bills SET sync_status='SYNCED', server_id=? WHERE transaction_uuid=?
    Worker->>Queue: DELETE FROM sync_queue WHERE transaction_uuid=?
    Worker-->>UI: "All data synchronized with Hostinger MySQL" (Green indicator)
```

---

## 2. Detailed Step-by-Step Breakdown

### Step 1 — UI & ViewModel Execution
- **Trigger**: Cashier inputs items or quick sale amount, selects discount (Flat ₹ / Percentage %), picks payment method (Cash / UPI / Other), and taps **"Complete & Generate Bill"**.
- **Action**: `SaleViewModel.completeSale()` gathers active cart state and invokes `BillRepository.createSale()`.

### Step 2 — Offline-First Room Storage & Snapshot Capture
- **Repository Execution**:
  1. Generates unique `transactionUuid` (`UUID.randomUUID()`).
  2. Generates sequential boutique invoice number (e.g., `MC-2026-000001`).
  3. Captures current shop branding snapshots (`shop_name_snapshot`, `shop_address_snapshot`, `shop_mobile_snapshot`, `shop_gstin_snapshot`, `show_gstin_snapshot`) from `SettingsDao`.
  4. Inserts `BillEntity` and `BillItemEntity` rows atomically in local SQLite via Room (`SyncStatus.PENDING`).
  5. Inserts payload into `sync_queue` table with status `PENDING`.
  6. Returns `Bill` domain model immediately so the cashier sees the receipt without waiting for network.

### Step 3 — Background Synchronization Engine (`SyncWorker`)
- **Execution**:
  1. `SyncWorker` is triggered by Android `WorkManager` (periodic every 15 mins or manual "Sync Now").
  2. Queries `sync_queue` for un-synced items (`getPendingItems()`).
  3. Verifies JWT token from `TokenStorage`. If token is absent or expired, automatically invokes `POST /api/v1/auth/login` to obtain a fresh token.
  4. Dispatches `POST /api/v1/sync` payload with `BatchSyncRequest` via Retrofit over TLS/HTTPS.

### Step 4 — Hostinger Backend Processing & MySQL Transaction
- **Endpoint**: `POST /api/v1/sync`
- **Controller Logic**:
  1. `syncController.syncBatch` parses incoming `sync_items`.
  2. Opens dedicated MySQL connection and executes `connection.beginTransaction()`.
  3. **Idempotency Guard**: Queries `SELECT id, bill_number FROM bills WHERE transaction_uuid = ?`. If found, rolls back and returns status `DUPLICATE` with existing `server_id`.
  4. If new, inserts into `bills`, `bill_items`, and `payments` tables with accurate decimal monetary amounts and snapshot fields.
  5. Updates customer loyalty records (`total_bills`, `lifetime_spend`, `last_purchase_at`).
  6. Inserts log record into `sync_logs`.
  7. Executes `connection.commit()`.
  8. Returns HTTP 200 response with array of results containing `server_id` and `status`.

### Step 5 — Local State Reconciliation & UI Update
- **Android Receipt**:
  1. For each `SUCCESS` or `DUPLICATE` result, `SyncRepository` deletes the item from `sync_queue`.
  2. Updates `BillEntity.syncStatus = SYNCED` and `BillEntity.serverId = res.server_id` in Room.
  3. Flow updates `pendingSyncCount`, transitioning the Settings UI to display `"All data synchronized with Hostinger MySQL"`.
