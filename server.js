require('dotenv').config();
const express = require('express');
const cors = require('cors');
const apiRoutes = require('./src/routes/api');

const app = express();
const DEFAULT_PORT = parseInt(process.env.PORT || '8080', 10);

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

// Function to start server with automatic port retry if busy (e.g. macOS AirPlay on 5000)
function startServer(port = DEFAULT_PORT, attempts = 0) {
  const server = app.listen(port, '0.0.0.0', () => {
    console.log(`\n====================================================`);
    console.log(`✨ Matoshree Collection Shop Manager Backend Running ✨`);
    console.log(`🚀 Server listening on: http://localhost:${port}`);
    console.log(`📡 Health Check URL:   http://localhost:${port}/api/v1/health`);
    console.log(`====================================================\n`);
  });

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE' && attempts < 5) {
      const nextPort = port + 1;
      console.warn(`[!] Port ${port} is currently busy (e.g. macOS AirPlay). Automatically trying port ${nextPort}...`);
      startServer(nextPort, attempts + 1);
    } else {
      console.error('[Server Error]', err);
    }
  });
}

// Start Server when run directly
if (require.main === module) {
  startServer(DEFAULT_PORT);
}

module.exports = app;
