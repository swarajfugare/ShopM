# Release Notes v3.6.4 (Build 62) - Payment Cancellation Resilience & Integrity

## महत्त्वाचे बदल (Key Changes in v3.6.4)

### 1. "रद्द करा" (Cancel Payment) दुरुस्ती
- **Firestore Security Rules CEL Short-circuit**: जुन्या / लेगसी नोंदींमध्ये `gid` नसताना नियमांमध्ये property access त्रुटी (CEL runtime evaluation failure) मुळे येणारा `PERMISSION_DENIED` एरर दूर केला. `!('gid' in resource.data)` द्वारे सुरक्षित short-circuit लागू केला.
- **SetOptions.merge() Reversal Batch**: Reversal बॅचमध्ये `update()` ऐवजी `set(..., SetOptions.merge())` वापरल्याने सर्व्हरवर डॉक्युमेंट पाथ अनुपलब्ध असल्यावर येणारा `NOT_FOUND` दोष टाळला.
- **All Bill Copies Synchronized**: बिलाच्या दोन्ही प्रती (रूट `/bills/` आणि दुकान `/shops/{shopId}/bills/`) एकाच वेळी अपडेट होतात; रद्द केलेला भरणा बिलाच्या `payments` यादीतून काढून बाकी रक्कम (Pending) योग्य रीतीने पूर्ववत केली जाते.
- **Fail-safe Error Reporting & Rollback**: भरणा रद्द करणे अयशस्वी झाल्यास तात्पुरती स्क्रीन स्थिती तत्काळ पूर्ववत (Rollback) केली जाते आणि सर्व्हरकडून आलेला प्रत्यक्ष एरर मेसेज स्क्रीनवर स्पष्ट दिसतो.

### 2. Orphan Check Wiring (R2)
- ग्राहक प्रोफाईल आणि उधारी स्क्रीनवर डिलिट केलेल्या बिलांची पडताळणी (`isBillConfirmedDeleted`) जोडली गेली, ज्यामुळे खातेवही आणि बिलांची रक्कम न बदलता अचूक नोंद तपासली जाते.

---

## Owner Re-test Checklist (T1 - T8)
- [ ] **T1**: उधारी जमा करणे (उदा. 1000 बिलावर 200 जमा -> बाकी 500 होते).
- [ ] **T2**: Payments टॅबमध्ये 200 रुपयांची नोंद दिसणे व त्यावर "रद्द करा" बटण असणे.
- [ ] **T3**: "रद्द करा" वर क्लिक करून पुष्टी करणे -> भरणा रद्द होऊन बाकी पुन्हा 700 होणे.
- [ ] **T4**: Payments टॅबमध्ये मूळ भरणा "रद्द भरणा" म्हणून लाल/ऑडिट स्वरूपात दिसणे.
- [ ] **T5**: Khatavahi टॅबमध्ये `+200 (रद्द भरणा)` जमा होऊन चालू बाकी पूर्ववत होणे.
- [ ] **T6**: Bills टॅबमध्ये बिलाची बाकी (Pending) पूर्ववत 700 दिसणे.
- [ ] **T7**: इतर सर्व वैशिष्ट्ये (नवीन बिल, ग्राहक यादी, स्टॉक, खर्च, दैनिक क्लोजिंग) पूर्वीसारखीच कार्यरत राहणे.
- [ ] **T8**: सुरक्षा नियम तैनात करणे: `firebase deploy --only firestore:rules`.
