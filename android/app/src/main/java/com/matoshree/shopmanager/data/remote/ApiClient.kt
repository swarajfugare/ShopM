package com.matoshree.shopmanager.data.remote

import android.content.Context
import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.security.TokenStorage
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthInterceptor(
    private val tokenStorage: TokenStorage,
    private val baseUrlProvider: () -> String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip auth header for login or public endpoints
        if (original.url.encodedPath.contains("auth/login")) {
            return chain.proceed(original)
        }

        var token = tokenStorage.getToken()
        if (token.isNullOrBlank()) {
            token = performAutoLogin()
        }

        val requestBuilder = original.newBuilder()
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = chain.proceed(requestBuilder.build())

        // If 401 Unauthorized, refresh token once and retry original request
        if (response.code == 401) {
            response.close()
            tokenStorage.clear()
            val freshToken = performAutoLogin()
            val retryBuilder = original.newBuilder()
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
            if (!freshToken.isNullOrBlank()) {
                retryBuilder.header("Authorization", "Bearer $freshToken")
            }
            return chain.proceed(retryBuilder.build())
        }

        return response
    }

    private fun performAutoLogin(): String? {
        return try {
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val body = okhttp3.RequestBody.create(jsonMediaType, "{\"mobile\":\"9876543210\",\"pin\":\"1234\"}")
            val loginReq = okhttp3.Request.Builder()
                .url(baseUrlProvider() + "api/v1/auth/login")
                .post(body)
                .build()

            val tempClient = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()

            tempClient.newCall(loginReq).execute().use { res ->
                if (res.isSuccessful) {
                    val resBody = res.body?.string()
                    if (resBody != null) {
                        val root = Json.parseToJsonElement(resBody).jsonObject
                        val data = root["data"]?.jsonObject
                        val jwt = data?.get("token")?.jsonPrimitive?.contentOrNull
                        if (!jwt.isNullOrBlank()) {
                            tokenStorage.saveToken(jwt)
                            return jwt
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

object ApiClient {
    // Production Hostinger API Base URL
    private var baseUrl: String = "https://blueviolet-ibis-158713.hostingersite.com/"

    fun getBaseUrl(): String = baseUrl

    fun setBaseUrl(url: String) {
        baseUrl = if (url.endsWith("/")) url else "$url/"
    }

    fun create(context: Context): MatoshreeApiService {
        val tokenStorage = TokenStorage(context)
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage) { baseUrl })
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MatoshreeApiService::class.java)
    }
}
