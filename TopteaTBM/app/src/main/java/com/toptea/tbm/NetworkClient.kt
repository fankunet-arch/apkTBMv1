package com.toptea.tbm

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    // ✅ 修复: 根据API文档使用正确的Base URL
    // API文档: https://hqv3.toptea.es/smsys/api/
    private const val BASE_URL = "https://hqv3.toptea.es/smsys/api/"
    // 完整API端点: https://hqv3.toptea.es/smsys/api/check_update

    // 创建一个会打印日志的 HTTP 客户端，方便调试
    private val okHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY  // 打印完整请求和响应
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
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}