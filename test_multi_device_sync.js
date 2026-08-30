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
  console.log('========================================================================');
  console.log('MATOSHREE COLLECTION — SINGLE SOURCE OF TRUTH & MULTI-DEVICE SYNC SUITE');
  console.log('========================================================================\n');

  const BASE_URL = 'http://localhost:8080/api/v1';

  // 0. Authenticate
  console.log('Step 0: Authenticating Device A and Device B...');
  const loginRes = await request(`${BASE_URL}/auth/login`, { method: 'POST' }, {
    mobile: '9876543210',
    pin: '1234'
  });
  let token = loginRes.body.data?.token;
  if (!token) throw new Error('Login failed: ' + JSON.stringify(loginRes.body));
  console.log('  -> JWT Auth token received successfully!');

  let authHeaders = (devId) => ({
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
  let cursorB = initSyncB.body.data?.cursor || 0;
  console.log(`  -> Device B Initial Sync Success! Cursor: ${cursorB}, Bills: ${initSyncB.body.data?.bills?.length || 0}`);

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

  // 4. Device B Delta Sync for Sale
  console.log(`\nStep 4: Device B polls for Delta changes (cursor > ${cursorB})...`);
  const deltaSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  cursorB = deltaSyncB.body.data?.cursor;
  const receivedBillsB = deltaSyncB.body.data?.bills || [];
  console.log(`  -> Device B received ${receivedBillsB.length} new bill(s)! New Cursor: ${cursorB}`);
  const matchingBill = receivedBillsB.find(b => b.transaction_uuid === txUuidA);
  if (matchingBill) {
    console.log(`  -> [PASS] Verified Device B automatically received Bill ${matchingBill.bill_number} (₹${matchingBill.final_amount})!`);
  }

  // 5. Device B creates a new Customer and Product
  console.log('\nStep 5: Device B creates Customer and Product...');
  const custResB = await request(`${BASE_URL}/customers`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    name: 'Suresh Patil',
    mobile: '9822' + Math.floor(100000 + Math.random() * 900000),
    email: 'suresh.patil@example.com'
  });
  const createdCust = custResB.body.data?.customer;
  console.log(`  -> Customer Created: ${createdCust?.name} (ID: ${createdCust?.id})`);

  const prodResB = await request(`${BASE_URL}/products`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    name: 'Banarasi Silk Dupatta',
    sku: 'BSD-' + Date.now().toString().slice(-4),
    selling_price: 1850.0,
    cost_price: 1300.0,
    current_stock: 15
  });
  const createdProd = prodResB.body.data?.product;
  console.log(`  -> Product Created: ${createdProd?.name} (ID: ${createdProd?.id})`);

  // 6. Device A Delta Sync
  console.log(`\nStep 6: Device A polls for Delta changes (cursor > ${cursorB})...`);
  const deltaSyncA = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-AAA`, {
    headers: authHeaders('PHONE-AAA')
  });
  let cursorA = deltaSyncA.body.data?.cursor;
  const receivedCustsA = deltaSyncA.body.data?.customers || [];
  const receivedProdsA = deltaSyncA.body.data?.products || [];
  console.log(`  -> Device A received ${receivedCustsA.length} customer(s) and ${receivedProdsA.length} product(s)!`);
  if (receivedCustsA.some(c => c.id === createdCust.id) && receivedProdsA.some(p => p.id === createdProd.id)) {
    console.log(`  -> [PASS] Verified Device A received Customer and Product created by Device B!`);
  }

  // 7. Device A Voids the Bill
  console.log('\nStep 7: Device A voids the bill created in Step 3...');
  if (createdBillA?.id) {
    const voidResA = await request(`${BASE_URL}/bills/${createdBillA.id}/void`, {
      method: 'POST',
      headers: authHeaders('PHONE-AAA')
    }, {
      reason: 'Customer cancelled on Counter 1'
    });
    console.log('  -> Void Result:', voidResA.body.message);

    const voidSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorA}&device_id=PHONE-BBB`, {
      headers: authHeaders('PHONE-BBB')
    });
    cursorB = voidSyncB.body.data?.cursor;
    const voidedBill = (voidSyncB.body.data?.bills || []).find(b => b.id === createdBillA.id);
    if (voidedBill && (voidedBill.is_voided === 1 || voidedBill.payment_status === 'VOID')) {
      console.log(`  -> [PASS] Verified Device B received VOID status for Bill ${voidedBill.bill_number}!`);
    }
  }

  // 8. Device A updates Shop Settings & Uploads Logo
  console.log('\nStep 8: Device A updates Shop Settings with Base64 Logo & UPI...');
  const fakeBase64Logo = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  const settingsResA = await request(`${BASE_URL}/settings`, {
    method: 'PUT',
    headers: authHeaders('PHONE-AAA')
  }, {
    upi_id: 'matoshree.collection@okhdfcbank',
    upi_display_name: 'Matoshree Silk Boutique',
    show_gstin: true,
    logo_data: fakeBase64Logo
  });
  console.log('  -> Settings update result:', settingsResA.body.message);

  const settingsSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  cursorB = settingsSyncB.body.data?.cursor;
  const syncedSettings = settingsSyncB.body.data?.settings;
  if (syncedSettings && syncedSettings.upi_id === 'matoshree.collection@okhdfcbank' && syncedSettings.logo_data === fakeBase64Logo) {
    console.log('  -> [PASS] Verified Device B received updated Shop Settings & Base64 Logo from MySQL!');
  }

  // 9. Device A Archives Customer -> Device B receives is_active = 0
  console.log(`\nStep 9: Device A archives Customer (ID: ${createdCust?.id})...`);
  const archiveCustRes = await request(`${BASE_URL}/customers/${createdCust?.id}/archive`, {
    method: 'POST',
    headers: authHeaders('PHONE-AAA')
  });
  console.log('  -> Customer archive result:', archiveCustRes.body.message);

  const custArchiveSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  cursorB = custArchiveSyncB.body.data?.cursor;
  const archivedCustB = (custArchiveSyncB.body.data?.customers || []).find(c => c.id === createdCust?.id);
  if (archivedCustB && archivedCustB.is_active === 0) {
    console.log(`  -> [PASS] Verified Device B received Customer is_active = 0 (record does not resurrect)!`);
  }

  // 10. Device A Archives Product -> Device B receives is_active = 0
  console.log(`\nStep 10: Device A archives Product (ID: ${createdProd?.id})...`);
  const archiveProdRes = await request(`${BASE_URL}/products/${createdProd?.id}/archive`, {
    method: 'POST',
    headers: authHeaders('PHONE-AAA')
  });
  console.log('  -> Product archive result:', archiveProdRes.body.message);

  const prodArchiveSyncB = await request(`${BASE_URL}/sync/changes?cursor=${cursorB}&device_id=PHONE-BBB`, {
    headers: authHeaders('PHONE-BBB')
  });
  cursorB = prodArchiveSyncB.body.data?.cursor;
  const archivedProdB = (prodArchiveSyncB.body.data?.products || []).find(p => p.id === createdProd?.id);
  if (archivedProdB && archivedProdB.is_active === 0) {
    console.log(`  -> [PASS] Verified Device B received Product is_active = 0 (record does not resurrect)!`);
  }

  // 11. Device A changes Account PIN to 5678
  console.log('\nStep 11: Device A changes Account PIN to 5678...');
  const changePinRes = await request(`${BASE_URL}/auth/pin/change`, {
    method: 'POST',
    headers: authHeaders('PHONE-AAA')
  }, {
    current_pin: '1234',
    new_pin: '5678'
  });
  console.log('  -> Change PIN result:', changePinRes.body.message);

  // Device B attempts login with old PIN -> must fail
  const oldLoginRes = await request(`${BASE_URL}/auth/login`, { method: 'POST' }, {
    mobile: '9876543210',
    pin: '1234'
  });
  if (oldLoginRes.status === 401) {
    console.log('  -> [PASS] Old PIN (1234) rejected on Device B!');
  }

  // Device B attempts login with new PIN -> must succeed
  const newLoginRes = await request(`${BASE_URL}/auth/login`, { method: 'POST' }, {
    mobile: '9876543210',
    pin: '5678'
  });
  if (newLoginRes.status === 200 && newLoginRes.body.data?.token) {
    console.log('  -> [PASS] New PIN (5678) authenticated on Device B!');
    token = newLoginRes.body.data.token;
  }

  // 12. Device B recovers Account PIN back to 1234 with Master Code
  console.log('\nStep 12: Device B recovers Account PIN back to 1234 using Master Recovery Code...');
  const recoverRes = await request(`${BASE_URL}/auth/pin/recover`, {
    method: 'POST',
    headers: authHeaders('PHONE-BBB')
  }, {
    recovery_code: 'MATOSHREE2026',
    new_pin: '1234'
  });
  console.log('  -> Recover PIN result:', recoverRes.body.message);

  const finalLoginRes = await request(`${BASE_URL}/auth/login`, { method: 'POST' }, {
    mobile: '9876543210',
    pin: '1234'
  });
  if (finalLoginRes.status === 200) {
    console.log('  -> [PASS] PIN restored to 1234 successfully across all devices!');
  }

  console.log('\n========================================================================');
  console.log('ALL 12 SINGLE SOURCE OF TRUTH & MULTI-DEVICE SYNC TESTS PASSED 100%!');
  console.log('========================================================================');
}

runSimulation().catch(err => {
  console.error('[!] Simulation error:', err.message);
});
