# Matoshree Collection — Smart Shop Manager Backend

Production REST API for **Matoshree Collection** Indian clothing/fashion boutique.

## 🚀 Quick Start (Local & Hostinger)

### 1. Installation
```bash
npm install
```

### 2. Environment Configuration
Copy `.env.example` to `.env` and fill in your database credentials:
```bash
cp .env.example .env
```

### 3. Start the Server
```bash
npm start
```
The server will be live at `http://localhost:5000`.

---

## 📡 API Endpoints Reference

### 1. Health Check (Public)
```bash
curl -X GET http://localhost:5000/api/v1/health
```

### 2. Authentication (Public)
Login with default boutique credentials:
```bash
curl -X POST http://localhost:5000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "mobile": "+919876543210",
    "pin": "1234"
  }'
```
*Returns JWT token to use in `Authorization: Bearer <token>` for all endpoints below.*

### 3. Dashboard Summary (Protected)
```bash
curl -X GET http://localhost:5000/api/v1/dashboard \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 4. Create Sale (Detailed / Quick)
```bash
curl -X POST http://localhost:5000/api/v1/sales \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sale_type": "QUICK",
    "final_amount": 18450.00,
    "payment_method": "UPI",
    "note": "Festival purchase"
  }'
```

### 5. Customer Directory & Autocomplete
```bash
curl -X GET "http://localhost:5000/api/v1/customers?search=Priya" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 6. Catalog Products
```bash
curl -X GET http://localhost:5000/api/v1/products \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 7. Daily Closing Reconciliation
```bash
curl -X GET http://localhost:5000/api/v1/daily-closing \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 8. Batch Offline Sync Ingestion
```bash
curl -X POST http://localhost:5000/api/v1/sync \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sync_items": [
      {
        "transaction_uuid": "tx-12345",
        "entity_type": "SALE"
      }
    ]
  }'
```
