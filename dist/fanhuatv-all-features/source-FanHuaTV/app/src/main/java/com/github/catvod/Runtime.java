package com.github.catvod;

import android.content.Context;

/** TVBox catvod 宿主兼容桩：jar 内 JS/Demo 蜘蛛通过 Runtime.context() 取 Context。 */
public final class Runtime {

    private Runtime() {
    }

    public static Context context() {
        try {
            Class<?> clz = Class.forName("android.app.ActivityThread");
            Object thread = clz.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            return (Context) clz.getMethod("getApplication").invoke(thread);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
