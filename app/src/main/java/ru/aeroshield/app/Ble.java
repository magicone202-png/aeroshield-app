package ru.aeroshield.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Bluetooth LE с прибором 2 (прошивка AeroShield_ESP32C3).
 * Те же UUID, что в прошивке: прибор шлёт "12.4;1834" или "W;640", принимает A1 / A0 / T / C.
 */
@SuppressLint("MissingPermission")
public final class Ble {
    static final UUID SVC = UUID.fromString("6e400001-a3a0-4b6f-9c3e-0a5e00000001");
    static final UUID TX  = UUID.fromString("6e400002-a3a0-4b6f-9c3e-0a5e00000001"); // прибор -> телефон
    static final UUID RX  = UUID.fromString("6e400003-a3a0-4b6f-9c3e-0a5e00000001"); // телефон -> прибор
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static Ble inst;
    public static synchronized Ble get(Context c) {
        if (inst == null) inst = new Ble(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothLeScanner scanner;
    private boolean wantConnected = false;

    private Ble(Context c) { ctx = c; }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** Ищем прибор с нашим сервисом и сразу подключаемся к первому найденному. */
    public void scan() {
        BluetoothAdapter a = adapter();
        if (a == null) { fail("На телефоне нет Bluetooth"); return; }
        if (!a.isEnabled()) { fail("Включите Bluetooth на телефоне"); return; }
        try {
            disconnect();
            wantConnected = true;
            scanner = a.getBluetoothLeScanner();
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            Hub.bleState = "scan"; Hub.bleError = "";
            scanner.startScan(Collections.singletonList(f), s, scanCb);
            main.postDelayed(() -> {
                if ("scan".equals(Hub.bleState)) { stopScan(); fail("Прибор не найден. Включите его и поднесите ближе"); }
            }, 12000);
        } catch (SecurityException e) {
            fail("Нет разрешения на Bluetooth. Разрешите его в настройках приложения");
        } catch (Exception e) {
            fail("Ошибка Bluetooth: " + e.getMessage());
        }
    }

    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (!"scan".equals(Hub.bleState)) return;
            stopScan();
            BluetoothDevice d = r.getDevice();
            String n = null;
            try { n = d.getName(); } catch (Exception ignored) {}
            Hub.bleName = n != null ? n : "AeroShield";
            Hub.bleState = "link";
            try { gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE); }
            catch (Exception e) { fail("Не удалось подключиться"); }
        }
        @Override public void onScanFailed(int code) { fail("Поиск не запустился (код " + code + ")"); }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                rx = null;
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
                Hub.bleState = "off";
                // связь пропала сама — пробуем вернуть через 3 секунды
                if (wantConnected) main.postDelayed(Ble.this::scan, 3000);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { fail("Это не прибор АэроЩит"); g.disconnect(); return; }
            BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
            rx = s.getCharacteristic(RX);
            if (tx != null) {
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            Hub.bleState = "on"; Hub.bleError = "";
            // если тревога уже идёт — сразу включаем сирену прибора
            if (Hub.alarmOn && Hub.ack != 1) main.postDelayed(() -> send("A1"), 800);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            String t = new String(v, StandardCharsets.UTF_8).trim();
            if (t.startsWith("W")) { Hub.warm = true; Hub.coAt = System.currentTimeMillis(); return; }
            try {
                String p = t.split(";")[0];
                Hub.co = Double.parseDouble(p);
                Hub.warm = false;
                Hub.coAt = System.currentTimeMillis();
            } catch (Exception ignored) {}
        }
    };

    /** Команда прибору 2: A1 — сирена, A0 — отбой, T — проверка сирены, C — калибровка. */
    public void send(String cmd) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = rx;
        if (g == null || c == null) return;
        try {
            c.setValue(cmd.getBytes(StandardCharsets.UTF_8));
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            g.writeCharacteristic(c);
        } catch (Exception ignored) {}
    }

    public void disconnect() {
        wantConnected = false;
        stopScan();
        BluetoothGatt g = gatt;
        gatt = null; rx = null;
        if (g != null) { try { g.disconnect(); g.close(); } catch (Exception ignored) {} }
        Hub.bleState = "off";
    }

    private void fail(String msg) {
        Hub.bleState = "error";
        Hub.bleError = msg;
    }
}
