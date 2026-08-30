const { pool, isDbConnected } = require('../config/db');
const { calculateItemFinancials, calculateQuickSaleFinancials } = require('./financialService');

// In-Memory fallback store with seeded initial boutique data
const memoryStore = {
  shop: {
    id: 1,
    name: 'Matoshree Collection',
    address: 'Shop No. 4, Silk Heritage Complex, Main Market, Kolhapur',
    city: 'Kolhapur',
    state: 'Maharashtra',
    pincode: '416002',
    mobile: '+91 98765 43210',
    email: 'contact@matoshreeboutique.in',
    gst_number: '27AAAAA0000A1Z5',
    currency: 'INR',
    default_profit_margin: 25.0
  },
  users: [
    {
      id: 1,
      shop_id: 1,
      name: 'Matoshree Admin',
      mobile: '+919876543210',
      email: 'admin@matoshree.in',
      pin: '1234',
      password: 'admin123',
      role: 'OWNER',
      is_active: 1
    }
  ],
  customers: [
    { id: 1, shop_id: 1, name: 'Priya Sharma', mobile: '+91 98765 43210', email: 'priya@example.com', total_bills: 4, lifetime_spend: 38450.00, tier: 'VIP' },
    { id: 2, shop_id: 1, name: 'Sunita Patil', mobile: '+91 98765 43211', email: 'sunita@example.com', total_bills: 2, lifetime_spend: 22500.00, tier: 'REGULAR' },
    { id: 3, shop_id: 1, name: 'Sushma Deshmukh', mobile: '+91 87654 32109', email: 'sushma@example.com', total_bills: 1, lifetime_spend: 18500.00, tier: 'REGULAR' },
    { id: 4, shop_id: 1, name: 'Sujata Kulkarni', mobile: '+91 76543 21098', email: 'sujata@example.com', total_bills: 3, lifetime_spend: 31200.00, tier: 'VIP' },
    { id: 5, shop_id: 1, name: 'Anita Desai', mobile: '+91 91234 56789', email: 'anita@example.com', total_bills: 2, lifetime_spend: 4650.00, tier: 'REGULAR' }
  ],
  categories: [
    { id: 1, shop_id: 1, name: 'Silk Sarees', description: 'Pure silk, Paithani, Kanjeevaram & Banarasi Sarees' },
    { id: 2, shop_id: 1, name: 'Cotton Sarees', description: 'Chanderi, Handloom & Daily Wear Cotton Sarees' },
    { id: 3, shop_id: 1, name: 'Designer Lehengas', description: 'Bridal & Party Wear Lehengas' },
    { id: 4, shop_id: 1, name: 'Kurtis & Suits', description: 'Semi-stitched and ready-made designer suits' },
    { id: 5, shop_id: 1, name: 'Dupattas & Stoles', description: 'Zari border and embroidered dupattas' },
    { id: 6, shop_id: 1, name: 'Accessories & Jewelry', description: 'Traditional boutique accessories' }
  ],
  products: [
    { id: 1, shop_id: 1, category_id: 1, name: 'Emerald Silk Kanjeevaram Saree', sku: 'MC-SK-9082', selling_price: 12499.00, cost_price: 9374.00, current_stock: 14 },
    { id: 2, shop_id: 1, category_id: 1, name: 'Royal Paithani Silk Saree (Gold Zari)', sku: 'MC-PS-4011', selling_price: 18500.00, cost_price: 13875.00, current_stock: 8 },
    { id: 3, shop_id: 1, category_id: 5, name: 'Kanjeevaram Gold Dupatta', sku: 'MC-KD-004', selling_price: 4000.00, cost_price: 3000.00, current_stock: 20 },
    { id: 4, shop_id: 1, category_id: 2, name: 'Chanderi Pure Cotton Saree', sku: 'MC-CC-102', selling_price: 2850.00, cost_price: 2100.00, current_stock: 25 },
    { id: 5, shop_id: 1, category_id: 4, name: 'Anarkali Embroidered Kurti Set', sku: 'MC-AK-701', selling_price: 3450.00, cost_price: 2500.00, current_stock: 12 },
    { id: 6, shop_id: 1, category_id: 6, name: 'Temple Gold Finish Choker', sku: 'MC-JW-441', selling_price: 4200.00, cost_price: 3150.00, current_stock: 5 }
  ],
  bills: [
    {
      id: 1,
      shop_id: 1,
      customer_id: 1,
      customer_name: 'Priya Sharma',
      customer_mobile: '+91 98765 43210',
      bill_number: 'MC-2026-001042',
      transaction_uuid: 'tx-seed-1042',
      sale_type: 'DETAILED',
      subtotal: 12499.00,
      discount_amount: 0.00,
      final_amount: 12499.00,
      estimated_profit: 0.00,
      actual_profit: 3125.00,
      profit_type: 'ACTUAL',
      payment_method: 'UPI',
      payment_status: 'PAID',
      bill_date: new Date().toISOString().replace('T', ' ').substring(0, 19),
      is_voided: 0,
      items: [
        { product_name: 'Emerald Silk Kanjeevaram Saree', quantity: 1, selling_price: 12499.00, line_total: 12499.00 }
      ]
    },
    {
      id: 2,
      shop_id: 1,
      customer_id: 2,
      customer_name: 'Sunita Patil',
      customer_mobile: '+91 98765 43211',
      bill_number: 'MC-2026-001041',
      transaction_uuid: 'tx-seed-1041',
      sale_type: 'QUICK',
      subtotal: 5950.00,
      discount_amount: 0.00,
      final_amount: 5950.00,
      estimated_profit: 1487.50,
      actual_profit: 0.00,
      profit_type: 'ESTIMATED',
      payment_method: 'CASH',
      payment_status: 'PAID',
      bill_date: new Date().toISOString().replace('T', ' ').substring(0, 19),
      is_voided: 0,
      items: []
    }
  ],
  expenses: [
    { id: 1, shop_id: 1, category: 'PACKAGING', amount: 850.00, payment_method: 'CASH', expense_date: new Date().toISOString().substring(0, 10), note: 'Boutique bags' }
  ],
  closings: [],
  targets: [
    { shop_id: 1, target_type: 'MONTHLY', year: new Date().getFullYear(), month: new Date().getMonth() + 1, target_amount: 500000.00 }
  ],
  syncLogs: []
};

module.exports = {
  memoryStore,
  isDbConnected
};
