package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * TVBox catvod SpiderApi 兼容桩。
 *
 * 原版 TVBox 的 SpiderApi 由宿主实现：提供本地代理地址、并发请求、网页解析等能力。
 * FanHuaTV 之前是全空实现，jar 内用到 spiderApi 的蜘蛛（XBPQ / Demo / 网盘类）
 * 会静默失败——搜索为空、播放返回空。这里对齐标准语义。
 */
public class SpiderApi {

    private static final String TAG = "FanHuaTV";
    /** 本地代理端口，与 com.fanhuatv.jar.LocalProxy 一致 */
    public static final int LOCAL_PROXY_PORT = 9978;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    /** jar 内期望返回 String（不是 int） */
    public String getPort() {
        return String.valueOf(LOCAL_PROXY_PORT);
    }

    /** 本地代理服务地址；useProxy=false 时返回空串（与 TVBox 语义一致） */
    public String getAddress(boolean useProxy) {
        return useProxy ? "http://127.0.0.1:" + LOCAL_PROXY_PORT : "";
    }

    /**
     * 并发批量请求（XBPQ 等使用）。
     * requests 为 TVBox 约定的 JSON 数组，每项含 url / header（JSON 字符串或对象）/ method / body。
     * 返回与入参等长的数组字符串，每项形如 {"code":200,"body":"..."}。
     */
    public String multiReq(JsonArray requests) {
        if (requests == null || requests.size() == 0) return "";
        try {
            int size = requests.size();
            List<Future<Object[]>> futures = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                final int index = i;
                final JsonElement element = requests.get(i);
                futures.add(EXECUTOR.submit((Callable<Object[]>) () -> fetchOne(index, element)));
            }
            JsonArray result = new JsonArray();
            for (Future<Object[]> future : futures) {
                Object[] pair = future.get(25, TimeUnit.SECONDS);
                JsonObject o = new JsonObject();
                o.addProperty("code", (Integer) pair[0]);
                o.addProperty("body", (String) pair[1]);
                result.add(o);
            }
            return result.toString();
        } catch (Throwable e) {
            Log.w(TAG, "multiReq 失败：" + e.getMessage());
            return "";
        }
    }

    private Object[] fetchOne(int index, JsonElement element) {
        try {
            if (element == null || !element.isJsonObject()) return new Object[]{0, ""};
            JsonObject o = element.getAsJsonObject();
            String url = str(o, "url");
            if (TextUtils.isEmpty(url)) return new Object[]{0, ""};
            Request.Builder builder = new Request.Builder().url(url);
            JsonElement header = o.get("header");
            if (header != null) {
                if (header.isJsonObject()) {
                    for (java.util.Map.Entry<String, JsonElement> e : header.getAsJsonObject().entrySet()) {
                        builder.header(e.getKey(), e.getValue().getAsString());
                    }
                } else if (header.isJsonPrimitive() && header.getAsString().trim().startsWith("{")) {
                    JsonObject h = com.google.gson.JsonParser.parseString(header.getAsString()).getAsJsonObject();
                    for (java.util.Map.Entry<String, JsonElement> e : h.entrySet()) {
                        builder.header(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            String method = str(o, "method").toUpperCase();
            if ("POST".equals(method)) {
                String body = str(o, "body");
                builder.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
            } else if ("GET".equals(method)) {
                builder.get();
            }
            try (Response resp = CLIENT.newCall(builder.build()).execute()) {
                return new Object[]{resp.code(), resp.body() == null ? "" : resp.body().string()};
            }
        } catch (Throwable e) {
            return new Object[]{0, ""};
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    /**
     * 网页解析（TVBox SuperParse）：把 url 交给解析脚本执行。
     * FanHuaTV 未内置远程 SuperParse 服务，返回空串，由调用方回落自身逻辑。
     */
    public String webParse(String url, String script) {
        return "";
    }

    public void log(String msg) {
        SpiderDebug.log(msg);
    }
}
