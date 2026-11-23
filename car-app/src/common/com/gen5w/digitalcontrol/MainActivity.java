package com.gen5w.digitalcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.digitalcontrol.edition.EditionConfig;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 1920x720 widescreen in-car management & pairing console for Gen5W Digital Control.
 * Uses persistent ScrollViews and in-place telemetry updates so periodic refreshes never
 * reset scroll position or interrupt taps.
 */
public final class MainActivity extends Activity implements CarLinkService.UiListener {
    private static final int BG_SCREEN = 0xFF060D1A;
    private static final int BG_CARD = 0xFF0E1729;
    private static final int BORDER_CARD = 0xFF1E3A5F;
    private static final long ADMIN_UNLOCK_DURATION_MS = 120_000L;

    private static final int MODE_NONE = 0;
    private static final int MODE_LOADING = 1;
    private static final int MODE_FIRST_PIN = 2;
    private static final int MODE_DASHBOARD = 3;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private CarLinkService linkService;
    private boolean serviceBound = false;
    private long adminUnlockedUntilMs = 0L;

    // First-run PIN setup state
    private String firstPinDraft = "";
    private String currentPinInput = "";

    private FrameLayout rootFrame;
    private int currentMode = MODE_NONE;
    private VehicleSnapshot latestVehicle = VehicleSnapshot.empty();
    private String cachedHardwareHint = "";

    // Persistent dashboard views
    private TextView tvHeaderActivity;
    private TextView tvBtBadge;
    private int lastBtBadgePositive = -1;
    private TextView tvChgBadge;
    private int lastChgBadgePositive = -1;
    private TextView btnLockToggle;
    private Boolean lastUnlockedVisual = null;

    private TextView tvHvSoc;
    private TextView tvRange;
    private TextView tvAux12v;
    private TextView tvOutside;
    private TextView tvChgStatus;
    private TextView tvChgRemain;
    private TextView tvDoorsLocks;
    private TextView tvPrecond;
    private TextView tvBattTemp;
    private TextView tvUtilMode;
    private TextView tvHvac;
    private TextView tvWheelSeats;
    private TextView tvGearTrunk;

    private TextView tvRemServer;
    private TextView tvRemStatus;
    private TextView tvRemCarId;
    private LinearLayout remCfgBtnRow;

    private LinearLayout rightCol;
    private String lastRightColSig = "";
    private TextView tvPairingModeBtn;
    private Boolean lastPairingWinVisual = null;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            pollDaemonTelemetryAsync();
            render();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder instanceof CarLinkService.LocalBinder) {
                linkService = ((CarLinkService.LocalBinder) binder).getService();
                serviceBound = true;
                linkService.addUiListener(MainActivity.this);
                render();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            linkService = null;
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG_SCREEN);
        setContentView(rootFrame, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Intent svcIntent = new Intent(this, CarLinkService.class);
        startService(svcIntent);
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE);

        fetchDaemonHardwareHintAsync();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(periodicRefresh);
        uiHandler.post(periodicRefresh);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(periodicRefresh);
        adminUnlockedUntilMs = 0L;
        if (linkService != null && linkService.getPendingPairingPrompt() == null) {
            linkService.closePairingWindow();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (linkService != null) {
            linkService.removeUiListener(this);
        }
        if (serviceBound) {
            unbindService(conn);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onStateChanged() {
        render();
    }

    private boolean isAdminUnlocked() {
        return System.currentTimeMillis() < adminUnlockedUntilMs;
    }

    private void runWithPinGate(final TrustVault vault, final Runnable action) {
        if (vault == null || action == null) return;
        if (isAdminUnlocked()) {
            adminUnlockedUntilMs = System.currentTimeMillis() + ADMIN_UNLOCK_DURATION_MS;
            action.run();
            return;
        }
        showPinUnlockDialog(vault, new Runnable() {
            @Override
            public void run() {
                adminUnlockedUntilMs = System.currentTimeMillis() + ADMIN_UNLOCK_DURATION_MS;
                render();
                action.run();
            }
        });
    }

    private void fetchDaemonHardwareHintAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject info = DaemonClient.queryDaemonInfo();
                if (info != null) {
                    cachedHardwareHint = info.optString("hardwareHint", "");
                }
            }
        }, "digctl-ui-info");
        t.setDaemon(true);
        t.start();
    }

    private void pollDaemonTelemetryAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject snapJson = DaemonClient.queryVehicleJson();
                if (snapJson != null) {
                    final VehicleSnapshot snap = VehicleSnapshot.fromJson(snapJson);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            latestVehicle = snap;
                            if (currentMode == MODE_DASHBOARD && linkService != null) {
                                updateDashboardInPlace(linkService.getVault(), false);
                            }
                        }
                    });
                }
            }
        }, "digctl-ui-veh");
        t.setDaemon(true);
        t.start();
    }

    private void render() {
        if (linkService == null) {
            if (currentMode != MODE_LOADING) {
                currentMode = MODE_LOADING;
                clearDashboardRefs();
                rootFrame.removeAllViews();
                TextView loading = makeLabel("Starting Digital Control+ service...", 20f, 0xFFCBD5E1, true);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                rootFrame.addView(loading, lp);
            }
            return;
        }

        TrustVault vault = linkService.getVault();
        if (!vault.isPinConfigured()) {
            currentMode = MODE_FIRST_PIN;
            clearDashboardRefs();
            rootFrame.removeAllViews();
            rootFrame.addView(buildFirstRunPinSetupView(vault));
        } else {
            if (currentMode != MODE_DASHBOARD) {
                currentMode = MODE_DASHBOARD;
                clearDashboardRefs();
                rootFrame.removeAllViews();
                rootFrame.addView(buildMainDashboardShell(vault));
                updateDashboardInPlace(vault, true);
            } else {
                updateDashboardInPlace(vault, false);
            }
        }
    }

    private void clearDashboardRefs() {
        lastBtBadgePositive = -1;
        lastChgBadgePositive = -1;
        lastUnlockedVisual = null;
        lastRightColSig = "";
        tvPairingModeBtn = null;
        lastPairingWinVisual = null;
    }

    // ---- First-Use PIN Creation Screen ----

    private View buildFirstRunPinSetupView(final TrustVault vault) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(44, 24, 44, 24);

        LinearLayout infoCol = makeCard("FIRST-TIME SECURITY SETUP");
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f);
        infoLp.setMargins(0, 0, 28, 0);
        outer.addView(infoCol, infoLp);

        infoCol.addView(makeLabel("Create Your Car Security PIN", 32f, 0xFFFFFFFF, true));
        TextView desc = makeLabel(
                "Before any phone can pair with this vehicle over Bluetooth, create a 4 to 8 digit owner PIN.\n\n"
                + "\u2022 Protects against anyone pairing an extra phone without your knowledge.\n"
                + "\u2022 Required when using buttons, pairing phones, or changing remote settings.\n"
                + "\u2022 Removing and re-adding this app automatically wipes all phone trust relationships and resets this PIN.",
                17f, 0xFFCBD5E1, false);
        desc.setLineSpacing(4f, 1.1f);
        desc.setPadding(0, 14, 0, 20);
        infoCol.addView(desc);

        TextView stepBadge = makeBadge(
                firstPinDraft.isEmpty() ? "STEP 1 OF 2: ENTER NEW 4–8 DIGIT PIN" : "STEP 2 OF 2: CONFIRM YOUR PIN",
                0xFF0C4A6E, 0xFF38BDF8);
        infoCol.addView(stepBadge);

        TextView closeBtn = makeActionButton("Exit Without Setting Up", 0xFF1E293B, 0xFF475569);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.setMargins(0, 22, 0, 0);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        infoCol.addView(closeBtn, closeLp);

        // Right side: On-screen tactile PIN keypad
        LinearLayout padCard = makeCard(firstPinDraft.isEmpty() ? "ENTER NEW PIN" : "RE-ENTER PIN TO CONFIRM");
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f);
        outer.addView(padCard, padLp);

        final TextView dots = makeLabel(maskDots(currentPinInput), 34f, 0xFF38BDF8, true);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(16, 18, 16, 18);
        stylePill(dots, 0xFF091224, 0xFF1E3A5F);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMargins(0, 4, 0, 12);
        padCard.addView(dots, dotsLp);

        addKeypadRows(padCard, new Runnable() {
            @Override
            public void run() {
                dots.setText(maskDots(currentPinInput));
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (!TrustVault.isValidPinFormat(currentPinInput)) {
                    Toast.makeText(MainActivity.this, "Enter 4 to 8 digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (firstPinDraft.isEmpty()) {
                    firstPinDraft = currentPinInput;
                    currentPinInput = "";
                    render();
                } else if (!firstPinDraft.equals(currentPinInput)) {
                    Toast.makeText(MainActivity.this, "PINs did not match — try again", Toast.LENGTH_SHORT).show();
                    firstPinDraft = "";
                    currentPinInput = "";
                    render();
                } else {
                    try {
                        vault.setupInitialPin(firstPinDraft, cachedHardwareHint);
                        firstPinDraft = "";
                        currentPinInput = "";
                        adminUnlockedUntilMs = System.currentTimeMillis() + ADMIN_UNLOCK_DURATION_MS;
                        linkService.notifyTrustVaultChanged();
                        Toast.makeText(MainActivity.this, "Security PIN saved! Ready to pair your phone.", Toast.LENGTH_SHORT).show();
                        render();
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "Error saving PIN: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        return outer;
    }

    // ---- Main 1920x720 Two-Column Console (Persistent ScrollViews) ----

    private View buildMainDashboardShell(final TrustVault vault) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(28, 16, 28, 16);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 14);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(makeLabel(
                "Digital Control+  \u00B7  " + EditionConfig.EDITION_LABEL,
                25f, 0xFFFFFFFF, true));
        tvHeaderActivity = makeLabel("", 14f, 0xFF94A3B8, false);
        titleCol.addView(tvHeaderActivity);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvBtBadge = makeBadge("BT APP IDLE", 0xFF1E293B, 0xFF475569);
        header.addView(tvBtBadge, marginRight(12));

        tvChgBadge = makeBadge("HV SoC: --%", 0xFF1E293B, 0xFF475569);
        header.addView(tvChgBadge, marginRight(12));

        btnLockToggle = makeActionButton("Unlock with PIN", 0xFF0369A1, 0xFF38BDF8);
        btnLockToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isAdminUnlocked()) {
                    adminUnlockedUntilMs = 0L;
                    if (linkService != null) linkService.closePairingWindow();
                    render();
                } else if (linkService != null) {
                    showPinUnlockDialog(linkService.getVault(), new Runnable() {
                        @Override
                        public void run() {
                            adminUnlockedUntilMs = System.currentTimeMillis() + ADMIN_UNLOCK_DURATION_MS;
                            render();
                        }
                    });
                }
            }
        });
        header.addView(btnLockToggle, marginRight(12));

        TextView closeBtn = makeActionButton("Close", 0xFF1E293B, 0xFF64748B);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        // Two-column body with persistent ScrollViews
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView leftScroll = new ScrollView(this);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        leftScroll.addView(leftCol);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f);
        leftLp.setMargins(0, 0, 18, 0);
        cols.addView(leftScroll, leftLp);

        ScrollView rightScroll = new ScrollView(this);
        rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rightScroll.addView(rightCol);
        cols.addView(rightScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        // === LEFT COLUMN: EV CHARGING, TELEMETRY, QUICK CONTROLS, & OPTIONAL REMOTE RELAY ===
        LinearLayout evCard = makeCard("LIVE EV CHARGING & VEHICLE TELEMETRY");
        leftCol.addView(evCard);

        LinearLayout statRow1 = makeRow();
        tvHvSoc = addStatBox(statRow1, "HV Battery", "--%");
        tvRange = addStatBox(statRow1, "Est. Range", "--");
        tvAux12v = addStatBox(statRow1, "12V Aux", "--%");
        tvOutside = addStatBox(statRow1, "Outside", "--");
        evCard.addView(statRow1);

        LinearLayout statRow2 = makeRow();
        tvChgStatus = addStatBox(statRow2, "Charge Status", "IDLE");
        tvChgRemain = addStatBox(statRow2, "Time Left", "--");
        tvDoorsLocks = addStatBox(statRow2, "Doors / Locks", "--");
        evCard.addView(statRow2);

        LinearLayout statRow3 = makeRow();
        tvPrecond = addStatBox(statRow3, "Battery Preconditioning", "OFF");
        tvBattTemp = addStatBox(statRow3, "HV Pack Temp", "--");
        tvUtilMode = addStatBox(statRow3, "Utility Mode", "--");
        evCard.addView(statRow3);

        LinearLayout statRow4 = makeRow();
        tvHvac = addStatBox(statRow4, "Cabin HVAC", "OFF");
        tvWheelSeats = addStatBox(statRow4, "Wheel / Seats", "--");
        tvGearTrunk = addStatBox(statRow4, "Gear / Hatch", "--");
        evCard.addView(statRow4);

        LinearLayout quickCard = makeCard("LOCAL IN-CAR QUICK CONTROLS (TAP TO RUN)");
        leftCol.addView(quickCard);
        LinearLayout qRow1 = makeRow();
        addQuickCmdBtn(qRow1, "Lock Doors", "door_lock", "", 0xFF10B981);
        addQuickCmdBtn(qRow1, "Unlock Doors", "door_unlock", "", 0xFFF59E0B);
        addQuickCmdBtn(qRow1, "Precondition", "precondition_toggle", "", 0xFF8B5CF6);
        quickCard.addView(qRow1);

        LinearLayout qRow2 = makeRow();
        addQuickCmdBtn(qRow2, "Open Hatch", "trunk_open", "", 0xFF0284C7);
        addQuickCmdBtn(qRow2, "Close Hatch", "trunk_close", "", 0xFF1E3A5F);
        addQuickCmdBtn(qRow2, "Charge Port", "charge_door_toggle", "", 0xFF0EA5E9);
        quickCard.addView(qRow2);

        LinearLayout qRow3 = makeRow();
        addQuickCmdBtn(qRow3, "Climate Toggle", "climate_toggle", "", 0xFF38BDF8);
        addQuickCmdBtn(qRow3, "Wheel Heat", "wheel_heat_toggle", "", 0xFFEF4444);
        addQuickCmdBtn(qRow3, "Drv Seat Heat", "seat_heat_driver_cycle", "", 0xFFF97316);
        quickCard.addView(qRow3);

        LinearLayout qRow4 = makeRow();
        addQuickCmdBtn(qRow4, "Windows All Up", "window_all_close", "", 0xFF10B981);
        addQuickCmdBtn(qRow4, "Windows All Down", "window_all_open", "", 0xFFF59E0B);
        addQuickCmdBtn(qRow4, "Vent 25%", "window_all_vent", "", 0xFF0EA5E9);
        quickCard.addView(qRow4);

        LinearLayout qRow5 = makeRow();
        addQuickCmdBtn(qRow5, "Utility Mode", "utility_mode_on", "", 0xFF8B5CF6);
        addQuickCmdBtn(qRow5, "Bluelink A/C ON", "tmu_fatc_on", "", 0xFF0EA5E9);
        addQuickCmdBtn(qRow5, "Bluelink A/C OFF", "tmu_fatc_off", "", 0xFF475569);
        quickCard.addView(qRow5);

        if (EditionConfig.REMOTE_ENABLED) {
            LinearLayout remCard = makeCard("OPTIONAL SELF-HOSTED E2EE REMOTE SERVER");
            leftCol.addView(remCard);
            tvRemServer = makeLabel("", 16f, 0xFF7DD3FC, true);
            tvRemStatus = makeLabel("", 14f, 0xFFCBD5E1, false);
            tvRemCarId = makeLabel("", 13f, 0xFF94A3B8, false);
            remCard.addView(tvRemServer);
            remCard.addView(tvRemStatus);
            remCard.addView(tvRemCarId);

            remCfgBtnRow = makeRow();
            remCfgBtnRow.setPadding(0, 12, 0, 0);
            TextView cfgBtn = makeActionButton("Configure Server / Account Token", 0xFF1E3A5F, 0xFF38BDF8);
            cfgBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (linkService != null) {
                        final TrustVault curVault = linkService.getVault();
                        runWithPinGate(curVault, new Runnable() {
                            @Override
                            public void run() {
                                showRemoteConfigDialog(curVault);
                            }
                        });
                    }
                }
            });
            remCfgBtnRow.addView(cfgBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            remCard.addView(remCfgBtnRow);
        }

        return shell;
    }

    private void updateDashboardInPlace(final TrustVault vault, boolean forceRebuildRight) {
        if (linkService == null) return;

        setTextIfChanged(tvHeaderActivity, "Activity: " + linkService.getLastActivityNote());

        int btCount = linkService.getAuthenticatedBtSessionCount();
        setTextIfChanged(tvBtBadge, btCount > 0 ? ("BT APP LINKED (" + btCount + ")") : "BT APP IDLE");
        int wantBtPos = btCount > 0 ? 1 : 0;
        if (lastBtBadgePositive != wantBtPos) {
            lastBtBadgePositive = wantBtPos;
            stylePill(tvBtBadge, btCount > 0 ? 0xFF064E3B : 0xFF1E293B, btCount > 0 ? 0xFF10B981 : 0xFF475569);
        }

        boolean chg = latestVehicle.isCharging();
        setTextIfChanged(tvChgBadge, chg
                ? ("CHARGING \u00B7 " + latestVehicle.raw("hv.soc") + "%")
                : ("HV SoC: " + latestVehicle.raw("hv.soc") + "%"));
        int wantChgPos = chg ? 1 : 0;
        if (lastChgBadgePositive != wantChgPos) {
            lastChgBadgePositive = wantChgPos;
            stylePill(tvChgBadge, chg ? 0xFF0C4A6E : 0xFF1E293B, chg ? 0xFF38BDF8 : 0xFF475569);
        }

        boolean unlocked = isAdminUnlocked();
        setTextIfChanged(btnLockToggle, unlocked ? "Lock PIN Gate" : "Unlock with PIN");
        if (lastUnlockedVisual == null || lastUnlockedVisual.booleanValue() != unlocked) {
            lastUnlockedVisual = Boolean.valueOf(unlocked);
            stylePill(btnLockToggle, unlocked ? 0xFF78350F : 0xFF0369A1, unlocked ? 0xFFF59E0B : 0xFF38BDF8);
        }

        // EV Telemetry fields
        setTextIfChanged(tvHvSoc, latestVehicle.raw("hv.soc") + "%");
        setTextIfChanged(tvRange, latestVehicle.formatRange(true));
        setTextIfChanged(tvAux12v, latestVehicle.raw("battery12v.soc") + "%");
        setTextIfChanged(tvOutside, latestVehicle.formatAmbientTemp(true));

        String chgPower = latestVehicle.raw("charging.power_kw");
        setTextIfChanged(tvChgStatus, chg
                ? ("ACTIVE" + ("--".equals(chgPower) ? "" : (" (" + chgPower + " kW)")))
                : "IDLE");
        setTextIfChanged(tvChgRemain, latestVehicle.formatChargeRemain());
        setTextIfChanged(tvDoorsLocks,
                ("1".equals(latestVehicle.raw("door.lock_state")) ? "LOCKED" : "UNLOCKED")
                        + " \u00B7 " + latestVehicle.buildDoorsSummary());

        String unit = latestVehicle.isCelsius() ? "\u00B0C" : "\u00B0F";
        setTextIfChanged(tvPrecond, latestVehicle.formatPreconditioningStatus());
        setTextIfChanged(tvBattTemp, latestVehicle.formatBatteryTemp(!latestVehicle.isCelsius()));
        setTextIfChanged(tvUtilMode, latestVehicle.formatUtilityModeStatus());
        setTextIfChanged(tvHvac, latestVehicle.flag("climate.on")
                ? (latestVehicle.raw("climate.driver_temp") + unit + " \u00B7 Fan " + latestVehicle.raw("climate.fan_speed"))
                : "OFF");
        setTextIfChanged(tvWheelSeats,
                (latestVehicle.flag("wheel_heat") ? "Wheel ON" : "Wheel OFF")
                        + " \u00B7 DrvHeat " + latestVehicle.raw("seat_heat.driver"));
        setTextIfChanged(tvGearTrunk,
                (latestVehicle.flag("gear.park") ? "PARK" : "GEAR")
                        + " \u00B7 " + (latestVehicle.flag("trunk.open") ? "HATCH OPEN" : "HATCH SHUT"));

        if (EditionConfig.REMOTE_ENABLED && tvRemServer != null) {
            String cfgUrl = vault.getRemoteServerUrl();
            setTextIfChanged(tvRemServer, cfgUrl.isEmpty()
                    ? "Server: Not configured (Bluetooth-only mode)"
                    : ("Server: " + cfgUrl));
            setTextIfChanged(tvRemStatus, "Status: " + linkService.getRemoteStatusSummary());
            setTextIfChanged(tvRemCarId, "Car E2EE Relay ID: " + vault.getCarId());
        }

        updateRightColumn(vault, unlocked, forceRebuildRight);
    }

    private void updateRightColumn(final TrustVault vault, final boolean unlocked, boolean forceRebuild) {
        if (rightCol == null || linkService == null) return;

        final CarLinkService.PendingPairingPrompt prompt = linkService.getPendingPairingPrompt();
        final boolean winOpen = unlocked && linkService.isPairingWindowOpen();
        final List<CarLinkService.BtPhoneCandidate> btDevs = linkService.listBluetoothPhones();
        final List<TrustVault.TrustedPhone> trustedPhones = vault.listTrustedPhones();

        StringBuilder sigSb = new StringBuilder();
        sigSb.append("u=").append(unlocked ? '1' : '0').append("|w=").append(winOpen ? '1' : '0').append('|');
        if (prompt != null) {
            sigSb.append("p=").append(prompt.sessionId).append(':').append(prompt.sas4Code)
                    .append(':').append(prompt.phoneConfirmed ? '1' : '0')
                    .append(':').append(prompt.carApproved ? '1' : '0').append('|');
        }
        if (btDevs != null) {
            for (CarLinkService.BtPhoneCandidate c : btDevs) {
                sigSb.append("b=").append(c.address).append(':').append(c.name)
                        .append(':').append(c.profileConnected ? '1' : '0')
                        .append(':').append(c.alreadyTrusted ? '1' : '0').append('|');
            }
        }
        for (TrustVault.TrustedPhone tp : trustedPhones) {
            sigSb.append("t=").append(tp.phoneId).append(':').append(tp.phoneName)
                    .append(':').append(tp.lastSeenAtMs / 60000L).append('|');
        }
        String sig = sigSb.toString();

        if (!forceRebuild && sig.equals(lastRightColSig)) {
            if (tvPairingModeBtn != null) {
                updatePairingWindowButtonInPlace(winOpen);
            }
            return;
        }

        lastRightColSig = sig;
        rightCol.removeAllViews();
        tvPairingModeBtn = null;
        lastPairingWinVisual = null;

        if (prompt != null) {
            LinearLayout pairPromptCard = makeCard("CONFIRM 4-CHARACTER PAIRING CODE");
            stylePill(pairPromptCard, 0xFF172554, 0xFF38BDF8);
            rightCol.addView(pairPromptCard);

            pairPromptCard.addView(makeLabel(
                    "Phone requesting pairing: " + prompt.phoneName + " (" + prompt.platform.toUpperCase(Locale.US) + ")",
                    18f, 0xFFFFFFFF, true));
            pairPromptCard.addView(makeLabel(
                    "Verify this exact 4-character code matches your phone screen before approving:",
                    15f, 0xFFBAE6FD, false));

            TextView codeBox = makeLabel(spaceOutCode(prompt.sas4Code), 42f, 0xFF34D399, true);
            codeBox.setGravity(Gravity.CENTER);
            codeBox.setPadding(20, 16, 20, 16);
            stylePill(codeBox, 0xFF052E16, 0xFF10B981);
            LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            codeLp.setMargins(0, 12, 0, 10);
            pairPromptCard.addView(codeBox, codeLp);

            pairPromptCard.addView(makeLabel(
                    prompt.phoneConfirmed
                            ? "\u2713 Phone confirmed matching code — tap Approve below to finish!"
                            : "Waiting for you to tap Confirm on the phone app too...",
                    16f, prompt.phoneConfirmed ? 0xFF6EE7B7 : 0xFFFDE68A, true));

            LinearLayout decisionRow = makeRow();
            decisionRow.setPadding(0, 12, 0, 0);
            TextView approveBtn = makeActionButton(
                    prompt.carApproved ? "Approved on Car \u2713 (Waiting for Phone...)" : "Approve & Pair Phone",
                    0xFF065F46, 0xFF10B981);
            approveBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    linkService.approvePendingPairingOnCar(prompt.sessionId);
                }
            });
            decisionRow.addView(approveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(this);
            decisionRow.addView(spacer, new LinearLayout.LayoutParams(12, 1));

            TextView rejectBtn = makeActionButton("Reject", 0xFF7F1D1D, 0xFFEF4444);
            rejectBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    linkService.rejectPendingPairingOnCar(prompt.sessionId);
                }
            });
            decisionRow.addView(rejectBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));
            pairPromptCard.addView(decisionRow);
        }

        // Pairing & Connected Car Bluetooth Phones Card (Always visible; prompts PIN if locked)
        LinearLayout btCard = makeCard("BLUETOOTH PHONE PAIRING & DETECTED CAR BT DEVICES");
        rightCol.addView(btCard);

        LinearLayout winRow = makeRow();
        tvPairingModeBtn = makeActionButton("", 0xFF0284C7, 0xFF38BDF8);
        updatePairingWindowButtonInPlace(winOpen);
        tvPairingModeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runWithPinGate(vault, new Runnable() {
                    @Override
                    public void run() {
                        linkService.openPairingWindowAndProbeConnectedPhones();
                        render();
                        Toast.makeText(MainActivity.this,
                                "Scanning & connecting to companion app on your phone...", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        winRow.addView(tvPairingModeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btCard.addView(winRow);

        TextView btNote = makeLabel(
                "Bluetooth phones paired to Gen5W head unit (open the companion app on your phone):",
                14f, 0xFF94A3B8, false);
        btNote.setPadding(0, 4, 0, 6);
        btCard.addView(btNote);

        if (btDevs == null || btDevs.isEmpty()) {
            btCard.addView(makeLabel(
                    "No Classic BT phone currently bonded — iOS phones also pair automatically over Bluetooth LE when Pairing Mode is active!",
                    14f, 0xFF94A3B8, false));
        } else {
            for (final CarLinkService.BtPhoneCandidate cand : btDevs) {
                LinearLayout row = makeRow();
                row.setPadding(14, 10, 14, 10);
                stylePill(row, 0xFF091224, cand.profileConnected ? 0xFF0EA5E9 : 0xFF1E293B);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 6, 0, 4);

                String statusTag = (cand.profileConnected ? "CONNECTED TO CAR BT" : "Bonded")
                        + (cand.alreadyTrusted ? " \u00B7 Trusted" : "");
                TextView lbl = makeLabel(cand.name + "  [" + statusTag + "]", 16f, 0xFFF1F5F9, cand.profileConnected);
                row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView connBtn = makeActionButton("Connect App", 0xFF0369A1, 0xFF38BDF8);
                connBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        runWithPinGate(vault, new Runnable() {
                            @Override
                            public void run() {
                                linkService.openPairingWindowAndProbeConnectedPhones();
                                linkService.connectToAddressNow(cand.address);
                                render();
                            }
                        });
                    }
                });
                row.addView(connBtn);
                btCard.addView(row, rlp);
            }
        }

        // Trusted Paired Phones List (Buttons remain visible when locked and prompt PIN on tap)
        LinearLayout trustedCard = makeCard("TRUSTED COMPANION PHONES");
        rightCol.addView(trustedCard);

        if (trustedPhones.isEmpty()) {
            trustedCard.addView(makeLabel(
                    "No phones paired yet. Tap Start Pairing Mode or Connect App above to pair your Android or iPhone app.",
                    15f, 0xFF94A3B8, false));
        } else {
            SimpleDateFormat df = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            for (final TrustVault.TrustedPhone tp : trustedPhones) {
                LinearLayout row = makeRow();
                row.setPadding(14, 10, 14, 10);
                stylePill(row, 0xFF091224, 0xFF1E3A5F);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, 8);

                String meta = tp.phoneName + " (" + tp.platform.toUpperCase(Locale.US) + ")  \u00B7  Last seen "
                        + (tp.lastSeenAtMs > 0 ? df.format(new Date(tp.lastSeenAtMs)) : "never");
                row.addView(makeLabel(meta, 16f, 0xFFE2E8F0, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView revBtn = makeActionButton("Revoke", 0xFF7F1D1D, 0xFFEF4444);
                revBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        runWithPinGate(vault, new Runnable() {
                            @Override
                            public void run() {
                                linkService.revokeTrustedPhone(tp.phoneId);
                            }
                        });
                    }
                });
                row.addView(revBtn);
                trustedCard.addView(row, rlp);
            }
        }

        TextView resetAllBtn = makeActionButton(
                "Reset PIN & Remove All Trusted Phones", 0xFF450A0A, 0xFFEF4444);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 12, 0, 0);
        resetAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runWithPinGate(vault, new Runnable() {
                    @Override
                    public void run() {
                        confirmResetAllDialog();
                    }
                });
            }
        });
        trustedCard.addView(resetAllBtn, rlp);
    }

    private void updatePairingWindowButtonInPlace(boolean winOpen) {
        if (tvPairingModeBtn == null || linkService == null) return;
        setTextIfChanged(tvPairingModeBtn, winOpen
                ? ("Pairing Mode Active (" + linkService.getPairingWindowRemainingSec() + "s) — Rescan Now")
                : "Start Pairing Mode (120s) & Connect to Phone");
        if (lastPairingWinVisual == null || lastPairingWinVisual.booleanValue() != winOpen) {
            lastPairingWinVisual = Boolean.valueOf(winOpen);
            stylePill(tvPairingModeBtn, winOpen ? 0xFF065F46 : 0xFF0284C7, winOpen ? 0xFF10B981 : 0xFF38BDF8);
        }
    }

    // ---- Dialogs & Helpers ----

    private static void setTextIfChanged(TextView tv, String value) {
        if (tv == null) return;
        String safe = value == null ? "" : value;
        if (!safe.contentEquals(tv.getText())) {
            tv.setText(safe);
        }
    }

    private void showPinUnlockDialog(final TrustVault vault, final Runnable onUnlocked) {
        long lockoutMs = vault.getRemainingLockoutMs();
        if (lockoutMs > 0L) {
            Toast.makeText(this, "Too many failed attempts. Wait " + ((lockoutMs + 999L) / 1000L) + "s.", Toast.LENGTH_SHORT).show();
            return;
        }
        currentPinInput = "";
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 24);
        root.setBackgroundColor(BG_CARD);

        root.addView(makeLabel("Enter Owner Security PIN", 24f, 0xFFFFFFFF, true));
        final TextView dots = makeLabel(maskDots(""), 34f, 0xFF38BDF8, true);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(16, 16, 16, 16);
        stylePill(dots, 0xFF091224, 0xFF1E3A5F);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, 14, 0, 14);
        root.addView(dots, dlp);

        final AlertDialog[] holder = new AlertDialog[1];
        addKeypadRows(root, new Runnable() {
            @Override
            public void run() {
                dots.setText(maskDots(currentPinInput));
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (vault.verifyPin(currentPinInput)) {
                    currentPinInput = "";
                    if (holder[0] != null) holder[0].dismiss();
                    onUnlocked.run();
                } else {
                    currentPinInput = "";
                    dots.setText(maskDots(""));
                    long lock = vault.getRemainingLockoutMs();
                    Toast.makeText(MainActivity.this,
                            lock > 0L ? "Locked out for 15s" : "Incorrect PIN",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        b.setView(root);
        b.setNegativeButton("Cancel", null);
        holder[0] = b.create();
        holder[0].show();
    }

    private void showRemoteConfigDialog(final TrustVault vault) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(28, 20, 28, 12);

        box.addView(makeLabel("Self-Hosted Server Domain or URL (https:// is added automatically; leave blank for Bluetooth-only):", 16f, 0xFFCBD5E1, true));
        final EditText urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        urlEdit.setHint("your-server.example.com");
        urlEdit.setText(vault.getRemoteServerUrl());
        box.addView(urlEdit);

        TextView tokHdr = makeLabel("Optional Server / Account Bearer Token:", 16f, 0xFFCBD5E1, true);
        tokHdr.setPadding(0, 12, 0, 0);
        box.addView(tokHdr);
        final EditText tokEdit = new EditText(this);
        tokEdit.setSingleLine(true);
        tokEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        tokEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokEdit.setText(vault.getRemoteAccountToken());
        box.addView(tokEdit);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Self-Hosted Remote Server")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String normalized = RemoteEnvelope.normalizeServerUrl(urlEdit.getText().toString());
                        vault.setRemoteServerUrl(normalized);
                        vault.setRemoteAccountToken(normalized.isEmpty() ? "" : tokEdit.getText().toString().trim());
                        if (linkService != null) linkService.notifyTrustVaultChanged();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (vault.isRemoteServerConfigured()) {
            b.setNeutralButton("Disable Server", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    vault.setRemoteServerUrl("");
                    vault.setRemoteAccountToken("");
                    if (linkService != null) linkService.notifyTrustVaultChanged();
                    render();
                }
            });
        }
        b.show();
    }

    private void confirmResetAllDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Reset PIN & Revoke All Phones?")
                .setMessage("This immediately deletes your PIN, rotates the car's E2EE identity keypair, and removes all paired phones.")
                .setPositiveButton("Reset Everything", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        adminUnlockedUntilMs = 0L;
                        if (linkService != null) {
                            linkService.resetVaultAndDisconnectAll();
                        }
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addKeypadRows(LinearLayout parent, final Runnable onDigitChanged, final Runnable onSubmit) {
        String[][] rows = new String[][] {
                { "1", "2", "3" },
                { "4", "5", "6" },
                { "7", "8", "9" },
                { "CLR", "0", "OK" }
        };
        for (String[] r : rows) {
            LinearLayout row = makeRow();
            for (int i = 0; i < r.length; i++) {
                final String key = r[i];
                int fill = "OK".equals(key) ? 0xFF065F46 : ("CLR".equals(key) ? 0xFF334155 : 0xFF162238);
                int stroke = "OK".equals(key) ? 0xFF10B981 : ("CLR".equals(key) ? 0xFF64748B : 0xFF28436B);
                TextView btn = makeActionButton(key, fill, stroke);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f);
                btn.setPadding(20, 20, 20, 20);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if ("CLR".equals(key)) {
                            currentPinInput = "";
                            onDigitChanged.run();
                        } else if ("OK".equals(key)) {
                            onSubmit.run();
                        } else if (currentPinInput.length() < 8) {
                            currentPinInput += key;
                            onDigitChanged.run();
                        }
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i < r.length - 1) lp.setMargins(0, 0, 12, 0);
                row.addView(btn, lp);
            }
            parent.addView(row);
        }
    }

    private void addQuickCmdBtn(LinearLayout row, String label, final String cmd, final String arg, int border) {
        TextView btn = makeActionButton(label, 0xFF162238, border);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Runnable sendCmd = new Runnable() {
                    @Override
                    public void run() {
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final DaemonClient.CmdResult res = DaemonClient.sendVehicleCommand(cmd, arg, "car_ui");
                                uiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_SHORT).show();
                                        pollDaemonTelemetryAsync();
                                    }
                                });
                            }
                        }, "digctl-quick-cmd");
                        t.setDaemon(true);
                        t.start();
                    }
                };
                if (linkService != null) {
                    runWithPinGate(linkService.getVault(), sendCmd);
                } else {
                    sendCmd.run();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, 10, 0);
        row.addView(btn, lp);
    }

    private TextView addStatBox(LinearLayout row, String caption, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(14, 10, 14, 10);
        stylePill(box, 0xFF091224, 0xFF1E293B);
        box.addView(makeLabel(caption, 13f, 0xFF94A3B8, false));
        TextView v = makeLabel(value, 18f, 0xFFFFFFFF, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, 10, 0);
        row.addView(box, lp);
        return v;
    }

    private LinearLayout makeCard(String sectionTitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 16, 18, 16);
        stylePill(card, BG_CARD, BORDER_CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 14);
        card.setLayoutParams(lp);
        TextView hdr = makeLabel(sectionTitle, 14f, 0xFF7DD3FC, true);
        hdr.setPadding(0, 0, 0, 10);
        card.addView(hdr);
        return card;
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView makeLabel(String text, float pxSize, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxSize);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeBadge(String text, int fill, int stroke) {
        TextView tv = makeLabel(text, 15f, 0xFFFFFFFF, true);
        tv.setPadding(16, 12, 16, 12);
        tv.setGravity(Gravity.CENTER);
        stylePill(tv, fill, stroke);
        return tv;
    }

    private TextView makeActionButton(String text, int fill, int stroke) {
        TextView tv = makeLabel(text, 17f, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(18, 16, 18, 16);
        stylePill(tv, fill, stroke);
        return tv;
    }

    private static void stylePill(View v, int fill, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(12f);
        gd.setStroke(2, stroke);
        v.setBackground(gd);
    }

    private static LinearLayout.LayoutParams marginRight(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, px, 0);
        return lp;
    }

    private static String maskDots(String pin) {
        if (pin == null || pin.isEmpty()) return "\u2014 \u2014 \u2014 \u2014";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) {
            if (i > 0) sb.append("  ");
            sb.append('\u25CF');
        }
        return sb.toString();
    }

    private static String spaceOutCode(String code) {
        if (code == null) return "----";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) sb.append("   ");
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }
}
