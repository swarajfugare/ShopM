package com.matoshree.shopmanager.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class GoogleAuthManager(private val context: Context) {
    private val TAG = "GoogleAuthManager"
    private val prefs = context.getSharedPreferences("matoshree_google_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTH_EMAIL = "auth_google_email"
        private const val KEY_AUTH_NAME = "auth_google_name"
        private const val KEY_AUTH_TIME = "auth_google_timestamp"

        val AUTHORIZED_EMAILS = setOf(
            "swarajfugare23@gmail.com",
            "sadhanafugare86@gmail.com"
        )
    }

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun isUserLoggedIn(): Boolean {
        // 1. Check local verified session
        val savedEmail = prefs.getString(KEY_AUTH_EMAIL, null)?.lowercase()?.trim()
        if (savedEmail != null && AUTHORIZED_EMAILS.contains(savedEmail)) {
            return true
        }

        // 2. Check GoogleSignIn last signed-in account
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        val email = lastAccount?.email?.lowercase()?.trim()
        if (email != null && AUTHORIZED_EMAILS.contains(email)) {
            saveSession(email, lastAccount.displayName ?: "Owner")
            return true
        }

        return false
    }

    fun handleSignInResult(data: Intent?): Result<String> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            val email = account.email?.lowercase()?.trim()

            if (email != null && AUTHORIZED_EMAILS.contains(email)) {
                saveSession(email, account.displayName ?: "Owner")
                Log.d(TAG, "Google Login Authorized: $email")
                Result.success(email)
            } else {
                Log.w(TAG, "Google Login Denied: Unauthorized account: $email")
                googleSignInClient.signOut()
                clearSession()
                Result.failure(Exception("प्रवेश नाकारला: हे खाते अधिकृत नाही. केवळ अधिकृत मालकच प्रवेश करू शकतात."))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-in failed with code: ${e.statusCode}, message: ${e.message}")
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
        prefs.edit()
            .putString(KEY_AUTH_EMAIL, email)
            .putString(KEY_AUTH_NAME, name)
            .putLong(KEY_AUTH_TIME, System.currentTimeMillis())
            .apply()
    }

    fun signOut(onComplete: () -> Unit = {}) {
        clearSession()
        googleSignInClient.signOut().addOnCompleteListener {
            onComplete()
        }
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
    }
}
