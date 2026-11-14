# Release Notes - v3.6.0 (Build 58)

**Release Date:** November 14, 2026  
**Package:** `com.matoshree.collection`  
**Database Version:** Room v9  
**Binary Deliverable:** `Matoshree-Collection-Latest.apk` (Signed Release APK)  
**SHA-256 Checksum:** `2e2253d1dda1f39494fb1b4d5dc79a416b855de00740b691ec42dd31bd9b0288`

---

## Overview

Release v3.6.0 is the comprehensive production release (Round 15) addressing all residual defects, standardizing bilingual localization, clarifying manual udhari retention, and fortifying data integrity:

1. **B1: Orphan Transactions Red Box & Action Resolution**
   - Fixed ID truncation bug in `FirestoreUdhariRepository` (`tx_...` was being truncated to `tx`), which previously caused "हटवा" on orphans to fail silently.
   - Added complete orphan transaction action set in Customer Profile Payments tab:
     - **हटवा (Delete / Soft-delete):** Moves orphan transaction safely to TRASH (`status = "TRASH"`, `isDeleted = true`).
     - **दुर्लक्ष (Ignore):** Excludes orphan from balance calculations while keeping it on file (`status = "IGNORED"`).
     - **सामान्य भरणा (General Payment):** Converts orphan payment to an unlinked FIFO payment credited against the customer's older debts.
     - **सर्व हटवा (Bulk Delete):** One-tap soft-delete of all orphan records for the customer with preview and confirmation dialog.
   - User feedback is provided via Snackbar and confirm dialogs (no silent failures).

2. **B2: Analytics Page Streamlining**
   - Removed the comparison toggle, CSV, and WhatsApp export action row situated directly above the Daily Balance Sheet ("दैनिक ताळेबंद अहवाल").
   - Preserved all other analytics capabilities: 11 date presets, custom date picker, single-day report, summary metrics, trend graphs, top lists, category cards, bottom report sharing, and customizable sections.
   - Verified via unit test `AnalyticsScreenCleanupTest`.

3. **B3: Bilingual Spoken Marathi & English Localization**
   - Rewritten all user-visible text into simple everyday spoken Marathi (e.g. बिल, पेमेंट, स्टॉक, कस्टमर, रिपोर्ट, कॅश, UPI, सेव्ह, प्रिंट, बाकी, जुनी उधारी, हटवा, रद्द करा), eliminating heavy, unnatural bureaucratic phrasing.
   - Created full 1:1 English string parity in `values-en/strings.xml` for all keys in `values/strings.xml`.
   - Added in-app Language Switcher ("भाषा (Language)") in Settings Screen with Marathi (Default) and English options, persisted via `LocaleHelper`.
   - Guaranteed every button, tab, chip, and action label is at most 2 words in both Marathi and English.
   - Verified via expanded `ButtonLabelLengthTest`.

4. **B4: Direct Manual Udhari Retention & Classification**
   - Added explicit `kind` field to `UdhariTransaction` and `UdhariTransactionDto` (`MANUAL`, `BILL_CREDIT`, `CHECKOUT_PAYMENT`, `SETTLEMENT`, `GENERAL_PAYMENT`).
   - Manual udhari ("जुनी उधारी") is strictly preserved as active debt and never classified as an orphan, even when the customer has 0 bills.
   - Added optional "नोट / संदर्भ" field in Add Udhari dialog (defaulting to "जुनी उधारी").
   - Fixed orphan heuristic in `UdhariLedger`: modern bills (`MC-`) are verified against locally known bills, while manual opening debits and legacy non-bill debts are never orphaned.

5. **B5: Production Fortifications & Edge-Case Repairs**
   - **B5.1:** Removed `limit(3000)` and arbitrary caps from stored bill recalculation, adding safety guards against recalculation when transactions dataset is empty.
   - **B5.2:** Trash restoration now only recovers transactions explicitly marked `cascadedBy = "BILL_DELETE"`, preventing unrelated transactions from inadvertently re-activating.
   - **B5.3:** Analytics query efficiency on Room indices.
   - **B5.5:** Diagnostic data check exact math verified: `123 = 84 + 39 + 0 + 0 + 0 + 0 + 0`.

---

## Verification & Test Results

- **Unit Tests:** 122 unit tests passed cleanly via `./scripts/verify.sh` (`testDebugUnitTest`):
  - `Round15RegressionTest` (manual udhari retention with 0 bills, bill key format mismatch, Swaraj Fugare deleted bills zero due, soft delete, ignore, and general payment conversion)
  - `AnalyticsScreenCleanupTest` (verified comparison, CSV, and WhatsApp buttons removed above daily report)
  - `ButtonLabelLengthTest` (full parity between Marathi and English string resources; <= 2 words per button/tab/chip)
  - `Round14RegressionTest`, `Round13RegressionTest`, `LedgerConsistencyTest`, `GlobalIdV352Test`, `ShopMetricsCalculatorTest`
- **Build Verification:**
  - Debug APK: Built cleanly.
  - Release APK: Compiled, Proguard/R8 minified, and signed cleanly (`Matoshree-Collection-Latest.apk`).
