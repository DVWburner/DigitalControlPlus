package com.gen5w.digitalcontrol.edition;

import android.content.Context;

import com.gen5w.digitalcontrol.TrustVault;

/**
 * Unified build configuration: Bluetooth companion control out-of-the-box, plus optional
 * user-configured self-hosted E2EE Remote Relay (disabled/blank until user specifies a server URL).
 */
public final class EditionConfig {
    public static final boolean REMOTE_ENABLED = true;
    public static final String EDITION_LABEL = "Bluetooth + Optional Self-Hosted Relay";
    public static final String IPC_SOCKET_NAME = "gen5w_digitalctl_pub";
    public static final String DEFAULT_REMOTE_URL = "";

    public interface RemoteBridgeHandle {
        void notifyTrustUpdated();
        void notifyConnectivityChanged();
        String getStatusSummary();
        void stop();
    }

    private EditionConfig() {}

    public static RemoteBridgeHandle startRemoteBridgeIfEnabled(Context context, TrustVault vault) {
        final CarRemoteRelayWorker worker = new CarRemoteRelayWorker(context, vault);
        worker.start();
        return new RemoteBridgeHandle() {
            @Override
            public void notifyTrustUpdated() {
                worker.notifyTrustUpdated();
            }

            @Override
            public void notifyConnectivityChanged() {
                worker.notifyConnectivityChanged();
            }

            @Override
            public String getStatusSummary() {
                return worker.getStatusSummary();
            }

            @Override
            public void stop() {
                worker.stop();
            }
        };
    }
}
