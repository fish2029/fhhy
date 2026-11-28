# ===== 手机版 release（R8）保留规则 =====
# 参考仓库里电视版一直在用的那套（能跑，说明这些边界是对的），补上手机版的包名。

# ---------- 全局属性 ----------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses, EnclosingMethod

# ---------- 宿主代码：大量按 FQCN 反射，整体不混淆不裁剪 ----------
# 上一版分类/搜索全挂就是漏了下面两块：jar 蜘蛛按名回调、网络/JSON 运行时按名反射
-keep class com.fanhuatv.** { *; }
# catvod 接口在工程内是源码（com/github/catvod/...），会被 R8 改名——jar 里加载的
# Spider 实现类找的就是这个包名，必须原样保留
-keep class com.github.catvod.** { *; }

# ---------- Gson：靠字段名/@SerializedName 反序列化配置与源站数据 ----------
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation class com.fanhuatv.core.model.** {
    <fields>;
}
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.reflect.TypeToken

# ---------- 网络层：OkHttp/Okio/Retrofit 走反射与拦截器注册 ----------
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------- Python 运行时（Chaquopy 按名反射进 Java 侧） ----------
-keep public class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ---------- 二维码（zxing）：宿主代码不直接引用，但 jar 蜘蛛按宿主类路径
# 加载（UC/夸克扫码登录生成二维码），不加 keep 会被 R8 整体裁掉 ----------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------- 安卓标准反射面 ----------
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclasseswithmembernames class * { native <methods>; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
}

# ---------- 兜底 ----------
-keep class com.fanhuatv.MainActivity { *; }
