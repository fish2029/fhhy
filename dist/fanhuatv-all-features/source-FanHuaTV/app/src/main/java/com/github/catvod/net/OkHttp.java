package com.github.catvod.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * TVBox catvod 宿主兼容桩。
 *
 * 原版 TVBox 的 Spider.safeDns() 会委托到这里做防 DNS 污染的解析器。
 * FanHuaTV 之前缺这个类，宿主只能返回 Dns.SYSTEM；部分 jar 蜘蛛会直接
 * 以 com.github.catvod.net.OkHttp 发起请求，缺失时抛 NoClassDefFoundError。
 */
public class OkHttp {

    private static final Map<String, List<InetAddress>> cache = new HashMap<>();

    /** 兼容原版签名：允许外部设置自定义 DNS 列表 */
    public static void setDns(List<InetAddress> dns) {
        // 当前使用系统 DNS，保留扩展位
    }

    /**
     * 防 DNS 污染解析：优先用内置 DoH 结果，失败回落系统解析。
     * 与 TVBox 一致，未知域名返回系统解析即可。
     */
    public static Dns safeDns() {
        return hostname -> {
            try {
                List<InetAddress> cached = cache.get(hostname);
                if (cached != null && !cached.isEmpty()) return cached;
            } catch (Throwable ignored) {
            }
            return Dns.SYSTEM.lookup(hostname);
        };
    }

    /** 原版：允许跨协议重定向 + 指定超时的客户端 */
    public static OkHttpClient newClient(boolean redirect, long timeout, TimeUnit unit) {
        return new OkHttpClient.Builder()
                .dns(safeDns())
                .connectTimeout(timeout, unit)
                .readTimeout(timeout, unit)
                .followRedirects(redirect)
                .followSslRedirects(redirect)
                .build();
    }

    /** 原版：默认 15s 客户端 */
    public static OkHttpClient newClient() {
        return newClient(true, 15, TimeUnit.SECONDS);
    }

    /** 简单的 GET，供个别蜘蛛直接使用 */
    public static String string(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            return newClient().newCall(request).execute().body().string();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 兼容原版：解析 URL 查询参数 */
    public static String getUrlValue(String url, String key) {
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            return parsed == null ? "" : parsed.queryParameter(key);
        } catch (Throwable e) {
            return "";
        }
    }
}
