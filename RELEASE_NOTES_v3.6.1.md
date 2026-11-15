# Release Notes - v3.6.1 (Build 59)

**Release Date:** 2026-11-15  
**Package:** `com.matoshree.collection`  
**Version:** `3.6.1` (Build `59`)  
**SHA-256 (Matoshree-Collection-Latest.apk):**  
`0ef9d79eb5bf65cbd1835a0b695d0ba78e7ff3147530b2b823d775b3e58d99da`

---

## 🚀 What Changed in v3.6.1

This release addresses and resolves the specific production observations reported during testing of v3.6.0 on live shop devices, strictly maintaining data integrity, zero amount alterations, and backward compatibility.

### 1. [Q1] Customer Same-Name Deletion Isolation & Soft-Delete Cascade
- **Bug Fixed:** Deleting one customer was erroneously selecting other customers who had the same name but different phone numbers.
- **Fix:** Customer lookup and deletion now strictly match by `gid`, `docId`, exact 10-digit mobile, or `legacyId`. Name-based matching has been removed.
- **Soft-Delete Cascade:** When a customer is moved to Trash, their associated bills and udhari records are marked as `status = "TRASH"` with `cascadedBy = "CUSTOMER_DELETE"` and `linkedCustomerGid = <gid>` instead of being hard-deleted. Restoring the customer from Trash immediately reactivates these records and re-deducts the stock that was restored during trash, keeping stock accurate.

### 2. [Q2 & Q3] Payment Reversal Persistence & Stored Bill Balance Synchronization
- **Bug Fixed:** Reversing a bill payment ("रद्द करा") did not persist correctly, and the Home screen bill item displayed stale balance numbers.
- **Fix:** 
  - `FirestoreUdhariRepository.reversePayment` now extracts clean bill numbers (e.g., stripping notes like `(₹200)` and handling multi-bill allocations) and searches both `bills` and `shopBills` collections.
  - Stored bill `paidAmount` and `pendingAmount` in Room and Firestore are atomically updated upon payment recording and reversal via `recomputeStoredBillValuesFromLedger`.
  - `awaitWriteTask` now rethrows Firestore exceptions to guarantee failed network writes are never silently swallowed.

### 3. [Q4] Manual Old Udhari Trash Restoration
- **Bug Fixed:** Restoring a manual "old udhari" entry from Trash failed to reflect back in the customer's active profile and due calculation.
- **Fix:** 
  - Restoring a `PAYMENT` or `PURCHASE_CREDIT` with `kind = "MANUAL"` clears `deletedAt`, resets `status = "ACTIVE"` and `isDeleted = false`, keeping `kind = "MANUAL"` and note intact.
  - Trashed manual udhari items display a descriptive title format (`जुनी उधारी - <नाव> - ₹<रक्कम>`) in Trash Bin.

### 4. [Q5] Expense Management: Soft-Delete to Trash & Editing
- **Feature Added:** Expenses can now be edited and soft-deleted directly from `ExpensesListScreen`.
- **Trash Integration:** Deleted expenses are sent to Trash with a 7-day auto-purge countdown and can be restored at any time. Active expense totals in Home, Analytics, and Daily Closing automatically update upon deletion or restoration.

### 5. [Q6] Product Selling & Cost Price Editing
- **Feature Added:** Selling price and cost price can now be updated directly from the product details screen via the "बदला" (Edit) dialog.
- **Historical Immutability:** Existing and past bills remain completely unchanged (their bill items store snapshot prices at time of creation). Only new bills created after the change use the updated prices.

### 6. [Q7] Marathi / English Dynamic Localization
- **UI Localization:** All 24 user-visible literals in the Dashboard and Bottom Navigation Bar have been moved to `values/strings.xml` and `values-en/strings.xml`.
- **Immediate Application:** Toggling language in Settings -> Language immediately reloads the activity, rendering all strings in the selected language.
- **Concise Labels:** Every button, tab, and chip label adheres strictly to a maximum of 2 words in both languages.

---

## 🔄 Rollback Procedure

If needed, the shop owner can roll back to v3.6.0:
1. Uninstall v3.6.1 from the device.
2. Install the previous release APK `v3.6.0` from GitHub Releases.
3. Login with the authorized Google Account and enter the 4-digit PIN.
4. All data in Firestore will sync down seamlessly as the Room schema version and Firestore document schemas are 100% backward-compatible.

---

## ⚠️ Known Limitations
- WhatsApp messages continue to be sent strictly through user action or automated background webhook triggers; test suites never transmit real WhatsApp traffic.
- Full UI string localization has been completed for Dashboard and Navigation; remaining screens will continue gradual translation in subsequent micro-updates.
