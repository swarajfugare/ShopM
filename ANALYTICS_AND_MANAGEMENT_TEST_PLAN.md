# Matoshree Collection — Analytics, Management & Security Test Plan

## 1. Scope and Objective
This test plan validates the end-to-end integration and functionality for:
- **Business Intelligence Analytics**: Period filtering (Today, This Week, This Month, This Year, Custom), KPI summaries, real-time Profit Analysis (Gross vs Net vs Cost), Sales Trend visual charts, Payment Breakdown, Category Expenses, Top Products & Top Customers.
- **Dedicated Products Catalog**: Search, Category filtering, Sorting, Product details dialog, and Safe Archive vs Delete behavior.
- **Customer Management**: Lifetime spend calculation, Profile view, Bill filtering, and Safe Archive vs Delete behavior.
- **Bill Voiding**: Financial integrity preservation, non-destructive status update, and customer lifetime spend deduction.
- **Security & PIN Management**: 4-digit PIN authentication, in-app PIN change dialog, and Owner Recovery Secret verification with rate-limiting.

---

## 2. Test Cases & Verification Matrix

| ID | Module | Scenario | Expected Result | Status |
|---|---|---|---|---|
| **TC-01** | Security | PIN Unlock with default PIN `1234` | App unlocks immediately and navigates to Dashboard. | **PASSED** |
| **TC-02** | Security | Forgot PIN with correct recovery secret | Secret verified via salted SHA-256; unlocks PIN reset flow. | **PASSED** |
| **TC-03** | Security | Change PIN in Settings | Validates current PIN and 4-digit match; updates PIN in encrypted storage. | **PASSED** |
| **TC-04** | Security | Rate limiting on failed recovery | Lockout after 5 failed attempts with 60-second cooldown timer. | **PASSED** |
| **TC-05** | Analytics | Executive KPI Summary | Displays Total Revenue, Bill count, Avg Bill, Customer & Catalog counts. | **PASSED** |
| **TC-06** | Analytics | Profit Analysis | Accurately calculates Gross Sales, Cost, Expenses, Net Profit, and Margin %. | **PASSED** |
| **TC-07** | Analytics | Date Filter Chips | Filters data within `Asia/Kolkata` date boundaries for Today/Week/Month/Year/Custom. | **PASSED** |
| **TC-08** | Analytics | Sales Trend Chart | Visual bar chart dynamically renders volume by time/date buckets. | **PASSED** |
| **TC-09** | Analytics | Payment Breakdown | Displays Cash vs UPI volume and percentage progress bars without Card option. | **PASSED** |
| **TC-10** | Analytics | Top Products & Customers | Ranks products by revenue/quantity and customers by total spend. | **PASSED** |
| **TC-11** | Products | Catalog Search & Filter | Filters products instantly by search term and category chips. | **PASSED** |
| **TC-12** | Products | Product Details Dialog | Shows Name, Category, Price, Cost, and Profit Margin with Delete/Archive action. | **PASSED** |
| **TC-13** | Products | Safe Delete vs Archive | Archives product if used in bills; permanently deletes if unused. | **PASSED** |
| **TC-14** | Customers | Customer Profile & Bills | Displays lifetime spend, total bills, and full invoice history. | **PASSED** |
| **TC-15** | Customers | Safe Customer Archive | Archives customer if bills exist to protect financial audit history. | **PASSED** |
| **TC-16** | Bills | Void Bill Workflow | Marks bill as VOID, adjusts customer lifetime spend, and excludes from sales totals. | **PASSED** |

---

## 3. Automation & Build Verification
- **Gradle Unit Tests**: `./gradlew testDebugUnitTest` -> **PASSED** (26 tasks executed/up-to-date).
- **Gradle Assembly Build**: `./gradlew assembleDebug` -> **PASSED** (BUILD SUCCESSFUL).
- **Live Device Execution**: Verified on Android Emulator (API 35).
