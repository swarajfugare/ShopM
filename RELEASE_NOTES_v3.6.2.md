# Release Notes - v3.6.2 (Build 60)

**Release Date:** 2026-11-15  
**Package:** `com.matoshree.collection`  
**Version:** `3.6.2` (Build `60`)  
**SHA-256 (Matoshree-Collection-Latest.apk):**  
`b5275c3a3cc80bb8dd5e287f0f77347ae22607cc07f9c5f80e1707db15805ed2`

---

## 🚀 What Changed in v3.6.2

This release delivers the Round 17 "Payment Integrity Fortification" updates to guarantee accurate payment allocation, persistent payment cancellation/reversal, optimized Firestore querying, resilient Room offline customer trash/restore, protection against premature orphan flagging, and strict 2-word UI action labels.

### 1. [P1] Payment Customer & Bill Matching Isolation
- **Problem Solved:** When recording a payment, customer lookup could accidentally match another customer with the same name.
- **Fortification:**
  - Customer selection and bill matching now strictly use `customerGid`, document ID, legacy ID, or exact 10-digit mobile number.
  - Name-based matching has been completely removed from payment and bill allocation paths.
  - Added strict ambiguity detection: if more than one customer matches the identifier, the operation rejects the ambiguous input with a clear error rather than guessing.

### 2. [P2] Clean Bill References in Payments, Cancellation & Reversal Persistence
- **Problem Solved:** Settlement payments sometimes stored formatted strings like `"MC-1 (₹200)"`, causing ledger allocation issues and preventing cancellation ("रद्द करा") from cleanly clearing the payment or restoring the bill's pending balance.
- **Fortification:**
  - Payment records now save the clean, exact `billNumber` (e.g., `"MC-1"`), exact `billGid`, `kind = "SETTLEMENT"`, and a common `transactionUuid` for grouped multi-bill allocations.
  - Backward compatibility: `UdhariLedger` handles both clean and legacy bill number formats seamlessly.
  - Payment reversal ("रद्द करा") atomically marks all transactions in the payment group as reversed, restores the associated bills' `paidAmount` and `pendingAmount`, and updates the customer's cached due balance with verified server acknowledgement (`awaitWriteTask`).

### 3. [P3] Firestore Query Optimization During Payment Recording
- **Problem Solved:** Payment recording executed full `.get()` scans on entire customer and bill collections, slowing down payment writes and increasing Firestore read quota usage.
- **Fortification:**
  - Replaced full collection reads with targeted queries matching only the specific customer's document IDs (`cust_m_*`, `cust_*`), customer mobile, and specific bill IDs.
  - Vastly reduces network latency and avoids unnecessary Firestore read operations.

### 4. [P4] Offline Customer Soft-Delete & Trash Restoration in Room
- **Problem Solved:** Trashing or restoring a customer in offline mode or before full cloud sync could leave Room local SQLite database out of sync with Firestore.
- **Fortification:**
  - Implemented Room-level soft-delete and cascading for bills (`voidReason = "CUSTOMER_DELETE"`) with stock restoration.
  - Customer restore from Trash (`restoreCustomerFromTrash`) unarchives the customer in Room, un-voids cascaded bills, and re-deducts product stock locally in Room, ensuring 100% offline consistency.

### 5. [P5] Protection Against Premature Transaction Orphaning
- **Problem Solved:** If bills were still loading or partially synced, ledger computation could prematurely flag valid payments or credits as orphans and remove them from active balance calculations.
- **Fortification:**
  - `UdhariLedger.computeCustomerLedger` now requires explicit confirmation of bill deletion (`isBillConfirmedDeleted` or `confirmedDeletedBillNumbers`) before classifying transactions as orphans.
  - Unconfirmed missing bills remain in active balance calculations and are flagged for review (`needsReview = true`), preventing accidental balance drops during background sync.

### 6. [P6] Button, Chip, and Action Labels Length Enforcement
- **Problem Solved:** Some action buttons exceeded the strict 2-word limit (e.g., void bill confirmation had 4 words).
- **Fortification:**
  - Shortened void bill confirmation button label to 2 words in both Marathi and English.
  - Verified 100% string key parity between `values/strings.xml` and `values-en/strings.xml`.
  - All button, tab, and chip labels are strictly 1 or 2 words.

---

## 🔄 Rollback Procedure

If needed, the shop owner can safely roll back to v3.6.1:
1. Uninstall v3.6.2 from the device.
2. Install the previous release APK `v3.6.1` from GitHub Releases.
3. Login with the authorized Google Account and enter the 4-digit PIN.
4. All data in Firestore will sync down seamlessly without data loss.

---

## ⚠️ Verification & Quality Assurance
- Total unit tests executed: **143 / 143 passed**.
- Full test suites: `LedgerConsistencyTest`, `UdhariLedgerConsistencyTest`, `GlobalIdV352Test`, `Round13RegressionTest`, `Round14RegressionTest`, `Round15RegressionTest`, `Round16RegressionTest`, `Round17P1PaymentCustomerIsolationTest`, `Round17P2PaymentReversalPersistenceTest`, `Round17P3PaymentQueryOptimizationTest`, `Round17P4CustomerOfflineRoomRestoreTest`, `Round17P5OrphanClassificationTest`, `ButtonLabelLengthTest`.
- Signed APK: `Matoshree-Collection-Latest.apk` tested and ready for production deployment.
