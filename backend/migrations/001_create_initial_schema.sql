-- ==============================================================================
-- MATOSHREE COLLECTION — HOSTINGER MYSQL SCHEMA
-- Migration: 001_create_initial_schema.sql
-- Description: Core 15 relational tables with strict foreign keys & indexes
-- ==============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- 1. SHOPS TABLE
CREATE TABLE IF NOT EXISTS `shops` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(255) NOT NULL,
    `logo_url` VARCHAR(500) NULL,
    `address` TEXT NULL,
    `city` VARCHAR(100) NULL,
    `state` VARCHAR(100) NULL,
    `pincode` VARCHAR(20) NULL,
    `mobile` VARCHAR(20) NOT NULL,
    `email` VARCHAR(100) NULL,
    `gst_number` VARCHAR(30) NULL,
    `currency` VARCHAR(10) DEFAULT 'INR',
    `timezone` VARCHAR(50) DEFAULT 'Asia/Kolkata',
    `is_active` TINYINT(1) DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. USERS TABLE
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `name` VARCHAR(150) NOT NULL,
    `mobile` VARCHAR(20) NOT NULL UNIQUE,
    `email` VARCHAR(100) NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `pin_hash` VARCHAR(255) NULL,
    `role` ENUM('OWNER', 'STAFF') DEFAULT 'STAFF',
    `is_active` TINYINT(1) DEFAULT 1,
    `last_login_at` DATETIME NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. CUSTOMERS TABLE
CREATE TABLE IF NOT EXISTS `customers` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `name` VARCHAR(150) NOT NULL,
    `mobile` VARCHAR(20) NOT NULL,
    `email` VARCHAR(100) NULL,
    `address` TEXT NULL,
    `notes` TEXT NULL,
    `total_bills` INT UNSIGNED DEFAULT 0,
    `lifetime_spend` DECIMAL(12,2) DEFAULT 0.00,
    `first_purchase_at` DATETIME NULL,
    `last_purchase_at` DATETIME NULL,
    `is_active` TINYINT(1) DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE,
    INDEX `idx_cust_search` (`shop_id`, `name`, `mobile`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. CATEGORIES TABLE
CREATE TABLE IF NOT EXISTS `categories` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `description` TEXT NULL,
    `is_active` TINYINT(1) DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uq_shop_category` (`shop_id`, `name`),
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. PRODUCTS TABLE
CREATE TABLE IF NOT EXISTS `products` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `category_id` BIGINT UNSIGNED NULL,
    `name` VARCHAR(200) NOT NULL,
    `sku` VARCHAR(100) NULL,
    `selling_price` DECIMAL(12,2) NOT NULL,
    `cost_price` DECIMAL(12,2) NULL,
    `default_profit_margin` DECIMAL(5,2) DEFAULT 25.00,
    `track_inventory` TINYINT(1) DEFAULT 0,
    `current_stock` INT DEFAULT 0,
    `image_url` VARCHAR(500) NULL,
    `is_active` TINYINT(1) DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`category_id`) REFERENCES `categories`(`id`) ON DELETE SET NULL,
    INDEX `idx_product_sku` (`shop_id`, `sku`),
    INDEX `idx_product_name` (`shop_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. BILLS TABLE
CREATE TABLE IF NOT EXISTS `bills` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `customer_id` BIGINT UNSIGNED NULL,
    `bill_number` VARCHAR(50) NOT NULL,
    `transaction_uuid` VARCHAR(64) NOT NULL UNIQUE,
    `sale_type` ENUM('DETAILED', 'QUICK') NOT NULL,
    `subtotal` DECIMAL(12,2) NOT NULL,
    `discount_amount` DECIMAL(12,2) DEFAULT 0.00,
    `tax_amount` DECIMAL(12,2) DEFAULT 0.00,
    `final_amount` DECIMAL(12,2) NOT NULL,
    `cost_amount` DECIMAL(12,2) DEFAULT 0.00,
    `estimated_profit` DECIMAL(12,2) DEFAULT 0.00,
    `actual_profit` DECIMAL(12,2) DEFAULT 0.00,
    `profit_type` ENUM('ESTIMATED', 'ACTUAL') DEFAULT 'ESTIMATED',
    `payment_method` ENUM('CASH', 'UPI', 'CARD', 'OTHER', 'SPLIT') NOT NULL,
    `payment_status` ENUM('PAID', 'PARTIAL', 'UNPAID', 'VOID') DEFAULT 'PAID',
    `note` TEXT NULL,
    `bill_date` DATETIME NOT NULL,
    `created_by` BIGINT UNSIGNED NULL,
    `device_id` VARCHAR(100) NULL,
    `sync_status` ENUM('PENDING', 'SYNCED', 'FAILED') DEFAULT 'SYNCED',
    `is_voided` TINYINT(1) DEFAULT 0,
    `void_reason` TEXT NULL,
    `voided_at` DATETIME NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE RESTRICT,
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`id`) ON DELETE SET NULL,
    FOREIGN KEY (`created_by`) REFERENCES `users`(`id`) ON DELETE SET NULL,
    INDEX `idx_bill_date` (`shop_id`, `bill_date`),
    INDEX `idx_bill_number` (`shop_id`, `bill_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. BILL_ITEMS TABLE (SNAPSHOT HISTORICAL ACCURACY)
CREATE TABLE IF NOT EXISTS `bill_items` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `bill_id` BIGINT UNSIGNED NOT NULL,
    `product_id` BIGINT UNSIGNED NULL,
    `product_name_snapshot` VARCHAR(200) NOT NULL,
    `sku_snapshot` VARCHAR(100) NULL,
    `category_id` BIGINT UNSIGNED NULL,
    `quantity` INT UNSIGNED NOT NULL DEFAULT 1,
    `selling_price` DECIMAL(12,2) NOT NULL,
    `cost_price` DECIMAL(12,2) NULL,
    `discount_amount` DECIMAL(12,2) DEFAULT 0.00,
    `line_total` DECIMAL(12,2) NOT NULL,
    `line_cost` DECIMAL(12,2) DEFAULT 0.00,
    `line_profit` DECIMAL(12,2) DEFAULT 0.00,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`bill_id`) REFERENCES `bills`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`product_id`) REFERENCES `products`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. PAYMENTS TABLE
CREATE TABLE IF NOT EXISTS `payments` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `bill_id` BIGINT UNSIGNED NOT NULL,
    `payment_method` ENUM('CASH', 'UPI', 'CARD', 'OTHER') NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `payment_date` DATETIME NOT NULL,
    `reference_number` VARCHAR(100) NULL,
    `note` TEXT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`bill_id`) REFERENCES `bills`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. EXPENSES TABLE
CREATE TABLE IF NOT EXISTS `expenses` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `category` ENUM('RENT', 'ELECTRICITY', 'SALARY', 'TRANSPORT', 'PACKAGING', 'MAINTENANCE', 'OTHER') NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `payment_method` ENUM('CASH', 'UPI', 'CARD', 'OTHER') DEFAULT 'CASH',
    `expense_date` DATE NOT NULL,
    `note` TEXT NULL,
    `created_by` BIGINT UNSIGNED NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE RESTRICT,
    FOREIGN KEY (`created_by`) REFERENCES `users`(`id`) ON DELETE SET NULL,
    INDEX `idx_expense_date` (`shop_id`, `expense_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. DAILY_CLOSINGS TABLE
CREATE TABLE IF NOT EXISTS `daily_closings` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `closing_date` DATE NOT NULL,
    `total_sales` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `total_bills` INT UNSIGNED NOT NULL DEFAULT 0,
    `cash_sales` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `upi_sales` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `card_sales` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `other_sales` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `gross_profit` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `total_expenses` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `net_profit` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `expected_cash` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `actual_cash` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `cash_difference` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    `notes` TEXT NULL,
    `closed_by` BIGINT UNSIGNED NULL,
    `closed_at` DATETIME NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uq_shop_closing_date` (`shop_id`, `closing_date`),
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE RESTRICT,
    FOREIGN KEY (`closed_by`) REFERENCES `users`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. TARGETS TABLE
CREATE TABLE IF NOT EXISTS `targets` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `target_type` ENUM('MONTHLY', 'YEARLY') NOT NULL,
    `year` INT NOT NULL,
    `month` INT NULL,
    `target_amount` DECIMAL(12,2) NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uq_target` (`shop_id`, `target_type`, `year`, `month`),
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. SETTINGS TABLE
CREATE TABLE IF NOT EXISTS `settings` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `setting_key` VARCHAR(100) NOT NULL,
    `setting_value` TEXT NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uq_shop_setting` (`shop_id`, `setting_key`),
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. DEVICES TABLE
CREATE TABLE IF NOT EXISTS `devices` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `device_uuid` VARCHAR(100) NOT NULL UNIQUE,
    `device_name` VARCHAR(150) NULL,
    `platform` VARCHAR(50) DEFAULT 'Android',
    `app_version` VARCHAR(30) NULL,
    `last_sync_at` DATETIME NULL,
    `is_active` TINYINT(1) DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. SYNC_LOGS TABLE
CREATE TABLE IF NOT EXISTS `sync_logs` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `device_id` VARCHAR(100) NOT NULL,
    `transaction_uuid` VARCHAR(64) NOT NULL,
    `entity_type` VARCHAR(50) NOT NULL,
    `entity_id` BIGINT UNSIGNED NULL,
    `operation` VARCHAR(20) NOT NULL,
    `status` ENUM('SUCCESS', 'FAILED', 'DUPLICATE') NOT NULL,
    `error_message` TEXT NULL,
    `synced_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. AUDIT_LOGS TABLE
CREATE TABLE IF NOT EXISTS `audit_logs` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `shop_id` BIGINT UNSIGNED NOT NULL,
    `user_id` BIGINT UNSIGNED NULL,
    `entity_type` VARCHAR(50) NOT NULL,
    `entity_id` BIGINT UNSIGNED NULL,
    `action` ENUM('CREATE', 'UPDATE', 'VOID', 'CLOSE_DAY', 'RESTORE') NOT NULL,
    `old_data` JSON NULL,
    `new_data` JSON NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`shop_id`) REFERENCES `shops`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
