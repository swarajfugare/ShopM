const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const fs = require('fs');

const PROJECT_ID = 'matoshree-collection-security-test';

describe('Zero-Trust Security Rules Unit Tests', () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: fs.readFileSync('firestore.rules', 'utf8'),
        host: '127.0.0.1',
        port: 8080
      },
      storage: {
        rules: fs.readFileSync('storage.rules', 'utf8'),
        host: '127.0.0.1',
        port: 9199
      }
    });
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    if (testEnv) {
      await testEnv.clearFirestore();
    }
  });

  describe('Firestore Security Rules', () => {
    it('1. Denies unauthenticated (anonymous) access', async () => {
      const anonContext = testEnv.unauthenticatedContext();
      const db = anonContext.firestore();
      await assertFails(db.collection('bills').get());
      await assertFails(db.collection('customers').doc('c1').set({ name: 'Test' }));
    });

    it('2. Denies non-whitelisted Google account', async () => {
      const attackerContext = testEnv.authenticatedContext('attacker-uid', {
        email: 'attacker@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = attackerContext.firestore();
      await assertFails(db.collection('bills').get());
      await assertFails(db.collection('customers').doc('c1').get());
    });

    it('3. Denies Google account with email_verified = false', async () => {
      const unverifiedContext = testEnv.authenticatedContext('unverified-owner', {
        email: 'swarajfugare23@gmail.com',
        email_verified: false,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = unverifiedContext.firestore();
      await assertFails(db.collection('bills').get());
    });

    it('4. Denies wrong sign_in_provider (e.g. password or anonymous)', async () => {
      const passwordContext = testEnv.authenticatedContext('password-user', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'password' }
      });
      const db = passwordContext.firestore();
      await assertFails(db.collection('bills').get());
    });

    it('5. Allows verified owner access', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = ownerContext.firestore();
      await assertSucceeds(db.collection('bills').doc('b1').set({
        finalAmount: 1500,
        createdAt: Date.now()
      }));
      await assertSucceeds(db.collection('customers').doc('c1').set({
        name: 'Valid Customer',
        mobile: '9876543210'
      }));
    });

    it('6. Allows secondary whitelisted owner access', async () => {
      const owner2Context = testEnv.authenticatedContext('owner-uid-2', {
        email: 'sadhanafugare86@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = owner2Context.firestore();
      await assertSucceeds(db.collection('bills').doc('b2').set({
        finalAmount: 500
      }));
    });

    it('7. Rejects invalid bill financial data (finalAmount < 0 or invalid type)', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = ownerContext.firestore();
      await assertFails(db.collection('bills').doc('b_invalid').set({
        finalAmount: -100
      }));
      await assertFails(db.collection('bills').doc('b_invalid2').set({
        finalAmount: 'invalid_string'
      }));
    });

    it('8. Default deny on unknown or unauthorized collections', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = ownerContext.firestore();
      await assertFails(db.collection('admin_secrets').doc('s1').set({ secret: '123' }));
      await assertFails(db.collection('unknown_collection').get());
    });

    it('9. Enforces GID validation, legacy creation, one-time repair, immutability, and overwrite protection', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const db = ownerContext.firestore();

      // (a) ULID create succeeds
      const validCusGid = 'cus_01HM5V7K8P9Q2W3E4R5T6Y7U8I';
      await assertSucceeds(db.collection('customers').doc(validCusGid).set({
        gid: validCusGid,
        name: 'Valid Gid Customer',
        mobile: '9876543210'
      }));

      // (b) Legacy create succeeds (prefix + 'L' + id)
      const legacyDocId = 'legacy_doc_123';
      const legacyGid = 'cus_Llegacy_doc_123';
      await assertSucceeds(db.collection('customers').doc(legacyDocId).set({
        gid: legacyGid,
        name: 'Legacy Customer',
        mobile: '9876543211'
      }));

      // (c) Legacy -> ULID one-time correction allowed
      const repairedUlid = 'cus_01HM5V7K8P9Q2W3E4R5T6Y7U99';
      await assertSucceeds(db.collection('customers').doc(legacyDocId).update({
        gid: repairedUlid
      }));

      // (d) Second GID change denied (now that it is a ULID, it is strictly immutable)
      await assertFails(db.collection('customers').doc(legacyDocId).update({
        gid: 'cus_01HM5V7K8P9Q2W3E4R5T6Y7UAA'
      }));

      // (e) Wrong prefix denied on create and update
      const badPrefixDoc = 'bil_01HM5V7K8P9Q2W3E4R5T6Y7U8I';
      await assertFails(db.collection('customers').doc(badPrefixDoc).set({
        gid: badPrefixDoc,
        name: 'Bad Prefix Customer'
      }));

      // (f) Overwrite denied (attempting to set without GID or with altered GID on existing doc)
      await assertFails(db.collection('customers').doc(validCusGid).set({
        name: 'Overwritten Customer Without Gid',
        mobile: '9876543210'
      }));
      await assertFails(db.collection('customers').doc(validCusGid).set({
        gid: 'cus_01HM5V7K8P9Q2W3E4R5T6Y7U99',
        name: 'Overwritten Customer With Different Gid',
        mobile: '9876543210'
      }));
    });
  });

  describe('Storage Security Rules', () => {
    it('1. Denies unauthenticated access to storage', async () => {
      const anonContext = testEnv.unauthenticatedContext();
      const storage = anonContext.storage();
      await assertFails(storage.ref('shops/matoshree_collection/branding/logo.png').getBytes());
    });

    it('2. Denies non-whitelisted account in storage', async () => {
      const attackerContext = testEnv.authenticatedContext('attacker-uid', {
        email: 'attacker@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const storage = attackerContext.storage();
      await assertFails(storage.ref('shops/matoshree_collection/branding/logo.png').getBytes());
    });

    it('3. Denies unverified Google email in storage', async () => {
      const unverifiedContext = testEnv.authenticatedContext('unverified', {
        email: 'swarajfugare23@gmail.com',
        email_verified: false,
        firebase: { sign_in_provider: 'google.com' }
      });
      const storage = unverifiedContext.storage();
      await assertFails(storage.ref('shops/matoshree_collection/branding/logo.png').getBytes());
    });

    it('4. Allows authorized owner access to branding images', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const storage = ownerContext.storage();
      await assertSucceeds(storage.ref('shops/matoshree_collection/branding/logo.png').getBytes());
    });

    it('5. Default deny on arbitrary storage paths', async () => {
      const ownerContext = testEnv.authenticatedContext('owner-uid-1', {
        email: 'swarajfugare23@gmail.com',
        email_verified: true,
        firebase: { sign_in_provider: 'google.com' }
      });
      const storage = ownerContext.storage();
      await assertFails(storage.ref('arbitrary/secret.txt').getBytes());
      await assertFails(storage.ref('backups/db.dump').getBytes());
    });
  });
});
