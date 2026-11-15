# Release Notes - v3.6.3 (Build 61)

**Release Date:** 2026-11-15  
**Package:** `com.matoshree.collection`  
**Version:** `3.6.3` (Build `61`)  
**SHA-256 (Matoshree-Collection-Latest.apk):**  
`d158ac1951fb6b71192085a7bba648ecc3ddbc5750e51c7ca8541cc95e0af5ac`

---

## 🚀 What Changed in v3.6.3

This release provides an urgent regression fix for **Udhari Jama** (customer payment recording) identified during field testing on v3.6.2.

### Root Cause Analysis & Fix
- **Issue:** In v3.6.2 (P3), payment recording switched to targeted Firestore queries (`whereEqualTo("customerMobile", ...)` and document ID lookups). Real shop Firestore documents have varied shapes (`cust_<millis>_<uuid>`, `cust_<smallId>`, `+91 92262 99212` with country code / spacing, and bills with legacy or null `customerGid`). As a result, server-side equality queries failed to match the customer and bill documents on real devices, resulting in empty matches. The payment could not be allocated to the customer's pending bill, falling back to a detached general payment without canonical customer linking. Because customer key resolution failed, the payment was omitted from the customer profile Payments tab, and customer outstanding balance did not reduce.
- **Fortification:**
  - Restored full in-memory collection lookup via `UdhariFirestoreGateway`: loads customer and bill collections (which is fast on small shop scale, < 200ms) with cache fallback, allowing `PhoneUtils.clean10DigitMobileInput` to normalize all phone number formats (`+91...`, spaces, etc.).
  - Retained strict P1 matching safety: matching strictly uses canonical `customerGid`, document ID, legacy ID, exact 10-digit mobile, or bill number/GID. Name matching is never performed. Ambiguous matches across multiple mobiles fail cleanly.
  - Transaction customer key resolution: resolved customer GID and clean 10-digit mobile are explicitly assigned to every transaction record so that `keyResolver` and profile ledger match 100%.
  - Payment reversal ("रद्द करा") persistence: safely restores bill `paidAmount`, `pendingAmount`, and customer `outstandingAmount` across all matching document paths via atomic Firestore batch write (`commitReversalBatch`).
  - Unit tests: Added `Round18UdhariPaymentRegressionTest` verifying real document shapes, phone formatting, bill updates, ledger calculations, and cancellation reversal without network dependencies.

---

## 🔄 Rollback Procedure

If needed, the shop owner can safely roll back to v3.6.1:
1. Uninstall v3.6.3 from the device.
2. Install the previous release APK `v3.6.1` from GitHub Releases.
3. Live Firestore data is preserved and backward compatible.
