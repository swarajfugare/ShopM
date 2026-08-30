const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middleware/auth');
const {
  authController,
  dashboardController,
  salesController,
  billsController,
  customersController,
  productsController,
  categoriesController,
  expensesController,
  closingController,
  analyticsController,
  syncController,
  settingsController
} = require('../controllers/apiControllers');

// Health Check
router.get('/health', (req, res) => {
  res.json({
    status: 'success',
    message: 'Matoshree Collection Backend REST API is operational',
    data: {
      service: 'Matoshree Collection Node.js Backend',
      version: '1.0.0',
      uptime_seconds: Math.floor(process.uptime()),
      timestamp: Date.now()
    }
  });
});

// Public Auth routes
router.post('/auth/login', authController.login);

// Friendly handler for browser GET requests on /auth/login
router.get('/auth/login', (req, res) => {
  res.json({
    status: 'info',
    message: 'Authentication endpoint requires a POST request with JSON credentials.',
    method: 'POST',
    endpoint: '/api/v1/auth/login',
    sample_request: {
      headers: { 'Content-Type': 'application/json' },
      body: {
        mobile: '+919876543210',
        pin: '1234'
      }
    }
  });
});

// Protected routes (JWT required)
router.use(authenticateToken);

// Auth Me
router.get('/auth/me', authController.me);

// Dashboard
router.get('/dashboard', dashboardController.getSummary);

// Sales (Atomic Bill & Inventory)
router.post('/sales', salesController.create);

// Bills
router.get('/bills', billsController.getAll);
router.get('/bills/:id', billsController.getById);
router.post('/bills/:id/void', billsController.voidBill);

// Customers
router.get('/customers', customersController.getAll);
router.post('/customers', customersController.create);

// Products & Categories
router.get('/products', productsController.getAll);
router.post('/products', productsController.create);
router.get('/categories', categoriesController.getAll);

// Expenses
router.get('/expenses', expensesController.getAll);
router.post('/expenses', expensesController.create);

// Daily Closing
router.get('/daily-closing', closingController.getPreview);
router.post('/daily-closing', closingController.submit);

// Analytics
router.get('/analytics/daily', analyticsController.getDaily);
router.get('/analytics/monthly', analyticsController.getMonthly);

// Batch Offline Sync
router.post('/sync', syncController.syncBatch);

// Settings
router.get('/settings', settingsController.getSettings);

module.exports = router;
