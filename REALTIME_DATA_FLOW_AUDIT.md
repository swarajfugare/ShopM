# MATOSHREE COLLECTION — REAL-TIME DATA PERSISTENCE AUDIT

**Date:** 30 August 2026  
**Audited Subsystems:** Android UI, ViewModels, Repositories, Room Database, Retrofit Client, Hostinger REST API, MySQL Database

---

## 1. Trace of Current Implementation (Where Flow Stopped)

| Data Flow | Trigger Point | ViewModel | Repository | Room Local DB | Immediate API Attempt | Backend Controller | Hostinger MySQL | Status / Failure Point |
|---|---|---|---|---|---|---|---|---|
| **A. Customer Creation** | `+ Add Customer` dialog in Sale & Customer screens | `CustomerViewModel.saveCustomer()` | `CustomerRepository.saveCustomer()` | **YES** (`CustomerEntity` inserted) | **NO** (Stopped at Room) | `customersController.create` existed but wasn't invoked immediately | **NO** (Did not reach MySQL immediately) | **FAILED (Room-Only by default)** |
| **B. Product Creation** | `+ Add Product` in Sale & Product screens | `ProductViewModel.saveProduct()` | `ProductRepository.saveProduct()` | **YES** (`ProductEntity` inserted) | **NO** (Stopped at Room) | `productsController.create` existed but wasn't invoked immediately | **NO** (Did not reach MySQL immediately) | **FAILED (Room-Only by default)** |
| **C. Detailed Sale** | Checkout -> `Complete & Generate Bill` | `SaleViewModel.completeSale(DETAILED)` | `BillRepository.createSale()` | **YES** (`BillEntity` + `BillItemEntity` inserted) | **NO** (Enqueued to `SyncQueue`, waited for background Worker) | `salesController.create` | **DELAYED** (Only reached MySQL on manual/periodic sync) | **DELAYED SYNC** |
| **D. Quick Sale** | Dashboard Bolt -> `Save Sale` | `SaleViewModel.completeSale(QUICK)` | `BillRepository.createSale()` | **YES** (`BillEntity` inserted) | **NO** (Enqueued to `SyncQueue`, waited for background Worker) | `salesController.create` | **DELAYED** (Only reached MySQL on manual/periodic sync) | **DELAYED SYNC** |
| **E. Payments** | Bill completion | Embedded in `createSale` | `BillRepository.createSale()` | **YES** (`paymentMethod` in `BillEntity`) | **NO** | `payments` table insertion was only handled in batch sync | **DELAYED** | **DELAYED SYNC** |
| **F. Category Creation** | Product bottom sheet | `ProductViewModel.saveCategory()` | `ProductRepository.saveCategory()` | **YES** (`CategoryEntity` inserted) | **NO** (Route `POST /categories` was missing in Express) | Missing `POST /categories` route in `api.js` | **NO** | **FAILED (Missing API Route)** |
| **G. Settings** | Shop Details & UPI Edit dialogs | `SettingsViewModel.saveShopSettings()` | `SettingsDao.set()` | **YES** (Key-value in `SettingsEntity`) | **NO** (Stopped at Room) | `settingsController.updateShop` existed but was not called from UI | **NO** | **FAILED (Room-Only)** |

---

## 2. Root Causes Identified

1. **Passive Offline-Only Enqueue Pattern in Repositories**:
   - `CustomerRepository`, `ProductRepository`, `BillRepository`, and `ExpenseRepository` did not inject `MatoshreeApiService` or attempt immediate online network persistence when connected. They wrote exclusively to Room SQLite and enqueued items to `SyncQueueDao` to await the next WorkManager cycle.
2. **Missing `POST /categories` Express Route**:
   - `src/routes/api.js` only defined `GET /categories` but omitted `POST /categories`, preventing category synchronization.
3. **Customer Phone Normalization & Duplicate Handling**:
   - `customersController.create` lacked 10-digit mobile number normalization (`+91 98765 43210` vs `9876543210`), resulting in duplicate customer creation risks.
4. **Missing Immediate Online Fallback Policy**:
   - When network is online, all create operations should attempt REST API immediately, obtain server IDs, and update Room entities as `SYNCED`. WorkManager should strictly serve as the offline/retry queue.

---

## 3. Real-Time Online Persistence Strategy

```
[User Action in Android UI]
          ↓
[ViewModel -> Repository]
          ↓
    [Is Online?]
     /        \
 (YES)        (NO)
  /              \
[Attempt REST API]  [Save Room with PENDING]
   /        \                   ↓
(200 OK)  (Error)      [Enqueue to SyncQueue]
  /            \                ↓
[Update Room] [Save PENDING] [WorkManager SyncWorker]
[Set SYNCED]  [Enqueue Queue]
```
