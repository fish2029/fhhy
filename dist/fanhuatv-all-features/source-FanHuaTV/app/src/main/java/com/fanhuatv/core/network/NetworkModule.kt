package com.fanhuatv.core.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * 宽松布尔适配器：站点 JSON 里 searchable/enabled 常见 0/1、"true"/"false"、"1" 等写法，
     * Gson 默认只接受 true/false 字面量，遇到数字会抛 IllegalStateException（截图里的报错）。
     * 这里统一兜住：数字非0为true、字符串 true/1/yes/on 为true，其它为false。
     */
    private val lenientBoolean = object : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(input: JsonReader): Boolean? = when (input.peek()) {
            JsonToken.BOOLEAN -> input.nextBoolean()
            JsonToken.NUMBER -> input.nextDouble() != 0.0
            JsonToken.STRING -> input.nextString().let {
                it.equals("true", true) || it == "1" || it.equals("yes", true) || it.equals("on", true)
            }
            JsonToken.NULL -> { input.nextNull(); false }
            else -> { input.skipValue(); false }
        }
    }

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .serializeNulls()
        .registerTypeAdapter(Boolean::class.java, lenientBoolean)
        .registerTypeAdapter(java.lang.Boolean::class.java, lenientBoolean)
        .create()

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // release 构建下签名头日志必须关闭，这里用 level NONE 由 BuildConfig 决定
            level = HttpLoggingInterceptor.Level.NONE
        }

    @Provides
    @Singleton
    fun provideOkHttp(
        logging: HttpLoggingInterceptor,
        signInterceptor: SignInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(signInterceptor)
        .addInterceptor(logging)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1/") // 占位，实际请求全部走 @Url 全路径
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
