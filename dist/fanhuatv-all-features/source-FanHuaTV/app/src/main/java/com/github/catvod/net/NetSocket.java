package com.github.catvod.net;

/** TVBox catvod 宿主兼容桩：jar 内 Demo 蜘蛛引用，普通影视蜘蛛不会走到。 */
public class NetSocket {

    public interface Listener {
        void onEvent(Event event);
    }

    public static class Event {
        public String data;
        public int code;
    }

    public boolean send(byte[] bytes) {
        return false;
    }
}
