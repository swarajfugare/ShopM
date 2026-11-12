package com.matoshree.shopmanager.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class GoogleAuthManager(private val context: Context) {
    private val TAG = "GoogleAuthManager"

    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "matoshree_secure_auth_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fail closed: Never fallback to plaintext preferences
            try {
                context.deleteSharedPreferences("matoshree_secure_auth_prefs")
            } catch (_: Exception) {}
            null
        }
    }

    companion object {
        private const val KEY_AUTH_EMAIL = "auth_google_email"
        private const val KEY_AUTH_NAME = "auth_google_name"
        private const val KEY_AUTH_TIME = "auth_google_timestamp"

        // Authoritative Owner Whitelist (Must strictly match firestore.rules and storage.rules)
        val AUTHORIZED_EMAILS = setOf(
            "swarajfugare23@gmail.com",
            "sadhanafugare86@gmail.com"
        )
    }

    private val webClientId: String? by lazy {
        try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) context.getString(resId) else null
        } catch (e: Exception) {
            null
        }
    }

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        webClientId?.let { id ->
            if (id.isNotBlank()) {
                gsoBuilder.requestIdToken(id)
            }
        }

        GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun isUserLoggedIn(): Boolean {
        // 1. Primary check: Firebase Auth currentUser verified Google identity
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            val email = firebaseUser.email?.lowercase()?.trim()
            if (email != null && AUTHORIZED_EMAILS.contains(email)) {
                return true
            }
        }

        // 2. Local verified Keystore session
        val savedEmail = prefs?.getString(KEY_AUTH_EMAIL, null)?.lowercase()?.trim()
        if (savedEmail != null && AUTHORIZED_EMAILS.contains(savedEmail)) {
            return true
        }

        // 3. GoogleSignIn client cached account
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        val email = lastAccount?.email?.lowercase()?.trim()
        if (email != null && AUTHORIZED_EMAILS.contains(email)) {
            saveSession(email, lastAccount.displayName ?: "Owner")
            return true
        }

        return false
    }

    fun getLoggedInEmail(): String? {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            val email = firebaseUser.email?.lowercase()?.trim()
            if (email != null && AUTHORIZED_EMAILS.contains(email)) return email
        }

        val saved = prefs?.getString(KEY_AUTH_EMAIL, null)?.lowercase()?.trim()
        if (saved != null && AUTHORIZED_EMAILS.contains(saved)) return saved

        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        val email = lastAccount?.email?.lowercase()?.trim()
        if (email != null && AUTHORIZED_EMAILS.contains(email)) return email
        return null
    }

    suspend fun handleSignInResult(data: Intent?): Result<String> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            val email = account.email?.lowercase()?.trim()

            if (email != null && AUTHORIZED_EMAILS.contains(email)) {
                // Link Google account with Firebase Auth
                val idToken = account.idToken
                if (!idToken.isNullOrBlank()) {
                    try {
                        val credential = GoogleAuthProvider.getCredential(idToken, null)
                        FirebaseAuth.getInstance().signInWithCredential(credential).await()
                        Log.d(TAG, "Firebase Auth successfully verified with Google ID Token")
                    } catch (fe: Exception) {
                        Log.w(TAG, "Firebase credential link warning (offline or fallback active): ${fe.message}")
                    }
                }

                saveSession(email, account.displayName ?: "Owner")
                Result.success(email)
            } else {
                Log.w(TAG, "Google Login Denied: Unauthorized account")
                googleSignInClient.signOut()
                clearSession()
                Result.failure(Exception("प्रवेश नाकारला: हे खाते अधिकृत नाही. केवळ अधिकृत मालकच प्रवेश करू शकतात."))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-in failed with code: ${e.statusCode}")
            val msg = when (e.statusCode) {
                12501 -> "लॉगिन रद्द केले."
                12500 -> "Google प्रमाणीकरण त्रुटी (12500). कृपया Firebase Console मध्ये SHA-1 की जोडल्याची खात्री करा."
                10 -> "सर्टिफिकेट त्रुटी (10). कृपया Firebase Console मध्ये SHA-1 की जोडा."
                7 -> "इंटरनेट कनेक्शन नाही. कृपया इंटरनेट तपासा."
                else -> "Google प्रमाणीकरण त्रुटी (${e.statusCode})."
            }
            Result.failure(Exception(msg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveSession(email: String, name: String) {
        prefs?.edit()
            ?.putString(KEY_AUTH_EMAIL, email)
            ?.putString(KEY_AUTH_NAME, name)
            ?.putLong(KEY_AUTH_TIME, System.currentTimeMillis())
            ?.apply()
    }

    fun signOut(onComplete: () -> Unit = {}) {
        clearSession()
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {}
        googleSignInClient.signOut().addOnCompleteListener {
            onComplete()
        }
    }

    private fun clearSession() {
        prefs?.edit()?.clear()?.apply()
    }
}
