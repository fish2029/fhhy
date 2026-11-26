package com.fanhuatv.core.network

import com.fanhuatv.core.security.NativeBridge
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 请求签名拦截器：核心签名头由 Native 层生成密钥，Java 层无法拿到明文算法。
 * 防抓包重放 + 防接口被盗用。
 */
@Singleton
class SignInterceptor @Inject constructor(
    private val nativeBridge: NativeBridge
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ts = System.currentTimeMillis().toString()
        val secret = nativeBridge.apiSecret()
        val token = nativeBridge.signRequest(request.url.toString(), ts, secret)
        val signed = request.newBuilder()
            .header("X-FH-TS", ts)
            .header("X-FH-SIGN", token)
            .header("X-FH-CLIENT", "fanhuatv")
            .build()
        return chain.proceed(signed)
    }
}
