# CONTRIBUTING-AI.md: Mandatory Rules for AI Agents Working on ShopM

This repository belongs to a single-owner cloth-shop billing and stock application used on 2+ phones in a real production environment.
Any regression directly damages shop operations and customer trust.
Every AI agent working on this codebase **MUST STRICTLY COMPLY** with the following rules:

---

## 1. Core Architectural & Code Quality Rules

### R1. Single Source of Truth
- **Authoritative Engine**: ONE `UdhariLedger` (pure Kotlin, JVM-testable, independent of Android UI/Services) is the ONLY authority for customer balance, per-bill outstanding, lifetime purchases, total paid, and chronological event ledger (`events`).
- **Unified Resolver**: ONE `CustomerIdentity` resolver. Never match across different customers by name or local auto-increment Room id.
- **No Duplicate Business Logic**: No ViewModel, Screen, Worker, or Repository may re-implement filtering, debit/credit accumulation, or running balance arithmetic. Delete duplicate logic instead of leaving dead copies.

### R2. Test-First for Every Bug
- Before altering any implementation code, write a unit test reproducing the exact failure mode.
- Verify that the test **FAILS** on the existing codebase.
- Apply the minimal necessary fix and verify that the test **PASSES**.
- Document the test name and proof in the release report.

### R3. Golden-Data Consistency Suite
- Maintain `LedgerConsistencyTest` running in `./gradlew testDebugUnitTest`.
- The suite must verify:
  1. `Khate Page Balance` == `Customer Profile Due` == `Sum of per-bill outstandings` == `WhatsApp Reminder Amount`.
  2. `Home Screen Total Udhari` == `Khate Header Total` == `Sum of individual customer rows`.
  3. Customer Profile entry counts must be strictly de-duplicated (zero duplicate records).
  4. Cross-phone sync scenarios with identical data produce identical ledger results regardless of ingestion order.

### R4. Zero Regressions Before Commit
- Run the full test suite before changing code, and run it again after.
- If ANY previously passing test fails, the change is broken and MUST NOT be committed.
- Verify clean compilation of both Debug and Release APKs with ProGuard/R8.

### R5. Atomic Commits & No Drive-By Refactoring
- Keep commits focused on a single topic / bug.
- Every commit message must list the issue addressed, tests added, and files modified.
- Never reformat, rewrite, or rename unrelated functioning components.

### R6. "Do Not Touch" Preserved Behavior
- **Home Bills**: Today's bills displayed first; fallback to this week's bills if today has no bills.
- **WhatsApp Policy**: NEVER send a message at bill creation time. Messages sent ONLY on due date or Monday reminders (max 1 message per customer/day).
- **Security Lock**: PIN & Biometric AppLock integrity must never be bypassed or weakened.
- **Data Protection**: Zero data loss. Never use `fallbackToDestructiveMigration()`. Never hard-delete shop data without restorable trash or explicit user confirmation.

### R7. Verification Script
- Run `scripts/verify.sh` which executes `./gradlew testDebugUnitTest assembleDebug assembleRelease`.
- Paste the exact verification summary in the release report.
- Never use speculative words ("100%", "zero bugs", "guaranteed") without concrete test assertions.
