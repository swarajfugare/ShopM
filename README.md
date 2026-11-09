# Matoshree Collection — Smart Shop Manager (Version 1.0)

A native Android application designed specifically for **Matoshree Collection** for billing, product management, customer directory with WhatsApp-style lists, Udhari (Credit) ledger tracking, barcode thermal label printing, and multi-device cloud synchronization.

---

## 🌟 Key Features

1. **POS Billing & Checkout**:
   - Quick Bill (instant walk-in sales) and Detailed Bill modes.
   - Continuous rapid barcode scanning for fast cart building.
   - Dynamic UPI QR code generation and Marathi/English localized receipts.

2. **Customer & Udhari Management**:
   - Customer profile with lifetime spend, total bills, and balance tracking.
   - Direct Udhari / Old Balance entry (`+ उधारी जोडा`) with instant receipt generation.
   - WhatsApp HD statement image sharing and thermal receipt printing.
   - WhatsApp Business style list management (Tags, Group filters, Multi-select bulk actions).

3. **Product Catalog & Thermal Labels**:
   - 5-digit numeric SKU generation for narrow 50mm × 38mm thermal stickers.
   - Category filtering, inventory stock tracking, and bulk barcode label printing.

4. **Bluetooth Thermal Printing**:
   - 58mm Bluetooth thermal printer integration for bills and sticky product labels.
   - Custom shop logo branding and QR payment integration.

5. **Cloud Synchronization & Offline-First**:
   - Realtime multi-device sync via Cloud Firestore and Firebase Storage.
   - Sub-second local SQLite persistent caching for uninterrupted offline billing and operations.

---

## 📱 Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose & Material 3
- **Architecture**: MVVM + Clean Architecture Flow
- **Cloud Backend**: Google Cloud Firestore & Firebase Auth
- **Local Persistence**: Room SQLite Database & Firestore Offline Persistence
- **Hardware Integration**: Bluetooth ESC/POS Thermal Printing & AndroidX CameraX (ML Kit Barcode Scanning)

---

## 🚀 Building and Running

1. Open the `android` folder in **Android Studio**.
2. Sync Gradle dependencies.
3. Connect your Android device or start an emulator.
4. Run `./gradlew assembleDebug` to build the APK.
