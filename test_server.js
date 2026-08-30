const http = require('http');
const app = require('./server');

const server = app.listen(5001, async () => {
  console.log('Testing server on port 5001...');

  try {
    // 1. Test Health endpoint
    const healthRes = await makeRequest('GET', '/api/v1/health');
    console.log('[1] Health check result:', healthRes.status, healthRes.data.message);
    if (healthRes.data.status !== 'success') throw new Error('Health check failed');

    // 2. Test Login endpoint
    const loginRes = await makeRequest('POST', '/api/v1/auth/login', {
      mobile: '+919876543210',
      pin: '1234'
    });
    console.log('[2] Login result:', loginRes.status, loginRes.data.message);
    const token = loginRes.data.data.token;
    if (!token) throw new Error('Login failed to return token');

    // 3. Test Dashboard with Token
    const dashRes = await makeRequest('GET', '/api/v1/dashboard', null, token);
    console.log('[3] Dashboard summary result:', dashRes.status, 'Today sales:', dashRes.data.data.today.sales);

    // 4. Test Quick Sale
    const saleRes = await makeRequest('POST', '/api/v1/sales', {
      sale_type: 'QUICK',
      final_amount: 18450,
      payment_method: 'UPI',
      note: 'Verification test sale'
    }, token);
    console.log('[4] Quick sale result:', saleRes.status, 'Bill:', saleRes.data.data.bill.bill_number, 'Est Profit:', saleRes.data.data.bill.estimated_profit);

    console.log('\n===========================================');
    console.log('✅ ALL BACKEND ENDPOINTS VERIFIED & WORKING!');
    console.log('===========================================\n');
    server.close(() => process.exit(0));
  } catch (err) {
    console.error('❌ Test failed:', err.message);
    server.close(() => process.exit(1));
  }
});

function makeRequest(method, path, body = null, token = null) {
  return new Promise((resolve, reject) => {
    const postData = body ? JSON.stringify(body) : '';
    const headers = {
      'Content-Type': 'application/json'
    };
    if (postData) {
      headers['Content-Length'] = Buffer.byteLength(postData);
    }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    const req = http.request({
      hostname: 'localhost',
      port: 5001,
      path: path,
      method: method,
      headers: headers
    }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, data: JSON.parse(data) });
        } catch (e) {
          resolve({ status: res.statusCode, data: data });
        }
      });
    });

    req.on('error', reject);
    if (postData) req.write(postData);
    req.end();
  });
}
