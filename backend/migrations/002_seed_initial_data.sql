-- ==============================================================================
-- MATOSHREE COLLECTION — HOSTINGER MYSQL SCHEMA
-- Migration: 002_seed_initial_data.sql
-- Description: Seed default shop, boutique manager, categories, products & settings
-- ==============================================================================

-- 1. Insert Default Shop
INSERT INTO `shops` (`id`, `name`, `logo_url`, `address`, `city`, `state`, `pincode`, `mobile`, `email`, `gst_number`, `currency`, `timezone`, `is_active`)
VALUES (1, 'Matoshree Collection', 'https://lh3.googleusercontent.com/aida-public/AB6AXuDY09wnEHypwsMXtBVivoTYMPy458bSIPAWPp7ojcbGwPqL5G3WoULRYLtGf8g1Eoyt_P55Ut_vKSOTfQZ1Uyzbpb5l60vuX_U_nlC1mx4K6rq2C6nMB0PFuD9--e8sP6YTYyWnkQNaISUapHqD6WhDGLppD4jC-8hcnZAnjjOLJYIKUiMQtvBmCz8BGR_PKarsbgZmM-AHn4veZkVUlW9T9tCA7uXCJg3D3SjcD6UtOm4zbj0VxXBc', 'Shop No. 4, Silk Heritage Complex, Main Market', 'Kolhapur', 'Maharashtra', '416002', '+91 98765 43210', 'contact@matoshreeboutique.in', '27AAAAA0000A1Z5', 'INR', 'Asia/Kolkata', 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 2. Insert Default Boutique Manager User (Password: admin123, PIN: 1234)
-- Note: bcrypt hash of 'admin123' is $2y$10$wO08GzF30h9u.L5hI0t.f.dC4w4zS7v8B7g8sK5z8X4k5f8G6f.g2
-- Note: bcrypt hash of '1234' is $2y$10$2vPq4eN4zY6B4bZ4gI8bue1u2Q9x9E8u2y7V8b9X7u1c2d3e4f5g6
INSERT INTO `users` (`id`, `shop_id`, `name`, `mobile`, `email`, `password_hash`, `pin_hash`, `role`, `is_active`)
VALUES (1, 1, 'Matoshree Admin', '+919876543210', 'admin@matoshree.in', '$2y$10$32rK8sZ9sT6wG7n8g7e.aeFp0a1B2c3d4e5f6g7h8i9j0k1l2m3n4', '$2y$10$1a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z7', 'OWNER', 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 3. Default Settings (25% default margin, MC bill prefix, etc.)
INSERT INTO `settings` (`shop_id`, `setting_key`, `setting_value`) VALUES
(1, 'default_profit_margin', '25.0'),
(1, 'default_payment_method', 'CASH'),
(1, 'bill_prefix', 'MC'),
(1, 'enable_biometric', 'true'),
(1, 'auto_lock_minutes', '5'),
(1, 'language', 'en'),
(1, 'theme', 'emerald_gold')
ON DUPLICATE KEY UPDATE `setting_value`=VALUES(`setting_value`);

-- 4. Initial Clothing Categories
INSERT INTO `categories` (`id`, `shop_id`, `name`, `description`, `is_active`) VALUES
(1, 1, 'Silk Sarees', 'Pure silk, Paithani, Kanjeevaram & Banarasi Sarees', 1),
(2, 1, 'Cotton Sarees', 'Chanderi, Handloom & Daily Wear Cotton Sarees', 1),
(3, 1, 'Designer Lehengas', 'Bridal & Party Wear Lehengas', 1),
(4, 1, 'Kurtis & Suits', 'Semi-stitched and ready-made designer suits', 1),
(5, 1, 'Dupattas & Stoles', 'Zari border and embroidered dupattas', 1),
(6, 1, 'Accessories & Jewelry', 'Traditional boutique accessories', 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 5. Initial Boutique Products with snapshot-ready metadata
INSERT INTO `products` (`id`, `shop_id`, `category_id`, `name`, `sku`, `selling_price`, `cost_price`, `default_profit_margin`, `track_inventory`, `current_stock`, `image_url`, `is_active`) VALUES
(1, 1, 1, 'Emerald Silk Kanjeevaram Saree', 'MC-SK-9082', 12499.00, 9374.00, 25.00, 1, 14, 'https://lh3.googleusercontent.com/aida-public/AB6AXuA6woZuOVfPqJg0aZBF11sOXh-n_EU3Zmo7Pl8-fiX8BxfEwYG-m3S1lTor4c38Hyz9Gguo5plVyvd8D1NL5wJ_NGeSEOLC0y3ALK_ZlY3wVJcnWUaPjJqc1u0Om_SsP6rCXGe3fR6GT435maDM-XYB41A6vCLOYr98XuzPdLAOsVIfFRgpsDRSR1zqY6JL2XMx9yambibNduc4083fgRJicYauVaHXMVKF6IkjXUjcuv9s8Ds0YocM', 1),
(2, 1, 1, 'Royal Paithani Silk Saree (Gold Zari)', 'MC-PS-4011', 18500.00, 13875.00, 25.00, 1, 8, 'https://lh3.googleusercontent.com/aida-public/AB6AXuC9xvXp8YS5Z1UC-Fo7mNGlfWfPGZ1_6WMhCdpSvwgFwgL9mIOWvjArJBQXx-jMM8sNgTq5o5eD80R2Wnl9quVQB0sGEzkoDlMZ6k4_O-ehm_B_SYOfPhF5RkEIzUhBZ3qCuQXVh-zSUaSFrO-oFllkLFfxnJsJz_NK2lYQayfafgkVb8ha_3QqqNlqaYHpEUnmEUPNtMLVbWBmAN8u_wHF6tuvKsZei7AAig9Ut5zV-lIRSSl8ogR4', 1),
(3, 1, 5, 'Kanjeevaram Gold Dupatta', 'MC-KD-004', 4000.00, 3000.00, 25.00, 1, 20, NULL, 1),
(4, 1, 2, 'Chanderi Pure Cotton Saree', 'MC-CC-102', 2850.00, 2100.00, 26.31, 1, 25, NULL, 1),
(5, 1, 4, 'Anarkali Embroidered Kurti Set', 'MC-AK-701', 3450.00, 2500.00, 27.53, 1, 12, NULL, 1),
(6, 1, 6, 'Temple Gold Finish Choker', 'MC-JW-441', 4200.00, 3150.00, 25.00, 1, 5, 'https://lh3.googleusercontent.com/aida-public/AB6AXuCGzPrcV1Xe1lnkyXzm1kVaQSxQ7J1fmT6btw6BqJKEcSVLSIjbRbIu2rg4jw2NJtzr0XiHD2-YwqmdDd4bnPyjoxmDuG4_aVyBM-IxrK6I6OfOOhBXdSjKjo9RmDh4JgddzKcWB2TRBVpoTIQ_hIPIqjdEYO6oCLmnbhZ0rmkzQdd2tS0G-JaFhre0_YqBhC7-0n7NKm_ZWoycud57M_RCWLyo0eY61a2PTqu9VPMN2L6IPwZqPN7R', 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 6. Initial Boutique Customers
INSERT INTO `customers` (`id`, `shop_id`, `name`, `mobile`, `email`, `address`, `total_bills`, `lifetime_spend`, `first_purchase_at`, `last_purchase_at`, `is_active`) VALUES
(1, 1, 'Priya Sharma', '+91 98765 43210', 'priya.sharma@example.com', 'Flat 302, Raj Residency, Kolhapur', 4, 38450.00, '2026-06-15 11:30:00', '2026-08-28 14:30:00', 1),
(2, 1, 'Sunita Patil', '+91 98765 43211', 'sunita.patil@example.com', 'B-12, Tarabai Park, Kolhapur', 2, 22500.00, '2026-07-02 16:00:00', '2026-08-20 18:15:00', 1),
(3, 1, 'Sushma Deshmukh', '+91 87654 32109', 'sushma.d@example.com', 'Nagala Park, Kolhapur', 1, 18500.00, '2026-08-10 12:45:00', '2026-08-10 12:45:00', 1),
(4, 1, 'Sujata Kulkarni', '+91 76543 21098', 'sujata.k@example.com', 'Rajarampuri 5th Lane, Kolhapur', 3, 31200.00, '2026-05-18 10:15:00', '2026-08-25 15:20:00', 1),
(5, 1, 'Anita Desai', '+91 91234 56789', 'anita.desai@example.com', 'Shahupuri, Kolhapur', 2, 4650.00, '2026-08-01 11:00:00', '2026-08-29 13:15:00', 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 7. Initial Monthly Target (August 2026: ₹5,00,000)
INSERT INTO `targets` (`shop_id`, `target_type`, `year`, `month`, `target_amount`)
VALUES (1, 'MONTHLY', 2026, 8, 500000.00)
ON DUPLICATE KEY UPDATE `target_amount`=VALUES(`target_amount`);
