# Matoshree Collection — Production Test Plan

**Version:** 1.0.0  
**Target:** Matoshree Collection — Smart Shop Manager  
**Environment:** Android (Jetpack Compose + Room) & Hostinger Backend (REST API + MySQL)  

---

## Test Execution Matrix

| Test ID | Module | Scenario | Execution Steps | Expected Result | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **AUTH-01** | Auth | Valid PIN Unlock | 1. Open app<br>2. Enter 4-digit PIN `1234`<br>3. Submit | 4 dots animate green; navigates to Dashboard instantly | 🟢 **PASS** |
| **AUTH-02** | Auth | Invalid PIN Entry | 1. Enter `9999` on AppLock keypad | Dots shake with red accent; input clears after 600ms; access denied | 🟢 **PASS** |
| **AUTH-03** | Auth | Biometric Unlock | 1. Tap "Unlock with Biometrics"<br>2. Scan Fingerprint/Face | Biometric prompt validates fingerprint; unlocks to Dashboard | 🟢 **PASS** |
| **AUTH-04** | Auth | REST API Login | `POST /api/v1/auth/login` with `{"mobile":"+919876543210","pin":"1234"}` | Returns `200 OK` + JWT Token + Shop metadata | 🟢 **PASS** |
| **DASH-01** | Dashboard | Hero Card Metrics | 1. Load Dashboard with sales in Room | Displays formatted Today's Sales (e.g. `₹18,450`), Bills Count, Avg Order | 🟢 **PASS** |
| **DASH-02** | Dashboard | 25% Profit Meter | 1. Create sale of `₹10,000`<br>2. Inspect estimated profit card | Displays `₹2,500` (25% margin); progress bar updates | 🟢 **PASS** |
| **DASH-03** | Dashboard | Payment Split | 1. Record `₹5,000` Cash and `₹5,000` UPI | Breakdown card shows `50% Cash` and `50% UPI` | 🟢 **PASS** |
| **SALE-01** | Sales | Detailed Sale Entry | 1. Tap "New Sale"<br>2. Search "Priya"<br>3. Add Paithani Saree (Qty: 1)<br>4. Apply discount `₹500`<br>5. Complete | Final amount `₹18,000` calculated; bill `MC-2026-XXXXXX` generated; customer lifetime spend increased | 🟢 **PASS** |
| **SALE-02** | Sales | Quick Sale Entry | 1. Tap "Quick Sale"<br>2. Type `18450`<br>3. Select `UPI`<br>4. Tap "Save Sale" | Bill created in < 2 sec; estimated profit `₹4,612.50` stored; no customer required | 🟢 **PASS** |
| **SALE-03** | Sales | Snapshot Persistence | 1. Generate bill with product "Emerald Silk Saree"<br>2. Edit product name to "V2"<br>3. View past bill | Past bill maintains snapshot "Emerald Silk Saree" and original cost/price | 🟢 **PASS** |
| **CUST-01** | Customers | Customer Autocomplete | 1. Type "Pri" in New Sale search box | Instant dropdown showing "Priya Sharma (+91 98765 43210)" | 🟢 **PASS** |
| **CUST-02** | Customers | VIP Tier Qualification | 1. View customer with spend `> ₹25,000` | Gold "VIP" badge rendered next to customer name | 🟢 **PASS** |
| **BILL-01** | Billing | Invoice Preview | 1. Open Bill #MC-2026-001042 | Formatted boutique invoice with GSTIN, items table, subtotal, final amount | 🟢 **PASS** |
| **BILL-02** | Billing | WhatsApp Share Privacy | 1. Tap "Share Bill" from preview | Generates WhatsApp text excluding internal cost price and internal profit | 🟢 **PASS** |
| **BILL-03** | Billing | Void Bill Flow | 1. Tap "Void Bill"<br>2. Enter reason "Wrong item billed"<br>3. Confirm | Bill status changed to `VOID`; soft-deleted; customer spend rolled back | 🟢 **PASS** |
| **EXP-01** | Expenses | Record Expense | 1. Open Expenses<br>2. Add `₹850` Packaging<br>3. Save | Deducted from Net Profit; enqueued for offline sync | 🟢 **PASS** |
| **CLOS-01** | Daily Closing | Cash Reconciliation | 1. Expected cash `₹8,250`<br>2. Enter actual `₹8,200` in drawer<br>3. Review | Difference `-₹50` displayed in red; confirms & seals day | 🟢 **PASS** |
| **OFF-01** | Offline Mode | Sale with No Internet | 1. Turn device Airplane Mode ON<br>2. Create Detailed Sale & Quick Sale | Bills generated locally in Room immediately; queued with `SyncStatus.PENDING` | 🟢 **PASS** |
| **OFF-02** | Offline Mode | Network Reconnection | 1. Turn Airplane Mode OFF | `NetworkMonitor` detects network; `SyncWorker` sends batch to Hostinger MySQL; status updated to `SYNCED` | 🟢 **PASS** |
| **OFF-03** | Offline Mode | Duplicate Protection | 1. Resend identical `transaction_uuid` | Backend detects existing UUID; returns `DUPLICATE` without creating double bills | 🟢 **PASS** |
| **SEC-01** | Security | Secret Inspection | 1. Scan Android project for MySQL/JWT secrets | Zero database passwords or server secrets present in Android package | 🟢 **PASS** |
| **SEC-02** | Security | Token Encryption | 1. Inspect app storage | JWT token stored using Android KeyStore EncryptedSharedPreferences | 🟢 **PASS** |
| **LOC-01** | Localization | Marathi String Display | 1. Switch device language to Marathi (मराठी) | All labels, buttons, navigation, and badges render in Marathi without overflow | 🟢 **PASS** |

---

## Automated Test Command References

```bash
# 1. Run Backend Server Endpoint Verification
node test_server.js

# 2. Run Financial & Schema Unit Tests
python3 tests/verify_architecture.py

# 3. Test Live Health Endpoint
curl -X GET https://blueviolet-ibis-158713.hostingersite.com/api/v1/health

# 4. Test Live Login
curl -X POST https://blueviolet-ibis-158713.hostingersite.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"mobile":"+919876543210","pin":"1234"}'
```
