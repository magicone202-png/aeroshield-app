package ru.aeroshield.app;

import org.json.JSONObject;

/**
 * Общее состояние приложения. Фоновая служба и Bluetooth пишут сюда,
 * а экран (WebView) раз в секунду читает через Android.state().
 */
public final class Hub {
    private Hub() {}

    // Bluetooth с прибором 2 (житель)
    public static volatile String bleState = "off";   // off | scan | link | on | error
    public static volatile String bleName = "";
    public static volatile String bleError = "";
    public static volatile double co = -1;             // ppm, -1 = нет данных
    public static volatile boolean warm = false;       // прибор 2 прогревает датчик
    public static volatile long coAt = 0;

    // Фоновая охрана (опрос прибора 1 по Wi-Fi)
    public static volatile boolean watching = false;
    public static volatile boolean linkOk = false;     // прибор 1 отвечает
    public static volatile long linkAt = 0;
    public static volatile boolean alarmOn = false;
    public static volatile int alarmKind = 0;
    public static volatile long alarmSeq = 0;
    public static volatile long alarmAt = 0;
    public static volatile int ack = 0;
    public static volatile boolean ringing = false;

    public static synchronized String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ble", bleState);
            o.put("bleName", bleName);
            o.put("bleErr", bleError);
            o.put("co", co);
            o.put("warm", warm);
            o.put("coAge", coAt == 0 ? -1 : (System.currentTimeMillis() - coAt) / 1000);
            o.put("watching", watching);
            o.put("linkOk", linkOk);
            o.put("linkAge", linkAt == 0 ? -1 : (System.currentTimeMillis() - linkAt) / 1000);
            o.put("alarmOn", alarmOn);
            o.put("kind", alarmKind);
            o.put("seq", alarmSeq);
            o.put("at", alarmAt);
            o.put("ack", ack);
            o.put("ringing", ringing);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
