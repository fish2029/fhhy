package com.fanhuatv.core.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI 桥接层：所有核心密钥、签名比对、完整性标记都下沉到 native。
 * 方法名与 native-core.cpp 中 JNIEXPORT 函数名严格对应。
 */
@Singleton
class NativeBridge @Inject constructor() {

    init {
        System.loadLibrary("fanhua-native")
    }

    /** 获取混淆后的 API 密钥（native 拼接 + 异或，返回需解码） */
    external fun nativeApiSecret(): String

    /** native 层签名指纹比对 */
    external fun nativeVerifySignature(expected: String, actual: String): Boolean

    /** native 完整性标记 */
    external fun nativeIntegrityFlag(): Int

    /** 解码密钥：native 返回的是 0x5A 异或结果 */
    fun apiSecret(): String = decode(nativeApiSecret())

    /** 用 native 密钥对 url+ts 生成签名 token */
    fun signRequest(url: String, ts: String, secret: String): String {
        val raw = "$url|$ts|$secret"
        var hash = 1469598103934665603UL
        for (byte in raw.toByteArray()) {
            hash = hash xor byte.toULong()
            hash *= 1099511628211UL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private fun decode(value: String): String =
        value.map { (it.code xor 0x5A).toChar() }.joinToString("")
}
