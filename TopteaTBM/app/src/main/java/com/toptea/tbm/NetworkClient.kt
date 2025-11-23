package com.toptea.tbm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private const val BASE_URL = "http://hqv3.toptea.es" // 您的服务器地址

    // Gson 配置为 Lenient 模式，兼容后台可能返回的 BOM / 轻微格式问题
    private val gson: Gson by lazy {
        GsonBuilder()
            .setLenient()
            .create()
    }

    // 创建一个会打印日志的 HTTP 客户端，方便调试
    private val okHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
            .readTimeout(30, TimeUnit.SECONDS)    // 读取超时
            .build()
    }

    // 创建 Retrofit 实例
    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    /**
     * 原始请求调试工具：当 JSON 解析失败时，回落到最基础的 HTTP 调用，
     * 记录服务器返回的原始字符串，便于排查接口异常（如返回 HTML、PHP 报错等）。
     */
    suspend fun fetchRawCheckUpdate(requestBody: CheckUpdateRequest): String? {
        return try {
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/smsys/api/check_update")
                .addHeader("X-Toptea-Secret", "TOPTEA_SECURE_KEY_2025")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }
}