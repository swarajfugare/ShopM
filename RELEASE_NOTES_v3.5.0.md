# Matoshree Collection v3.5.0 (Build 53) - Permanent Typed Global ID Architecture

### Architectural Overview
This major release establishes a permanent, globally unique, typed ID (`gid`) architecture across all database entities and relationships in ShopM (`com.matoshree.collection`), replacing fragile per-device SQLite auto-increment IDs, bill numbers, and customer names as foreign keys.

### Key Changes & Fortifications

1. **Permanent Typed Global IDs (`gid`)**:
   - Monotonic Crockford Base32 ULID generation (`48-bit timestamp + 80-bit random`, 26 characters + 4-character prefix = 30 characters).
   - Strict entity prefix scheme:
     - `cus_`: Customer
     - `bil_`: Bill
     - `bli_`: Bill Item
     - `prd_`: Product
     - `cat_`: Category
     - `stk_`: Stock Movement
     - `udh_`: Udhari Credit
     - `pay_`: Payment
     - `rev_`: Reversal
     - `exp_`: Expense
     - `clo_`: Daily Closing
     - `rmd_`: Reminder Log
     - `dev_`: Device

2. **Deterministic Legacy Backfill**:
   - For all existing records, GIDs are computed deterministically offline using `prefix + "L" + base32(docId)`.
   - Allows multiple phones to independently derive identical GIDs for existing Firestore documents without network coordination.

3. **Room Database Migration (v6 to v7)**:
   - Non-destructive schema migration `MIGRATION_6_7` (and cumulative `MIGRATION_3_7`, `MIGRATION_4_7`, `MIGRATION_5_7`).
   - SQLite cursor-based backfill for all existing entities before creating unique indexes.
   - Foreign key relations migrated to `customerGid`, `categoryGid`, `productGid`, and `billGid`.

4. **Safety & Zero Data-Loss Protections**:
   - Automated pre-migration full JSON database backup stored in `context.filesDir/pre_v3_5_migration_backup/`.
   - Post-migration totals verification ensuring bill totals, udhari totals, and record counts match pre-migration numbers within $\epsilon < 0.001$.
   - Flagging of ambiguous legacy records (`needsReview = true`) and surfacing in Customer Diagnostics.

5. **UdhariLedger Unified Resolution**:
   - Matching customers by `customerGid` first.
   - Complete elimination of fuzzy matching heuristics for non-review records.
   - Deduplication of bills and transactions strictly by `gid`.

6. **Cloud Security Rules & Dual-Version Warning**:
   - Firestore security rules updated with `isValidGid(prefix, docId)` and `isImmutableGid()`, backward compatible with legacy documents.
   - Dual-version detection banner on Home/Dashboard warning when an older app version (< 3.5.0) is detected on another device in the shop.

### Verification & Quality Assurance
- **Verification Suite**: `./scripts/verify.sh` executed cleanly.
- **Unit Tests**: 89 unit tests passed (0 failures, 0 errors), including `IdGeneratorTest`, `GlobalIdMigrationTest`, `LedgerConsistencyTest`, and `UdhariLedgerConsistencyTest`.
- **Production Build**: R8/ProGuard minification and lint vital checks completed successfully.

### Artifacts & Checksums
- `Matoshree-Collection-Latest.apk`:
  - SHA-256: `d8288b4beef26655ef68ae88eed47aa24c80d02dc56ccbf151fb7293eec047cc`
- `app-debug.apk`:
  - SHA-256: `349a7a17e0203e7535345725a1096d0f774fb698b4f38d47ca52d22c40f04281`
