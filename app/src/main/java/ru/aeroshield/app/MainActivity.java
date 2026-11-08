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

import java.util.ArrayList;
import java.util.List;

/**
 * Один экран: WebView с интерфейсом из assets/index.html.
 * JavaScript вызывает Java через объект Android (сеть, Bluetooth, фоновая охрана).
 */
public class MainActivity extends Activity {
    private WebView web;

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
        web.loadUrl("file:///android_asset/index.html");
        askPermissions();
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
