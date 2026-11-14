# Release Notes - v3.5.4 (Build 57)

**Release Date:** November 14, 2026  
**Package:** `com.matoshree.collection`  
**Database Version:** Room v9  
**Binary Deliverable:** `Matoshree-Collection-Latest.apk` (Signed Release APK)  
**SHA-256 Checksum:** `b679a115d988085fc501faa9c816ca0bf9d074663ae9daa17c1a67c42cb064f2`

---

## Overview

Release v3.5.4 addresses bug reports and UI/UX requests from live phone testing of v3.5.3:

1. **P0-A (Phantom Udhari & Payments from DELETED Bills):**
   - Cascaded bill voiding/trashing directly to matching `udhari_transactions` (`status = "TRASH"`).
   - In `UdhariLedger`, transactions referencing deleted/voided app bills are classified as orphans and excluded from customer balance and Khatavahi entries, leaving 0 due for customers whose bills were deleted (e.g. Swaraj Fugare).
   - Legacy generic payments (`DIRECT-PAY`, `MULTI-BILL...`) classified as GENERAL payments applied FIFO.
   - Enforced visible error rejection when entering a payment amount greater than remaining due.

2. **P0-B (Diagnostic Data-Check Exact Math & Document Breakdown):**
   - Total Firestore Document count now adds up:
     `Firestore Total (123) = Directory (84) + Legacy Duplicate Cloud Docs (39) + Archived (0) + Trash (0) + Blank Name (0) + Walk-in (0)`.
   - Displays all 123 cloud documents with docId, classification, and status.
   - Added overpaid customer breakdown with detailed explanations (e.g. Suvarna Dandage Rs 1270).
   - Added direct "हटवा" action for orphan transactions and safe repair cleanup for phantom transactions.

3. **P1-C (Customer Profile Udhari Tab Delete & Action Dialog):**
   - Manual udhari without payments offers direct "हटवा" soft-deletion to TRASH.
   - Manual udhari with payments explains that payments must be cancelled first.
   - Bill-linked udhari directs to "बिल पहा" for voiding on bill screen.
   - Orphan/ghost transactions display an explanatory banner and offer direct "हटवा" action.

4. **P1-D (Bill Cards Synchronized with Ledger):**
   - Customer profile Bills tab now computes paid and pending amounts using authoritative `UdhariLedger` breakdown, showing "पूर्ण भरणा" immediately upon udhari payment.
   - Added `recomputeStoredBillValuesFromLedger()` in `BillRepository` and `BillDao.updateBillPaymentAmounts`.

5. **P1-E (New Customer Duplication Elimination):**
   - Removed optimistic list mutation in `CustomerViewModel.saveCustomer`, relying on Room Flow as single source of truth.
   - Derived canonical `(docId, gid)` in `saveCustomer` and `createAndSelectCustomer`.
   - Removed destructive `purgeCustomerHistory` on customer revive.

6. **P2-F (Button Labels Standardized to 1-2 Words):**
   - Standardized all button labels to 1-2 concise Marathi words across all screens.
   - Enhanced `ButtonLabelLengthTest` to scan UI files to ensure no button label literals exceed 2 words.

7. **P2-G (Analytics Page Revamp & Date Range Controls):**
   - Implemented pure Kotlin `AnalyticsAggregator` domain engine.
   - Added all 11 date presets: Today, Yesterday, This week, Last week, This month, Last month, This quarter, FY (Apr-Mar), This year, Last year, Custom range.
   - Single-day Day Report view with total sales, cash, UPI, udhari collected, udhari given, net cash in hand, expenses, day profit, and bills list.
   - Compare period toggle with delta amount & delta % (green up / red down).
   - Customizable sections (toggle on/off 10 sections via dialog).
   - Drill-downs on top products (sales history dialog) and expense categories.
   - One-tap CSV export & formatted WhatsApp summary sharing.

---

## Verification & Test Results

- **Unit Tests:** 118 unit tests passed cleanly via `./scripts/verify.sh` (`testDebugUnitTest`):
  - `Round14RegressionTest` (Swaraj Fugare deleted bills phantom udhari, Savita Dandage DIRECT-PAY FIFO, overpayment rejection, ledger per-bill breakdown)
  - `AnalyticsAggregatorTest` (metrics, date presets, financial year, deltas, comparison, day reports, CSV/WhatsApp export)
  - `ButtonLabelLengthTest` (AppLabels and UI button label scanner test)
  - `Round13RegressionTest`, `LedgerConsistencyTest`, `GlobalIdV352Test`, `ShopMetricsCalculatorTest`
- **Builds:**
  - Debug APK: Built successfully
  - Release APK: Built and signed with Proguard/R8 optimization (`Matoshree-Collection-Latest.apk`)
