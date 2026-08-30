require('dotenv').config();
const mysql = require('mysql2/promise');

const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || process.env.DB_PASS || '',
  database: process.env.DB_NAME || 'matoshree_db',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0,
  charset: 'utf8mb4'
};

let pool = null;
let isConnected = false;

try {
  pool = mysql.createPool(dbConfig);
  // Test connection
  pool.getConnection()
    .then(conn => {
      isConnected = true;
      console.log(`[✓] MySQL Database Connected successfully: ${dbConfig.database}@${dbConfig.host}`);
      conn.release();
    })
    .catch(err => {
      isConnected = false;
      console.warn(`[!] MySQL Connection Note: ${err.message}. Running in Mock/In-Memory Mode for local testing until Hostinger credentials are provided.`);
    });
} catch (e) {
  console.warn(`[!] Failed to initialize MySQL pool: ${e.message}`);
}

module.exports = {
  pool,
  isDbConnected: () => isConnected,
  query: async (sql, params = []) => {
    if (pool && isConnected) {
      return await pool.execute(sql, params);
    }
    throw new Error('Database not connected');
  }
};
