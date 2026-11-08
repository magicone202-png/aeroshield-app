package ru.aeroshield.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Фоновая «охрана» жителя: каждые 2 секунды спрашивает прибор 1 по Wi-Fi,
 * есть ли тревога. Работает при заблокированном экране. При тревоге —
 * сирена, вибрация, уведомление на весь экран и команда сирены прибору 2.
 */
public class WatchService extends Service {
    static final String CH_WATCH = "watch";
    static final String CH_ALARM = "alarm2";
    static final int ID_WATCH = 1, ID_ALARM = 2, ID_LOST = 3;
    static final String[] KIND_T = {"Опасность БПЛА", "Аварийный выброс", "Учебная тревога"};
    static final String[] KIND_X = {
            "Угроза атаки беспилотников. Немедленно пройдите в укрытие.",
            "Повышенная концентрация вредных веществ. Закройте окна, оставайтесь дома.",
            "Учебная тревога: проверка оповещения. Подтвердите получение."};

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private Ringtone ring;
    private long handledSeq = -1;        // по какой тревоге уже отправили A1 прибору 2
    private long lostSince = 0;
    private boolean lostShown = false;

    public static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    public static void stop(Context c) { c.stopService(new Intent(c, WatchService.class)); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("aero", MODE_PRIVATE); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        channels(this);
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel w = new NotificationChannel(CH_WATCH, "Охрана включена", NotificationManager.IMPORTANCE_LOW);
        w.setDescription("Постоянное уведомление: приложение следит за тревогами");
        nm.createNotificationChannel(w);
        NotificationChannel a = new NotificationChannel(CH_ALARM, "Тревога", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Сигнал тревоги от диспетчера станции");
        a.enableVibration(true);
        a.setSound(null, null);            // звук играем сами, громко и по кругу
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("АэроЩит-ГО: охрана включена")
                .setContentText("Слежу за сигналами диспетчера станции")
                .setOngoing(true)
                .setContentIntent(openApp(this, 10))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_WATCH, n);

        if (!running) {
            running = true;
            Hub.watching = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aeroshield:watch");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) { wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "aeroshield:wifi"); wifiLock.acquire(); }
            worker = new Thread(this::loop, "watch");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            pollOnce();
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    private void pollOnce() {
        SharedPreferences p = prefs(this);
        String base = p.getString("base", "");
        String key = p.getString("resKey", "");
        if (base.isEmpty() || key.isEmpty()) { Hub.linkOk = false; return; }
        Net.Resp r;
        try { r = Net.request("GET", base + "/api/rstate?k=" + URLEncoder.encode(key, "UTF-8"), null, 4000); }
        catch (Exception e) { r = null; }
        if (r == null || r.code != 200) {
            Hub.linkOk = false;
            if (lostSince == 0) lostSince = System.currentTimeMillis();
            if (!lostShown && System.currentTimeMillis() - lostSince > 30000) { lostShown = true; notifyLost(true); }
            return;
        }
        if (lostShown) notifyLost(false);
        lostSince = 0; lostShown = false;
        Hub.linkOk = true;
        Hub.linkAt = System.currentTimeMillis();
        try {
            JSONObject j = new JSONObject(r.body);
            boolean on = j.optBoolean("on");
            int kind = j.optInt("kind");
            long seq = j.optLong("seq");
            int ack = j.optInt("ack");
            Hub.alarmOn = on; Hub.alarmKind = kind; Hub.alarmSeq = seq; Hub.ack = ack;
            Hub.alarmAt = j.optLong("at");
            if (on && ack == 0) {
                if (!Hub.ringing) startAlarm(kind);
                if (handledSeq != seq) { handledSeq = seq; Ble.get(this).send("A1"); }
            } else {
                if (Hub.ringing) stopAlarm();
                if (!on && handledSeq != -1) { handledSeq = -1; Ble.get(this).send("A0"); clearAlarmNote(); }
                if (on && ack != 0) updateAckNote(kind, ack);
            }
        } catch (Exception ignored) {}
    }

    // ---------- Тревога ----------
    private void startAlarm(int kind) {
        Hub.ringing = true;
        kind = Math.max(0, Math.min(2, kind));
        PendingIntent full = openApp(this, 11);
        Notification n = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⚠ " + KIND_T[kind])
                .setContentText(KIND_X[kind])
                .setStyle(new Notification.BigTextStyle().bigText(KIND_X[kind] + "\nЭкстренные службы: 112"))
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(0xFFB5122B)
                .setOngoing(true)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .addAction(new Notification.Action.Builder(null, "Я в укрытии", AckReceiver.pending(this, 1)).build())
                .addAction(new Notification.Action.Builder(null, "Понятно", AckReceiver.pending(this, 2)).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT;
        getSystemService(NotificationManager.class).notify(ID_ALARM, n);

        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ring = RingtoneManager.getRingtone(this, u);
            if (ring != null) {
                ring.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
                ring.play();
            }
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 300, 700, 300, 1200, 800}, 0));
        } catch (Exception ignored) {}
        // открываем экран тревоги поверх всего (если система разрешит)
        try {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    void stopAlarm() {
        Hub.ringing = false;
        try { if (ring != null) ring.stop(); } catch (Exception ignored) {}
        ring = null;
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.cancel(); } catch (Exception ignored) {}
    }

    private void updateAckNote(int kind, int ack) {
        kind = Math.max(0, Math.min(2, kind));
        Notification n = new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(KIND_T[kind] + " — действует")
                .setContentText(ack == 1 ? "Вы отметились: в укрытии. Ждите отбоя." : "Прочитано. Пройдите в укрытие.")
                .setContentIntent(openApp(this, 12))
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ALARM, n);
    }

    private void clearAlarmNote() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(ID_ALARM);
        Notification n = new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Отбой тревоги")
                .setContentText("Можно покинуть укрытие")
                .setContentIntent(openApp(this, 13))
                .setAutoCancel(true)
                .build();
        nm.notify(ID_ALARM, n);
    }

    private void notifyLost(boolean lost) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!lost) { nm.cancel(ID_LOST); return; }
        Notification n = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Нет связи с пультом станции")
                .setContentText("Проверьте Wi‑Fi: телефон должен быть в той же сети, что и прибор")
                .setContentIntent(openApp(this, 14))
                .setAutoCancel(true)
                .build();
        nm.notify(ID_LOST, n);
    }

    static PendingIntent openApp(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        running = false;
        Hub.watching = false;
        Hub.linkOk = false;
        stopAlarm();
        if (worker != null) worker.interrupt();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        getSystemService(NotificationManager.class).cancel(ID_ALARM);
        super.onDestroy();
    }

    /** Ответ жителя: отправляем на прибор 1 и глушим сирену. */
    static void sendAck(Context c, int st) {
        SharedPreferences p = prefs(c);
        String base = p.getString("base", ""), key = p.getString("resKey", "");
        long seq = Hub.alarmSeq;
        Hub.ringing = false;
        stopRingStatic(c);
        new Thread(() -> {
            try {
                Net.request("POST", base + "/api/ack", "k=" + URLEncoder.encode(key, "UTF-8") + "&st=" + st + "&seq=" + seq, 4000);
            } catch (Exception ignored) {}
        }).start();
        if (st == 1) Ble.get(c).send("A0");      // житель в укрытии — сирену прибора можно выключить
    }

    static WatchService self;
    { self = this; }
    static void stopRingStatic(Context c) {
        WatchService s = self;
        if (s != null) s.stopAlarm();
    }
}
