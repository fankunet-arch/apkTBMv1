package com.toptea.tbm

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface ApiService {

    // 这里的暗号必须跟后台商量好，目前先写死做演示
    // 对应我们文档里的 X-Toptea-Secret
    @Headers("X-Toptea-Secret: TOPTEA_SECURE_KEY_2025")
    @POST("/smsys/api/check_update")
    suspend fun checkUpdate(@Body request: CheckUpdateRequest): ApiResponse
}