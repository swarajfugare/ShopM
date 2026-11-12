package com.matoshree.shopmanager.data.firebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object FirebaseStorageManager {
    private const val TAG = "FirebaseStorageManager"
    private const val LOGO_STORAGE_PATH = "branding/shop_logo.png"

    private fun logoRef(): StorageReference {
        return FirebaseManager.storage.reference.child(LOGO_STORAGE_PATH)
    }

    suspend fun uploadLogo(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            FirebaseManager.ensureAuthenticated()

            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBmp = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBmp == null) return@withContext null

            val maxDimension = 400
            val scale = (maxDimension.toFloat() / originalBmp.width.coerceAtLeast(originalBmp.height)).coerceAtMost(1.0f)
            val scaledBmp = Bitmap.createScaledBitmap(
                originalBmp,
                (originalBmp.width * scale).toInt().coerceAtLeast(1),
                (originalBmp.height * scale).toInt().coerceAtLeast(1),
                true
            )

            // Cache locally
            val localLogoFile = File(context.filesDir, "shop_logo.png")
            val fos = FileOutputStream(localLogoFile)
            scaledBmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            fos.flush()
            fos.close()

            // Prepare byte stream for Firebase Storage
            val baos = ByteArrayOutputStream()
            scaledBmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
            val data = baos.toByteArray()

            val ref = logoRef()
            ref.putBytes(data).await()
            val downloadUrl = try { ref.downloadUrl.await().toString() } catch (e: Exception) { "" }

            // Update Firestore shop document
            val newVersion = System.currentTimeMillis()
            FirebaseManager.shopDoc().update(
                mapOf(
                    "logoPath" to LOGO_STORAGE_PATH,
                    "logoUrl" to downloadUrl,
                    "logoVersion" to newVersion,
                    "updatedAt" to newVersion
                )
            ).await()

            Log.d(TAG, "Logo uploaded to Firebase Storage and Firestore metadata updated (v$newVersion)")
            localLogoFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload logo to Firebase Storage: ${e.message}", e)
            null
        }
    }

    suspend fun downloadAndCacheLogo(context: Context, downloadUrlOrPath: String?): File? = withContext(Dispatchers.IO) {
        try {
            val localLogoFile = File(context.filesDir, "shop_logo.png")
            if (downloadUrlOrPath.isNullOrBlank()) return@withContext null

            if (downloadUrlOrPath.startsWith("http://") || downloadUrlOrPath.startsWith("https://")) {
                val url = URL(downloadUrlOrPath)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.inputStream.use { input ->
                    FileOutputStream(localLogoFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext localLogoFile
            } else {
                // Download directly from Storage Reference
                val ref = FirebaseManager.storage.reference.child(downloadUrlOrPath)
                val maxBytes: Long = 5 * 1024 * 1024 // 5MB limit
                val bytes = ref.getBytes(maxBytes).await()
                FileOutputStream(localLogoFile).use { output ->
                    output.write(bytes)
                }
                return@withContext localLogoFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download logo from Firebase: ${e.message}")
            null
        }
    }

    suspend fun removeLogo(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            FirebaseManager.ensureAuthenticated()

            val localLogoFile = File(context.filesDir, "shop_logo.png")
            if (localLogoFile.exists()) localLogoFile.delete()

            try {
                logoRef().delete().await()
            } catch (e: Exception) {}

            val newVersion = System.currentTimeMillis()
            FirebaseManager.shopDoc().update(
                mapOf(
                    "logoPath" to null,
                    "logoUrl" to null,
                    "logoVersion" to newVersion,
                    "updatedAt" to newVersion
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove logo: ${e.message}", e)
            false
        }
    }
}
