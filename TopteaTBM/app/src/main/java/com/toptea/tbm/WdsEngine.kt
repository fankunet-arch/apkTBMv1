package com.toptea.tbm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// 1. 定义 WDS 专用接口
interface WdsApiService {
    // URL 中包含 Token
    @GET("wds/api/auto_collect.php?token=3UsMvup5VdFWmFw7UcyfXs5FRJNumtzdqabS5Eepdzb77pWtUBbjGgc")
    suspend fun autoCollect(): WdsResponse
}

// 2. WDS 管理单例
object WdsEngine {
    private const val TAG = "WdsEngine"
    private const val BASE_URL = "https://dc.abcabc.net/"

    // 缓冲时间：5分钟 (毫秒)
    // 作用：确保服务器数据已入库，避免卡点请求查不到数据
    private const val BUFFER_TIME_MS = 5 * 60 * 1000L

    // 默认兜底间隔：30分钟
    // 作用：如果计算失败或 API 挂了，用这个频率重试，防止死循环或不再请求
    private const val FALLBACK_INTERVAL_MS = 30 * 60 * 1000L

    private val wdsHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val wdsApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(wdsHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WdsApiService::class.java)
    }

    // 启动智能采集
    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "WDS Engine Started (Smart Mode)")

            while (isActive) {
                var nextDelay = FALLBACK_INTERVAL_MS // 默认下一轮等待时间

                try {
                    Log.d(TAG, ">>> Requesting WDS API...")
                    // 1. 立即请求一次
                    val response = wdsApi.autoCollect()

                    // 记录结果 (静默模式)
                    Log.d(TAG, "WDS Response: ok=${response.ok}, local=${response.now_local}, action=${response.action}")

                    // 2. 计算下一次采集的最佳时间点
                    // 如果服务器返回了 slots 和 当前时间，我们就可以进行智能计算
                    if (response.now_local != null && !response.slots.isNullOrEmpty()) {
                        val smartDelay = calculateSmartDelay(response.now_local, response.slots)
                        if (smartDelay > 0) {
                            nextDelay = smartDelay
                            Log.i(TAG, "Smart Plan: Next request in ${smartDelay / 1000 / 60} minutes")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Request failed: ${e.message}. Retry in ${FALLBACK_INTERVAL_MS/60000} mins.")
                    // 网络错误时，使用默认兜底时间，避免频繁报错
                    nextDelay = FALLBACK_INTERVAL_MS
                }

                // 3. 进入休眠，等待下一轮
                delay(nextDelay)
            }
        }
    }

    /**
     * 核心算法：计算距离下一个窗口期的毫秒数
     * @param nowStr 当前服务器时间 "2025-11-23 00:31:07"
     * @param slots 时间槽列表 ["01:15", "11:15", ...]
     */
    private fun calculateSmartDelay(nowStr: String, slots: List<String>): Long {
        try {
            // 格式化工具
            val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val sdfSlot = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            // 1. 解析服务器当前时间
            val nowTime = sdfFull.parse(nowStr) ?: return -1L
            val todayStr = sdfDay.format(nowTime)

            // 2. 寻找下一个最近的 Slot
            var nextSlotMillis: Long? = null

            // 遍历所有 Slot
            val sortedSlots = slots.sorted() // 确保时间从小到大
            for (slot in sortedSlots) {
                // 拼凑出今天这个 slot 的完整时间： "2025-11-23" + " " + "01:15"
                val slotTimeStr = "$todayStr $slot"
                val slotDate = sdfSlot.parse(slotTimeStr) ?: continue

                // 如果这个 slot 的时间比现在晚，说明就是它了！
                if (slotDate.time > nowTime.time) {
                    nextSlotMillis = slotDate.time
                    break
                }
            }

            // 3. 如果今天没找到 (说明现在很晚了，比如 23:00，所有slot都过去了)
            // 那么目标就是明天的第一个 slot
            if (nextSlotMillis == null) {
                val firstSlot = sortedSlots.first() // 取列表第一个
                val slotTimeStr = "$todayStr $firstSlot"
                val slotDate = sdfSlot.parse(slotTimeStr)

                if (slotDate != null) {
                    // 加一天 (24小时)
                    val cal = Calendar.getInstance()
                    cal.time = slotDate
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                    nextSlotMillis = cal.timeInMillis
                }
            }

            // 4. 计算最终延迟
            return if (nextSlotMillis != null) {
                val diff = nextSlotMillis - nowTime.time
                // 基础等待时间 + 缓冲时间 (5分钟)
                val finalDelay = diff + BUFFER_TIME_MS

                // 保护机制：如果计算出来的时间太短(比如只剩10秒)，强制至少等 Buffer 时间
                if (finalDelay < BUFFER_TIME_MS) BUFFER_TIME_MS else finalDelay
            } else {
                -1L
            }

        } catch (e: Exception) {
            Log.e(TAG, "Smart calc error: ${e.message}")
            return -1L
        }
    }
}