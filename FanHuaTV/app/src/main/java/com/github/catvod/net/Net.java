package com.github.catvod.net;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TVBox catvod 宿主兼容桩。
 * fanhua.jar 里少量蜘蛛（Demo/XBPQ 等）会引用 Spider.net.get()/sleep()，
 * 缺失会在调用点抛 NoClassDefFoundError，导致整条搜索/播放链失败。
 */
public class Net {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public String get(String url) {
        return get(url, 15000);
    }

    /** timeoutMillis：毫秒；jar 内传入 250 等小值时按毫秒处理 */
    public String get(String url, int timeoutMillis) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = CLIENT.newCall(request).execute()) {
                return response.body() == null ? "" : response.body().string();
            }
        } catch (Throwable e) {
            return "";
        }
    }

    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
