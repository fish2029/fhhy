package com.github.catvod.crawler;

import android.util.Log;

/**
 * 爬虫调试日志工具。jar 内爬虫类通过此类输出日志。
 */
public class SpiderDebug {

    public static boolean debug = false;

    public static boolean isEnabled() {
        return true;
    }

    public static void log(Throwable th) {
        if (th != null) Log.e("SpiderDebug", "Spider error", th);
    }

    public static void log(String msg) {
        if (msg == null) return;
        if (debug) {
            Log.i("SpiderDebug", msg);
        } else {
            Log.v("SpiderDebug", msg);
        }
    }

    public static void log(String msg, Throwable t) {
        Log.e("SpiderDebug", msg, t);
    }
}
