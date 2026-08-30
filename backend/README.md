# Matoshree Collection — Hostinger Backend Setup Guide

## 1. Directory Structure on Hostinger
Upload the contents of `backend/` to your Hostinger `public_html/` (or a subdomain like `api.matoshreeboutique.in`):

```
public_html/
├── .env                  <-- Copy from .env.example with your Hostinger DB credentials
├── .htaccess             <-- From public/.htaccess
├── index.php             <-- From public/index.php
├── config/
│   ├── config.php
│   └── database.php
├── migrations/
│   ├── 001_create_initial_schema.sql
│   ├── 002_seed_initial_data.sql
│   └── migrate.php
└── src/
    ├── Controllers/
    ├── Middleware/
    ├── Services/
    └── Utils/
```

## 2. Setting Up MySQL Database on Hostinger
1. Log into your **Hostinger hPanel**.
2. Go to **Databases** -> **Management** -> **Create a New MySQL Database and User**.
3. Copy the database name, username, and password into your `.env` file:
   ```env
   DB_HOST=localhost
   DB_NAME=u123456789_matoshree
   DB_USER=u123456789_admin
   DB_PASS=YourSecurePassword
   ```
4. Open **phpMyAdmin** in hPanel, import `migrations/001_create_initial_schema.sql` and `migrations/002_seed_initial_data.sql`, OR run the migration runner via SSH / Cron:
   ```bash
   php migrations/migrate.php
   ```

## 3. Verifying the API
Visit `https://your-domain.com/api/v1/health` in your browser. You will see:
```json
{
  "status": "success",
  "message": "API Gateway operational",
  "data": {
    "service": "Matoshree Collection REST API",
    "version": "1.0.0",
    "status": "healthy"
  }
}
```
Default Login Credentials (Seeded):
- **Mobile:** `+919876543210`
- **PIN:** `1234` (or Password: `admin123`)
