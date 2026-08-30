require('dotenv').config();
const express = require('express');
const cors = require('cors');
const apiRoutes = require('./src/routes/api');

const app = express();
const PORT = process.env.PORT || 5000;

// Enable CORS
app.use(cors({
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Origin', 'X-Requested-With', 'Content-Type', 'Accept', 'Authorization', 'X-Device-ID']
}));

// Body parsing
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Root welcome / health
app.get('/', (req, res) => {
  res.json({
    status: 'success',
    message: 'Welcome to Matoshree Collection — Smart Shop Manager Backend API',
    endpoints: {
      health: '/api/v1/health',
      login: '/api/v1/auth/login',
      dashboard: '/api/v1/dashboard',
      sales: '/api/v1/sales',
      bills: '/api/v1/bills',
      customers: '/api/v1/customers',
      products: '/api/v1/products',
      sync: '/api/v1/sync'
    }
  });
});

// Mount versioned REST API
app.use('/api/v1', apiRoutes);

// 404 Handler
app.use((req, res) => {
  res.status(404).json({
    status: 'error',
    message: `Endpoint ${req.method} ${req.originalUrl} not found`,
    timestamp: Date.now()
  });
});

// Error handling middleware
app.use((err, req, res, next) => {
  console.error('[Error]', err);
  res.status(500).json({
    status: 'error',
    message: err.message || 'Internal Server Error',
    timestamp: Date.now()
  });
});

// Start Server when run directly
if (require.main === module) {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`====================================================`);
    console.log(`✨ Matoshree Collection Shop Manager Backend Running ✨`);
    console.log(`🚀 Server listening on: http://0.0.0.0:${PORT}`);
    console.log(`📡 Health Check URL:   http://localhost:${PORT}/api/v1/health`);
    console.log(`====================================================`);
  });
}

module.exports = app;
