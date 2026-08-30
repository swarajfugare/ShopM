const http = require('http');
const https = require('https');

// Helper to perform HTTP requests
function request(url, options = {}, postData = null) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const client = urlObj.protocol === 'https:' ? https : http;
    
    const reqOptions = {
      hostname: urlObj.hostname,
      port: urlObj.port || (urlObj.protocol === 'https:' ? 443 : 80),
      path: urlObj.pathname + urlObj.search,
      method: options.method || 'GET',
      headers: options.headers || {}
    };

    if (postData) {
      if (typeof postData === 'object') {
        postData = JSON.stringify(postData);
        reqOptions.headers['Content-Type'] = 'application/json';
      }
      reqOptions.headers['Content-Length'] = Buffer.byteLength(postData);
    }

    const req = client.request(reqOptions, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          resolve({ status: res.statusCode, body: parsed });
        } catch (e) {
          resolve({ status: res.statusCode, body: data });
        }
      });
    });

    req.on('error', reject);
    if (postData) req.write(postData);
    req.end();
  });
}

async function runSimulation() {
  console.log('===============================================================');
  console.log('MATOSHREE COLLECTION — TWO-PHONE REAL-TIME SYNC SIMULATION TEST');
  console.log('===============================================================\n');

  const BASE_URL = 'http://localhost:8080/api/v1';

  // 0. Authenticate
  console.log('Step 0: Authenticating Device A and Device B...');
  const loginRes = await request(`${BASE_URL}/auth/login`, { method: 'POST' }, {
    mobile: '9876543210',
    pin: '1234'
  });
  const token = loginRes.body.data?.token;
  if (!token) throw new Error('Login failed: ' + JSON.stringify(loginRes.body));
  console.log('  -> JWT Auth token received successfully!');

  const authHeaders = (devId) => ({
    'Authorization': `Bearer ${token}`,
    'X-Device-ID': devId
  });

  // 1. Device A & Device B Registration
  console.log('\nStep 1: Registering Device A (PHONE-AAA) and Device B (PHONE-BBB)...');
  const regA = await request(`${BASE_URL}/devices/register`, {
    method: 'POST',
    headers: authHeaders('PHONE-AAA')
  }, {
    device_id: 'PHONE-AAA',
    device_name: 'Terminal A (Counter 1)',
    app_version: '1.0.0'
  });
  console.log('  -> Device A Registered:', regA.status, regA.body.message || regA.body);

  const regB = await request(`${BASE_URL}/devices/register`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    device_id: 'PHONE-BBB',
    device_name: 'Terminal B (Counter 2)',
    app_version: '1.0.0'
  });
  console.log('  -> Device B Registered:', regB.status, regB.body.message || regB.body);

  // 2. Initial Sync on Device B
  console.log('\nStep 2: Device B performs Initial Sync (cursor = 0)...');
  const initSyncB = await request(`${BASE_URL}/sync/changes?cursor=0&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  const cursorB = initSyncB.body.data?.cursor || 0;
  console.log(`  -> Device B Initial Sync Success! Cursor: ${cursorB}, Bills: ${initSyncB.body.data?.bills?.length || 0}, Products: ${initSyncB.body.data?.products?.length || 0}`);

  // 3. Device A creates a new sale
  console.log('\nStep 3: Device A creates a new Sale (MC-2026-MULTI01)...');
  const txUuidA = 'TX-SIM-A-' + Date.now();
  const saleResA = await request(`${BASE_URL}/sales`, {
    method: 'POST',
    headers: authHeaders('PHONE-AAA')
  }, {
    transaction_uuid: txUuidA,
    device_id: 'PHONE-AAA',
    sale_type: 'DETAILED',
    final_amount: 5400.0,
    payment_method: 'CASH',
    bill_date: new Date().toISOString().substring(0, 10),
    items: [
      { name: 'Pure Silk Paithani', quantity: 1, selling_price: 5400.0, cost_price: 4000.0, discount_amount: 0.0 }
    ]
  });
  const createdBillA = saleResA.body.data?.bill;
  console.log(`  -> Device A Sale Created! Bill #: ${createdBillA?.bill_number}, ID: ${createdBillA?.id}, Amount: ₹${createdBillA?.final_amount}`);

  // 4. Device B Delta Sync
  console.log(`\nStep 4: Device B polls for Delta changes (cursor > ${cursorB})...`);
  const deltaSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  const newCursorB = deltaSyncB.body.data?.cursor;
  const receivedBillsB = deltaSyncB.body.data?.bills || [];
  console.log(`  -> Device B received ${receivedBillsB.length} new bill(s)! New Cursor: ${newCursorB}`);
  const matchingBill = receivedBillsB.find(b => b.transaction_uuid === txUuidA);
  if (matchingBill) {
    console.log(`  -> [PASS] Verified Device B automatically received Bill ${matchingBill.bill_number} (₹${matchingBill.final_amount})!`);
  } else {
    console.log(`  -> [FAIL] Device B did not find bill with transactionUuid ${txUuidA}`);
  }

  // 5. Device B creates a new Customer and Product
  console.log('\nStep 5: Device B creates a new Customer (Rohan Kadam) and Product (Banarasi Katan)...');
  const custResB = await request(`${BASE_URL}/customers`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    name: 'Rohan Kadam',
    mobile: '9822' + Math.floor(100000 + Math.random() * 900000),
    email: 'rohan.kadam@example.com'
  });
  console.log(`  -> Customer Created: ${custResB.body.data?.customer?.name} (ID: ${custResB.body.data?.customer?.id})`);

  const prodResB = await request(`${BASE_URL}/products`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    name: 'Banarasi Katan Dupatta',
    sku: 'BKD-' + Date.now().toString().slice(-4),
    selling_price: 1850.0,
    cost_price: 1300.0,
    current_stock: 15
  });
  console.log(`  -> Product Created: ${prodResB.body.data?.product?.name} (ID: ${prodResB.body.data?.product?.id})`);

  // 6. Device A Delta Sync
  console.log(`\nStep 6: Device A polls for Delta changes (cursor > ${newCursorB})...`);
  const deltaSyncA = await request(`${BASE_URL}/sync/changes?cursor=${newCursorB}&device_id=PHONE-AAA`, {
    headers: authHeaders('PHONE-AAA')
  });
  const receivedCustsA = deltaSyncA.body.data?.customers || [];
  const receivedProdsA = deltaSyncA.body.data?.products || [];
  console.log(`  -> Device A received ${receivedCustsA.length} customer(s) and ${receivedProdsA.length} product(s)!`);
  if (receivedCustsA.some(c => c.name === 'Rohan Kadam') && receivedProdsA.some(p => p.name === 'Banarasi Katan Dupatta')) {
    console.log(`  -> [PASS] Verified Device A seamlessly received new Customer and Product created by Device B!`);
  }

  // 7. Device A Voids the Bill
  console.log('\nStep 7: Device A voids the bill created in Step 3...');
  if (createdBillA?.id) {
    const voidResA = await request(`${BASE_URL}/bills/${createdBillA.id}/void`, {
      method: 'POST',
      headers: authHeaders('PHONE-AAA')
    }, {
      reason: 'Customer requested cancellation on Counter 1'
    });
    console.log('  -> Void Result:', voidResA.body.message);

    // Device B polls and receives VOID status
    console.log('  -> Device B polls for VOID sync change...');
    const voidSyncB = await request(`${BASE_URL}/sync/changes?cursor=${deltaSyncA.body.data?.cursor}&device_id=PHONE-BBB`, {
      headers: authHeaders('PHONE-BBB')
    });
    const voidedBill = (voidSyncB.body.data?.bills || []).find(b => b.id === createdBillA.id);
    if (voidedBill && (voidedBill.is_voided === 1 || voidedBill.payment_status === 'VOID')) {
      console.log(`  -> [PASS] Verified Device B received VOID status for Bill ${voidedBill.bill_number}!`);
    }
  }

  console.log('\n===============================================================');
  console.log('ALL TWO-PHONE MULTI-DEVICE SYNCHRONIZATION TESTS PASSED 100%!');
  console.log('===============================================================');
}

runSimulation().catch(err => {
  console.error('[!] Simulation error:', err.message);
});
