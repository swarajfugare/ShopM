# Release Notes - v3.5.3 (Build 56)

**Release Date:** November 14, 2026  
**Package:** `com.matoshree.collection`  
**Database Version:** Room v9  
**Binary Deliverable:** `Matoshree-Collection-Latest.apk` (Signed Release APK)  
**SHA-256 Checksum:** `89b14530675f786eb693418cc63a2abff47a66bbab5dc9b11e86d782f2aef680`

---

## Overview

Release v3.5.3 delivers critical stability fixes, customer reconciliation, and usability features requested from live device testing of v3.5.2:
1. **P0-A (Missing Customers Reconciliation):** Automatic cloud-to-Room customer reconciliation on startup and snapshot streams. Removed destructive deduplication deletes. Implemented pre-migration SQLite physical database backups before Room initialization.
2. **P0-B (New Customer Duplication & Search):** Unified customer `(docId, gid)` calculation using `IdGenerator.canonicalCustomerDocIdAndGid()`. Customer search reads directly from Room's single source of truth with `.distinctBy { it.gid }`.
3. **P0-C (Doubled / Foreign Payments & Calculation Breakdown):** Fixed checkout payment double-counting. Foreign payments referencing unknown bills (e.g., `MC-2026-97860`) are isolated into `orphanPayments` rather than corrupting balances via FIFO advances. Added detailed calculation breakdown in customer profile.
4. **P1-D (Consistent Bill Counts):** Removed stale denormalized `customer.totalBills` counters from directory cards; UI displays live counts computed from active non-void bills.
5. **P1-E (Payment Reversal & Audit Trail):** Added working "रद्द करा" payment cancellation with `rev_` reversal records, `isReversed` marking, and ledger exclusion.
6. **P1-F (Udhari Details & Actions Dialog):** Tapping "तपशील व कृती" on any udhari row opens an action sheet with "बिल पहा", "भरणा जमा", "बदला", and "हटवा".
7. **P1-G (Discard Bill Confirmation):** Added back press interceptor on `NewSaleScreen` with "बिल रद्द करायचे?" confirmation dialog.
8. **P2-H (Concise 1-2 Word Button Labels):** Standardized button and chip text across screens to concise 1-2 word Marathi labels defined in `Labels.kt`.
9. **P2-I (Configurable Screenshot Permission):** Added "स्क्रीनशॉट परवानगी" toggle in Settings -> Security, defaulting to allowed for standard screens while enforcing `FLAG_SECURE` on `AppLockScreen`.

---

## Detailed Root Causes & Fixes

### P0-A: Cloud Customer Reconciliation & Safe Persistence
- **Root Cause:** A discrepancy between directory count (82) and diagnostic tool (121) occurred because inactive or newly synced remote customer records were either un-reconciled locally or merged out by local deduplication logic.
- **Fix:**
  - Removed `deduplicateCustomerRows()` and any destructive SQL deletions.
  - Implemented `RealtimeSyncManager.reconcileCustomersFromCloud()`, triggered on app launch to ensure all active cloud customer documents are upserted into Room.
  - Added physical SQLite database backup (`filesDir/db_pre_migration_backups`) before Room opens.
  - Enhanced Settings -> Customer Data Check to display "Directory N / Firestore M / Hidden K" breakdown with reasons and missing customer names.

### P0-B: Canonical Identity & Local Search Dedup
- **Root Cause:** When creating a customer, Room and Firestore derived slightly different document and GID formats before sync echoed back, causing a temporary double listing. Customer search also queried Firestore remotely, introducing race conditions.
- **Fix:**
  - Introduced `IdGenerator.canonicalCustomerDocIdAndGid(mobile, docId, gid)` used identically across Room and Firestore repositories.
  - Updated `CustomerRepository.searchCustomers` to query Room only as the authoritative local cache, deduplicated by GID.

### P0-C: Foreign / Orphan Payment Isolation & Breakdown
- **Root Cause:** Payments referencing bills belonging to other customers or unknown bill numbers (such as `MC-2026-97860`) were absorbed by FIFO logic as advance customer payments, reducing credit balances erroneously. In addition, checkout advance payments could be double-counted.
- **Fix:**
  - Isolated foreign payments referencing unknown bills into `orphanPayments`. They are excluded from the customer's ledger balance and flagged in diagnostics.
  - Added `CustomerCalculationBreakdown` in `CustomerLedgerResult` and displayed it in Customer Profile -> Payments tab.

### P1-D: Bill Count Normalization
- **Root Cause:** Customer directory displayed `customer.totalBills`, which was a legacy denormalized column that got out of sync with actual bill records.
- **Fix:** Directory cards and customer profile calculate bill counts directly from active non-void bills.

### P1-E: Payment Cancellation ("रद्द करा")
- **Root Cause:** Cancel payment buttons had no underlying reversal transaction logic.
- **Fix:**
  - Carried `txGid`, `originalTxId`, and `isCancellable` in `CustomerPaymentRecord`.
  - Added `reversePayment()` in `UdhariRepository` and `FirestoreUdhariRepository`: generates `rev_` transaction record, marks original transaction as `isReversed = true`, and directly updates bill paid amounts and customer balances without scanning entire collections.

### P1-F: Udhari Action BottomSheet
- **Root Cause:** Clicking "तपशील व कृती" on udhari rows did not trigger an action sheet.
- **Fix:**
  - Implemented `selectedLedgerEntryForAction` dialog in customer profile for every udhari row.
  - Provides quick actions: "बिल पहा" (for bill-linked rows), "भरणा जमा" (prefilling remaining amount), "बदला" (for manual entries), and "हटवा" (soft-delete to TRASH).

### P1-G: Discard Bill Confirmation
- **Root Cause:** Pressing back while preparing a bill immediately navigated away without saving or warning.
- **Fix:** Added `BackHandler` and top app bar interceptor to display "बिल रद्द करायचे?" confirmation with "रद्द करा" and "चालू ठेवा".

### P2-H: Short Button Labels (1-2 Words)
- **Fix:** Centralized standard Marathi UI labels in `Labels.kt` and validated with `ButtonLabelLengthTest` to ensure no labels exceed 2 words.

### P2-I: Screenshot Toggle
- **Fix:** Added `isScreenshotAllowed` and `setScreenshotAllowed` in `AppLockManager` with Settings UI toggle. `AppLockScreen` maintains `FLAG_SECURE` strictly.

---

## Verification & Test Results

- **Unit Tests:** All unit test suites passed cleanly via `./scripts/verify.sh` (`testDebugUnitTest`):
  - `Round13RegressionTest` (P0-A, P0-B, P0-C, P1-D, P1-E, P1-F, P1-G, P2-I regression assertions)
  - `ButtonLabelLengthTest` (1-2 word label assertions)
  - `GlobalIdV352Test`, `GlobalIdRepairAndOverpaymentTest`, `LedgerConsistencyTest`, `UdhariLedgerTest`, `ShopMetricsCalculatorTest`
- **Builds:**
  - Debug APK: Built successfully
  - Release APK: Built and signed with Proguard/R8 optimization (`Matoshree-Collection-Latest.apk`)
