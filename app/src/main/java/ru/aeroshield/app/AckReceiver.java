package ru.aeroshield.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Кнопки «Я в укрытии» / «Понятно» прямо в уведомлении о тревоге. */
public class AckReceiver extends BroadcastReceiver {
    static PendingIntent pending(Context c, int st) {
        Intent i = new Intent(c, AckReceiver.class).putExtra("st", st);
        return PendingIntent.getBroadcast(c, 100 + st, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra("st", 2);
        WatchService.sendAck(c, st);
    }
}
