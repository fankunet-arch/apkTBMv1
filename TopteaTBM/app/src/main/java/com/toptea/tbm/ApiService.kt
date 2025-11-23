package com.toptea.tbm

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface ApiService {

    // ✅ 修复: 使用相对路径，配合BASE_URL拼接
    // BASE_URL = "https://hqv3.toptea.es/smsys/api/"
    // 完整URL = "https://hqv3.toptea.es/smsys/api/check_update"
    @Headers("X-Toptea-Secret: TOPTEA_SECURE_KEY_2025")
    @POST("check_update")
    suspend fun checkUpdate(@Body request: CheckUpdateRequest): ApiResponse
}