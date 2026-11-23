package com.gen5w.digitalcontrol.phone.edition;

import android.content.Context;

import com.gen5w.digitalcontrol.phone.PhoneStore;
import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import java.util.List;

/**
 * Unified Remote Server bridge for the Android Companion Phone App.
 * Disabled/blank by default (Bluetooth-only mode) until the user configures their own
 * self-hosted HTTPS server URL.
 */
public final class PhoneEditionBridge {
    public static final boolean REMOTE_ENABLED = true;
    public static final String DEFAULT_REMOTE_URL = "";
    public static final long CAR_WIFI_ONLINE_THRESHOLD_MS = 60_000L;

    public static final class PendingRemoteItem {
        public final String commandId;
        public final String label;
        public final String status;
        public final long expiresAtMs;
        public final String resultMessage;

        public PendingRemoteItem(String commandId, String label, String status, long expiresAtMs, String resultMessage) {
            this.commandId = commandId;
            this.label = label;
            this.status = status;
            this.expiresAtMs = expiresAtMs;
            this.resultMessage = resultMessage == null ? "" : resultMessage;
        }
    }

    public interface Listener {
        void onRemoteQueueChanged();
        void onRemoteTelemetryReceived(VehicleSnapshot snapshot);
        void onRemoteEventMessage(boolean ok, String message);
    }

    public interface Controller {
        boolean isRemoteFallbackActive();
        void setRemoteFallbackActive(boolean enabled);
        boolean isCarWifiRecentlyOnline();
        long getLastCarWifiCheckInMs();
        List<PendingRemoteItem> getPendingCommands();
        void enqueueRemoteCommand(String action, String arg, ClimateTheme theme, String displayLabel);
        void cancelRemoteCommand(String commandId);
        void startPollingIfNeeded();
        void stopPolling();
    }

    private PhoneEditionBridge() {}

    public static Controller createController(Context context, PhoneStore store, Listener listener) {
        return new PhoneRemoteClient(store, listener);
    }
}
