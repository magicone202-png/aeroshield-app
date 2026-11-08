package ru.aeroshield.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * Один экран: WebView с интерфейсом из assets/index.html.
 * JavaScript вызывает Java через объект Android (сеть, Bluetooth, фоновая охрана).
 */
public class MainActivity extends Activity {
    // Откуда приложение берёт обновления экранов (публичный репозиторий на GitHub)
    static final String RAW = "https://raw.githubusercontent.com/magicone202-png/aeroshield-app/main/";
    private WebView web;
    private int uiLoaded = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#11181A"));
        getWindow().setNavigationBarColor(Color.parseColor("#11181A"));
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WatchService.channels(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#11181A"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        loadUi();
        askPermissions();
        checkUpdates();
    }

    // ---------- Экраны и их обновление без переустановки ----------
    private File cacheFile() { return new File(new File(getFilesDir(), "ui"), "index.html"); }

    static int uiVersion(String html) {
        Matcher m = Pattern.compile("name=\"ui-version\" content=\"(\\d+)\"").matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String readAsset() {
        try (InputStream is = getAssets().open("index.html")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    private String readFile(File f) {
        try (InputStream is = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    /** Берём скачанную версию экранов, если она новее встроенной в APK. */
    private void loadUi() {
        int assetVer = uiVersion(readAsset());
        File f = cacheFile();
        int cacheVer = f.exists() ? uiVersion(readFile(f)) : 0;
        if (cacheVer > assetVer) { uiLoaded = cacheVer; web.loadUrl("file://" + f.getAbsolutePath()); }
        else { uiLoaded = assetVer; web.loadUrl("file:///android_asset/index.html"); }
    }

    private int appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Раз при запуске: смотрим version.json на GitHub. Новые экраны скачиваем сами, о новом APK сообщаем. */
    private void checkUpdates() {
        new Thread(() -> {
            try {
                Net.Resp v = Net.request("GET", RAW + "version.json?t=" + System.currentTimeMillis(), null, 8000);
                if (v.code != 200) return;
                JSONObject j = new JSONObject(v.body);
                int ui = j.optInt("ui"), apk = j.optInt("apk");
                if (apk > appVersion()) js("window.onApkUpdate && onApkUpdate(" + JSONObject.quote(j.optString("apkUrl")) + ")");
                if (ui > uiLoaded) {
                    Net.Resp h = Net.request("GET", RAW + "app/src/main/assets/index.html?t=" + System.currentTimeMillis(), null, 15000);
                    if (h.code == 200 && h.body.contains("AEROSHIELD_UI") && uiVersion(h.body) >= ui) {
                        File f = cacheFile();
                        f.getParentFile().mkdirs();
                        File tmp = new File(f.getParentFile(), "index.tmp");
                        try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(h.body.getBytes(StandardCharsets.UTF_8)); }
                        if (tmp.renameTo(f)) js("window.onUiUpdate && onUiUpdate(" + uiVersion(h.body) + ")");
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> ask = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
    }

    private void js(String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    @Override public void onBackPressed() {
        // экран сам решает, что делать с «Назад»; если некуда — сворачиваем приложение (охрана продолжает работать)
        web.evaluateJavascript("(window.onBack && onBack()) ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) moveTaskToBack(true);
        });
    }

    /** Методы, которые видит JavaScript как Android.xxx() */
    class Bridge {
        /** Асинхронный HTTP к прибору 1. Ответ придёт в window.__net(id, code, text). */
        @JavascriptInterface public void http(String id, String method, String url, String body) {
            new Thread(() -> {
                Net.Resp r = Net.request(method, url, body, 5000);
                js("window.__net(" + JSONObject.quote(id) + "," + r.code + "," + JSONObject.quote(r.body) + ")");
            }).start();
        }

        @JavascriptInterface public String state() { return Hub.toJson(); }

        @JavascriptInterface public void reloadUi() { web.post(MainActivity.this::loadUi); }
        @JavascriptInterface public int uiVersion() { return uiLoaded; }
        @JavascriptInterface public void openUrl(String url) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        }

        /** Включить фоновую охрану жителя: адрес прибора 1 и ключ жителя. */
        @JavascriptInterface public void startWatch(String base, String resKey) {
            WatchService.prefs(MainActivity.this).edit().putString("base", base).putString("resKey", resKey).apply();
            WatchService.start(MainActivity.this);
        }
        @JavascriptInterface public void stopWatch() { WatchService.stop(MainActivity.this); }

        @JavascriptInterface public void ack(int st) { WatchService.sendAck(MainActivity.this, st); }

        @JavascriptInterface public void bleScan() { askPermissions(); Ble.get(MainActivity.this).scan(); }
        @JavascriptInterface public void bleOff() { Ble.get(MainActivity.this).disconnect(); }
        @JavascriptInterface public void bleSend(String cmd) { Ble.get(MainActivity.this).send(cmd); }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }

        /** Открыть настройки батареи: чтобы Android не усыплял охрану. */
        @JavascriptInterface public void batterySettings() {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
            }
        }
    }
}
