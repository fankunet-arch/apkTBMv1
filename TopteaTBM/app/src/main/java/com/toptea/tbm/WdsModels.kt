package com.toptea.tbm

import com.google.gson.annotations.SerializedName

/**
 * 对应 WDS API 返回的 JSON 结构
 * {"ok":true,"now_local":"...","slots":["..."],"in_window":false,"action":"noop","reason":"..."}
 */
data class WdsResponse(
    val ok: Boolean,
    val now_local: String?,
    val timezone: String?,
    val window_min: Int?,
    val slots: List<String>?,
    val in_window: Boolean?,
    val action: String?, // "noop" or other
    val reason: String?
)