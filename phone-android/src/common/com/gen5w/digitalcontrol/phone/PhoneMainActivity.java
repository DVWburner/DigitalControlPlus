package com.gen5w.digitalcontrol.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.digitalcontrol.phone.edition.PhoneEditionBridge;
import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.CommandLogEntry;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.KeyBackupBundle;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Companion Android Phone App for Gen5W Digital Control.
 *
 * Designed as a clean, touch-friendly 5-tab interface to prevent accidental scroll-vs-tap
 * conflicts while grouping controls & sensors logically:
 *  - Tab 0 (Home): Compact connection summary + AC Profiles (Climate Themes)
 *  - Tab 1 (Climate): Live AC sensor readout + cabin HVAC & direct 4-step seat level choosers
 *  - Tab 2 (Body): Doors, Locks, Hatch (Open/Close), Charge Port & Windows (no scroll needed)
 *  - Tab 3 (Sensors): Last confirmation time, Door Locks, Tire (TPMS) status, Hatch & EV Battery
 *  - Tab 4 (Profile): Detailed connection status, Command Activity Log button, Pairing, Server & Key Backup
 */
public final class PhoneMainActivity extends Activity
        implements PhoneBtClient.Listener, PhoneEditionBridge.Listener {
    private static final int REQ_BT_PERMS = 1001;
    private static final int REQ_SAVE_BACKUP_DOC = 1002;
    private static final int REQ_OPEN_BACKUP_DOC = 1003;

    private static final int TAB_HOME = 0;
    private static final int TAB_CLIMATE = 1;
    private static final int TAB_BODY = 2;
    private static final int TAB_SENSORS = 3;
    private static final int TAB_PROFILE = 4;

    private static final int BG_ROOT = 0xFF070D19;
    private static final int BG_SURFACE = 0xFF0F172A;
    private static final int BG_SUBTLE = 0xFF0A1120;
    private static final int BORDER_SUBTLE = 0xFF1E293B;
    private static final int BORDER_ACCENT = 0xFF1E3A5F;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private PhoneStore store;
    private PhoneBtClient btClient;
    private PhoneEditionBridge.Controller remoteCtrl;
    private String pendingExportEncryptedJson;

    private LinearLayout rootShell;
    private int currentTab = TAB_HOME;
    private final View[] tabPages = new View[5];
    private final LinearLayout[] navTabItems = new LinearLayout[5];
    private final TextView[] navTabIcons = new TextView[5];
    private final TextView[] navTabLabels = new TextView[5];

    // Persistent Top Header + Connection Summary Strip
    private TextView tvHeaderVehicle;
    private TextView tvWifiCompactPill;
    private int lastWifiPillState = -99;
    private LinearLayout connStrip;
    private TextView tvConnDot;
    private TextView tvConnPrimary;
    private TextView tvConnSecondary;
    private TextView btnQuickRemoteMode;
    private TextView tvToastBanner;
    private int lastConnVisualKey = -99;

    // Tab 0 (Home): Active Pending Remote Strip + AC Profiles
    private LinearLayout homePendingCard;
    private LinearLayout homePendingContainer;
    private String lastPendingSig = "";
    private LinearLayout themesListContainer;
    private String lastThemesSig = "";
    private final List<TextView> themeActivateButtons = new ArrayList<TextView>();

    // Tab 1 (Climate): Live AC Sensor + Controls + Steering Wheel & Segmented Seat Level Choosers
    private TextView tvAcStateBadge;
    private TextView tvAcTargetTemp;
    private TextView tvAcFanMode;
    private TextView tvAcFlagsLine;
    private final TextView[] wheelHeatSegs = new TextView[2];
    private final TextView[] drvHeatSegs = new TextView[4];
    private final TextView[] drvCoolSegs = new TextView[4];
    private final TextView[] passHeatSegs = new TextView[4];
    private final TextView[] passCoolSegs = new TextView[4];
    private int lastWheelHeatVal = -9;
    private int lastDrvHeatVal = -9;
    private int lastDrvCoolVal = -9;
    private int lastPassHeatVal = -9;
    private int lastPassCoolVal = -9;

    // Tab 2 (Body): Doors, Hatch, Charge Port, Preconditioning, Windows & Utility Mode Test
    private TextView tvCtrlPrecondState;
    private TextView tvCtrlBattTemp;
    private TextView tvCtrlUtilModeState;
    private TextView tvCtrlEvReadyState;
    private final List<View> interactiveControlContainers = new ArrayList<View>();
    private final List<TextView> vehicleControlButtons = new ArrayList<TextView>();
    private Boolean lastControlsEnabled = null;

    // Tab 3 (Sensors): Confirmation Timestamp, Door Locks, Tires, Hatch, EV Battery & Preconditioning
    private TextView tvSensorConfirmTime;
    private TextView tvSensorConfirmAgo;
    private TextView tvSensorLockBadge;
    private TextView tvSensorDoorsDetail;
    private TextView tvSensorHatchBadge;
    private TextView tvSensorChargePort;
    private TextView tvSensorTireSummary;
    private TextView tvTireFl;
    private TextView tvTireFr;
    private TextView tvTireRl;
    private TextView tvTireRr;
    private TextView tvSensorSocBig;
    private TextView tvSensorRange;
    private TextView tvSensorChargeState;
    private TextView tvSensorPrecond;
    private TextView tvSensorBattTemp;
    private TextView tvSensorAux12v;
    private TextView tvSensorOutside;

    // Tab 4 (Profile): Connection Details, Command Log Button, Server & Pairing Management
    private TextView tvProfBtStatus;
    private TextView tvProfWifiStatus;
    private TextView tvProfPairedCar;
    private TextView tvProfKeyFp;
    private TextView tvProfLogSummary;
    private TextView btnProfLogOpen;
    private EditText edProfServerUrl;
    private EditText edProfServerToken;
    private CheckBox cbProfRemoteFallback;
    private CheckBox cbProfUseMiles;
    private TextView btnProfSaveServer;
    private TextView btnProfClearServer;
    private TextView btnProfUnpairCar;
    private boolean suppressProfToggleCallback = false;

    // Live Command Log Dialog Auto-Refresh
    private AlertDialog activeLogDialog;
    private LinearLayout activeLogListContainer;
    private String lastDialogLogSig = "";
    private AlertDialog activeSasDialog;

    private VehicleSnapshot snapshot = VehicleSnapshot.empty();
    private boolean btConnected = false;
    private String btStatusLine = "Looking for car BT signal...";
    private String lastActionFeedback = "";
    private long lastActionFeedbackAtMs = 0L;

    private final Runnable uiTickRunnable = new Runnable() {
        @Override
        public void run() {
            updateConnectionHeader();
            if (currentTab == TAB_HOME) {
                updateHomePendingSection();
            } else if (currentTab == TAB_SENSORS) {
                updateSensorsTab();
            } else if (currentTab == TAB_PROFILE) {
                updateProfileDynamicLabels();
            }
            if (activeLogDialog != null && activeLogDialog.isShowing()) {
                refreshCommandLogDialogInPlace(false);
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PhoneStore(this);
        snapshot = store.loadCachedVehicleSnapshot();
        btClient = new PhoneBtClient(store, this);
        remoteCtrl = PhoneEditionBridge.createController(this, store, this);

        rootShell = new LinearLayout(this);
        rootShell.setOrientation(LinearLayout.VERTICAL);
        rootShell.setBackgroundColor(BG_ROOT);
        setContentView(rootShell, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildAppChromeAndTabs();
        ensureBluetoothRuntimePermissions();
        selectTab(TAB_HOME);
        updateAllSections(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBluetoothRuntimePermissions()) {
            btClient.start();
        }
        if (PhoneEditionBridge.REMOTE_ENABLED) {
            remoteCtrl.startPollingIfNeeded();
        }
        uiHandler.removeCallbacks(uiTickRunnable);
        uiHandler.postDelayed(uiTickRunnable, 1000L);
        updateAllSections(false);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(uiTickRunnable);
        if (PhoneEditionBridge.REMOTE_ENABLED) {
            remoteCtrl.stopPolling();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        btClient.stop();
        remoteCtrl.stopPolling();
        super.onDestroy();
    }

    private boolean hasBluetoothRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBluetoothRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothRuntimePermissions()) {
            requestPermissions(new String[] {
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_SCAN"
            }, REQ_BT_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS && hasBluetoothRuntimePermissions()) {
            btClient.start();
            updateAllSections(false);
        }
    }

    // ---- Transport & Relay Listeners ----

    @Override
    public void onConnectionStateChanged(boolean connected, String statusMessage) {
        this.btConnected = connected;
        this.btStatusLine = statusMessage == null ? "Looking for car BT signal..." : statusMessage;
        updateAllSections(false);
    }

    @Override
    public void onPairingCodeChallenge(final String sas4Code, final String carName) {
        if (activeSasDialog != null && activeSasDialog.isShowing()) {
            activeSasDialog.dismiss();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));

        box.addView(makeText("Confirm this 4-character code matches your " + carName + " screen:", 14f, 0xFFE2E8F0, false));
        TextView bigCode = makeText(spaceCode(sas4Code), 32f, 0xFF34D399, true);
        bigCode.setGravity(Gravity.CENTER);
        bigCode.setPadding(dp(12), dp(14), dp(12), dp(14));
        styleBox(bigCode, 0xFF052E16, 0xFF10B981, dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(12), 0, dp(8));
        box.addView(bigCode, clp);
        box.addView(makeText("Also tap 'Approve & Pair Phone' on the car screen.", 12f, 0xFF94A3B8, false));

        activeSasDialog = new AlertDialog.Builder(this)
                .setTitle("Verify Pairing Code")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Confirm Match", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        btClient.confirmPendingSasCode();
                        setFeedback("Confirmed code " + sas4Code + " — waiting for car...");
                        updateConnectionHeader();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        btClient.cancelPendingPairing();
                    }
                })
                .create();
        activeSasDialog.show();
    }

    @Override
    public void onPairingSucceeded(String carName) {
        if (activeSasDialog != null && activeSasDialog.isShowing()) {
            activeSasDialog.dismiss();
        }
        Toast.makeText(this, "Paired with " + carName + "!", Toast.LENGTH_SHORT).show();
        if (PhoneEditionBridge.REMOTE_ENABLED) {
            remoteCtrl.startPollingIfNeeded();
        }
        populateProfileFieldsFromStore();
        updateAllSections(false);
    }

    @Override
    public void onUntrustedByCar(String reason) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        setFeedback(reason);
        updateAllSections(false);
    }

    @Override
    public void onTelemetryUpdated(VehicleSnapshot snap) {
        this.snapshot = snap;
        updateClimateSensorAndSeats();
        updateBodyBatteryStatus();
        updateSensorsTab();
    }

    @Override
    public void onCommandResult(boolean ok, String message) {
        setFeedback((ok ? "\u2713 " : "\u26A0 ") + message);
        updateConnectionHeader();
        updateProfileDynamicLabels();
        if (activeLogDialog != null && activeLogDialog.isShowing()) {
            refreshCommandLogDialogInPlace(true);
        }
    }

    @Override
    public void onRemoteQueueChanged() {
        updateConnectionHeader();
        updateControlInteractivity();
        updateHomePendingSection();
        updateProfileDynamicLabels();
        if (activeLogDialog != null && activeLogDialog.isShowing()) {
            refreshCommandLogDialogInPlace(false);
        }
    }

    @Override
    public void onRemoteTelemetryReceived(VehicleSnapshot snap) {
        if (!btConnected) {
            this.snapshot = snap;
            updateClimateSensorAndSeats();
            updateBodyBatteryStatus();
            updateSensorsTab();
        }
    }

    @Override
    public void onRemoteEventMessage(boolean ok, String message) {
        setFeedback((ok ? "\u2713 " : "\u26A0 ") + message);
        updateConnectionHeader();
        updateProfileDynamicLabels();
        if (activeLogDialog != null && activeLogDialog.isShowing()) {
            refreshCommandLogDialogInPlace(true);
        }
    }

    private void setFeedback(String msg) {
        this.lastActionFeedback = msg == null ? "" : msg;
        this.lastActionFeedbackAtMs = System.currentTimeMillis();
    }

    // ---- Command Execution Dispatcher (with Short Offline Wi-Fi Warning) ----

    private boolean isControlInteractive() {
        if (btConnected) return true;
        return PhoneEditionBridge.REMOTE_ENABLED && remoteCtrl.isRemoteFallbackActive();
    }

    private void triggerAction(
            final String action,
            final String arg,
            final ClimateTheme theme,
            final String label,
            final boolean confirmSafetyFirst) {
        if (!isControlInteractive()) {
            Toast.makeText(this, "Looking for car BT signal...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (confirmSafetyFirst) {
            String via = btConnected ? "via Bluetooth" : "via Wi-Fi Server";
            new AlertDialog.Builder(this)
                    .setTitle(label + "?")
                    .setMessage("Send '" + label + "' " + via + "?")
                    .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            checkOfflineAndDispatch(action, arg, theme, label);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            checkOfflineAndDispatch(action, arg, theme, label);
        }
    }

    private void checkOfflineAndDispatch(
            final String action,
            final String arg,
            final ClimateTheme theme,
            final String label) {
        if (btConnected) {
            setFeedback(label + " sent.");
            updateConnectionHeader();
            btClient.sendCommand(action, arg, theme, label, null);
            updateProfileDynamicLabels();
            return;
        }
        if (PhoneEditionBridge.REMOTE_ENABLED && remoteCtrl.isRemoteFallbackActive()) {
            if (!remoteCtrl.isCarWifiRecentlyOnline()) {
                long lastMs = remoteCtrl.getLastCarWifiCheckInMs();
                String shortWarn = lastMs <= 0L
                        ? "Car hasn't checked in on Wi-Fi yet. It may miss this command. Queue anyway?"
                        : ("Car offline on Wi-Fi (" + formatRelativeAgoShort(lastMs) + "). It may miss this command. Queue anyway?");
                new AlertDialog.Builder(this)
                        .setTitle("Car Not Available")
                        .setMessage(shortWarn)
                        .setPositiveButton("Queue Anyway", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                enqueueRemoteNow(action, arg, theme, label);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                enqueueRemoteNow(action, arg, theme, label);
            }
        }
    }

    private void enqueueRemoteNow(String action, String arg, ClimateTheme theme, String label) {
        setFeedback("Sending " + label + "...");
        updateConnectionHeader();
        remoteCtrl.enqueueRemoteCommand(action, arg, theme, label);
    }

    // ---- Shell & 5-Tab Layout Construction ----

    private void buildAppChromeAndTabs() {
        rootShell.removeAllViews();
        interactiveControlContainers.clear();
        vehicleControlButtons.clear();
        themeActivateButtons.clear();

        // 1. Sleek Top Bar + Persistent Connection Summary
        LinearLayout topSection = new LinearLayout(this);
        topSection.setOrientation(LinearLayout.VERTICAL);
        topSection.setPadding(dp(16), dp(12), dp(16), dp(8));
        topSection.setBackgroundColor(BG_ROOT);

        LinearLayout titleRow = makeRow(0);
        LinearLayout brandCol = new LinearLayout(this);
        brandCol.setOrientation(LinearLayout.VERTICAL);
        brandCol.addView(makeText("Digital Control+", 19f, 0xFFFFFFFF, true));
        tvHeaderVehicle = makeText("", 11f, 0xFF94A3B8, false);
        brandCol.addView(tvHeaderVehicle);
        titleRow.addView(brandCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Compact "(Wi-Fi ●)" pill shown quietly when Bluetooth is connected
        tvWifiCompactPill = makeText("Wi-Fi \u25CF", 11f, 0xFF94A3B8, true);
        tvWifiCompactPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        styleBox(tvWifiCompactPill, 0xFF0F172A, 0xFF334155, dp(999));
        tvWifiCompactPill.setVisibility(View.GONE);
        tvWifiCompactPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_PROFILE);
            }
        });
        titleRow.addView(tvWifiCompactPill);
        topSection.addView(titleRow);

        // Clean Connection Summary Strip
        connStrip = new LinearLayout(this);
        connStrip.setOrientation(LinearLayout.VERTICAL);
        connStrip.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.setMargins(0, dp(8), 0, 0);
        connStrip.setLayoutParams(stripLp);

        LinearLayout stripRow = makeRow(0);
        tvConnDot = makeText("\u25CF", 13f, 0xFF34D399, true);
        tvConnDot.setPadding(0, 0, dp(8), 0);
        stripRow.addView(tvConnDot);

        LinearLayout stripTextCol = new LinearLayout(this);
        stripTextCol.setOrientation(LinearLayout.VERTICAL);
        tvConnPrimary = makeText("Looking for car BT signal...", 13f, 0xFFF8FAFC, true);
        tvConnSecondary = makeText("", 11f, 0xFF94A3B8, false);
        stripTextCol.addView(tvConnPrimary);
        stripTextCol.addView(tvConnSecondary);
        stripRow.addView(stripTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnQuickRemoteMode = makeSmallPillButton("Wi-Fi Mode", 0xFF0C4A6E, 0xFF38BDF8);
        btnQuickRemoteMode.setVisibility(View.GONE);
        btnQuickRemoteMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remoteCtrl.setRemoteFallbackActive(!remoteCtrl.isRemoteFallbackActive());
            }
        });
        stripRow.addView(btnQuickRemoteMode);
        connStrip.addView(stripRow);

        tvToastBanner = makeText("", 11f, 0xFF38BDF8, true);
        tvToastBanner.setPadding(0, dp(5), 0, 0);
        tvToastBanner.setVisibility(View.GONE);
        connStrip.addView(tvToastBanner);

        topSection.addView(connStrip);
        rootShell.addView(topSection);

        // 2. Center Tab Viewport
        FrameLayout viewport = new FrameLayout(this);
        rootShell.addView(viewport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabPages[TAB_HOME] = buildHomeTab();
        tabPages[TAB_CLIMATE] = buildClimateTab();
        tabPages[TAB_BODY] = buildBodyTab();
        tabPages[TAB_SENSORS] = buildSensorsTab();
        tabPages[TAB_PROFILE] = buildProfileTab();

        for (int i = 0; i < tabPages.length; i++) {
            viewport.addView(tabPages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 3. Bottom Tab Navigation Bar
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1E293B);
        rootShell.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(0xFF0B1324);
        bottomBar.setPadding(dp(6), dp(6), dp(6), dp(8));

        String[] icons = new String[] { "\u2726", "\u2744", "\u25A3", "\u25CE", "\u2699" };
        String[] titles = new String[] { "Home", "Climate", "Controls", "Sensors", "Profile" };
        for (int i = 0; i < 5; i++) {
            final int tabIdx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(4), dp(6), dp(4), dp(6));

            TextView ic = makeText(icons[i], 15f, 0xFF64748B, true);
            ic.setGravity(Gravity.CENTER);
            TextView lb = makeText(titles[i], 11f, 0xFF64748B, true);
            lb.setGravity(Gravity.CENTER);
            item.addView(ic);
            item.addView(lb);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(tabIdx);
                }
            });
            navTabItems[i] = item;
            navTabIcons[i] = ic;
            navTabLabels[i] = lb;
            bottomBar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        rootShell.addView(bottomBar);
    }

    private void selectTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabPages.length; i++) {
            boolean active = (i == index);
            tabPages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            navTabIcons[i].setTextColor(active ? 0xFF38BDF8 : 0xFF64748B);
            navTabLabels[i].setTextColor(active ? 0xFFF8FAFC : 0xFF64748B);
            styleBox(navTabItems[i], active ? 0xFF13233F : 0x00000000, active ? 0xFF1E3A5F : 0x00000000, dp(10));
        }
        if (index == TAB_SENSORS) {
            updateSensorsTab();
        } else if (index == TAB_CLIMATE) {
            updateClimateSensorAndSeats();
        } else if (index == TAB_BODY) {
            updateBodyBatteryStatus();
        } else if (index == TAB_PROFILE) {
            updateProfileDynamicLabels();
        }
    }

    // ---- TAB 0: HOME (AC Profiles + Active Queue Summary) ----

    private View buildHomeTab() {
        ScrollView sv = makeTabScrollView();
        LinearLayout page = makeTabPageColumn();
        sv.addView(page);

        homePendingCard = makeCard("PENDING WI-FI COMMANDS");
        homePendingCard.setVisibility(View.GONE);
        homePendingContainer = new LinearLayout(this);
        homePendingContainer.setOrientation(LinearLayout.VERTICAL);
        homePendingCard.addView(homePendingContainer);
        page.addView(homePendingCard);

        LinearLayout profilesCard = makeCard("AC PROFILES");
        TextView hint = makeText("Tap a preset to edit, or tap Activate to start cabin conditioning.", 11f, 0xFF94A3B8, false);
        hint.setPadding(0, 0, 0, dp(8));
        profilesCard.addView(hint);

        themesListContainer = new LinearLayout(this);
        themesListContainer.setOrientation(LinearLayout.VERTICAL);
        profilesCard.addView(themesListContainer);

        TextView addThemeBtn = makeButton("+ New AC Profile", BG_SUBTLE, 0xFF38BDF8);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(0, dp(4), 0, 0);
        addThemeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClimateThemeEditorDialog(null);
            }
        });
        profilesCard.addView(addThemeBtn, addLp);
        page.addView(profilesCard);

        return sv;
    }

    // ---- TAB 1: CLIMATE (Current AC Sensor + Cabin Controls + Direct Seat Level Choosers) ----

    private View buildClimateTab() {
        ScrollView sv = makeTabScrollView();
        LinearLayout page = makeTabPageColumn();
        sv.addView(page);

        // 1. Live AC Sensor Card (kept exclusively on Climate tab)
        LinearLayout sensorCard = makeCard("CURRENT AC STATUS");
        LinearLayout topRow = makeRow(dp(6));
        tvAcTargetTemp = makeText("--\u00B0", 28f, 0xFF38BDF8, true);
        topRow.addView(tvAcTargetTemp);

        View sp = new View(this);
        topRow.addView(sp, new LinearLayout.LayoutParams(dp(12), 1));

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        tvAcStateBadge = makeText("HVAC --", 13f, 0xFFE2E8F0, true);
        tvAcFanMode = makeText("Fan -- \u00B7 Vent --", 12f, 0xFF94A3B8, false);
        rightCol.addView(tvAcStateBadge);
        rightCol.addView(tvAcFanMode);
        topRow.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sensorCard.addView(topRow);

        tvAcFlagsLine = makeText("A/C --  \u00B7  Heat --  \u00B7  Wheel --  \u00B7  Defrost --", 11f, 0xFFCBD5E1, false);
        tvAcFlagsLine.setPadding(dp(10), dp(7), dp(10), dp(7));
        styleBox(tvAcFlagsLine, BG_SUBTLE, BORDER_SUBTLE, dp(8));
        sensorCard.addView(tvAcFlagsLine);
        page.addView(sensorCard);

        // 2. Cabin Climate Controls
        LinearLayout ctrlGroup = new LinearLayout(this);
        ctrlGroup.setOrientation(LinearLayout.VERTICAL);
        interactiveControlContainers.add(ctrlGroup);

        LinearLayout hvacCard = makeCard("CABIN CLIMATE");
        LinearLayout r1 = makeRow(dp(8));
        addCtrlBtn(r1, "Climate ON", "climate_on", "", 0xFF0EA5E9, false);
        addCtrlBtn(r1, "AUTO", "climate_auto_on", "", 0xFF10B981, false);
        addCtrlBtn(r1, "Climate OFF", "climate_off", "", 0xFF475569, false);
        hvacCard.addView(r1);

        LinearLayout r2 = makeRow(dp(8));
        addCtrlBtn(r2, "Temp \u2212", "climate_driver_temp_down", "", 0xFF0284C7, false);
        addCtrlBtn(r2, "Temp +", "climate_driver_temp_up", "", 0xFFF97316, false);
        addCtrlBtn(r2, "Fan \u2212", "climate_fan_down", "", 0xFF334155, false);
        addCtrlBtn(r2, "Fan +", "climate_fan_up", "", 0xFF0EA5E9, false);
        hvacCard.addView(r2);

        LinearLayout r3 = makeRow(dp(0));
        addCtrlBtn(r3, "Front Defrost", "climate_front_defrost_toggle", "", 0xFFF59E0B, false);
        addCtrlBtn(r3, "Rear Defog", "rear_defrost_toggle", "", 0xFFF59E0B, false);
        hvacCard.addView(r3);
        ctrlGroup.addView(hvacCard);

        // 3. Steering Wheel Heat (Off / On) + Direct Front Seat Level Choosers (Off / 1 / 2 / 3)
        LinearLayout seatsCard = makeCard("STEERING WHEEL & FRONT SEATS");
        addWheelHeatChooserRow(seatsCard);
        addSeatLevelChooserRow(seatsCard, "Driver Heat", "seat_heat_driver_set", drvHeatSegs, 0xFFEF4444);
        addSeatLevelChooserRow(seatsCard, "Driver Cool", "seat_vent_driver_set", drvCoolSegs, 0xFF0EA5E9);
        addSeatLevelChooserRow(seatsCard, "Passenger Heat", "seat_heat_passenger_set", passHeatSegs, 0xFFEF4444);
        addSeatLevelChooserRow(seatsCard, "Passenger Cool", "seat_vent_passenger_set", passCoolSegs, 0xFF0EA5E9);
        ctrlGroup.addView(seatsCard);

        page.addView(ctrlGroup);
        return sv;
    }

    private void addWheelHeatChooserRow(LinearLayout parent) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, 0, 0, dp(10));
        wrap.setLayoutParams(wlp);

        TextView lbl = makeText("Steering Wheel Heat", 12.5f, 0xFFCBD5E1, true);
        lbl.setPadding(0, 0, 0, dp(5));
        wrap.addView(lbl);

        LinearLayout segRow = makeRow(0);
        String[] names = new String[] { "Off", "On" };
        String[] cmds = new String[] { "wheel_heat_off", "wheel_heat_on" };
        for (int i = 0; i < 2; i++) {
            final String cmd = cmds[i];
            final String human = "Steering Wheel Heat " + names[i];
            TextView seg = makeText(names[i], 13f, 0xFFFFFFFF, true);
            seg.setGravity(Gravity.CENTER);
            seg.setPadding(dp(10), dp(11), dp(10), dp(11));
            styleBox(seg, BG_SUBTLE, BORDER_SUBTLE, dp(9));
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    triggerAction(cmd, "", null, human, false);
                }
            });
            wheelHeatSegs[i] = seg;
            vehicleControlButtons.add(seg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i == 0) lp.setMargins(0, 0, dp(8), 0);
            segRow.addView(seg, lp);
        }
        wrap.addView(segRow);
        parent.addView(wrap);
    }

    private void addSeatLevelChooserRow(
            LinearLayout parent,
            final String title,
            final String setCmd,
            final TextView[] outSegs,
            final int activeStroke) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, 0, 0, dp(8));
        wrap.setLayoutParams(wlp);

        TextView lbl = makeText(title, 12f, 0xFFCBD5E1, true);
        lbl.setPadding(0, 0, 0, dp(4));
        wrap.addView(lbl);

        LinearLayout segRow = makeRow(0);
        String[] names = new String[] { "Off", "1", "2", "3" };
        for (int lvl = 0; lvl <= 3; lvl++) {
            final int targetLvl = lvl;
            final String humanLvl = lvl == 0 ? "Off" : ("Lvl " + lvl);
            TextView seg = makeText(names[lvl], 12f, 0xFFFFFFFF, true);
            seg.setGravity(Gravity.CENTER);
            seg.setPadding(dp(6), dp(9), dp(6), dp(9));
            styleBox(seg, BG_SUBTLE, BORDER_SUBTLE, dp(8));
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    triggerAction(setCmd, String.valueOf(targetLvl), null, title + " " + humanLvl, false);
                }
            });
            outSegs[lvl] = seg;
            vehicleControlButtons.add(seg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (lvl < 3) lp.setMargins(0, 0, dp(6), 0);
            segRow.addView(seg, lp);
        }
        wrap.addView(segRow);
        parent.addView(wrap);
    }

    // ---- TAB 2: BODY & ACCESS CONTROLS (Doors, Hatch Open/Close, Charge, Front Windows) ----

    private View buildBodyTab() {
        ScrollView sv = makeTabScrollView();
        LinearLayout page = makeTabPageColumn();
        sv.addView(page);

        LinearLayout ctrlGroup = new LinearLayout(this);
        ctrlGroup.setOrientation(LinearLayout.VERTICAL);
        interactiveControlContainers.add(ctrlGroup);

        // 1. Doors & Hatch (Open/Close only — no height/speed clutter)
        LinearLayout accessCard = makeCard("DOORS & HATCH");
        LinearLayout ar1 = makeRow(dp(8));
        addCtrlBtn(ar1, "Lock Doors", "door_lock", "", 0xFF10B981, false);
        addCtrlBtn(ar1, "Unlock Doors", "door_unlock", "", 0xFFF59E0B, true);
        accessCard.addView(ar1);

        LinearLayout ar2 = makeRow(dp(0));
        addCtrlBtn(ar2, "Open Hatch", "trunk_open", "", 0xFF0284C7, true);
        addCtrlBtn(ar2, "Close Hatch", "trunk_close", "", 0xFF334155, true);
        accessCard.addView(ar2);
        ctrlGroup.addView(accessCard);

        // 2. Windows (Front & All-Window presets — no sunroof/shade or rear window buttons)
        LinearLayout winCard = makeCard("WINDOWS");
        LinearLayout wr1 = makeRow(dp(8));
        addCtrlBtn(wr1, "Vent 25%", "window_all_vent", "", 0xFF0EA5E9, false);
        addCtrlBtn(wr1, "All 50%", "window_all_half", "", 0xFF0284C7, false);
        addCtrlBtn(wr1, "All Up", "window_all_close", "", 0xFF10B981, false);
        addCtrlBtn(wr1, "All Down", "window_all_open", "", 0xFFF59E0B, true);
        winCard.addView(wr1);

        LinearLayout wr2 = makeRow(dp(0));
        addCtrlBtn(wr2, "Driver Down", "window_fl_open", "", 0xFF0369A1, false);
        addCtrlBtn(wr2, "Driver Up", "window_fl_close", "", 0xFF334155, false);
        addCtrlBtn(wr2, "Pass Down", "window_fr_open", "", 0xFF0369A1, false);
        addCtrlBtn(wr2, "Pass Up", "window_fr_close", "", 0xFF334155, false);
        winCard.addView(wr2);
        ctrlGroup.addView(winCard);

        // 3. EV Charge Port & Battery Preconditioning (with live status + pack temp)
        LinearLayout evCtrlCard = makeCard("CHARGE PORT & BATTERY PRECONDITIONING");
        LinearLayout er0 = makeRow(dp(10));
        tvCtrlPrecondState = addSensorTile(er0, "Preconditioning", "OFF");
        tvCtrlBattTemp = addSensorTile(er0, "HV Battery Temp", "--");
        evCtrlCard.addView(er0);

        LinearLayout er1 = makeRow(dp(8));
        addCtrlBtn(er1, "Open Charge Port", "charge_door_open", "", 0xFF0EA5E9, false);
        addCtrlBtn(er1, "Close Charge Port", "charge_door_close", "", 0xFF334155, false);
        evCtrlCard.addView(er1);

        LinearLayout er2 = makeRow(dp(0));
        addCtrlBtn(er2, "Toggle Battery Preconditioning", "precondition_toggle", "", 0xFFF59E0B, false);
        evCtrlCard.addView(er2);
        ctrlGroup.addView(evCtrlCard);

        // 4. Utility Mode & Ignition-Off Bluelink Climate (Testing Controls)
        LinearLayout utilCard = makeCard("UTILITY & BLUELINK CLIMATE (IGN-OFF)");
        TextView tvBluelinkHint = makeText(
                "Home AC Profiles & Climate tab set target temp (62-82\u00B0F), defrost, wheel & seats when Ign is off (E-GMP locks fan to Auto 1).",
                11.5f, 0xFF94A3B8, false);
        tvBluelinkHint.setPadding(0, 0, 0, dp(8));
        utilCard.addView(tvBluelinkHint);

        LinearLayout ur0 = makeRow(dp(10));
        tvCtrlUtilModeState = addSensorTile(ur0, "Utility Mode", "--");
        tvCtrlEvReadyState = addSensorTile(ur0, "VCU Powertrain", "--");
        utilCard.addView(ur0);

        LinearLayout ur1 = makeRow(dp(8));
        addCtrlBtn(ur1, "Engage Utility Mode", "utility_mode_on", "", 0xFF8B5CF6, false);
        utilCard.addView(ur1);

        LinearLayout ur2 = makeRow(dp(0));
        addCtrlBtn(ur2, "Bluelink Climate ON", "tmu_fatc_on", "", 0xFF0EA5E9, false);
        addCtrlBtn(ur2, "Bluelink Climate OFF", "tmu_fatc_off", "", 0xFF475569, false);
        utilCard.addView(ur2);
        ctrlGroup.addView(utilCard);

        page.addView(ctrlGroup);
        return sv;
    }

    // ---- TAB 3: SENSORS (Last Confirmation, Door Locks, Tires, Hatch, EV Charge) ----

    private View buildSensorsTab() {
        ScrollView sv = makeTabScrollView();
        LinearLayout page = makeTabPageColumn();
        sv.addView(page);

        // 1. Time of Last Confirmation
        LinearLayout confirmCard = makeCard("LAST VEHICLE CONFIRMATION");
        tvSensorConfirmTime = makeText("No telemetry received yet", 15f, 0xFFF8FAFC, true);
        tvSensorConfirmAgo = makeText("Connect via Bluetooth or Wi-Fi server", 12f, 0xFF94A3B8, false);
        confirmCard.addView(tvSensorConfirmTime);
        confirmCard.addView(tvSensorConfirmAgo);
        page.addView(confirmCard);

        // 2. Security & Closures (Door Lock Status + Hatch Status)
        LinearLayout closureCard = makeCard("LOCKS, DOORS & HATCH");
        LinearLayout cr1 = makeRow(dp(8));
        tvSensorLockBadge = addSensorTile(cr1, "Door Locks", "--");
        tvSensorHatchBadge = addSensorTile(cr1, "Rear Hatch", "--");
        closureCard.addView(cr1);

        LinearLayout cr2 = makeRow(dp(0));
        tvSensorDoorsDetail = addSensorTile(cr2, "Doors Ajar", "--");
        tvSensorChargePort = addSensorTile(cr2, "Charge Door", "--");
        closureCard.addView(cr2);
        page.addView(closureCard);

        // 3. Tire Pressure Status (TPMS)
        LinearLayout tireCard = makeCard("TIRE STATUS (TPMS)");
        tvSensorTireSummary = makeText("Waiting for TPMS", 13f, 0xFFE2E8F0, true);
        tvSensorTireSummary.setPadding(0, 0, 0, dp(8));
        tireCard.addView(tvSensorTireSummary);

        LinearLayout tr1 = makeRow(dp(6));
        tvTireFl = addSensorTile(tr1, "Front Left", "--");
        tvTireFr = addSensorTile(tr1, "Front Right", "--");
        tireCard.addView(tr1);

        LinearLayout tr2 = makeRow(dp(0));
        tvTireRl = addSensorTile(tr2, "Rear Left", "--");
        tvTireRr = addSensorTile(tr2, "Rear Right", "--");
        tireCard.addView(tr2);
        page.addView(tireCard);

        // 4. EV Battery & Driveway Charging (no AC readings here)
        LinearLayout evCard = makeCard("EV BATTERY & CHARGING");
        LinearLayout socRow = makeRow(dp(8));
        tvSensorSocBig = makeText("--%", 32f, 0xFF38BDF8, true);
        socRow.addView(tvSensorSocBig);
        View sp = new View(this);
        socRow.addView(sp, new LinearLayout.LayoutParams(dp(12), 1));

        LinearLayout chgCol = new LinearLayout(this);
        chgCol.setOrientation(LinearLayout.VERTICAL);
        tvSensorChargeState = makeText("Standby", 14f, 0xFFE2E8F0, true);
        tvSensorRange = makeText("Est. Range: --", 12f, 0xFF94A3B8, false);
        chgCol.addView(tvSensorChargeState);
        chgCol.addView(tvSensorRange);
        socRow.addView(chgCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        evCard.addView(socRow);

        LinearLayout pr = makeRow(dp(8));
        tvSensorPrecond = addSensorTile(pr, "Preconditioning", "OFF");
        tvSensorBattTemp = addSensorTile(pr, "HV Battery Temp", "--");
        evCard.addView(pr);

        LinearLayout er = makeRow(dp(0));
        tvSensorAux12v = addSensorTile(er, "12V Aux Battery", "--");
        tvSensorOutside = addSensorTile(er, "Outside Temp", "--");
        evCard.addView(er);
        page.addView(evCard);

        return sv;
    }

    // ---- TAB 4: PROFILE (Connection Details, Command Log Button, Server & Key Management) ----

    private View buildProfileTab() {
        ScrollView sv = makeTabScrollView();
        LinearLayout page = makeTabPageColumn();
        sv.addView(page);

        // 1. Detailed Connection & Signed-In Status Card
        LinearLayout connDetailCard = makeCard("CONNECTION & TRUST DETAILS");
        tvProfPairedCar = makeText("", 14f, 0xFFFFFFFF, true);
        tvProfBtStatus = makeText("", 12f, 0xFFCBD5E1, false);
        tvProfWifiStatus = makeText("", 12f, 0xFFCBD5E1, false);
        tvProfKeyFp = makeText("", 11f, 0xFF94A3B8, false);
        tvProfBtStatus.setPadding(0, dp(4), 0, 0);
        tvProfWifiStatus.setPadding(0, dp(2), 0, 0);
        tvProfKeyFp.setPadding(0, dp(4), 0, 0);
        connDetailCard.addView(tvProfPairedCar);
        connDetailCard.addView(tvProfBtStatus);
        connDetailCard.addView(tvProfWifiStatus);
        connDetailCard.addView(tvProfKeyFp);
        page.addView(connDetailCard);

        // 2. Command Activity Log Card (Button to view Sent vs Executed vs Timed Out)
        LinearLayout logCard = makeCard("COMMAND ACTIVITY LOG");
        tvProfLogSummary = makeText("Track every command sent, executed, or timed out.", 12f, 0xFF94A3B8, false);
        tvProfLogSummary.setPadding(0, 0, 0, dp(8));
        logCard.addView(tvProfLogSummary);

        btnProfLogOpen = makeButton("View Command Log", 0xFF13233F, 0xFF38BDF8);
        btnProfLogOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCommandActivityLogDialog();
            }
        });
        logCard.addView(btnProfLogOpen);
        page.addView(logCard);

        // 3. Remote Server & Signed-In Token Card
        LinearLayout serverCard = makeCard("REMOTE WI-FI SERVER");
        serverCard.addView(makeText("Server Domain or URL (https:// is added automatically; blank for BT-only):", 11.5f, 0xFF94A3B8, false));
        edProfServerUrl = new EditText(this);
        edProfServerUrl.setSingleLine(true);
        edProfServerUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edProfServerUrl.setFocusable(true);
        edProfServerUrl.setFocusableInTouchMode(true);
        edProfServerUrl.setHint("your-server.example.com");
        edProfServerUrl.setTextColor(0xFFFFFFFF);
        edProfServerUrl.setHintTextColor(0xFF475569);
        serverCard.addView(edProfServerUrl);

        TextView tokLbl = makeText("Optional Account / Server Bearer Token:", 11.5f, 0xFF94A3B8, false);
        tokLbl.setPadding(0, dp(8), 0, 0);
        serverCard.addView(tokLbl);

        edProfServerToken = new EditText(this);
        edProfServerToken.setSingleLine(true);
        edProfServerToken.setFocusable(true);
        edProfServerToken.setFocusableInTouchMode(true);
        edProfServerToken.setHint("Optional token");
        edProfServerToken.setTextColor(0xFFFFFFFF);
        edProfServerToken.setHintTextColor(0xFF475569);
        edProfServerToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        serverCard.addView(edProfServerToken);

        cbProfRemoteFallback = new CheckBox(this);
        cbProfRemoteFallback.setText("Allow queueing commands over Wi-Fi when BT is out of range");
        cbProfRemoteFallback.setTextColor(0xFFE2E8F0);
        cbProfRemoteFallback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!suppressProfToggleCallback) {
                    remoteCtrl.setRemoteFallbackActive(isChecked);
                }
            }
        });
        serverCard.addView(cbProfRemoteFallback);

        btnProfSaveServer = makeButton("Save Server Settings", 0xFF0C4A6E, 0xFF38BDF8);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(0, dp(6), 0, 0);
        btnProfSaveServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProfileServerSettings();
            }
        });
        serverCard.addView(btnProfSaveServer, saveLp);

        btnProfClearServer = makeButton("Switch to Bluetooth-Only Mode", BG_SUBTLE, 0xFF475569);
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clrLp.setMargins(0, dp(6), 0, 0);
        btnProfClearServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edProfServerUrl.setText("");
                edProfServerToken.setText("");
                saveProfileServerSettings();
            }
        });
        serverCard.addView(btnProfClearServer, clrLp);
        page.addView(serverCard);

        // 4. Vehicle Pairing, Units & Security Key Backup Card
        LinearLayout manageCard = makeCard("VEHICLE PAIRING & SECURITY KEY");

        cbProfUseMiles = new CheckBox(this);
        cbProfUseMiles.setText("Display range in Miles (mi)");
        cbProfUseMiles.setTextColor(0xFFE2E8F0);
        cbProfUseMiles.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!suppressProfToggleCallback) {
                    store.setUseMiles(isChecked);
                    updateSensorsTab();
                }
            }
        });
        manageCard.addView(cbProfUseMiles);

        TextView pairCarBtn = makeButton("Pair with Car via Bluetooth...", 0xFF0369A1, 0xFF38BDF8);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, dp(6), 0, 0);
        pairCarBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBluetoothPairPickerDialog();
            }
        });
        manageCard.addView(pairCarBtn, plp);

        TextView keyBackupBtn = makeButton("Security Key Backup & Restore...", BG_SUBTLE, 0xFF38BDF8);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.setMargins(0, dp(6), 0, 0);
        keyBackupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyBackupSheet();
            }
        });
        manageCard.addView(keyBackupBtn, klp);

        btnProfUnpairCar = makeButton("Forget Paired Car", 0xFF450A0A, 0xFFEF4444);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMargins(0, dp(8), 0, 0);
        btnProfUnpairCar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUnpairDialog();
            }
        });
        manageCard.addView(btnProfUnpairCar, ulp);

        page.addView(manageCard);
        populateProfileFieldsFromStore();
        return sv;
    }

    // ---- In-Place Section Updaters ----

    private void updateAllSections(boolean forceRebuildThemes) {
        if (rootShell == null) return;
        updateConnectionHeader();
        updateControlInteractivity();
        updateHomePendingSection();
        updateThemesSection(forceRebuildThemes);
        updateClimateSensorAndSeats();
        updateBodyBatteryStatus();
        updateSensorsTab();
        updateProfileDynamicLabels();
    }

    private void updateConnectionHeader() {
        boolean paired = store.isCarPaired();
        boolean remConfigured = store.isRemoteServerConfigured();
        boolean wifiOnline = remConfigured && remoteCtrl.isCarWifiRecentlyOnline();
        long lastWifiMs = remoteCtrl.getLastCarWifiCheckInMs();

        setTextIfChanged(tvHeaderVehicle, paired ? store.getPairedCarName() : "Not paired yet");

        // When connected over Bluetooth, hide verbose Wi-Fi status and only show small "(Wi-Fi ●)" pill if configured
        if (btConnected && remConfigured) {
            if (tvWifiCompactPill.getVisibility() != View.VISIBLE) {
                tvWifiCompactPill.setVisibility(View.VISIBLE);
            }
            int pillState = wifiOnline ? 1 : (lastWifiMs > 0L ? 0 : -1);
            if (lastWifiPillState != pillState) {
                lastWifiPillState = pillState;
                int dotColor = wifiOnline ? 0xFF34D399 : (lastWifiMs > 0L ? 0xFFF59E0B : 0xFF64748B);
                tvWifiCompactPill.setTextColor(dotColor);
                styleBox(tvWifiCompactPill, 0xFF0A1426, dotColor, dp(999));
            }
            setTextIfChanged(tvWifiCompactPill, "Wi-Fi \u25CF");
        } else {
            if (tvWifiCompactPill.getVisibility() != View.GONE) {
                tvWifiCompactPill.setVisibility(View.GONE);
            }
        }

        // Main connection strip
        int visualKey;
        if (btConnected) {
            visualKey = 10;
            tvConnDot.setTextColor(0xFF34D399);
            setTextIfChanged(tvConnPrimary, "Bluetooth Connected");
            setTextIfChanged(tvConnSecondary, "Direct encrypted link active");
            if (btnQuickRemoteMode.getVisibility() != View.GONE) {
                btnQuickRemoteMode.setVisibility(View.GONE);
            }
        } else if (!paired) {
            visualKey = 0;
            tvConnDot.setTextColor(0xFFF59E0B);
            setTextIfChanged(tvConnPrimary, "No Car Paired");
            setTextIfChanged(tvConnSecondary, "Open the Profile tab to pair over Bluetooth.");
            if (btnQuickRemoteMode.getVisibility() != View.GONE) {
                btnQuickRemoteMode.setVisibility(View.GONE);
            }
        } else if (!remConfigured) {
            visualKey = 1;
            tvConnDot.setTextColor(0xFFF59E0B);
            setTextIfChanged(tvConnPrimary, "Looking for car BT signal...");
            setTextIfChanged(tvConnSecondary, "Move closer to your car while it is awake or charging.");
            if (btnQuickRemoteMode.getVisibility() != View.GONE) {
                btnQuickRemoteMode.setVisibility(View.GONE);
            }
        } else {
            boolean remActive = remoteCtrl.isRemoteFallbackActive();
            if (!remActive) {
                visualKey = 2;
                tvConnDot.setTextColor(0xFF94A3B8);
                setTextIfChanged(tvConnPrimary, "Looking for car BT signal...");
                setTextIfChanged(tvConnSecondary, "Wi-Fi queueing paused");
            } else if (wifiOnline) {
                visualKey = 3;
                tvConnDot.setTextColor(0xFF38BDF8);
                setTextIfChanged(tvConnPrimary, "Car Available via Wi-Fi");
                setTextIfChanged(tvConnSecondary, "No BT \u00B7 Checked in " + formatRelativeAgoShort(lastWifiMs));
            } else {
                visualKey = 4;
                tvConnDot.setTextColor(0xFFF59E0B);
                setTextIfChanged(tvConnPrimary, "Car Not Available on Wi-Fi");
                setTextIfChanged(tvConnSecondary, lastWifiMs > 0L
                        ? ("Last check-in " + formatRelativeAgoShort(lastWifiMs) + " \u00B7 Commands still queue (5m)")
                        : "No Wi-Fi check-in yet \u00B7 Commands still queue (5m)");
            }
            if (btnQuickRemoteMode.getVisibility() != View.VISIBLE) {
                btnQuickRemoteMode.setVisibility(View.VISIBLE);
            }
            setTextIfChanged(btnQuickRemoteMode, remActive ? "Wi-Fi ON" : "Wi-Fi OFF");
            styleBox(btnQuickRemoteMode, remActive ? 0xFF0C4A6E : BG_SUBTLE, remActive ? 0xFF38BDF8 : 0xFF475569, dp(999));
        }

        if (lastConnVisualKey != visualKey) {
            lastConnVisualKey = visualKey;
            if (visualKey == 10) {
                styleBox(connStrip, 0xFF06281E, 0xFF10B981, dp(12));
            } else if (visualKey == 3) {
                styleBox(connStrip, 0xFF0A2239, 0xFF38BDF8, dp(12));
            } else {
                styleBox(connStrip, BG_SURFACE, BORDER_ACCENT, dp(12));
            }
        }

        boolean showFeedback = lastActionFeedback != null && !lastActionFeedback.isEmpty()
                && (System.currentTimeMillis() - lastActionFeedbackAtMs) < 18_000L;
        if (showFeedback) {
            setTextIfChanged(tvToastBanner, lastActionFeedback);
            if (tvToastBanner.getVisibility() != View.VISIBLE) {
                tvToastBanner.setVisibility(View.VISIBLE);
            }
        } else if (tvToastBanner.getVisibility() != View.GONE) {
            tvToastBanner.setVisibility(View.GONE);
        }
    }

    private void updateControlInteractivity() {
        boolean enabled = isControlInteractive();
        if (lastControlsEnabled == null || lastControlsEnabled.booleanValue() != enabled) {
            lastControlsEnabled = Boolean.valueOf(enabled);
            for (View container : interactiveControlContainers) {
                container.setAlpha(enabled ? 1.0f : 0.42f);
            }
            for (TextView btn : vehicleControlButtons) {
                btn.setEnabled(enabled);
            }
            for (TextView btn : themeActivateButtons) {
                btn.setEnabled(enabled);
                btn.setAlpha(enabled ? 1.0f : 0.38f);
            }
        }
    }

    private void updateHomePendingSection() {
        if (homePendingCard == null) return;
        long now = System.currentTimeMillis();
        List<PhoneEditionBridge.PendingRemoteItem> all = remoteCtrl.getPendingCommands();
        List<PhoneEditionBridge.PendingRemoteItem> activeOnly = new ArrayList<PhoneEditionBridge.PendingRemoteItem>();
        for (PhoneEditionBridge.PendingRemoteItem it : all) {
            if ("QUEUED".equals(it.status) && now < it.expiresAtMs) {
                activeOnly.add(it);
            }
        }
        if (activeOnly.isEmpty() || btConnected) {
            if (homePendingCard.getVisibility() != View.GONE) {
                homePendingCard.setVisibility(View.GONE);
            }
            lastPendingSig = "";
            return;
        }
        if (homePendingCard.getVisibility() != View.VISIBLE) {
            homePendingCard.setVisibility(View.VISIBLE);
        }

        StringBuilder sb = new StringBuilder();
        for (PhoneEditionBridge.PendingRemoteItem it : activeOnly) {
            long remSec = Math.max(0L, (it.expiresAtMs - now) / 1000L);
            sb.append(it.commandId).append(':').append(remSec).append('|');
        }
        String sig = sb.toString();
        if (sig.equals(lastPendingSig)) return;
        lastPendingSig = sig;

        homePendingContainer.removeAllViews();
        for (final PhoneEditionBridge.PendingRemoteItem it : activeOnly) {
            LinearLayout row = makeRow(dp(6));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            styleBox(row, BG_SUBTLE, 0xFF38BDF8, dp(8));

            long remSec = Math.max(0L, (it.expiresAtMs - now) / 1000L);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(makeText(it.label + " sent.", 13f, 0xFFFFFFFF, true));
            col.addView(makeText(String.format(Locale.US, "Waiting for car \u00B7 %d:%02d left", remSec / 60L, remSec % 60L),
                    11f, 0xFF7DD3FC, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView cancelBtn = makeSmallPillButton("Cancel", 0xFF7F1D1D, 0xFFEF4444);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    remoteCtrl.cancelRemoteCommand(it.commandId);
                }
            });
            row.addView(cancelBtn);
            homePendingContainer.addView(row);
        }
    }

    private void updateThemesSection(boolean forceRebuild) {
        if (themesListContainer == null) return;
        List<ClimateTheme> themes = store.loadClimateThemes();
        StringBuilder sb = new StringBuilder();
        for (ClimateTheme t : themes) {
            sb.append(t.id).append(':').append(t.name).append(':').append(t.buildSummaryLine())
                    .append(':').append(t.accentColor).append('|');
        }
        String sig = sb.toString();
        boolean enabled = isControlInteractive();

        if (!forceRebuild && sig.equals(lastThemesSig)) {
            for (TextView btn : themeActivateButtons) {
                btn.setEnabled(enabled);
                btn.setAlpha(enabled ? 1.0f : 0.38f);
            }
            return;
        }

        lastThemesSig = sig;
        themesListContainer.removeAllViews();
        themeActivateButtons.clear();

        for (final ClimateTheme t : themes) {
            LinearLayout tBox = new LinearLayout(this);
            tBox.setOrientation(LinearLayout.HORIZONTAL);
            tBox.setGravity(Gravity.CENTER_VERTICAL);
            tBox.setPadding(dp(12), dp(11), dp(12), dp(11));
            styleBox(tBox, BG_SUBTLE, t.accentColor, dp(10));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMargins(0, 0, 0, dp(8));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(makeText(t.name, 15f, 0xFFFFFFFF, true));
            info.addView(makeText(t.buildSummaryLine(), 11f, 0xFF94A3B8, false));
            info.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showClimateThemeEditorDialog(t);
                }
            });
            tBox.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView startBtn = makeButton("Activate", 0xFF065F46, 0xFF10B981);
            startBtn.setEnabled(enabled);
            startBtn.setAlpha(enabled ? 1.0f : 0.38f);
            startBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    triggerAction("apply_climate_theme", "", t, t.name, false);
                }
            });
            tBox.addView(startBtn);
            themeActivateButtons.add(startBtn);
            themesListContainer.addView(tBox, tlp);
        }
    }

    private void updateClimateSensorAndSeats() {
        if (tvAcTargetTemp == null) return;
        boolean hvacOn = snapshot.flag("climate.on");
        String unit = snapshot.isCelsius() ? "\u00B0C" : "\u00B0F";
        String drvTemp = snapshot.raw("climate.driver_temp");
        if ("--".equals(drvTemp)) {
            setTextIfChanged(tvAcTargetTemp, "--\u00B0");
        } else if ("LO".equalsIgnoreCase(drvTemp) || "HI".equalsIgnoreCase(drvTemp)) {
            setTextIfChanged(tvAcTargetTemp, drvTemp);
        } else {
            setTextIfChanged(tvAcTargetTemp, drvTemp + unit);
        }

        tvAcStateBadge.setTextColor(hvacOn ? 0xFF34D399 : 0xFF94A3B8);
        String autoStr = snapshot.flag("climate.auto") ? (" \u00B7 AUTO " + snapshot.raw("climate.auto_level")) : "";
        setTextIfChanged(tvAcStateBadge, (hvacOn ? "CLIMATE ON" : ("--".equals(snapshot.raw("climate.on")) ? "HVAC --" : "CLIMATE OFF")) + autoStr);
        setTextIfChanged(tvAcFanMode, "Fan " + snapshot.raw("climate.fan_speed") + "  \u00B7  Vent: " + snapshot.formatVentMode());

        String flags = "A/C: " + onOffDash("climate.ac")
                + "  \u00B7  Heat: " + onOffDash("climate.heat")
                + "  \u00B7  Wheel: " + onOffDash("wheel_heat")
                + "  \u00B7  Defrost: " + (snapshot.flag("climate.front_defrost") || snapshot.flag("defogger") ? "ON" : "OFF")
                + (snapshot.isUtilityModeActive() ? "  \u00B7  Util: ON" : "");
        setTextIfChanged(tvAcFlagsLine, flags);

        String whRaw = snapshot.raw("wheel_heat");
        int wh = "--".equals(whRaw) ? -1 : (snapshot.flag("wheel_heat") ? 1 : 0);
        int dh = snapshot.intVal("seat_heat.driver", -1);
        int dc = snapshot.intVal("seat_vent.driver", -1);
        int ph = snapshot.intVal("seat_heat.passenger", -1);
        int pc = snapshot.intVal("seat_vent.passenger", -1);

        if (wh != lastWheelHeatVal) {
            lastWheelHeatVal = wh;
            highlightSeatSegments(wheelHeatSegs, wh, wh == 1 ? 0xFF7F1D1D : 0xFF1E293B, wh == 1 ? 0xFFEF4444 : 0xFF64748B);
        }
        if (dh != lastDrvHeatVal) {
            lastDrvHeatVal = dh;
            highlightSeatSegments(drvHeatSegs, dh, 0xFF7F1D1D, 0xFFEF4444);
        }
        if (dc != lastDrvCoolVal) {
            lastDrvCoolVal = dc;
            highlightSeatSegments(drvCoolSegs, dc, 0xFF0C4A6E, 0xFF38BDF8);
        }
        if (ph != lastPassHeatVal) {
            lastPassHeatVal = ph;
            highlightSeatSegments(passHeatSegs, ph, 0xFF7F1D1D, 0xFFEF4444);
        }
        if (pc != lastPassCoolVal) {
            lastPassCoolVal = pc;
            highlightSeatSegments(passCoolSegs, pc, 0xFF0C4A6E, 0xFF38BDF8);
        }
    }

    private void updateBodyBatteryStatus() {
        if (tvCtrlPrecondState == null) return;
        String status = snapshot.formatPreconditioningStatus();
        setTextIfChanged(tvCtrlPrecondState, status);
        tvCtrlPrecondState.setTextColor(snapshot.isPreconditioningActive()
                ? 0xFF34D399
                : (snapshot.isPreconditioningPending() ? 0xFFFBBF24 : 0xFFE2E8F0));
        setTextIfChanged(tvCtrlBattTemp, snapshot.formatBatteryTemp(!snapshot.isCelsius()));

        if (tvCtrlUtilModeState != null) {
            String utilTxt = snapshot.formatUtilityModeStatus();
            setTextIfChanged(tvCtrlUtilModeState, utilTxt);
            boolean utilOn = snapshot.isUtilityModeActive();
            boolean utilWarn = snapshot.intVal("hv.utility_warn", -1) == 2 && !utilOn;
            tvCtrlUtilModeState.setTextColor(utilOn ? 0xFF34D399 : (utilWarn ? 0xFFFBBF24 : 0xFFE2E8F0));
        }
        if (tvCtrlEvReadyState != null) {
            String rdyTxt = snapshot.formatEvReadyStatus();
            setTextIfChanged(tvCtrlEvReadyState, rdyTxt);
            tvCtrlEvReadyState.setTextColor(snapshot.intVal("hv.ready", -1) == 1 ? 0xFF34D399 : 0xFF94A3B8);
        }
    }

    private void highlightSeatSegments(TextView[] segs, int activeLvl, int activeFill, int activeStroke) {
        for (int i = 0; i < segs.length; i++) {
            if (segs[i] == null) continue;
            boolean sel = (i == activeLvl);
            styleBox(segs[i], sel ? activeFill : BG_SUBTLE, sel ? activeStroke : BORDER_SUBTLE, dp(8));
            segs[i].setTextColor(sel ? 0xFFFFFFFF : 0xFFCBD5E1);
        }
    }

    private void updateSensorsTab() {
        if (tvSensorConfirmTime == null) return;

        long confirmMs = Math.max(snapshot.updatedAtMs, remoteCtrl.getLastCarWifiCheckInMs());
        if (confirmMs <= 0L) {
            setTextIfChanged(tvSensorConfirmTime, "No vehicle confirmation yet");
            setTextIfChanged(tvSensorConfirmAgo, "Waiting for Bluetooth or Wi-Fi telemetry");
        } else {
            String clock = new SimpleDateFormat("MMM d, h:mm:ss a", Locale.US).format(new Date(confirmMs));
            setTextIfChanged(tvSensorConfirmTime, clock);
            String channel = btConnected ? "Live via Bluetooth" : ("Confirmed " + formatRelativeAgoShort(confirmMs));
            setTextIfChanged(tvSensorConfirmAgo, channel);
        }

        // Door Lock Status
        String lockRaw = snapshot.raw("door.lock_state");
        if ("--".equals(lockRaw)) {
            setTextIfChanged(tvSensorLockBadge, "--");
            tvSensorLockBadge.setTextColor(0xFF94A3B8);
        } else {
            boolean locked = snapshot.flag("door.lock_state");
            setTextIfChanged(tvSensorLockBadge, locked ? "LOCKED" : "UNLOCKED");
            tvSensorLockBadge.setTextColor(locked ? 0xFF34D399 : 0xFFFBBF24);
        }

        // Hatch Status
        String hatchRaw = snapshot.raw("trunk.open");
        if ("--".equals(hatchRaw)) {
            setTextIfChanged(tvSensorHatchBadge, "--");
            tvSensorHatchBadge.setTextColor(0xFF94A3B8);
        } else {
            boolean open = snapshot.flag("trunk.open");
            setTextIfChanged(tvSensorHatchBadge, open ? "OPEN" : "CLOSED");
            tvSensorHatchBadge.setTextColor(open ? 0xFFFBBF24 : 0xFF34D399);
        }

        // Doors Ajar & Charge Port
        String anyOpen = snapshot.raw("door.any_open");
        if ("--".equals(anyOpen)) {
            setTextIfChanged(tvSensorDoorsDetail, "--");
            tvSensorDoorsDetail.setTextColor(0xFF94A3B8);
        } else {
            boolean open = "1".equals(anyOpen);
            setTextIfChanged(tvSensorDoorsDetail, open ? snapshot.buildDoorsSummary() : "All Closed");
            tvSensorDoorsDetail.setTextColor(open ? 0xFFFBBF24 : 0xFFE2E8F0);
        }

        String cdm = snapshot.raw("charge_door.open");
        setTextIfChanged(tvSensorChargePort, "--".equals(cdm) ? "--" : (snapshot.flag("charge_door.open") ? "OPEN" : "CLOSED"));

        // Tire Pressure Status (TPMS)
        String tireSummary = snapshot.buildTiresSummary();
        boolean anyLow = snapshot.isSingleTireLow("tyre.fl_status")
                || snapshot.isSingleTireLow("tyre.fr_status")
                || snapshot.isSingleTireLow("tyre.rl_status")
                || snapshot.isSingleTireLow("tyre.rr_status");
        setTextIfChanged(tvSensorTireSummary, tireSummary);
        tvSensorTireSummary.setTextColor(anyLow ? 0xFFF87171 : 0xFF34D399);

        updateSingleTireView(tvTireFl, "tyre.fl_status");
        updateSingleTireView(tvTireFr, "tyre.fr_status");
        updateSingleTireView(tvTireRl, "tyre.rl_status");
        updateSingleTireView(tvTireRr, "tyre.rr_status");

        // EV Battery & Charging
        String soc = snapshot.raw("hv.soc");
        setTextIfChanged(tvSensorSocBig, "--".equals(soc) ? "--%" : (soc + "%"));
        boolean chg = snapshot.isCharging();
        String pwr = snapshot.raw("charging.power_kw");
        tvSensorChargeState.setTextColor(chg ? 0xFF34D399 : 0xFFE2E8F0);
        setTextIfChanged(tvSensorChargeState, chg
                ? ("Charging" + ("--".equals(pwr) ? "" : (" \u00B7 " + pwr + " kW")) + " (" + snapshot.formatChargeRemain() + ")")
                : ("--".equals(soc) ? "Waiting for telemetry" : "Not Charging"));
        setTextIfChanged(tvSensorRange, "Est. Range: " + snapshot.formatRange(store.isUseMiles()));

        String preStatus = snapshot.formatPreconditioningStatus();
        setTextIfChanged(tvSensorPrecond, preStatus);
        tvSensorPrecond.setTextColor(snapshot.isPreconditioningActive()
                ? 0xFF34D399
                : (snapshot.isPreconditioningPending() ? 0xFFFBBF24 : 0xFFE2E8F0));
        setTextIfChanged(tvSensorBattTemp, snapshot.formatBatteryTemp(!snapshot.isCelsius()));

        String aux = snapshot.raw("battery12v.soc");
        setTextIfChanged(tvSensorAux12v, "--".equals(aux) ? "--" : (aux + "%"));
        setTextIfChanged(tvSensorOutside, snapshot.formatAmbientTemp(true));
    }

    private void updateSingleTireView(TextView tv, String key) {
        String txt = snapshot.formatSingleTire(key);
        setTextIfChanged(tv, txt);
        if ("LOW".equals(txt)) {
            tv.setTextColor(0xFFF87171);
        } else if ("OK".equals(txt)) {
            tv.setTextColor(0xFF34D399);
        } else {
            tv.setTextColor(0xFF94A3B8);
        }
    }

    private void populateProfileFieldsFromStore() {
        if (edProfServerUrl == null) return;
        suppressProfToggleCallback = true;
        edProfServerUrl.setText(store.getRemoteServerUrl());
        edProfServerToken.setText(store.getRemoteAccountToken());
        cbProfRemoteFallback.setChecked(remoteCtrl.isRemoteFallbackActive());
        cbProfUseMiles.setChecked(store.isUseMiles());
        suppressProfToggleCallback = false;
        updateProfileDynamicLabels();
    }

    private void updateProfileDynamicLabels() {
        if (tvProfPairedCar == null) return;
        boolean paired = store.isCarPaired();
        setTextIfChanged(tvProfPairedCar, paired
                ? ("Paired Vehicle: " + store.getPairedCarName())
                : "No Vehicle Paired Yet");
        setTextIfChanged(tvProfBtStatus, "Bluetooth: " + (btConnected ? "Connected (Encrypted)" : btStatusLine));

        if (!store.isRemoteServerConfigured()) {
            setTextIfChanged(tvProfWifiStatus, "Wi-Fi Server: Disabled (Bluetooth-only mode)");
        } else {
            long lastWifi = remoteCtrl.getLastCarWifiCheckInMs();
            boolean online = remoteCtrl.isCarWifiRecentlyOnline();
            String host = store.getRemoteServerUrl().replace("https://", "");
            String authTag = store.getRemoteAccountToken().isEmpty() ? "Key Auth" : "Token Signed-In";
            String seenTag = lastWifi <= 0L ? "never checked in" : ("checked in " + formatRelativeAgoShort(lastWifi));
            setTextIfChanged(tvProfWifiStatus, "Wi-Fi Server: " + host + " (" + (online ? "Online" : "Offline")
                    + " \u00B7 " + seenTag + " \u00B7 " + authTag + ")");
        }

        setTextIfChanged(tvProfKeyFp, "Phone Security Key: " + store.getKeyFingerprintShort()
                + (paired ? ("  \u00B7  Car ID: " + store.getPairedCarId()) : ""));

        List<CommandLogEntry> log = store.loadCommandLog();
        if (log.isEmpty()) {
            setTextIfChanged(tvProfLogSummary, "No commands logged yet.");
            setTextIfChanged(btnProfLogOpen, "View Command Log");
        } else {
            CommandLogEntry latest = log.get(0);
            setTextIfChanged(tvProfLogSummary, "Latest: " + latest.formatFriendlyHeadline());
            setTextIfChanged(btnProfLogOpen, "View Command Log (" + log.size() + ")");
        }

        setTextIfChanged(btnProfSaveServer, btConnected ? "Save & Sync Server to Car" : "Save Server Settings");
        btnProfClearServer.setVisibility(store.isRemoteServerConfigured() ? View.VISIBLE : View.GONE);
        btnProfUnpairCar.setVisibility(paired ? View.VISIBLE : View.GONE);
    }

    private void saveProfileServerSettings() {
        String normalized = RemoteEnvelope.normalizeServerUrl(edProfServerUrl.getText().toString());
        String tok = edProfServerToken.getText().toString().trim();
        edProfServerUrl.setText(normalized);
        store.setRemoteServerUrl(normalized);
        store.setRemoteAccountToken(normalized.isEmpty() ? "" : tok);
        if (normalized.isEmpty()) {
            remoteCtrl.stopPolling();
        } else {
            remoteCtrl.startPollingIfNeeded();
        }
        if (btConnected) {
            btClient.syncRemoteServerToCar(store.getRemoteServerUrl(), store.getRemoteAccountToken());
        }
        Toast.makeText(this,
                normalized.isEmpty()
                        ? "Switched to Bluetooth-only mode"
                        : (btConnected ? "Saved & synced server to car!" : "Saved server settings"),
                Toast.LENGTH_SHORT).show();
        updateAllSections(false);
    }

    // ---- User-Friendly Command Activity Log Dialog ----

    private void showCommandActivityLogDialog() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(12), dp(16), dp(8));

        activeLogListContainer = new LinearLayout(this);
        activeLogListContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        sv.addView(activeLogListContainer);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lastDialogLogSig = "";
        refreshCommandLogDialogInPlace(true);

        activeLogDialog = new AlertDialog.Builder(this)
                .setTitle("Command Activity Log")
                .setView(outer)
                .setPositiveButton("Done", null)
                .setNeutralButton("Clear Log", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.clearCommandLog();
                        updateProfileDynamicLabels();
                    }
                })
                .create();
        activeLogDialog.show();
    }

    private void refreshCommandLogDialogInPlace(boolean force) {
        if (activeLogListContainer == null) return;
        List<CommandLogEntry> entries = store.loadCommandLog();
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        for (CommandLogEntry e : entries) {
            long rem = e.isPendingRemote(now) ? ((e.expiresAtMs - now) / 1000L) : 0L;
            sb.append(e.id).append(':').append(e.state).append(':').append(rem).append(':').append(e.detail).append('|');
        }
        String sig = sb.toString();
        if (!force && sig.equals(lastDialogLogSig)) return;
        lastDialogLogSig = sig;

        activeLogListContainer.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = makeText("No commands sent yet.\nCommands will appear here as Sent, Executed, or Timed Out.",
                    13f, 0xFF94A3B8, false);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            activeLogListContainer.addView(empty);
            return;
        }

        for (final CommandLogEntry e : entries) {
            LinearLayout row = makeRow(dp(8));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            int stroke;
            int headlineColor;
            String badgeIcon;
            if (CommandLogEntry.STATE_EXECUTED.equals(e.state)) {
                stroke = 0xFF10B981;
                headlineColor = 0xFF6EE7B7;
                badgeIcon = "\u2713  ";
            } else if (CommandLogEntry.STATE_TIMED_OUT.equals(e.state)) {
                stroke = 0xFFF59E0B;
                headlineColor = 0xFFFDE68A;
                badgeIcon = "\u23F1  ";
            } else if (CommandLogEntry.STATE_FAILED.equals(e.state)) {
                stroke = 0xFFEF4444;
                headlineColor = 0xFFFCA5A5;
                badgeIcon = "\u26A0  ";
            } else if (CommandLogEntry.STATE_CANCELED.equals(e.state)) {
                stroke = 0xFF475569;
                headlineColor = 0xFF94A3B8;
                badgeIcon = "\u2715  ";
            } else {
                stroke = 0xFF38BDF8;
                headlineColor = 0xFFE0F2FE;
                badgeIcon = "\u2191  ";
            }
            styleBox(row, BG_SUBTLE, stroke, dp(9));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(makeText(badgeIcon + e.formatFriendlyHeadline(), 13.5f, headlineColor, true));
            col.addView(makeText(e.formatSubline(now), 11f, 0xFF94A3B8, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (e.isPendingRemote(now)) {
                TextView cancelBtn = makeSmallPillButton("Cancel", 0xFF7F1D1D, 0xFFEF4444);
                cancelBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        remoteCtrl.cancelRemoteCommand(e.id);
                    }
                });
                row.addView(cancelBtn);
            }
            activeLogListContainer.addView(row);
        }
    }

    // ---- AC Profile Editor (with Tap-to-Select Front Seat Level Pills & Zero Rear Clutter) ----

    private void showClimateThemeEditorDialog(final ClimateTheme existing) {
        final ClimateTheme working = existing != null ? existing : new ClimateTheme();
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(12), dp(18), dp(8));
        sv.addView(form);

        form.addView(makeText("Profile Name:", 12f, 0xFF94A3B8, true));
        final EditText nameEd = new EditText(this);
        nameEd.setSingleLine(true);
        nameEd.setText(working.name);
        form.addView(nameEd);

        form.addView(makeText("Target Temperature (e.g. 69, 78, 21.5, LO, HI):", 12f, 0xFF94A3B8, true));
        final EditText tempEd = new EditText(this);
        tempEd.setSingleLine(true);
        tempEd.setText(working.driverTemp);
        form.addView(tempEd);

        final CheckBox celsiusCb = new CheckBox(this);
        celsiusCb.setText("Temperature is in Celsius (\u00B0C)");
        celsiusCb.setChecked(working.celsius);
        form.addView(celsiusCb);

        form.addView(makeText("Blower Fan Speed (1 to 8):", 12f, 0xFF94A3B8, true));
        final EditText fanEd = new EditText(this);
        fanEd.setSingleLine(true);
        fanEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        fanEd.setText(String.valueOf(working.fanSpeed));
        form.addView(fanEd);

        final CheckBox acCb = new CheckBox(this);
        acCb.setText("A/C Compressor ON");
        acCb.setChecked(working.acOn);
        form.addView(acCb);

        final CheckBox heatCb = new CheckBox(this);
        heatCb.setText("Cabin Heater ON");
        heatCb.setChecked(working.heatOn);
        form.addView(heatCb);

        final CheckBox wheelCb = new CheckBox(this);
        wheelCb.setText("Heated Steering Wheel ON");
        wheelCb.setChecked(working.wheelHeat);
        form.addView(wheelCb);

        final CheckBox defCb = new CheckBox(this);
        defCb.setText("Defrost ON");
        defCb.setChecked(working.frontDefrost || working.rearDefrost);
        form.addView(defCb);

        final int[] selDrvHeat = new int[] { working.driverSeatHeat };
        final int[] selDrvVent = new int[] { working.driverSeatVent };
        final int[] selPassHeat = new int[] { working.passengerSeatHeat };
        final int[] selPassVent = new int[] { working.passengerSeatVent };

        addDialogLevelPicker(form, "Driver Seat Heat:", selDrvHeat, 0xFF7F1D1D, 0xFFEF4444);
        addDialogLevelPicker(form, "Driver Seat Cool:", selDrvVent, 0xFF0C4A6E, 0xFF38BDF8);
        addDialogLevelPicker(form, "Passenger Seat Heat:", selPassHeat, 0xFF7F1D1D, 0xFFEF4444);
        addDialogLevelPicker(form, "Passenger Seat Cool:", selPassVent, 0xFF0C4A6E, 0xFF38BDF8);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New AC Profile" : "Edit AC Profile")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        working.name = nameEd.getText().toString().trim();
                        if (working.name.isEmpty()) working.name = "Custom AC Profile";
                        working.driverTemp = tempEd.getText().toString().trim();
                        if (working.driverTemp.isEmpty()) working.driverTemp = "70";
                        working.passengerTemp = working.driverTemp;
                        working.syncZones = true;
                        working.celsius = celsiusCb.isChecked();
                        working.fanSpeed = parseIntClamped(fanEd.getText().toString(), 3, 1, 8);
                        working.acOn = acCb.isChecked();
                        working.heatOn = heatCb.isChecked();
                        working.wheelHeat = wheelCb.isChecked();
                        working.frontDefrost = defCb.isChecked();
                        working.rearDefrost = defCb.isChecked();
                        working.driverSeatHeat = selDrvHeat[0];
                        working.driverSeatVent = selDrvVent[0];
                        working.passengerSeatHeat = selPassHeat[0];
                        working.passengerSeatVent = selPassVent[0];
                        working.rearLeftSeatHeat = 0;
                        working.rearRightSeatHeat = 0;
                        working.accentColor = working.heatOn ? 0xFFF97316 : 0xFF0EA5E9;
                        store.upsertClimateTheme(working);
                        updateThemesSection(true);
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    store.deleteClimateTheme(existing.id);
                    updateThemesSection(true);
                }
            });
        }
        b.show();
    }

    private void addDialogLevelPicker(
            LinearLayout form,
            String caption,
            final int[] stateHolder,
            final int activeFill,
            final int activeStroke) {
        TextView lbl = makeText(caption, 12f, 0xFF94A3B8, true);
        lbl.setPadding(0, dp(8), 0, dp(4));
        form.addView(lbl);

        LinearLayout row = makeRow(0);
        final TextView[] pills = new TextView[4];
        String[] names = new String[] { "Off", "1", "2", "3" };
        for (int i = 0; i <= 3; i++) {
            final int val = i;
            TextView p = makeText(names[i], 12f, 0xFFFFFFFF, true);
            p.setGravity(Gravity.CENTER);
            p.setPadding(dp(6), dp(8), dp(6), dp(8));
            pills[i] = p;
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stateHolder[0] = val;
                    highlightSeatSegments(pills, val, activeFill, activeStroke);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 3) lp.setMargins(0, 0, dp(6), 0);
            row.addView(p, lp);
        }
        highlightSeatSegments(pills, stateHolder[0], activeFill, activeStroke);
        form.addView(row);
    }

    // ---- Bluetooth Pairing Dialog & Unpair Confirm ----

    private void showBluetoothPairPickerDialog() {
        final List<BluetoothDevice> bonded = btClient.listBondedDevicesSafe();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(10));

        box.addView(makeText(
                "1. Open 'Digital Control+' on your car screen and unlock Pairing Mode with your PIN.\n"
                + "2. Tap your car below (or tap 'Connect App' on the car screen):",
                12f, 0xFFCBD5E1, false));

        final AlertDialog[] holder = new AlertDialog[1];
        if (bonded.isEmpty()) {
            box.addView(makeText("\nNo bonded Bluetooth devices found. Pair your phone to the car's standard Bluetooth first.",
                    12f, 0xFFFDE68A, false));
        } else {
            for (final BluetoothDevice d : bonded) {
                TextView btn = makeButton("Pair with: " + safeDevName(d), 0xFF0369A1, 0xFF38BDF8);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(6), 0, 0);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        setFeedback("Connecting to " + safeDevName(d) + "...");
                        updateConnectionHeader();
                        btClient.startManualPairWithDevice(d);
                    }
                });
                box.addView(btn, lp);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Pair Car via Bluetooth")
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
    }

    private void confirmUnpairDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Forget Paired Car?")
                .setMessage("Remove " + store.getPairedCarName() + " from this phone?")
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.clearPairedCar();
                        btClient.stop();
                        if (hasBluetoothRuntimePermissions()) btClient.start();
                        updateAllSections(false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Security Key Backup & Restore (Google App Backup + Local .g5wkey File) ----

    private void showKeyBackupSheet() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout idCard = new LinearLayout(this);
        idCard.setOrientation(LinearLayout.VERTICAL);
        idCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        styleBox(idCard, BG_SUBTLE, BORDER_ACCENT, dp(10));
        idCard.addView(makeText("ACTIVE PHONE SECURITY KEY", 11f, 0xFF7DD3FC, true));
        idCard.addView(makeText("Phone ID: " + store.getPhoneId(), 12f, 0xFFE2E8F0, false));
        idCard.addView(makeText("Fingerprint: " + store.getKeyFingerprintShort(), 13f, 0xFF34D399, true));
        box.addView(idCard);

        box.addView(makeText("\n1. GOOGLE APP BACKUP", 11f, 0xFF7DD3FC, true));
        final CheckBox googleBackupCb = new CheckBox(this);
        googleBackupCb.setText("Include Security Key in Google App Backup");
        googleBackupCb.setTextColor(0xFFFFFFFF);
        googleBackupCb.setChecked(store.isCloudBackupEnabled());
        googleBackupCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                store.setCloudBackupEnabled(isChecked);
                Toast.makeText(PhoneMainActivity.this,
                        isChecked ? "Included in Google App Backup" : "Local device storage only",
                        Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(googleBackupCb);

        box.addView(makeText("\n2. LOCAL ENCRYPTED FILE (.g5wkey)", 11f, 0xFF7DD3FC, true));
        TextView exportBtn = makeButton("Save Encrypted Key Backup...", 0xFF065F46, 0xFF10B981);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(0, dp(6), 0, 0);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                showExportPassphraseDialog();
            }
        });
        box.addView(exportBtn, elp);

        TextView importFileBtn = makeButton("Restore Key from File...", 0xFF0369A1, 0xFF38BDF8);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, dp(6), 0, 0);
        importFileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                launchOpenBackupDocumentPicker();
            }
        });
        box.addView(importFileBtn, ilp);

        if (store.hasLocalDeviceBackupFile()) {
            final String ts = new SimpleDateFormat("MMM d, HH:mm", Locale.US)
                    .format(new Date(store.getLocalDeviceBackupTimestampMs()));
            TextView quickRestoreBtn = makeButton("Restore Local Snapshot (" + ts + ")", BG_SURFACE, 0xFF64748B);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.setMargins(0, dp(6), 0, 0);
            quickRestoreBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    try {
                        String raw = store.readLocalDeviceBackupFile();
                        showRestorePassphraseDialog(raw, "Local Snapshot (" + ts + ")");
                    } catch (Throwable t) {
                        Toast.makeText(PhoneMainActivity.this,
                                "Read failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
            box.addView(quickRestoreBtn, qlp);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Security Key Backup")
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
    }

    private void showExportPassphraseDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(12), dp(18), dp(8));

        form.addView(makeText("Create a passphrase (6+ chars) to encrypt your backup:", 12f, 0xFFCBD5E1, false));
        form.addView(makeText("\nPassphrase:", 12f, 0xFF7DD3FC, true));
        final EditText pass1 = new EditText(this);
        pass1.setSingleLine(true);
        pass1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass1);

        form.addView(makeText("Confirm Passphrase:", 12f, 0xFF7DD3FC, true));
        final EditText pass2 = new EditText(this);
        pass2.setSingleLine(true);
        pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass2);

        new AlertDialog.Builder(this)
                .setTitle("Encrypt Key Backup")
                .setView(form)
                .setPositiveButton("Encrypt & Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String p1 = pass1.getText().toString();
                        final String p2 = pass2.getText().toString();
                        if (p1.length() < 6) {
                            Toast.makeText(PhoneMainActivity.this, "Use at least 6 characters", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!p1.equals(p2)) {
                            Toast.makeText(PhoneMainActivity.this, "Passphrases did not match", Toast.LENGTH_LONG).show();
                            return;
                        }
                        encryptAndLaunchSavePickerAsync(p1);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void encryptAndLaunchSavePickerAsync(final String passphrase) {
        setFeedback("Encrypting key backup...");
        updateConnectionHeader();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    KeyBackupBundle bundle = store.buildBackupBundle();
                    final String encryptedJson = KeyBackupBundle.exportEncryptedJson(bundle, passphrase);
                    store.saveLocalDeviceBackupFile(encryptedJson);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pendingExportEncryptedJson = encryptedJson;
                            setFeedback("\u2713 Snapshot saved — pick folder for .g5wkey");
                            updateConnectionHeader();
                            launchSaveBackupDocumentPicker();
                        }
                    });
                } catch (final Throwable err) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setFeedback("\u26A0 Backup failed: " + err.getMessage());
                            updateConnectionHeader();
                        }
                    });
                }
            }
        }, "digctl-key-export");
        t.setDaemon(true);
        t.start();
    }

    private void launchSaveBackupDocumentPicker() {
        try {
            String dateTag = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "gen5w-security-key-" + dateTag + ".g5wkey");
            startActivityForResult(intent, REQ_SAVE_BACKUP_DOC);
        } catch (Throwable t) {
            Toast.makeText(this, "Saved local snapshot in app storage", Toast.LENGTH_LONG).show();
        }
    }

    private void launchOpenBackupDocumentPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_OPEN_BACKUP_DOC);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not open file picker: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_SAVE_BACKUP_DOC) {
            final String payload = pendingExportEncryptedJson;
            pendingExportEncryptedJson = null;
            if (payload == null || payload.isEmpty()) return;
            try {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    try {
                        os.write(payload.getBytes(CryptoBox.UTF8));
                        os.flush();
                    } finally {
                        os.close();
                    }
                }
                setFeedback("\u2713 Key backup saved (" + store.getKeyFingerprintShort() + ")");
                updateConnectionHeader();
                Toast.makeText(this, "Backup saved!", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Failed writing file: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_OPEN_BACKUP_DOC) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new IllegalStateException("Unable to open file");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (bos.size() + n > 512 * 1024) {
                            throw new IllegalStateException("Backup file too large");
                        }
                        bos.write(buf, 0, n);
                    }
                } finally {
                    is.close();
                }
                showRestorePassphraseDialog(new String(bos.toByteArray(), CryptoBox.UTF8), "Selected File");
            } catch (Throwable t) {
                Toast.makeText(this, "Could not read file: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showRestorePassphraseDialog(final String encryptedJson, final String sourceLabel) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(12), dp(18), dp(8));

        form.addView(makeText("Enter passphrase for '" + sourceLabel + "':", 12f, 0xFFCBD5E1, false));
        final EditText passEd = new EditText(this);
        passEd.setSingleLine(true);
        passEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(passEd);

        new AlertDialog.Builder(this)
                .setTitle("Restore Security Key")
                .setView(form)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String pass = passEd.getText().toString();
                        if (!pass.isEmpty()) {
                            decryptAndRestoreAsync(encryptedJson, pass);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void decryptAndRestoreAsync(final String encryptedJson, final String passphrase) {
        setFeedback("Decrypting security key...");
        updateConnectionHeader();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final KeyBackupBundle bundle = KeyBackupBundle.importEncryptedJson(encryptedJson, passphrase);
                    store.restoreBackupBundle(bundle);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            btClient.stop();
                            if (hasBluetoothRuntimePermissions()) btClient.start();
                            remoteCtrl.stopPolling();
                            remoteCtrl.startPollingIfNeeded();
                            populateProfileFieldsFromStore();
                            setFeedback("\u2713 Key restored (" + store.getKeyFingerprintShort() + ")");
                            updateAllSections(true);
                            Toast.makeText(PhoneMainActivity.this, "Security key restored!", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Throwable err) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setFeedback("\u26A0 Restore failed: " + err.getMessage());
                            updateConnectionHeader();
                        }
                    });
                }
            }
        }, "digctl-key-restore");
        t.setDaemon(true);
        t.start();
    }

    // ---- UI Widget & Formatting Helpers ----

    private String onOffDash(String key) {
        String r = snapshot.raw(key);
        return "--".equals(r) ? "--" : (snapshot.flag(key) ? "ON" : "OFF");
    }

    private static String formatRelativeAgoShort(long pastMs) {
        if (pastMs <= 0L) return "never";
        long sec = Math.max(0L, (System.currentTimeMillis() - pastMs) / 1000L);
        if (sec < 5L) return "just now";
        if (sec < 60L) return sec + "s ago";
        long min = sec / 60L;
        if (min < 60L) return min + "m ago";
        long hr = min / 60L;
        if (hr < 24L) return hr + "h ago";
        return (hr / 24L) + "d ago";
    }

    private ScrollView makeTabScrollView() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        return sv;
    }

    private LinearLayout makeTabPageColumn() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        return page;
    }

    private static void setTextIfChanged(TextView tv, String value) {
        if (tv == null) return;
        String safe = value == null ? "" : value;
        if (!safe.contentEquals(tv.getText())) {
            tv.setText(safe);
        }
    }

    private void addCtrlBtn(
            LinearLayout row,
            final String label,
            final String cmd,
            final String arg,
            int strokeColor,
            final boolean confirm) {
        TextView btn = makeButton(label, 0xFF132038, strokeColor);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerAction(cmd, arg, null, label, confirm);
            }
        });
        vehicleControlButtons.add(btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            View prev = row.getChildAt(row.getChildCount() - 1);
            LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) prev.getLayoutParams();
            plp.setMargins(0, 0, dp(7), 0);
            prev.setLayoutParams(plp);
        }
        row.addView(btn, lp);
    }

    private TextView addSensorTile(LinearLayout row, String caption, String val) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(9), dp(11), dp(9));
        styleBox(box, BG_SUBTLE, BORDER_SUBTLE, dp(9));
        box.addView(makeText(caption, 10.5f, 0xFF94A3B8, false));
        TextView valTv = makeText(val, 14f, 0xFFFFFFFF, true);
        valTv.setPadding(0, dp(2), 0, 0);
        box.addView(valTv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            View prev = row.getChildAt(row.getChildCount() - 1);
            LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) prev.getLayoutParams();
            plp.setMargins(0, 0, dp(7), 0);
            prev.setLayoutParams(plp);
        }
        row.addView(box, lp);
        return valTv;
    }

    private LinearLayout makeCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        styleBox(card, BG_SURFACE, BORDER_SUBTLE, dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(11));
        card.setLayoutParams(lp);
        TextView h = makeText(title, 10.5f, 0xFF7DD3FC, true);
        h.setPadding(0, 0, 0, dp(8));
        card.addView(h);
        return card;
    }

    private LinearLayout makeRow(int bottomMarginPx) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottomMarginPx);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView makeText(String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s == null ? "" : s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeButton(String s, int fill, int stroke) {
        TextView tv = makeText(s, 13f, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(13), dp(12), dp(13));
        styleBox(tv, fill, stroke, dp(10));
        return tv;
    }

    private TextView makeSmallPillButton(String s, int fill, int stroke) {
        TextView tv = makeText(s, 11f, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        styleBox(tv, fill, stroke, dp(999));
        return tv;
    }

    private static void styleBox(View v, int fill, int stroke, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(2, stroke);
        v.setBackground(gd);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private static int parseIntClamped(String s, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String safeDevName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? d.getAddress() : (n + " (" + d.getAddress() + ")");
        } catch (Throwable ignored) {
            return "Bluetooth Device";
        }
    }

    private static String spaceCode(String c) {
        if (c == null) return "----";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(c.charAt(i));
        }
        return sb.toString();
    }
}
