# Release Notes - Matoshree Collection v3.5.1 (Build 54)

## Highlights & Critical Fixes

### 1. Fix G1 Symptom 1: Customer Duplication in Directory & Search
- **Root Cause**: In v3.5.0, GIDs were derived using conflicting seeds (`mob_<mobile>` in Room vs Firestore document ID in Cloud). As a result, the Room row and Cloud row had different GIDs, causing directory search deduplication (`distinctBy { canonicalKey() }`) to treat them as two separate customers.
- **Fix (F1)**: All customer GIDs are now strictly derived from the permanent Firestore document ID (`IdGenerator.customerGid(docId)` = `cus_L<base32(docId)>`). Both local Room and Cloud Firestore compute the exact same GID offline without network coordination.

### 2. Fix G1 Symptom 2: Customers Showing Udhari When They Owe Nothing
- **Root Cause**: Mismatched `customerGid` on bills/transactions created orphaned "ghost customer" keys. Payments recorded with customer mobile or local ID were not resolving to the ghost bill key, leaving bills unpaid on ghost keys.
- **Fix (F1 & F3)**: Added a robust hierarchical resolution chain in `CustomerKeyResolver`:
  1. Match by customer `gid`
  2. Match by clean 10-digit mobile number
  3. Match by unique trimmed customer name
  4. Match by legacy local `customerId` / alias mappings
  All payments and bills now resolve to the canonical customer record.

### 3. Fix G1 Symptom 3: Missing Manual "Old Udhari" (BillId = 0)
- **Root Cause**: Manual credits had `customerGid = cus_Lcust_<id>`, which didn't match the customer's phone-derived GID (`cus_Lmob_<phone>`), hiding the entry on a disconnected key.
- **Fix (F1 & F3)**: Re-linked all legacy `cust_<id>` aliases to the customer's canonical GID. Manual credits (`PURCHASE_CREDIT`, `billId = 0`) now always appear on the customer's khate ledger and profile.

### 4. Overpayment Rejection (Owner's New Rule - F4)
- Any payment where `amount > currentDue` or `amount <= 0` is strictly rejected across:
  - Customer Profile payment dialog
  - Udhari / Khate page quick payment
  - Bill screen "Pay Due"
  - `FirestoreUdhariRepository.recordPayment` & Room/Cloud transactions
- Clear bilingual error displayed: `"भरणा रक्कम बाकीपेक्षा जास्त आहे. बाकी: ₹X (Payment exceeds due)"`
- Payments are never automatically converted into negative balances or advances. Existing legacy overpaid customers are surfaced in "Customer data check" under Settings for owner review without data loss.

### 5. Automated Data Repair (F2) & Room v8 Migration (`MIGRATION_7_8`)
- Added `docId` column to Room tables (`customers`, `bills`, `bill_items`, `products`, `categories`, `expenses`, `daily_closings`).
- `MIGRATION_7_8` safely upgrades existing v3.5.0 (v7) installations in-place, rewriting GIDs to deterministic docId-based GIDs, resolving collisions, and re-linking references.
- Cloud repair job synchronizes Firestore in batches $\le 400$ writes with kill switch support (`settings/migration.enabled`).

---

## Verification & Rollback Procedure

### In-Place Upgrade
1. Install `Matoshree-Collection-Latest.apk` (v3.5.1, Build 54) directly over v3.5.0 or v3.4.12.
2. The Room v8 migration and local GID repair run automatically at startup.
3. Total udhari, bill count, product stock, and expense totals remain 100% identical.

### Rollback Procedure
- Before the repair runs, `DataMigrationManager` generates a complete pre-migration JSON snapshot at:
  `/data/data/com.matoshree.collection/files/pre_v3_5_migration_backup/backup_<timestamp>.json`
- In the event of any rollback, this JSON snapshot can be restored through the diagnostic tool.

---

## Artifact Checksums (SHA-256)
- **Release APK (`Matoshree-Collection-Latest.apk`)**:
  `c673a6d5efe06f26d8edbf81f8f7e408c52f45737aa4779316d1be7bc7ebe905`
- **Debug APK (`app-debug.apk`)**:
  `0321ce7e188b7dd76f16bf81b863194d1c1a49392ca150fbdbdc4ed70e19dfc5`
