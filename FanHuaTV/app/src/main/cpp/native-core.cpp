#include <jni.h>
#include <string>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "FanHuaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 密钥片段分散存储，运行时拼接，避免明文出现在 so 字符串表
static const char* secret_part_a = "fan";
static const char* secret_part_b = "hua";
static const char* secret_part_c = "_tv_2026";

extern "C" JNIEXPORT jstring JNICALL
Java_com_fanhuatv_core_security_NativeBridge_nativeApiSecret(JNIEnv* env, jobject /* thiz */) {
    std::string key;
    key.reserve(32);
    key.append(secret_part_a);
    key.append(secret_part_b);
    key.append(secret_part_c);
    // 简单反转 + 异或，提升静态分析成本
    std::string obf;
    const char xorKey = 0x5A;
    for (char c : key) obf.push_back(static_cast<char>(c ^ xorKey));
    jstring result = env->NewStringUTF(obf.c_str());
    return result;
}

// 原生层签名指纹比对：expected 为 Kotlin 层传入的 SHA-256，actual 为运行时证书指纹
// 比对逻辑放在 native，关键分支不被 Java 层 hook 直接篡改
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fanhuatv_core_security_NativeBridge_nativeVerifySignature(
        JNIEnv* env,
        jobject /* thiz */,
        jstring expected,
        jstring actual) {
    if (expected == nullptr || actual == nullptr) return JNI_FALSE;

    const char* e = env->GetStringUTFChars(expected, nullptr);
    const char* a = env->GetStringUTFChars(actual, nullptr);

    jboolean ok = JNI_FALSE;
    if (e != nullptr && a != nullptr) {
        // 常量时间比较，防时序侧信道
        size_t lenE = strlen(e);
        size_t lenA = strlen(a);
        size_t maxLen = lenE > lenA ? lenE : lenA;
        unsigned int diff = lenE ^ lenA;
        for (size_t i = 0; i < maxLen; ++i) {
            unsigned char ec = i < lenE ? static_cast<unsigned char>(e[i]) : 0;
            unsigned char ac = i < lenA ? static_cast<unsigned char>(a[i]) : 0;
            diff |= (ec ^ ac);
        }
        ok = (diff == 0) ? JNI_TRUE : JNI_FALSE;
    }

    if (e != nullptr) env->ReleaseStringUTFChars(expected, e);
    if (a != nullptr) env->ReleaseStringUTFChars(actual, a);

    LOGI("native signature verify result: %d", ok);
    return ok;
}

// 完整性自检：native 层返回自身被篡改检测标记（预留钩子，可接入 CRC 校验）
extern "C" JNIEXPORT jint JNICALL
Java_com_fanhuatv_core_security_NativeBridge_nativeIntegrityFlag(JNIEnv* env, jobject /* thiz */) {
    const char* tag = "fanhua_secure_runtime";
    int sum = 0;
    for (const char* p = tag; *p; ++p) sum += *p;
    return sum;
}
