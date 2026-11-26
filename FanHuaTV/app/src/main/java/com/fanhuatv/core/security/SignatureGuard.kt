package com.fanhuatv.core.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.fanhuatv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 签名校验：运行时取当前 APK 签名证书 SHA-256，交给 native 与内置指纹比对。
 * 二次打包后签名变化 → 校验失败 → 上层阻断配置加载与播放。
 */
@Singleton
class SignatureGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nativeBridge: NativeBridge
) {

    data class Result(val passed: Boolean, val fingerprint: String, val reason: String = "")

    fun verify(): Result {
        if (!BuildConfig.SIGNATURE_CHECK_ENABLED) {
            return Result(true, "debug-skip", "调试构建已跳过签名校验")
        }
        val actual = runCatching { currentFingerprint() }.getOrElse { "" }
        if (actual.isBlank()) {
            return Result(false, "", "无法读取签名证书")
        }
        val passed = nativeBridge.nativeVerifySignature(
            BuildConfig.EXPECTED_SIGNATURE_SHA256,
            actual
        )
        return Result(
            passed = passed,
            fingerprint = actual,
            reason = if (passed) "签名校验通过" else "签名不匹配，疑似二次打包"
        )
    }

    private fun currentFingerprint(): String {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
        }

        val certificates: List<Certificate> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.signingCertificateHistory
                ?.map { it.toCertificate() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION") info.signatures?.map { it.toCertificate() } ?: emptyList()
        }
        val cert = certificates.firstOrNull() ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun android.content.pm.Signature.toCertificate(): Certificate {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(java.io.ByteArrayInputStream(toByteArray()))
    }
}
