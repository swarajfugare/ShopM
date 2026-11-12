# Matoshree Collection — Security Architecture & Operation Manual

## 1. Overview & Access Architecture

Matoshree Collection Shop Manager implements a multi-layered defence-in-depth model for retail shop operations, customer management, inventory, and billing.

### Authentication & Authorization Flow
1. **Google Identity Authentication**:
   - The owner signs in using Google Sign-In.
   - The app acquires an authenticated Google ID Token and exchanges it with Firebase Authentication using `GoogleAuthProvider.getCredential(idToken)`.
   - `FirebaseAuth.currentUser` serves as the authoritative authentication state. Anonymous authentication is disabled.
2. **Device-Local PIN Verification**:
   - Following Google authentication, the user authenticates via a 4-digit PIN.
   - The PIN is verified locally on the device using Android Keystore cryptographic signatures.
3. **Cloud Access**:
   - Cloud Firestore and Cloud Storage enforce access control server-side through security rules, evaluating the user's verified Google provider token against the authorized owner list.

---

## 2. Protection Mechanisms & Implementation Details

### Device-Local PIN Security
- **Cryptographic Hashing**: The PIN is signed using a non-extractable HMAC-SHA256 key generated inside the `AndroidKeyStore` (`KeyProperties.PURPOSE_SIGN`). The key generator attempts StrongBox-backed hardware keystore where supported, falling back to standard hardware Keystore.
- **Salt Generation**: A cryptographically random 16-byte salt (`SecureRandom`) is generated per installation and stored alongside the signed digest: `pin_mac = HMAC(keystoreKey, salt || pin)`.
- **Constant-Time Comparison**: Comparison of the computed MAC and stored MAC is performed using `MessageDigest.isEqual` to prevent side-channel timing attacks.
- **Proactive Lockout Rate Limiting**:
  - 3 failed attempts: 30-second lockout.
  - 5 failed attempts: 5-minute lockout.
  - 10 failed attempts: 15-minute lockout.
  - >10 failed attempts: 1-hour lockout.
  - Lockout counters and expiration timestamps are stored exclusively in Keystore-backed `EncryptedSharedPreferences`.
- **No Default PIN or Backdoors**:
  - The application contains no default PINs, static hashes, or master recovery passwords.
  - Insecure PIN candidates (`1234`, `2580`) are rejected during setup.
  - PIN reset is only possible by re-authenticating with the verified Google account.

### Storage & Session Management
- **Hardware Encryption**: Tokens and sensitive configuration (including WhatsApp CRM credentials) are stored using `EncryptedSharedPreferences` backed by `MasterKey` (AES256-GCM / AES256-SIV).
- **Fail-Closed Behavior**: If `EncryptedSharedPreferences` or the Android Keystore throws an exception, the app does not fall back to unencrypted storage. The corrupted keys and preferences are deleted, the local session is cleared, and re-authentication is required.
- **Legacy Storage Purge**: All legacy unencrypted or fallback preferences are purged upon initialization.

### Screen & Inactivity Protection
- **FLAG_SECURE**: WindowManager `FLAG_SECURE` is applied to prevent screen capture, screen recording, and to obscure the app's contents in the Android Recent Apps / multitasking thumbnail view. A toggle is provided in Settings.
- **Process-Wide Auto-Lock**: `AppLockManager` monitors application lifecycle via `ProcessLifecycleOwner`. When the app remains in the background longer than the configured timeout (immediately, 30s default, 1m, or 5m), it automatically locks and requires PIN or biometric verification to resume.

### Server-Side Cloud Rules
- **Firestore Rules**:
  - `isAuthorizedOwner()` verifies that `request.auth != null`, `sign_in_provider == 'google.com'`, `email_verified == true`, and that the token email matches the authorized owner list.
  - Financial validation: bill documents require non-negative `finalAmount` numeric values.
  - Customer, product, and udhari documents enforce field length and numeric boundary checks.
  - Default deny: any unmatched collection path is explicitly denied.
- **Storage Rules**:
  - Branding image uploads require verified owner credentials, max 5 MB file size, and image MIME types (`image/png`, `image/jpeg`, `image/webp`).
  - All other paths default to deny.

### Privacy & Build Protections
- **Scoped File Sharing**: `file_paths.xml` restricts `FileProvider` sharing exclusively to isolated subdirectories (`cache/receipts/`, `cache/labels/`, `cache/reports/`).
- **Private Notifications**: All notification builders specify `NotificationCompat.VISIBILITY_PRIVATE`, hiding customer names, amounts, and balances from the lock screen.
- **Log Stripping & Sanitization**: ProGuard / R8 rules strip debug log statements in release builds. Application logs do not output personal customer details, phone numbers, emails, tokens, or transaction amounts.
- **Release Signing**: Release builds require a configured `keystore.properties` and verify the expected certificate fingerprint (defence in depth). If `keystore.properties` is missing, `assembleRelease` fails with an explicit error.

---

## 3. Known Limitations

1. **Rooted / Compromised Devices**:
   - If the device operating system is rooted, an attacker with root privileges can inspect application memory or hook native methods. Root detection and Frida/debugger detection are present as defence in depth but cannot guarantee integrity on compromised operating systems.
2. **Local Session Trust**:
   - An authenticated session on an unlocked device permits offline operations. Biometric and PIN authentication protect against physical opportunistic access, but not against hardware extraction from a compromised kernel.
3. **Unencrypted Local Room Database**:
   - The local SQLite Room database is stored within the private Android sandbox (`/data/data/com.matoshree.collection/databases/`), protected by Linux OS UID isolation, but is not SQLCipher-encrypted at rest.
4. **Unencrypted Offline Firestore Cache**:
   - The Cloud Firestore offline persistence cache is stored within the private app sandbox and is unencrypted at rest.
5. **Sideloaded Distribution**:
   - Because the application is sideloaded rather than distributed through Google Play, Google Play Protect / Play Integrity API attestation is not available to verify device software authenticity.

---

## 4. Owner Manual Operations Checklist

The following tasks require access to external cloud consoles and local offline keys and must be performed directly by the owner:

1. **Rotate WhatsApp CRM API Key**:
   - Because the previous API key was exposed in older git commits, access the WhatsApp CRM dashboard and regenerate/revoke the API key.
   - Open app **Settings** > **WhatsApp Reminders** > **Edit** and enter the newly generated URL and API Key.

2. **Deploy Security Rules**:
   - Deploy the updated rules to Firebase using the Firebase CLI:
     ```bash
     firebase deploy --only firestore:rules,storage
     ```
   - In the Firebase Console (**Authentication** > **Sign-in method**):
     - Ensure the **Google** sign-in provider is enabled.
     - Ensure the **Anonymous** sign-in provider is disabled.
     - Ensure **Email/Password** sign-in is disabled.
     - Delete any existing anonymous user accounts in Authentication > Users.

3. **Google Cloud Console API Key Restrictions**:
   - Navigate to Google Cloud Console > APIs & Services > Credentials.
   - Restrict the Android API Key used by Firebase:
     - Set Application Restrictions to **Android apps**.
     - Add package name `com.matoshree.collection` and the release keystore SHA-1 fingerprint.
   - Download the updated `google-services.json` if configuration changed.

4. **Release Keystore Setup**:
   - Generate a private release keystore using `keytool`:
     ```bash
     keytool -genkey -v -keystore release-keystore.jks -alias matoshree-release -keyalg RSA -keysize 2048 -validity 10000
     ```
   - Store the keystore file and passwords in a secure offline location. Never commit the keystore or `keystore.properties` to version control.
   - Copy `keystore.properties.example` to `keystore.properties` and fill in the paths and passwords.
   - Obtain the SHA-256 fingerprint:
     ```bash
     keytool -list -v -keystore release-keystore.jks
     ```
     Add the hex fingerprint (without colons) to `certSha256` in `keystore.properties`.

5. **Historical Secret Invalidation**:
   - Any credentials present in past git commits (prior to v3.3.0) must be considered burned. Rotate all external API keys and revoke past tokens. Optionally, prune past repository history using `git-filter-repo`.
