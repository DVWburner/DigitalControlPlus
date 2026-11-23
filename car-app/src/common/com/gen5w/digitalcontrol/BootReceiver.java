package com.gen5w.digitalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Ensures CarLinkService starts automatically whenever the Gen5W head unit boots.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            context.startService(new Intent(context, CarLinkService.class));
        } catch (Throwable ignored) {
        }
    }
}
