const { pool, isDbConnected } = require('../config/db');

const initialSchemaSql = `
CREATE TABLE IF NOT EXISTS shops (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    address TEXT,
    city VARCHAR(100) DEFAULT 'Kolhapur',
    state VARCHAR(100) DEFAULT 'Maharashtra',
    pincode VARCHAR(20) DEFAULT '416002',
    mobile VARCHAR(20),
    email VARCHAR(100),
    gst_number VARCHAR(50) DEFAULT '27AAAAA0000A1Z5',
    show_gstin TINYINT(1) DEFAULT 1,
    upi_id VARCHAR(100) DEFAULT 'matoshree@upi',
    upi_display_name VARCHAR(150) DEFAULT 'Matoshree Collection',
    logo_url VARCHAR(500) DEFAULT NULL,
    logo_data MEDIUMTEXT DEFAULT NULL,
    currency VARCHAR(10) DEFAULT 'INR',
    default_profit_margin DECIMAL(5,2) DEFAULT 25.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    name VARCHAR(100) NOT NULL,
    mobile VARCHAR(20) NOT NULL UNIQUE,
    email VARCHAR(100),
    pin VARCHAR(255) NOT NULL DEFAULT '1234',
    password VARCHAR(255) NOT NULL DEFAULT 'admin123',
    role VARCHAR(50) NOT NULL DEFAULT 'OWNER',
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    name VARCHAR(150) NOT NULL,
    mobile VARCHAR(20) NOT NULL,
    email VARCHAR(100),
    address TEXT,
    total_bills INT DEFAULT 0,
    lifetime_spend DECIMAL(12,2) DEFAULT 0.00,
    tier VARCHAR(20) DEFAULT 'REGULAR',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS products (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    category_id INT,
    name VARCHAR(255) NOT NULL,
    sku VARCHAR(100),
    selling_price DECIMAL(10,2) NOT NULL,
    cost_price DECIMAL(10,2),
    current_stock INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bills (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    customer_id INT,
    customer_name VARCHAR(150) DEFAULT 'Walk-in Customer',
    customer_mobile VARCHAR(20),
    bill_number VARCHAR(50) NOT NULL UNIQUE,
    transaction_uuid VARCHAR(64) NOT NULL UNIQUE,
    sale_type VARCHAR(20) NOT NULL DEFAULT 'DETAILED',
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    discount_value DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    final_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    cost_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    estimated_profit DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    actual_profit DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    profit_type VARCHAR(20) NOT NULL DEFAULT 'ESTIMATED',
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH',
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PAID',
    note TEXT,
    bill_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    shop_name_snapshot VARCHAR(255) DEFAULT 'Matoshree Collection',
    shop_address_snapshot TEXT DEFAULT NULL,
    shop_mobile_snapshot VARCHAR(50) DEFAULT NULL,
    shop_gstin_snapshot VARCHAR(50) DEFAULT NULL,
    show_gstin_snapshot TINYINT(1) DEFAULT 1,
    is_voided TINYINT(1) NOT NULL DEFAULT 0,
    void_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bill_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    bill_id INT NOT NULL,
    product_id INT,
    product_name_snapshot VARCHAR(255) NOT NULL,
    sku_snapshot VARCHAR(100),
    quantity INT NOT NULL DEFAULT 1,
    selling_price DECIMAL(10,2) NOT NULL,
    cost_price DECIMAL(10,2),
    discount_amount DECIMAL(10,2) DEFAULT 0.00,
    line_total DECIMAL(12,2) NOT NULL,
    line_cost DECIMAL(12,2) NOT NULL,
    line_profit DECIMAL(12,2) NOT NULL,
    profit_type VARCHAR(20) DEFAULT 'ESTIMATED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS expenses (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    category VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH',
    expense_date DATE NOT NULL,
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS daily_closings (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    closing_date DATE NOT NULL UNIQUE,
    total_sales DECIMAL(12,2) DEFAULT 0.00,
    total_bills INT DEFAULT 0,
    cash_sales DECIMAL(12,2) DEFAULT 0.00,
    upi_sales DECIMAL(12,2) DEFAULT 0.00,
    gross_profit DECIMAL(12,2) DEFAULT 0.00,
    expected_cash DECIMAL(12,2) DEFAULT 0.00,
    actual_cash DECIMAL(12,2) DEFAULT 0.00,
    cash_difference DECIMAL(12,2) DEFAULT 0.00,
    notes TEXT,
    is_closed TINYINT(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS settings (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_id INT NOT NULL DEFAULT 1,
    setting_key VARCHAR(100) NOT NULL,
    setting_value TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_shop_setting (shop_id, setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
`;

async function runAutoMigration() {
  if (!isDbConnected() || !pool) return false;
  try {
    const statements = initialSchemaSql.split(';').map(s => s.trim()).filter(s => s.length > 0);
    for (const sql of statements) {
      await pool.query(sql);
    }

    // Dynamic column additions for existing tables
    const alterStatements = [
      "ALTER TABLE shops ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500) DEFAULT NULL",
      "ALTER TABLE shops ADD COLUMN IF NOT EXISTS logo_data MEDIUMTEXT DEFAULT NULL",
      "ALTER TABLE shops ADD COLUMN IF NOT EXISTS upi_id VARCHAR(100) DEFAULT 'matoshree@upi'",
      "ALTER TABLE shops ADD COLUMN IF NOT EXISTS upi_display_name VARCHAR(150) DEFAULT 'Matoshree Collection'",
      "ALTER TABLE shops ADD COLUMN IF NOT EXISTS show_gstin TINYINT(1) DEFAULT 1",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS discount_type VARCHAR(20) NOT NULL DEFAULT 'NONE'",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS discount_value DECIMAL(10,2) NOT NULL DEFAULT 0.00",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS shop_name_snapshot VARCHAR(255) DEFAULT 'Matoshree Collection'",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS shop_address_snapshot TEXT DEFAULT NULL",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS shop_mobile_snapshot VARCHAR(50) DEFAULT NULL",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS shop_gstin_snapshot VARCHAR(50) DEFAULT NULL",
      "ALTER TABLE bills ADD COLUMN IF NOT EXISTS show_gstin_snapshot TINYINT(1) DEFAULT 1"
    ];

    for (const alterSql of alterStatements) {
      try {
        await pool.query(alterSql);
      } catch (e) {
        // column may already exist
      }
    }

    // Seed default shop and admin user if empty
    const [shops] = await pool.query('SELECT COUNT(*) as count FROM shops');
    if (shops[0].count === 0) {
      await pool.query(`
        INSERT INTO shops (id, name, address, city, state, pincode, mobile, email, gst_number, currency, default_profit_margin)
        VALUES (1, 'Matoshree Collection', 'Shop No. 4, Silk Heritage Complex, Main Market, Kolhapur', 'Kolhapur', 'Maharashtra', '416002', '+91 98765 43210', 'contact@matoshree.in', '27AAAAA0000A1Z5', 'INR', 25.00)
      `);
    }

    const [users] = await pool.query('SELECT COUNT(*) as count FROM users');
    if (users[0].count === 0) {
      await pool.query(`
        INSERT INTO users (id, shop_id, name, mobile, email, pin, password, role, is_active)
        VALUES (1, 1, 'Matoshree Admin', '+919876543210', 'admin@matoshree.in', '1234', 'admin123', 'OWNER', 1)
      `);
    }

    console.log('[✓] Hostinger MySQL Tables, Alters, and Seed records initialized successfully!');
    return true;
  } catch (err) {
    console.warn('[!] Auto migration warning:', err.message);
    return false;
  }
}

module.exports = {
  runAutoMigration
};
