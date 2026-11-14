# Release Notes - v3.5.2 (Build 55)

**Release Date:** November 14, 2026  
**Package:** `com.matoshree.collection`  
**Database Schema:** Room v9 (from v8)  
**Binary Deliverable:** `Matoshree-Collection-Latest.apk` (Signed Release APK)

---

## Overview

Release v3.5.2 addresses customer identity issues reported after the v3.5.1 deployment:
1. **Directory Count Mismatch (172 vs 121):** Resolved duplicate customer rows caused by divergent docId generation between Room and Firestore.
2. **Wrong Customer Profile Opening:** Replaced colliding numeric ID navigation with permanent, globally unique string GIDs (`customerGid`).
3. **Cross-Customer Bill/Payment Contamination:** Restricted ledger association strictly to canonical customer GIDs and normalized 10-digit mobile numbers, eliminating local ID collision contamination.
4. **Room v9 Database Migration:** Added `customer_aliases` table to map deprecated GIDs to canonical GIDs and preserve full ledger history.

---

## Detailed Root Causes & Fixes

### 1. Unified Canonical DocId & Single Source of Truth (K1, K2)
- **Root Cause:** In Firestore, customer documents were keyed by `cust_m_<mobile>` (e.g. `cust_m_9822112233`). In Room v8, `docId` was synthesized as `'cust_' || serverId` (e.g. `cust_42`). When both rows were loaded, `CustomerRepository.getAllCustomers()` merged them by GID into a map, creating duplicate rows (121 unique customers + 51 duplicates = 172 rows).
- **Fix (K1):** For all customers with a valid 10-digit mobile number, `docId` is consistently computed as `cust_m_<cleanMobile>`. Both Room and Firestore derive the exact same canonical GID: `cus_Lcust_m_<cleanMobile>`.
- **Fix (K2):** Room is established as the single source of truth for directory listing. `CustomerRepository.getAllCustomers()` streams directly from Room with `.distinctBy { it.gid }`. Directory count and diagnostic count now match.

### 2. GID-Based Profile Navigation & Selection (K3)
- **Root Cause:** `CustomerProfile` route used `customerId: Long`. When cloud customers had numeric IDs generated from `hash(docId)` and local Room customers had auto-increment IDs `1, 2, 3...`, ID collisions occurred. Calling `customers.find { it.id == customerId }` returned the wrong customer.
- **Fix (K3):** Screen navigation, profile loading, customer multi-selection, and list tagging now operate on `customerGid: String`. Tapping any customer card opens that specific customer's profile.

### 3. Isolated Udhari Ledger Attribution (K4)
- **Root Cause:** `CustomerKeyResolver` contained fallback mappings `aliasToCanonical["id_$localId"]`. When two phones created different customers with the same local Room ID `5`, bills and payments from both customers were aggregated into one profile.
- **Fix (K4):** Numeric ID aliases and name-only fallbacks were removed from `buildCustomerKeyResolver`. Records are linked exclusively by `customerGid` (including canonical aliases) and normalized 10-digit mobile numbers.

### 4. Database Migration v8 to v9 with Customer Aliases (K5)
- **Migration Details:**
  - Creates `customer_aliases (aliasGid TEXT PRIMARY KEY, canonicalGid TEXT NOT NULL, mergedAt INTEGER NOT NULL)`.
  - Computes `cust_m_<mobile>` docIds and canonical GIDs for all existing customer rows.
  - Groups duplicate rows by mobile number and survivor selection.
  - Re-points foreign references in `bills` and `reminder_logs` to the survivor's canonical GID before cleaning up duplicate rows.
  - Fully backward-compatible migrations provided: `MIGRATION_8_9`, `MIGRATION_7_9`, `MIGRATION_6_9`, `MIGRATION_5_9`, `MIGRATION_4_9`, `MIGRATION_3_9`.

### 5. Diagnostics & Repair Tooling (K6)
- **Improvements:**
  - Upgraded `CustomerDiagnosticManager` to use the same filtering criteria as the customer directory.
  - Diagnostic scan verifies consistency between Directory and Room active records.
  - Pre-migration backup is stored locally before any repair runs.

---

## Verification & Validation

The changes were verified with the unit test suite:
- `GlobalIdV352Test`:
  - `testH1_Reproduction_FabricatedDocIdProducesDuplicate_FixedByRealDocId`: Verifies unification of Room and Firestore GIDs and single-row deduplication.
  - `testH2_Reproduction_NumericIdCollisionOpensWrongCustomer_FixedByCustomerGid`: Verifies that colliding numeric IDs do not open the wrong customer when navigating by `customerGid`.
  - `testK4_UdhariLedger_DoesNotCrossContaminateBillsViaNumericId`: Verifies ledger isolation between customers sharing local numeric IDs.
  - `testK5_AliasMapping_ResolvesOldGidToCanonicalGid`: Verifies resolution of legacy GIDs to canonical customer GIDs.
  - `testK2_DirectoryCountEqualsDiagnosticCount`: Verifies directory count matches diagnostic count.
- Golden Consistency Suites:
  - `UdhariLedgerConsistencyTest`: Verified.
  - `LedgerConsistencyTest`: Verified.
  - `GlobalIdRepairAndOverpaymentTest`: Verified.
  - `WhatsAppAndUdhariPolicyTest`: Verified.

---

## Rollback & Safety

- Local JSON snapshot created before migration at:
  `/data/data/com.matoshree.collection/files/pre_v3_5_migration_backup/`
- Safe in-place update supported over v3.5.1, v3.5.0, or v3.4.12.
