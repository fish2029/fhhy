# ===== 手机版 release（R8）保留规则 =====
# 三方库（Hilt/Dagger、Room、Media3、OkHttp、Chaquopy）自带 consumer proguard，AGP 自动合入。
# 本文件只负责「靠类名/字段名工作」的本地代码。

# 宿主代码里有大量按 FQCN 反射的边界：jar 蜘蛛（JarLoader/JarRuntime 把 SpiderApi
# 交给 jar、再回调）、Python 运行时（Chaquopy）、媒体引擎、Gson 实体等。
# 上一版就是漏了这些导致 jar/py 站点分类和搜索全挂——现在整包代码不混淆不裁剪，
# R8 只裁第三方库，功能与不开 R8 时完全一致。
-keep class com.fanhuatv.** { *; }

# catvod 接口（编译期 api 依赖，运行时要被 jar 按名加载）
-keep class com.github.catvod.** { *; }

# Gson 实体字段即协议字段；注解与泛型签名必须保留
-keepclassmembers,allowobfuscation class com.fanhuatv.core.model.** {
    <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*

# 入口兜底（manifest 引用的类本来就不会被裁，这里双保险）
-keep class com.fanhuatv.MainActivity { *; }
