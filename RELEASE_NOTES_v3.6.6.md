# Release Notes v3.6.6 (Build 64) - Payment Cancellation Root Cause Fix & Simplification

## 1. निश्चित झालेले मूळ कारण (Confirmed Root Cause)
- **दोष**: "रद्द करा" वर क्लिक केल्यावर "भरणा रद्द झाला" असा मेसेज यायचा, परंतु Payments टॅबमधून भरणा जात नव्हता आणि बाकी पूर्ववत होत नव्हती.
- **तांत्रिक पुरावा**:
  - `FirestoreModels.kt` मधील `UdhariTransactionDto` मध्ये `var isReversed: Boolean = false` असे होते (विना `@PropertyName`).
  - Google Cloud Firestore Android POJO मॅपर (JavaBeans मानक) बुलियनच्या `is*` प्रॉपर्टीचे 'is' काढून डॉक्युमेंटमध्ये **`reversed`** असे फील्ड तयार करत होता आणि वाचतानाही `reversed` शोधत होता.
  - परंतु बॅकएंड `FirestoreUdhariRepository.reversePayment` मध्ये raw key **`"isReversed" to true`** लिहिली जात होती.
  - परिणामी `toObject(UdhariTransactionDto::class.java)` ने `"isReversed"` कडे दुर्लक्ष केले व `isReversed = false` कायम राहिले!
  - `Round21FirestoreFieldMappingTest` मध्ये Firestore च्या प्रत्यक्ष `CustomClassMapper` द्वारे हे सिद्ध झाले की सिरीअलायझेशन `reversed: true` आणि `isReversed: null` तयार करत होते, ज्यामुळे डी-सिरीअलायझेशन नेहमी `false` येत होते.

---

## 2. केलेली खरी दुरुस्ती (The Root Fix - S2 & S3)
1. **S2 - अचूक ऍनोटेशन**:
   - `UdhariTransactionDto` मध्ये `@get:PropertyName("isReversed") @set:PropertyName("isReversed") var isReversed: Boolean = false` लागू केले.
   - आता फायरस्टोअर डॉक्युमेंट सिरीअलायझेशन आणि डी-सिरीअलायझेशन दोन्ही थेट `"isReversed"` फील्ड वाचतात आणि लिहितात.
   - जुन्या रद्द केलेल्या नोंदी (ज्यामध्ये `"isReversed": true` होते) त्या आता योग्य रीतीने वाचल्या जातात.
2. **S3 - सर्व्हर पडताळणी (Never Report False Success)**:
   - रिव्हर्सल बॅच सबमिट झाल्यावर सर्व्हरवरून डॉक्युमेंट वाचून `isReversed == true` असल्याची खात्री केली जाते; अन्यथा स्पष्ट त्रुटी दाखवून रोलबॅक केला जातो.

---

## 3. अनावश्यक अतिरिक्त गोष्टी काढून टाकणे (S5 Cleanup - Restoration to v3.6.3 Behaviour)
मालकाच्या सूचनेनुसार v3.6.4 आणि v3.6.5 मधील अनावश्यक बाबी काढून टाकल्या:
1. `RealtimeSyncManager.startUdhariListener` मध्ये `.filter { !it.isReversed }` पूर्ववत जोडला गेला, ज्यामुळे रद्द झालेले भरणा आपोआप सर्व स्क्रीनवरून नाहीसे होतात.
2. Payments टॅबमधून राखाडी रंगातील "रद्द भरणा (Reversed)" ऑडिट रो आणि खातेवहीतील जास्तीची "+200 (रद्द भरणा)" नोंद काढली. मालकाच्या अपेक्षेनुसार **रद्द केलेला भरणा Payments टॅबमधून थेट गायब होतो**.
3. सेटिंग्जमधील "संशयास्पद रद्द व्यवहार" (Suspicious Reversals) विभाग काढून टाकला.
4. `Repositories.kt` मधील `restoreBillDueAfterReversal` आणि `CustomerViewModel` मधील अतिरिक्त रूम राइट्स काढून टाकले.
5. बिलातील सुरुवातीच्या पैशांवर "रद्द करा" ऐवजी `"बिलातील पैसे (बिल बदला/रद्द करा)"` सूचना सुरक्षितपणे कायम ठेवली.

---

## 4. चाचण्या व पडताळणी (Automated Tests & Regression Proof)
- `Round21FirestoreFieldMappingTest`:
  - `testUdhariTransactionDto_isReversed_serializationAndDeserialization`: PASS
  - `testReflection_booleanIsProperties_inFirestoreModels`: PASS
  - `testS3_serverVerificationFailure_returnsClearErrorMessage`: PASS
  - `testS4_multiBillAndGeneralPaymentReversal_restoresBalancesCleanly`: PASS
- `Round20PaymentCancellationRegressionTest`: PASS (2 tests)
- `LedgerConsistencyTest`, `UdhariLedgerConsistencyTest`, `Round13` ते `Round19` चे सर्व जुने टेस्ट्स: पूर्णपणे GREEN.

---

## 5. Owner Re-test Checklist (T1 - T7)
- [ ] **T1 TEST Customer**: ₹९०० चे बिल (₹५०० रोख + ₹४०० उधारी).
- [ ] **T2 "उधारी जमा"**: ₹२०० जमा करा -> बाकी ₹२०० होते; Payments टॅबमध्ये ₹५०० चा भरणा (सूचना, नो रद्द बटण) आणि ₹२०० ची नोंद दिसेल.
- [ ] **T3 "रद्द करा" दाबा**: ₹२०० च्या नोंदीवरील "रद्द करा" दाबून पुष्टी करा -> **₹२०० ची नोंद Payments टॅबमधून थेट गायब होईल** आणि बाकी लगेच पुन्हा ₹४०० होईल.
- [ ] **T4 ॲप बंद व चालू**: ॲप पूर्ण बंद करून उघडा -> बाकी ₹४०० राहील; दुसऱ्या फोनवरही ₹४०० दिसेल.
- [ ] **T5 पुन्हा ₹२०० जमा**: पुन्हा ₹२०० उधारी जमा करा -> बाकी ₹२०० होईल आणि नवीन नोंद येईल.
- [ ] **T6 होम स्क्रीन उधारी तुलना**: होम स्क्रीनवरील एकूण उधारीमध्ये केवळ या टेस्ट ग्राहकाचा बदल झालेला दिसेल.
- [ ] **T7 इतर सर्व कार्ये**: नवीन बिल, बिलांची यादी, दैनिक क्लोजिंग, अ‍ॅनालिटिक्स, खर्च, स्टॉक/किंमत, ट्रॅश, जुनी उधारी सर्व नेहमीप्रमाणे अचूक चालतील.

---

## APK तपशील
- **File**: `Matoshree-Collection-Latest.apk`
- **Package**: `com.matoshree.collection`
- **Version**: 3.6.6 (Build 64)
- **SHA-256 Checksum**:
  `348562db644f84311652c82764c546c81ccb9d566e8a4411f7050c578e8f2f62`
