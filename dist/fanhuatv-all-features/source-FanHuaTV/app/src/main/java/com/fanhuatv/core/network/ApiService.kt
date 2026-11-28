package com.fanhuatv.core.network

import com.fanhuatv.core.model.AppConfig
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import retrofit2.http.Headers
import retrofit2.http.HeaderMap

/**
 * 全动态接口：只有一个 @Url 方法，BaseUrl 不固定。
 * 站点数据彻底由远程 JSON 决定，壳子不内置任何内容源。
 */
interface ApiService {

    @GET
    @Headers("User-Agent: FanHuaTV/1.0 (Android)")
    suspend fun fetchRaw(@Url url: String): Response<ResponseBody>

    @GET
    suspend fun fetchConfig(@Url url: String): Response<AppConfig>

    @GET
    suspend fun fetchLiveText(@Url url: String): Response<ResponseBody>

    /** 播放地址连通性探测：Range 只取 1 字节，避免把整部剧下载下来 */
    @GET
    suspend fun probeUrl(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>
}
