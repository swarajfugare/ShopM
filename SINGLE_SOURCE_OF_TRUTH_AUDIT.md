# MATOSHREE COLLECTION — SINGLE SOURCE OF TRUTH & MULTI-DEVICE DATA CONSISTENCY AUDIT

**Audit Date**: August 30, 2026  
**Shop Account**: Matoshree Collection (Shop ID = 1)  
**Authoritative Backend**: Hostinger MySQL (`https://blueviolet-ibis-158713.hostingersite.com`)

---

## 1. Executive Summary & Root Cause Analysis

An exhaustive audit of the Android client (Room, SharedPreferences, Files, Repositories, ViewModels) and Hostinger Backend (`index.php`, Node.js Express controllers, MySQL schema) identified the following data consistency failure points:

### Root Cause Analysis of Reported Symptoms:

1. **Delete Then Reappears Bug**:
   - **Root Cause**: When a user deleted or archived a customer/product on Android, `CustomerDao.archiveCustomer(id)` only updated local Room (`isActive = 0`), but did not call a server `ARCHIVE` API endpoint or write to the server before sync. On the next delta sync cycle (3 seconds later), the server (which still had the entity as active) sent it back, and Room upserted it back as active.
   - **Fix**: Online delete/archive must immediately call `POST /api/v1/customers/:id/archive` or `POST /api/v1/products/:id/archive` on MySQL, update `is_active = 0`, and write an `ARCHIVE` record into `sync_changes`. The delta sync payload must return `is_active = 0`, causing all client devices to update Room to inactive.

2. **Shop Logo Local-Only Storage**:
   - **Root Cause**: Logo was saved to `context.filesDir/shop_logo.png` or only locally in SharedPreferences, without committing base64 data to `shops.logo_data` / `shops.logo_url` in MySQL.
   - **Fix**: Upload logo via `PUT /api/v1/settings` (as base64 or URL) to MySQL `shops` table. Delta sync syncs `settings.logo_data` to all devices, and Room saves it into `app_settings` (`shop_logo_data`).

3. **Shop Info & UPI ID Inconsistency**:
   - **Root Cause**: Settings updates on Android were saving to Room `SettingsDao` without guaranteeing an online `PUT /api/v1/settings` call, and delta sync was not reactively updating `SettingsViewModel` flows across devices.
   - **Fix**: Server-first write for settings with fallback queue; `SyncManager` applies settings delta to Room `SettingsDao`, and `SettingsViewModel` exposes reactive flows.

4. **PIN Local-Only Hash**:
   - **Root Cause**: `PinManager.kt` stored salted SHA-256 hash strictly inside device-local `SharedPreferences` (`matoshree_security_prefs`). Changing PIN on Phone A did not update MySQL `users.pin` / `users.pin_hash`, so Phone B never knew about the change.
   - **Fix**: Add server endpoints `POST /api/v1/auth/pin/change` and `POST /api/v1/auth/pin/recover`. On login or delta sync, sync server PIN hash to local secure cache.

5. **Sync Capsule Placement in Header**:
   - **Root Cause**: Sync badge was placed inside `DashboardTopAppBar`, cluttering the header.
   - **Fix**: Remove top capsule from header, maintain clean boutique title "Matoshree Collection", and place complete synchronization monitoring and controls inside `SettingsScreen` under "Data & Synchronization".

---

## 2. Entity-by-Entity Data Flow & Consistency Matrix

| Entity | Android Local (Room/Cache) | Authoritative Server (MySQL) | Write Flow (Online) | Write Flow (Offline) | Delete / Archive Behavior | Sync & Conflict Rule |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Users & PIN** | `TokenStorage` + `PinManager` secure cache | `users` (`id`, `mobile`, `pin_hash`, `role`) | `POST /auth/pin/change` -> MySQL -> Room cache | Offline PIN uses cached hash; syncs upon reconnect | N/A (User accounts are permanent) | Server PIN hash is single source of truth |
| **Shops & Settings** | `app_settings` (Room) | `shops` (`name`, `address`, `mobile`, `gst_number`, `show_gstin`, `upi_id`, `upi_display_name`, `logo_data`, `default_profit_margin`) | `PUT /api/v1/settings` -> MySQL -> Room `app_settings` | Room `app_settings` + `sync_queue` -> SyncWorker -> MySQL | N/A | Server last-write wins |
| **Customers** | `customers` (Room) with `isActive`, `serverId`, `syncStatus` | `customers` (`id`, `shop_id`, `name`, `mobile`, `email`, `address`, `total_bills`, `lifetime_spend`, `is_active`) | `POST /api/v1/customers` -> MySQL -> Room `insertCustomer` | Room `PENDING` -> SyncWorker -> MySQL | `POST /customers/:id/archive` -> MySQL `is_active = 0` -> Room `isActive = 0` | Server `is_active` authoritative; never resurrects |
| **Products** | `products` (Room) with `isActive`, `serverId`, `syncStatus` | `products` (`id`, `shop_id`, `category_id`, `name`, `sku`, `selling_price`, `cost_price`, `is_active`) | `POST /api/v1/products` -> MySQL -> Room `insertProduct` | Room `PENDING` -> SyncWorker -> MySQL | `POST /products/:id/archive` -> MySQL `is_active = 0` -> Room `isActive = 0` | Server `is_active` authoritative; never resurrects |
| **Categories** | `categories` (Room) with `isActive`, `serverId` | `categories` (`id`, `shop_id`, `name`, `description`, `is_active`) | `POST /api/v1/categories` -> MySQL -> Room | Room `PENDING` -> SyncWorker -> MySQL | `POST /categories/:id/archive` -> MySQL `is_active = 0` | Server last-write wins |
| **Bills & Items** | `bills` + `bill_items` (Room) with `isVoided`, `syncStatus` | `bills` + `bill_items` (`transaction_uuid`, `is_voided`, `void_reason`, `payment_status`) | `POST /api/v1/sales` -> MySQL -> Room atomic full bill | Room `PENDING` -> SyncWorker -> MySQL | `POST /bills/:id/void` -> MySQL `is_voided = 1` -> Room `isVoided = true` | Bills are immutable except VOID; voided bills never revert |
| **Payments** | Embedded in Bill / Payment DTO | `payments` (`bill_id`, `amount`, `payment_method`, `payment_status`) | Created with Bill in atomic MySQL transaction | Created with Bill on Sync | VOID cascades payment status to 'VOID' | Linked 1:1 with Bill transaction |
| **Expenses** | `expenses` (Room) with `syncStatus` | `expenses` (`id`, `shop_id`, `category`, `amount`, `payment_method`, `expense_date`) | `POST /api/v1/expenses` -> MySQL -> Room | Room `PENDING` -> SyncWorker -> MySQL | `DELETE /expenses/:id` -> MySQL delete -> Room delete | Server last-write wins |
| **Daily Closings** | `daily_closings` (Room) | `daily_closings` (`id`, `closing_date`, `total_sales`, `is_closed`) | `POST /api/v1/daily-closing` -> MySQL -> Room | Room `PENDING` -> SyncWorker -> MySQL | N/A | Immutable per calendar date |

---

## 3. Data Ownership Classification

### A. Shared Server Data (Authoritative in Hostinger MySQL):
1. `users` (Account, Role, Salted PIN Hash)
2. `shops` (Shop details, UPI details, GSTIN, Logo Base64/URL)
3. `customers` (Profiles, Contact, Lifetime Spend, Total Bills, Active State)
4. `categories` (Product Categories)
5. `products` (Catalog, Pricing, Cost, Inventory, Active State)
6. `bills` & `bill_items` (Financial transactions, Snapshots, Void status)
7. `payments` (Payment records, Modes, Status)
8. `expenses` (Daily boutique operational expenses)
9. `daily_closings` (Register cash reconciliation)
10. `sync_changes` (Monotonic version change log)

### B. Device-Local Data (Android Local Only):
1. `device_id` (`PHONE-XXXX` stored in `SharedPreferences`)
2. `last_sync_cursor` (Local change pointer)
3. `sync_queue` (Temporary offline write buffer)
4. `biometric_enabled` (Device hardware biometric preference)
5. Temporary UI navigation state & cached Compose memory

---

## 4. Implementation Action Plan

1. **Backend**:
   - Add `POST /api/v1/auth/pin/change` and `POST /api/v1/auth/pin/recover` in `index.php` and `apiControllers.js`.
   - Add `POST /api/v1/customers/:id/archive` and `POST /api/v1/products/:id/archive`.
   - Update `PUT /api/v1/settings` to accept `logo_data` and save to `shops.logo_data`.
   - Update `sync_changes` logging to capture `ARCHIVE`, `VOID`, `SETTINGS`, and `PIN_CHANGE` operations with `change_version`.
   - Update `GET /api/v1/sync/changes` to return `is_active` for customers and products so Phone B archives them immediately without resurrection.
2. **Android Data Layer**:
   - Update `MatoshreeApiService.kt` with PIN change, customer archive, product archive, and settings endpoints.
   - Update `CustomerRepository`, `ProductRepository`, `BillRepository`, and `SettingsRepository` for Server-First execution with local cache update.
   - Update `SyncManager` to process `is_active = 0` / `is_voided = 1` during delta sync.
   - Update `PinManager` to synchronize PIN hash with server.
3. **Android UI**:
   - Clean up top app bar in `DashboardScreen.kt` (remove sync capsule, show clean title).
   - Add **Data & Synchronization** card in `SettingsScreen.kt` with live connection/sync state, counts, and Sync Now action.
4. **Verification**:
   - Full automated simulation of multi-device sync, archive, void, settings, logo, and PIN.
