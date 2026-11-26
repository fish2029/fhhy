# ===== 手机版 release（R8）保留规则 =====
# 说明：各三方库（Hilt/Dagger、Room、Media3、OkHttp、Chaquopy）自带 consumer
# proguard，AGP 会自动合入；这里只补「靠字段名/反射工作」的本地代码。

# Gson 实体：字段就是协议字段，改名/裁剪会导致解析丢字段。
# 允许类名混淆、只锁字段成员；@SerializedName 会另由注解机制保住协议名。
-keepclassmembers,allowobfuscation class com.fanhuatv.core.model.** {
    <fields>;
}

# Gson/Retrofit 反序列化依赖泛型签名与注解
-keepattributes Signature
-keepattributes *Annotation*

# 反射入口：SpderApi/JarRuntime 通过接口加载 jar 里的实现，实现类由 jar 自身管理，
# 但宿主这边的接口与回调类被 jar 按名反射调用，统一不混淆、不裁剪
-keep interface com.fanhuatv.spider.** { *; }
-keep class com.fanhuatv.jar.** { *; }

# Compose 入口/导航：路由字符串在编译期常量里，无需规则；此处仅兜底 Activity
-keep class com.fanhuatv.MainActivity { *; }
