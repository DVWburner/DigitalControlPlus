package com.gen5w.digitalcontrol.svc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.gen5w.digitalcontrol.shared.ClimateTheme;

import hmgapi.client.HmgApi;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direct Gen5W Binder vehicle control & telemetry bridge running inside ControlDaemon (root).
 * Resolves AIDL transaction codes dynamically via Stub.TRANSACTION_* fields for cross-firmware safety.
 */
final class DirectCarServiceBridge {
    private static final String SVC_HSCAN = "tm_hscan_service";
    private static final String IFACE_HSCAN = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    private static final String JAR_HSCAN = "/system/framework/com.hkmc.ext.hcan.jar";

    private static final String SVC_SYSTEM = "com.hkmc.micom.system";
    private static final String IFACE_SYSTEM = "com.hkmc.micom.ISystemService";

    private static final String SVC_DATC = "com.hkmc.micom.datc";
    private static final String IFACE_DATC = "com.hkmc.datc.IDATCService";
    private static final String IFACE_DATC_LISTENER = "com.hkmc.datc.IDATCListener";
    private static final String CLS_DATC_STATUS = "com.hkmc.datc.DatcStatus";

    private static final String SVC_HEV = "com.hkmc.micom.hev";
    private static final String IFACE_HEV = "com.hkmc.hev.IHEVService";

    private static final String SVC_BCM = "com.hkmc.micom.bcm";
    private static final String IFACE_BCM = "com.hkmc.bcm.IBCMService";
    private static final String IFACE_BCM_LISTENER = "com.hkmc.bcm.IBCMListener";

    private static final String SVC_USM = "com.hkmc.micom.usm";
    private static final String IFACE_USM = "com.hkmc.usm.IUSMService";

    private static final String SVC_VR = "com.hkmc.vr";
    private static final String IFACE_VR = "com.hkmc.vr.IVRService";

    private static final String SVC_TMU = "com.hkmc.micom.tmu";
    private static final String IFACE_TMU = "com.hkmc.tmu.ITMUService";

    private static final String SVC_AUDIO = "com.hkmc.micom.audiosetup";
    private static final String IFACE_AUDIO = "com.hkmc.media.IMicomAudioService";

    private static final String SVC_CLUSTER = "com.hkmc.micom.cluster";
    private static final String IFACE_CLUSTER = "com.hkmc.cluster.IClusterService";
    private static final String IFACE_CLUSTER_LISTENER = "com.hkmc.cluster.INavigationClusterListener";
    private static final int FB_CLUSTER_ON_BATT_INFO = 16;

    private static final int MISSING_TX = -2;
    private static final int MAX_AUDIO_LEVEL = 45;

    private static final int FB_BCM_ON_DOOR_ALL = 2;
    private static final int FB_BCM_ON_DOOR_LOCK = 3;
    private static final int FB_BCM_ON_SEAT_HEAT = 8;
    private static final int FB_BCM_ON_SEAT_VENT = 9;
    private static final int FB_BCM_ON_PARK_BRAKE = 14;
    private static final int FB_BCM_ON_GEAR_INFO = 19;
    private static final int FB_BCM_ON_REMOTE_HEAT = 20;
    private static final int FB_BCM_ON_WINDOW_OPEN = 30;
    private static final int FB_BCM_ON_DIGITAL_KEY = 37;
    private static final int FB_BCM_ON_MODEM_INFO = 39;
    private static final int FB_BCM_ON_BATT_COND = 45;
    private static final int FB_BCM_ON_SUNROOF_OPEN = 47;
    private static final int FB_BCM_ON_CDM_CHANGED = 48;
    private static final int MAX_OUT_ARRAY_BYTES = 64 * 1024;

    static final class CommandReply {
        final boolean ok;
        final String message;

        CommandReply(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    private static final class TxEntry {
        final int code;
        final Class<?> returnType;

        TxEntry(int code, Class<?> returnType) {
            this.code = code;
            this.returnType = returnType;
        }
    }

    private final Map<String, IBinder> binderCache = new HashMap<String, IBinder>();
    private final Map<String, TxEntry> txCache = new HashMap<String, TxEntry>();
    private Method mGetService;
    private ClassLoader hscanLoader;

    private volatile int lastKnownFanStep = 3;
    private volatile int lastAudioStream = 3;
    private volatile int lastKnownHeatState = -1;
    private volatile float lastHeaterKw = 0f;
    private volatile Boolean lastKnownCelsius = null;
    private volatile Integer cachedHevVcuRdyFlag = null;
    private volatile Integer cachedHevEvRdyFlag = null;
    private volatile Integer cachedHevBattCondFlag = null;
    private volatile Integer cachedHevUtilModeFlag = null;
    private volatile Integer cachedHevUtilWarnFlag = null;

    private IBinder registeredBcmService;
    private IBinder bcmListenerToken;
    private IBinder bcmListenerBinder;
    private IBinder registeredDatcService;
    private IBinder datcListenerToken;
    private IBinder datcListenerBinder;
    private IBinder registeredClusterService;
    private IBinder clusterListenerBinder;
    private volatile int txClusterBattInfo = -1;
    private volatile int clusterBattTempMinC = Integer.MIN_VALUE;
    private volatile int clusterBattCondInd = -1;
    private volatile long lastClusterBattReqMs = 0L;
    private volatile int txDatcStatus = -1;
    private volatile Object datcStatusCreator;
    private volatile Method mAmbientF;
    private volatile Method mAmbientC;
    private volatile Method mAmbientStateC;
    private volatile int datcOutsideF10 = Integer.MIN_VALUE;
    private volatile int datcOutsideC10 = Integer.MIN_VALUE;

    private volatile int txBcmDoorAll = -1;
    private volatile int txBcmDoorLock = -1;
    private volatile int txBcmSeatHeat = -1;
    private volatile int txBcmSeatVent = -1;
    private volatile int txBcmParkBrake = -1;
    private volatile int txBcmGearInfo = -1;
    private volatile int txBcmRemoteHeat = -1;
    private volatile int txBcmWindowOpen = -1;
    private volatile int txBcmDigitalKey = -1;
    private volatile int txBcmModemInfo = -1;
    private volatile int txBcmBattCond = -1;
    private volatile int txBcmSunroof = -1;
    private volatile int txBcmCdm = -1;
    private volatile int bcmCdmState = -1;

    private volatile int bcmUnlockFl = -1;
    private volatile int bcmUnlockFr = -1;
    private volatile int bcmUnlockRl = -1;
    private volatile int bcmUnlockRr = -1;
    private volatile int bcmOpenFl = -1;
    private volatile int bcmOpenFr = -1;
    private volatile int bcmOpenRl = -1;
    private volatile int bcmOpenRr = -1;
    private volatile int bcmTrunkOpen = -1;
    private volatile int bcmHoodOpen = -1;
    private volatile int bcmLockState = -1;
    private volatile int bcmWheelHeat = -1;
    private volatile int bcmDefogger = -1;
    private volatile int bcmSeatHeatDrv = -1;
    private volatile int bcmSeatHeatAst = -1;
    private volatile int bcmSeatHeatRl = -1;
    private volatile int bcmSeatHeatRr = -1;
    private volatile int bcmSeatVentDrv = -1;
    private volatile int bcmSeatVentAst = -1;
    private volatile int bcmParkBrake = -1;
    private volatile int bcmBattCond = -1;
    private volatile int bcmSunroofOpen = -1;
    private volatile int bcmWdwFl = -1;
    private volatile int bcmWdwFr = -1;
    private volatile int bcmWdwRl = -1;
    private volatile int bcmWdwRr = -1;
    private volatile boolean vrSignalsEnabled = false;
    private volatile boolean datcVrEnabled = false;

    // Direct IHEVService Battery Preconditioning state (bypasses HMG-API EV_RDY=1 check in Utility Mode)
    private static final int DIRECT_COND_ROUTE_KM = 9;
    private static final int DIRECT_COND_ROUTE_MIN = 18;
    private static final long DIRECT_COND_KEEP_MS = 4500L;
    private static final long DIRECT_COND_TIMEOUT_MS = 150_000L;
    private volatile boolean directCondActive = false;
    private volatile boolean directCondSawIndicator = false;
    private volatile long directCondStartMs = 0L;
    private volatile long lastDirectCondKeepMs = 0L;
    private volatile String directCondTerminalState = null;

    // OEM Bluelink HSCAN Remote Climate state (used when IGN1 is off / hv.ready != 1)
    private volatile boolean remoteClimateActive = false;
    private volatile String lastRemoteTargetTemp = null;
    private volatile Boolean lastRemoteTargetCelsius = null;
    private volatile boolean lastRemoteFrontDefrost = false;

    private final AtomicInteger activeCommandDepth = new AtomicInteger(0);

    void pollAll(Map<String, String> out) {
        ensureBcmListenerRegistered();
        ensureDatcListenerRegistered();
        ensureClusterListenerAndRequestBattInfo();

        // Fast vehicle state
        Float spd = callFloatArgByte(SVC_HSCAN, IFACE_HSCAN, "getCarSpeed", (byte) 0, true);
        if (spd != null && !Float.isNaN(spd) && spd >= 0f && spd < 400f) {
            out.put("speed", formatCompactFloat(spd));
            out.put("moving", spd > 0.5f ? "1" : "0");
        }
        Integer gearP = callIntNoArg(SVC_SYSTEM, IFACE_SYSTEM, "getGearPStatus", false);
        if (gearP != null && (gearP == 0 || gearP == 1)) {
            out.put("gear.park", gearP == 1 ? "1" : "0");
        }
        Integer evReady = pollHevEvReady();
        if (evReady != null && evReady >= 0 && evReady != 0xFFFF) {
            out.put("hv.ready", String.valueOf(evReady));
        }
        Integer utilMode = pollHevUtilityMode();
        if (utilMode != null && utilMode >= 0 && utilMode != 0xFFFF) {
            out.put("hv.utility_mode", String.valueOf(utilMode));
        }
        Integer utilWarn = pollHevUtilityWarn();
        if (utilWarn != null && utilWarn >= 0 && utilWarn != 0xFFFF) {
            out.put("hv.utility_warn", String.valueOf(utilWarn));
        }
        Integer evCondOpt = pollHevBattConditioningSetting();
        if (evCondOpt != null && (evCondOpt == 1 || evCondOpt == 2)) {
            out.put("hv.conditioning", String.valueOf(evCondOpt));
        }
        int battMinC = clusterBattTempMinC;
        if (battMinC >= -40 && battMinC <= 60) {
            out.put("hv.battery_temp_min", String.valueOf(battMinC));
            out.put("hv.battery_temp_min_f", String.valueOf(Math.round(battMinC * 1.8f + 32.0f)));
        }

        // EV Charging & Battery Telemetry
        Integer rangeKm = callIntArgByte(SVC_HSCAN, IFACE_HSCAN, "ableDriveDistance", (byte) 0, true);
        if (rangeKm != null && rangeKm >= 0 && rangeKm < 2500) {
            out.put("hv.range", String.valueOf(rangeKm));
        }
        Float soc = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "remainBatteryPercentage", true);
        if (soc != null && !Float.isNaN(soc) && soc >= 0f && soc <= 100f) {
            out.put("hv.soc", formatCompactFloat(soc));
        }
        Float odo = callFloatArgByte(SVC_HSCAN, IFACE_HSCAN, "getOdometer", (byte) 0, true);
        if (odo != null && !Float.isNaN(odo) && odo > 0f) {
            out.put("odometer", formatCompactFloat(odo));
        }
        Integer aux = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getBatSoc", true);
        if (aux != null && aux > 0 && aux <= 100) {
            out.put("battery12v.soc", String.valueOf(aux));
        }
        Integer chg = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getChargingStatus", true);
        if (chg != null && chg >= 0) {
            out.put("charging", String.valueOf(chg));
        }
        Integer chgMin = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRemainChargingTime", true);
        if (chgMin != null && chgMin >= 0 && chgMin < 10000) {
            out.put("charging.remain_min", String.valueOf(chgMin));
        }
        putByteFlag(out, "tyre.fl_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getFrontLeftTirePressureStatus", true));
        putByteFlag(out, "tyre.fr_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getFrontRightTirePressureStatus", true));
        putByteFlag(out, "tyre.rl_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRearLeftTirePressureStatus", true));
        putByteFlag(out, "tyre.rr_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRearRightTirePressureStatus", true));
        Float obcKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "getOBC_AcPwrDis", true);
        Float evseKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "getCCM_EvseMainsPwr", true);
        Float chgPower = (obcKw != null && !Float.isNaN(obcKw) && obcKw > 0.1f && obcKw < 400f) ? obcKw
                : ((evseKw != null && !Float.isNaN(evseKw) && evseKw > 0.1f && evseKw < 400f) ? evseKw : null);
        if (chgPower != null) {
            out.put("charging.power_kw", String.format(Locale.US, "%.1f", chgPower));
        }

        Float acKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "airConEleConsum", true);
        if (acKw != null && !Float.isNaN(acKw) && acKw >= 0f && acKw <= 50000f) {
            out.put("energy.climate_kw", acKw > 100f ? String.format(Locale.US, "%.2f", acKw / 1000f) : formatCompactFloat(acKw));
        }
        Float heatKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "heaterEleConsum", true);
        if (heatKw != null && !Float.isNaN(heatKw) && heatKw >= 0f && heatKw <= 50000f) {
            float normHeatKw = heatKw > 100f ? (heatKw / 1000f) : heatKw;
            lastHeaterKw = normHeatKw;
            out.put("energy.care_kw", heatKw > 100f ? String.format(Locale.US, "%.2f", normHeatKw) : formatCompactFloat(heatKw));
        }

        // Audio
        Integer curStream = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getCurStreamType", false);
        int[] tryStreams = (curStream != null && curStream >= 0) ? new int[] { curStream, 3, 14 } : new int[] { 3, 14 };
        for (int s : tryStreams) {
            Integer v = callVolumeGet(s);
            if (v != null && v >= 0 && v <= MAX_AUDIO_LEVEL) {
                lastAudioStream = s;
                out.put("audio.volume", String.valueOf(v));
                break;
            }
        }
        Integer muted = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getUserMuteState", false);
        if (muted != null) {
            out.put("audio.muted", muted != 0 ? "1" : "0");
        }

        pollDatcClimate(out);
        pollBodyAndSeats(out);
        maintainDirectBatteryConditioning(out);
    }

    private void pollDatcClimate(Map<String, String> out) {
        Float fUnit = callDatcStatus(13);
        int unitCode = (fUnit != null && !Float.isNaN(fUnit)) ? Math.round(fUnit) : 0;
        if (unitCode != 1 && unitCode != 2) {
            Integer legacyUnit = callIntNoArg(SVC_DATC, IFACE_DATC, "getTemperatureUnit", false);
            if (legacyUnit != null && (legacyUnit == 1 || legacyUnit == 2)) {
                unitCode = legacyUnit.intValue();
            }
        }
        boolean celsius;
        if (unitCode == 1 || unitCode == 2) {
            celsius = (unitCode == 1);
            lastKnownCelsius = Boolean.valueOf(celsius);
        } else if (lastKnownCelsius != null) {
            celsius = lastKnownCelsius.booleanValue();
        } else {
            celsius = false;
        }
        out.put("climate.temp_unit", celsius ? "C" : "F");

        int f10 = datcOutsideF10;
        int c10 = datcOutsideC10;
        if (f10 != Integer.MIN_VALUE) {
            out.put("outside_temp_f", formatCompactFloat(f10 / 10f));
            float cVal = (c10 != Integer.MIN_VALUE) ? (c10 / 10f) : (((f10 / 10f) - 32f) * 5f / 9f);
            out.put("outside_temp", formatCompactFloat(cVal));
        }

        Integer drvOnly = callIntNoArg(SVC_DATC, IFACE_DATC, "getDriverOnlyValue", false);
        if (drvOnly != null && drvOnly >= 0) {
            out.put("climate.driver_only", drvOnly == 1 ? "1" : "0");
        }

        Float fOn = callDatcStatus(12);
        boolean isOn = false;
        if (fOn != null && !Float.isNaN(fOn) && fOn >= 0f) {
            isOn = (Math.round(fOn) == 1);
            out.put("climate.on", isOn ? "1" : "0");
        }

        Float fAuto = callDatcStatus(8);
        if (fAuto != null && !Float.isNaN(fAuto) && fAuto >= 0f) {
            int autoLvl = Math.round(fAuto);
            if (autoLvl >= 0 && autoLvl <= 3) {
                out.put("climate.auto", autoLvl >= 1 ? "1" : "0");
                out.put("climate.auto_level", String.valueOf(autoLvl));
            }
        }

        putDatcBool(out, "climate.air_clean", callDatcStatus(7), 1);
        putDatcBool(out, "climate.ac", callDatcStatus(9), 1);
        putDatcBool(out, "climate.front_defrost", callDatcStatus(10), 1);
        putDatcBool(out, "climate.recirc", callDatcStatus(11), 1);
        Float fSync = callDatcStatus(22);
        boolean synced = false;
        if (fSync != null && !Float.isNaN(fSync) && fSync >= 0f) {
            synced = (Math.round(fSync) == 1);
            out.put("climate.sync", synced ? "1" : "0");
        }

        Float fHeat = callDatcStatus(48);
        Integer heatInfo = (fHeat != null && !Float.isNaN(fHeat) && (Math.round(fHeat) == 0 || Math.round(fHeat) == 1))
                ? Integer.valueOf(Math.round(fHeat))
                : callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 24, false);
        if (heatInfo != null && (heatInfo == 0 || heatInfo == 1)) {
            lastKnownHeatState = heatInfo.intValue();
            out.put("climate.heat", heatInfo == 1 ? "1" : "0");
        } else if (lastKnownHeatState >= 0) {
            out.put("climate.heat", (isOn && lastKnownHeatState == 1) ? "1" : "0");
        } else {
            out.put("climate.heat", (isOn && lastHeaterKw > 0.25f) ? "1" : "0");
        }

        Float fFan = callDatcStatus(5);
        if (fFan != null && !Float.isNaN(fFan) && fFan >= 0f) {
            int raw = Math.round(fFan);
            if (raw >= 0 && raw <= 9) {
                int step = raw <= 1 ? 0 : (raw - 1);
                if (step > 0) lastKnownFanStep = step;
                out.put("climate.fan_speed", String.valueOf(isOn ? step : 0));
            } else if (raw == 10) {
                out.put("climate.fan_speed", String.valueOf(isOn ? lastKnownFanStep : 0));
            }
        }

        Float fMode = callDatcStatus(6);
        if (fMode != null && !Float.isNaN(fMode) && fMode >= 0f) {
            out.put("climate.vent_mode", String.valueOf(Math.round(fMode)));
        }

        Float fDrv = callDatcStatus(2);
        String driverStr = null;
        if (fDrv != null && !Float.isNaN(fDrv) && fDrv >= 10f && fDrv <= 95f) {
            driverStr = celsius ? formatHalf(fDrv) : String.valueOf(Math.round(fDrv));
            out.put("climate.driver_temp", driverStr);
        } else if (fDrv != null && Math.round(fDrv) == 1) {
            driverStr = "LO";
            out.put("climate.driver_temp", "LO");
        } else if (fDrv != null && Math.round(fDrv) == 254) {
            driverStr = "HI";
            out.put("climate.driver_temp", "HI");
        }

        if (synced && driverStr != null) {
            out.put("climate.passenger_temp", driverStr);
        } else if (drvOnly != null && drvOnly == 1) {
            out.put("climate.passenger_temp", "OFF");
        } else if (celsius) {
            Float fPassC = callDatcStatus(29);
            if (fPassC != null && !Float.isNaN(fPassC)) {
                int raw = Math.round(fPassC);
                if (raw == 1) out.put("climate.passenger_temp", "LO");
                else if (raw == 254) out.put("climate.passenger_temp", "HI");
                else if (fPassC >= 4f && fPassC <= 38f) {
                    out.put("climate.passenger_temp", formatHalf((float) (fPassC / 2.0 + 14.0)));
                }
            }
        } else {
            Float fPassF = callDatcStatus(31);
            if (fPassF != null && !Float.isNaN(fPassF)) {
                int raw = Math.round(fPassF);
                if (raw == 1) out.put("climate.passenger_temp", "LO");
                else if (raw == 254) out.put("climate.passenger_temp", "HI");
                else if (raw >= 58 && raw <= 95) out.put("climate.passenger_temp", String.valueOf(raw));
                else if (raw >= 6 && raw <= 34) out.put("climate.passenger_temp", String.valueOf(raw + 56));
            }
        }

        // HSCAN Telematics Remote Climate readback (active when IGN1 is off and Bluelink/IG3 climate is running)
        Integer ig3Rly = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getIG3RlyMode", true);
        if (ig3Rly != null && ig3Rly >= 0) {
            out.put("hv.ig3", String.valueOf(ig3Rly));
        }
        Integer hscanAc = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getAirConOnOffStatus", true);
        boolean hscanOn = hscanAc != null && hscanAc > 0 && hscanAc <= 10;
        if (hscanOn) {
            remoteClimateActive = true;
            if (!isOn) {
                isOn = true;
                out.put("climate.on", "1");
                if (!out.containsKey("climate.auto") || "0".equals(out.get("climate.auto"))) {
                    out.put("climate.auto", "1");
                    out.put("climate.auto_level", "1");
                }
                if (!out.containsKey("climate.fan_speed") || "0".equals(out.get("climate.fan_speed"))) {
                    out.put("climate.fan_speed", "1");
                }
            }
        } else if (hscanAc != null && hscanAc == 0 && !"1".equals(out.get("hv.ready"))) {
            remoteClimateActive = false;
        }
        if (driverStr == null) {
            Integer hscanTempCode = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getSettingAirConTemperature", true);
            Integer hscanDisp = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getTempDisplyStatus", true);
            boolean hscanF = (hscanDisp != null) ? (hscanDisp == 1) : !celsius;
            String decoded = decodeHscanTempString(hscanTempCode, hscanF, celsius);
            if (decoded == null && lastRemoteTargetTemp != null) {
                decoded = convertTempLabelForDisplay(lastRemoteTargetTemp,
                        lastRemoteTargetCelsius != null && lastRemoteTargetCelsius.booleanValue(), celsius);
            }
            if (decoded != null) {
                out.put("climate.driver_temp", decoded);
                if (!out.containsKey("climate.passenger_temp")) {
                    out.put("climate.passenger_temp", decoded);
                }
            }
        }
        Integer hscanDef = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getFrontDefrosterOnOffStatus", true);
        if (hscanDef != null && hscanDef >= 0 && (hscanOn || !out.containsKey("climate.front_defrost"))) {
            out.put("climate.front_defrost", hscanDef != 0 ? "1" : "0");
        }
    }

    private void pollBodyAndSeats(Map<String, String> out) {
        Integer drvOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvSeatHeatOption", false);
        Integer drvSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvSeatHeatState", false);
        if (drvSeat == null || drvSeat < 0) drvSeat = callIntNoArg(SVC_USM, IFACE_USM, "getFrontSeatHeat_DrvState", false);
        decodeSeatState(out, "driver", drvSeat, true, drvOpt != null && drvOpt == 1);
        if ((drvSeat == null || drvSeat < 0) && bcmSeatHeatDrv >= 0) {
            out.put("seat_heat.driver", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatDrv)));
        }
        if (bcmSeatVentDrv >= 0 && (drvSeat == null || drvSeat == 2)) {
            out.put("seat_vent.driver", String.valueOf(normalizeVentValue(bcmSeatVentDrv)));
        }

        Integer astOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstSeatHeatOption", false);
        Integer astSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstSeatHeatState", false);
        if (astSeat == null || astSeat < 0) astSeat = callIntNoArg(SVC_USM, IFACE_USM, "getFrontSeatHeat_AstState", false);
        decodeSeatState(out, "passenger", astSeat, true, astOpt != null && astOpt == 1);
        if ((astSeat == null || astSeat < 0) && bcmSeatHeatAst >= 0) {
            out.put("seat_heat.passenger", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatAst)));
        }
        if (bcmSeatVentAst >= 0 && (astSeat == null || astSeat == 2)) {
            out.put("seat_vent.passenger", String.valueOf(normalizeVentValue(bcmSeatVentAst)));
        }

        Integer rlOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLSeatHeatOption", false);
        Integer rlSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLSeatHeatState", false);
        decodeSeatState(out, "rear_left", rlSeat, false, rlOpt != null && rlOpt == 1);
        if ((rlSeat == null || rlSeat < 0) && bcmSeatHeatRl >= 0) {
            out.put("seat_heat.rear_left", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatRl)));
        }

        Integer rrOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRSeatHeatOption", false);
        Integer rrSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRSeatHeatState", false);
        decodeSeatState(out, "rear_right", rrSeat, false, rrOpt != null && rrOpt == 1);
        if ((rrSeat == null || rrSeat < 0) && bcmSeatHeatRr >= 0) {
            out.put("seat_heat.rear_right", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatRr)));
        }

        Integer wFl = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvWdwOpenState", false);
        Integer wFr = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstWdwOpenState", false);
        Integer wRl = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLWdwOpenState", false);
        Integer wRr = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRWdwOpenState", false);
        putWindowFlag(out, "window.fl", (wFl != null && wFl >= 0) ? wFl : (bcmWdwFl >= 0 ? bcmWdwFl : null));
        putWindowFlag(out, "window.fr", (wFr != null && wFr >= 0) ? wFr : (bcmWdwFr >= 0 ? bcmWdwFr : null));
        putWindowFlag(out, "window.rl", (wRl != null && wRl >= 0) ? wRl : (bcmWdwRl >= 0 ? bcmWdwRl : null));
        putWindowFlag(out, "window.rr", (wRr != null && wRr >= 0) ? wRr : (bcmWdwRr >= 0 ? bcmWdwRr : null));

        Integer sr = callIntNoArg(SVC_BCM, IFACE_BCM, "getSunRoofOpnSta", false);
        if (sr != null && (sr == 0 || sr == 1)) {
            out.put("sunroof.open", sr == 1 ? "1" : "0");
        } else if (bcmSunroofOpen == 0 || bcmSunroofOpen == 1) {
            out.put("sunroof.open", bcmSunroofOpen == 1 ? "1" : "0");
        }

        putOpenFlag(out, "door.fl", bcmOpenFl);
        putOpenFlag(out, "door.fr", bcmOpenFr);
        putOpenFlag(out, "door.rl", bcmOpenRl);
        putOpenFlag(out, "door.rr", bcmOpenRr);
        if (bcmOpenFl >= 0 || bcmOpenFr >= 0 || bcmOpenRl >= 0 || bcmOpenRr >= 0) {
            boolean anyOpen = (bcmOpenFl == 1) || (bcmOpenFr == 1) || (bcmOpenRl == 1) || (bcmOpenRr == 1);
            out.put("door.any_open", anyOpen ? "1" : "0");
        }
        putLockFlag(out, "door.lock.fl", bcmUnlockFl);
        putLockFlag(out, "door.lock.fr", bcmUnlockFr);
        putLockFlag(out, "door.lock.rl", bcmUnlockRl);
        putLockFlag(out, "door.lock.rr", bcmUnlockRr);
        if (bcmLockState == 0 || bcmLockState == 1) {
            out.put("door.lock_state", bcmLockState == 0 ? "1" : "0");
        } else if (bcmUnlockFl == 0 || bcmUnlockFl == 1) {
            boolean anyUnlocked = (bcmUnlockFl == 1) || (bcmUnlockFr == 1) || (bcmUnlockRl == 1) || (bcmUnlockRr == 1);
            out.put("door.lock_state", anyUnlocked ? "0" : "1");
        }

        Integer cdmState = callIntNoArg(SVC_BCM, IFACE_BCM, "getHsOpnClsState", false);
        int effCdm = (cdmState != null && (cdmState == 1 || cdmState == 2)) ? cdmState.intValue() : bcmCdmState;
        if (effCdm == 1 || effCdm == 2) {
            out.put("charge_door.open", effCdm == 1 ? "1" : "0");
        }
        if (bcmTrunkOpen == 0 || bcmTrunkOpen == 1) {
            out.put("trunk.open", bcmTrunkOpen == 1 ? "1" : "0");
        }
        putOpenFlag(out, "hood.open", bcmHoodOpen);

        if (bcmWheelHeat == 0 || bcmWheelHeat == 1) {
            out.put("wheel_heat", bcmWheelHeat == 1 ? "1" : "0");
        }
        if (bcmDefogger == 0 || bcmDefogger == 1) {
            out.put("defogger", bcmDefogger == 1 ? "1" : "0");
        }

        Integer cond = callIntNoArg(SVC_BCM, IFACE_BCM, "getBattCondIndSta", false);
        if ((cond == null || cond < 0) && bcmBattCond >= 0) cond = bcmBattCond;
        if ((cond == null || cond < 0) && (clusterBattCondInd == 0 || clusterBattCondInd == 1)) cond = clusterBattCondInd;
        if (cond != null && (cond == 0 || cond == 1)) {
            out.put("hv.conditioning_active", cond == 1 ? "1" : "0");
        }

        Integer sysEpb = callIntNoArg(SVC_SYSTEM, IFACE_SYSTEM, "getEPBStatus", false);
        if (sysEpb != null && (sysEpb == 1 || sysEpb == 2)) {
            out.put("parking_brake", sysEpb == 2 ? "1" : "0");
        } else if (bcmParkBrake == 1 || bcmParkBrake == 2) {
            out.put("parking_brake", bcmParkBrake == 2 ? "1" : "0");
        }
    }

    CommandReply executeCommand(String rawCmd, String rawArg, Map<String, String> latest) {
        String cmd = rawCmd == null ? "" : rawCmd.trim().toLowerCase(Locale.US);
        String arg = rawArg == null ? "" : rawArg.trim();
        int colon = cmd.indexOf(':');
        if (colon > 0 && arg.isEmpty()) {
            arg = cmd.substring(colon + 1).trim();
            cmd = cmd.substring(0, colon).trim();
        }
        Map<String, String> snap = latest != null ? latest : new HashMap<String, String>();
        long t0 = System.currentTimeMillis();
        DiagLogger.logCarAction("EXEC-START", "cmd=" + cmd + " argLen=" + arg.length());
        activeCommandDepth.incrementAndGet();
        try {
            CommandReply rep = executeCommandInternal(cmd, arg, snap);
            DiagLogger.logCarAction("EXEC-DONE", "cmd=" + cmd + " ok=" + rep.ok
                    + " ms=" + (System.currentTimeMillis() - t0) + " msg=\"" + rep.message + "\"");
            return rep;
        } catch (Throwable t) {
            DiagLogger.logCarAction("EXEC-ERR", "cmd=" + cmd + " err=" + t);
            return new CommandReply(false, "Error: " + t.getMessage());
        } finally {
            activeCommandDepth.decrementAndGet();
        }
    }

    private CommandReply executeCommandInternal(String cmd, String arg, Map<String, String> snap) {
        // --- Atomic Custom Climate Theme Macro ---
        if ("apply_climate_theme".equals(cmd)) {
            return applyClimateThemeMacro(arg, snap);
        }

        // --- Doors & Locks ---
        if ("door_lock".equals(cmd) || "door_unlock".equals(cmd) || "door_lock_toggle".equals(cmd)) {
            boolean wantLock = "door_lock".equals(cmd)
                    || ("door_lock_toggle".equals(cmd) && !"1".equals(snap.get("door.lock_state")));
            boolean ok = callBoolWrite1(SVC_BCM, IFACE_BCM, "lockDoor", wantLock);
            if (ok) {
                bcmLockState = wantLock ? 0 : 1;
                callVoidNoArg(SVC_BCM, IFACE_BCM, "getDoorLockStatus");
            }
            return new CommandReply(ok, ok ? (wantLock ? "Doors LOCKED" : "Doors UNLOCKED") : "BCM lockDoor unavailable");
        }
        if ("door_open_all".equals(cmd) || "door_close_all".equals(cmd)) {
            boolean open = "door_open_all".equals(cmd);
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_AllPowerDoorOpen", open ? 1 : 2);
            return new CommandReply(ok, ok ? (open ? "Power Doors Opening" : "Power Doors Closing") : "USM Power Doors unavailable");
        }

        // --- Trunk / Hatch / Power Tailgate ---
        if ("trunk_open".equals(cmd) || "trunk_close".equals(cmd) || "trunk_toggle".equals(cmd)) {
            boolean currentlyOpen = "1".equals(snap.get("trunk.open")) || bcmTrunkOpen == 1;
            boolean wantOpen = "trunk_open".equals(cmd) || ("trunk_toggle".equals(cmd) && !currentlyOpen);
            if ("0".equals(snap.get("gear.park"))) {
                return new CommandReply(false, "Refused: Car must be in PARK for Trunk/Hatch");
            }
            refreshTrunkVrSignals();
            // OEM VoiceHandler uses C_PTGCNTLCmdAVN (1=Open, 0/2=Close) and C_TrunkTlgtCmdAVN (1=Open, 2=Close).
            // Send both VR signals cleanly so whichever CAN IL signal the PTG module monitors is triggered.
            boolean okPtg = callIntWrite1(SVC_VR, IFACE_VR, "set_PTGCNTL_Cmd_AVN", wantOpen ? 1 : 0);
            if (!wantOpen) {
                sleepMs(120L);
                okPtg = callIntWrite1(SVC_VR, IFACE_VR, "set_PTGCNTL_Cmd_AVN", 2) || okPtg;
            }
            boolean okTlgt = callIntWrite1(SVC_VR, IFACE_VR, "set_TrunkTlgt_Cmd_AVN", wantOpen ? 1 : 2);
            boolean ok = okPtg || okTlgt;
            return new CommandReply(ok, ok
                    ? (wantOpen ? "Hatch / Trunk OPEN requested" : "Hatch / Trunk CLOSE requested")
                    : "Hatch / PTG command unavailable");
        }
        if ("trunk_height_set".equals(cmd)) {
            int lvl = Math.max(0, Math.min(4, parseIntSafe(arg, 4)));
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_TrunkOpeningHeight", lvl);
            return new CommandReply(ok, ok ? ("Hatch Height -> Level " + lvl) : "USM Hatch Height unavailable");
        }
        if ("trunk_speed_set".equals(cmd)) {
            int spd = Math.max(0, Math.min(2, parseIntSafe(arg, 1)));
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_PowerTrunkSpeed", spd);
            return new CommandReply(ok, ok ? ("Hatch Speed -> " + (spd == 1 ? "Normal" : "Fast")) : "USM Hatch Speed unavailable");
        }

        // --- Power Windows, Sunroof & Sunshade ---
        if ("window_all_open".equals(cmd)) return setAllWindows(4, 5, "All Windows DOWN");
        if ("window_all_close".equals(cmd)) return setAllWindows(1, 6, "All Windows UP");
        if ("window_all_half".equals(cmd)) return setAllWindows(0, 4, "All Windows 50%");
        if ("window_all_vent".equals(cmd)) {
            ensureVrSignalsEnabled();
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Window_ALL_UPDOWN", 2);
            if (ok) {
                sleepMs(240L);
                callIntWrite1(SVC_VR, IFACE_VR, "set_RLWDW_Cmd_AVN", 3);
                sleepMs(240L);
                callIntWrite1(SVC_VR, IFACE_VR, "set_RRWDW_Cmd_AVN", 3);
            } else {
                ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd", 3, 3, 7, 3, 3);
            }
            return new CommandReply(ok, ok ? "All Windows Vent 25%" : "Window command unavailable");
        }
        // Individual C_*WindowCmdAVN hardware polarity: 6 = Full DOWN (Open), 5 = Full UP (Close)
        if ("window_fl_open".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 6, 6, 0, 0, 0, "Driver Window DOWN");
        if ("window_fl_close".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 5, 5, 0, 0, 0, "Driver Window UP");
        if ("window_fr_open".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 6, 0, 6, 0, 0, "Pass Window DOWN");
        if ("window_fr_close".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 5, 0, 5, 0, 0, "Pass Window UP");
        if ("window_rl_open".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 6, 0, 0, 6, 0, "Rear-L Window DOWN");
        if ("window_rl_close".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 5, 0, 0, 5, 0, "Rear-L Window UP");
        if ("window_rr_open".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 6, 0, 0, 0, 6, "Rear-R Window DOWN");
        if ("window_rr_close".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 5, 0, 0, 0, 5, "Rear-R Window UP");

        if ("sunroof_open".equals(cmd) || "sunroof_close".equals(cmd)) {
            ensureVrSignalsEnabled();
            int val = "sunroof_open".equals(cmd) ? 1 : 2;
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Glass_Cmd_AVN", val);
            if (!ok) ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Sunroof_Cmd_AVN", val);
            return new CommandReply(ok, ok ? ("Sunroof " + (val == 1 ? "OPEN" : "CLOSE")) : "Sunroof unavailable");
        }
        if ("sunshade_open".equals(cmd) || "sunshade_close".equals(cmd)) {
            ensureVrSignalsEnabled();
            int val = "sunshade_open".equals(cmd) ? 1 : 2;
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Blind_Cmd_AVN", val);
            return new CommandReply(ok, ok ? ("Sunshade " + (val == 1 ? "OPEN" : "CLOSE")) : "Sunshade unavailable");
        }

        // --- Climate & Cabin HVAC (All AC adjustments automatically sync Driver & Passenger temps) ---
        if ("climate_toggle".equals(cmd) || "climate_on".equals(cmd) || "climate_off".equals(cmd)) {
            boolean currentlyOn = "1".equals(snap.get("climate.on")) || remoteClimateActive;
            boolean wantOff = "climate_off".equals(cmd)
                    || ("climate_toggle".equals(cmd) && currentlyOn);
            int fanRaw = wantOff ? 0 : (Math.max(1, Math.min(8, lastKnownFanStep)) + 1);
            if (wantOff) {
                boolean fOk = callDatcSetFront(51, 0) || callDatcSetFront(5, 0);
                boolean vOk = callDatcSetVr(5, 0, 0);
                if (shouldUseRemoteClimate(snap) || remoteClimateActive || isIg3Active(snap)) {
                    CommandReply remOff = stopBluelinkRemoteClimate(snap);
                    return new CommandReply(remOff.ok || fOk || vOk,
                            remOff.ok ? remOff.message : "Climate OFF");
                }
                boolean ok = fOk || vOk;
                return new CommandReply(ok, ok ? "Climate OFF" : "DATC unavailable");
            } else {
                boolean fOk = callDatcSetFront(5, fanRaw);
                boolean vOk = callDatcSetVr(5, fanRaw, 0);
                boolean ok = fOk || vOk;
                if (ok) syncDualZoneTemps(snap, null);
                if (shouldUseRemoteClimate(snap)) {
                    CommandReply remOn = startBluelinkRemoteClimateFromSnapshot(snap, null, null, null, null);
                    return new CommandReply(remOn.ok || ok, remOn.ok ? remOn.message : "Climate ON (Synced)");
                }
                return new CommandReply(ok, ok ? "Climate ON (Synced)" : "DATC unavailable");
            }
        }
        if ("climate_auto_toggle".equals(cmd) || "climate_auto_on".equals(cmd) || "climate_auto_off".equals(cmd) || "climate_auto_set".equals(cmd)) {
            int level = "climate_auto_off".equals(cmd) ? 0
                    : ("climate_auto_on".equals(cmd) ? 1
                    : ("climate_auto_set".equals(cmd) ? Math.max(0, Math.min(3, parseIntSafe(arg, 1)))
                    : ("1".equals(snap.get("climate.auto")) ? 0 : 1)));
            boolean fOk = callDatcSetFront(8, level);
            boolean vOk = callDatcSetVr(8, level, 0);
            boolean ok = fOk || vOk;
            if (ok && level > 0) syncDualZoneTemps(snap, null);
            if (level > 0 && shouldUseRemoteClimate(snap)) {
                CommandReply remOn = startBluelinkRemoteClimateFromSnapshot(snap, null, null, null, null);
                return new CommandReply(remOn.ok || ok, remOn.ok ? remOn.message : ("Climate AUTO ON (Lvl " + level + ")"));
            }
            return new CommandReply(ok, ok ? (level == 0 ? "Climate AUTO OFF" : ("Climate AUTO ON (Lvl " + level + ")")) : "DATC unavailable");
        }
        if ("climate_ac_toggle".equals(cmd) || "climate_ac_on".equals(cmd) || "climate_ac_off".equals(cmd)) {
            boolean next = "climate_ac_on".equals(cmd)
                    || ("climate_ac_toggle".equals(cmd) && !"1".equals(snap.get("climate.ac")));
            boolean fOk = callDatcSetFront(9, next ? 1 : 0);
            boolean vOk = callDatcSetVr(9, next ? 1 : 0, 0);
            boolean ok = fOk || vOk;
            if (ok) syncDualZoneTemps(snap, null);
            if (next && shouldUseRemoteClimate(snap)) {
                CommandReply remOn = startBluelinkRemoteClimateFromSnapshot(snap, null, null, null, null);
                return new CommandReply(remOn.ok || ok, remOn.ok ? remOn.message : "A/C ON");
            }
            return new CommandReply(ok, ok ? ("A/C " + (next ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_heat_toggle".equals(cmd) || "climate_heat_on".equals(cmd) || "climate_heat_off".equals(cmd)) {
            boolean curHeat = "1".equals(snap.get("climate.heat")) || lastKnownHeatState == 1;
            boolean next = "climate_heat_on".equals(cmd)
                    || ("climate_heat_toggle".equals(cmd) && !curHeat);
            boolean ok = callDatcSetFront(48, next ? 1 : 0);
            if (ok) {
                lastKnownHeatState = next ? 1 : 0;
                syncDualZoneTemps(snap, null);
            }
            if (next && shouldUseRemoteClimate(snap)) {
                CommandReply remOn = startBluelinkRemoteClimateFromSnapshot(snap, null, null, null, null);
                return new CommandReply(remOn.ok || ok, remOn.ok ? remOn.message : "Cabin HEAT ON");
            }
            return new CommandReply(ok, ok ? ("Cabin HEAT " + (next ? "ON" : "OFF")) : "DATC Heat unavailable");
        }
        if ("climate_max_ac".equals(cmd)) {
            boolean celsius = isCelsius(snap);
            String target = celsius ? "17" : "62";
            boolean ok = callDatcSetVr(9, 1, 0);
            callDatcSetVr(11, 1, 0);
            callDatcSetVr(6, 1, 0);
            callDatcSetVr(5, 9, 0);
            applyZoneTempValue(true, target, snap);
            syncDualZoneTemps(snap, target);
            if (shouldUseRemoteClimate(snap)) {
                CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, target, Boolean.FALSE, null, null);
                if (rem.ok) return new CommandReply(true, "Remote Cool Applied (" + (celsius ? "17\u00B0C" : "62\u00B0F") + " \u00B7 Auto 1)");
            }
            return new CommandReply(ok, ok ? "MAX A/C Applied (LO + Max Fan)" : "DATC unavailable");
        }
        if ("climate_max_defrost".equals(cmd)) {
            boolean celsius = isCelsius(snap);
            String target = celsius ? "27" : "82";
            boolean ok = callDatcSetVr(10, 1, 0);
            callIntWrite1(SVC_VR, IFACE_VR, "set_RrDefogger_Cmd_AVN", 1);
            callDatcSetVr(5, 9, 0);
            applyZoneTempValue(true, celsius ? "30" : "86", snap);
            syncDualZoneTemps(snap, celsius ? "30" : "86");
            if (shouldUseRemoteClimate(snap)) {
                bcmDefogger = 1;
                CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, target, Boolean.TRUE, Boolean.TRUE, null);
                if (rem.ok) return new CommandReply(true, "Remote Defrost Applied (Front + Rear \u00B7 " + (celsius ? "27\u00B0C" : "82\u00B0F") + ")");
            }
            return new CommandReply(ok, ok ? "MAX Defrost Applied (Front + Rear)" : "DATC unavailable");
        }
        if ("climate_recirc_toggle".equals(cmd) || "climate_recirc_on".equals(cmd) || "climate_fresh_air".equals(cmd)) {
            boolean nextRecirc = "climate_recirc_on".equals(cmd)
                    || ("climate_recirc_toggle".equals(cmd) && !"1".equals(snap.get("climate.recirc")));
            boolean ok = callDatcSetVr(11, nextRecirc ? 1 : 2, 0);
            if (ok) syncDualZoneTemps(snap, null);
            return new CommandReply(ok, ok ? (nextRecirc ? "Recirculate Air" : "Fresh Air Intake") : "DATC unavailable");
        }
        if ("climate_sync_toggle".equals(cmd) || "climate_sync_on".equals(cmd) || "climate_sync_off".equals(cmd)) {
            boolean nextSync = "climate_sync_on".equals(cmd)
                    || ("climate_sync_toggle".equals(cmd) && !"1".equals(snap.get("climate.sync")));
            boolean ok = callDatcSetVr(22, nextSync ? 1 : 2, 0) || callDatcSetFront(22, nextSync ? 1 : 2);
            return new CommandReply(ok, ok ? ("Dual-Zone SYNC " + (nextSync ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_front_defrost_toggle".equals(cmd) || "climate_front_defrost_on".equals(cmd) || "climate_front_defrost_off".equals(cmd)) {
            boolean next = "climate_front_defrost_on".equals(cmd)
                    || ("climate_front_defrost_toggle".equals(cmd) && !"1".equals(snap.get("climate.front_defrost")));
            boolean ok = callDatcSetVr(10, next ? 1 : 0, 0);
            if (ok) syncDualZoneTemps(snap, null);
            if (shouldUseRemoteClimate(snap)) {
                lastRemoteFrontDefrost = next;
                callHscanBool1("requestFrontDefrosterOnOff", next);
                if ("1".equals(snap.get("climate.on")) || remoteClimateActive || next) {
                    CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, null, Boolean.valueOf(next), null, null);
                    ok = ok || rem.ok;
                }
            }
            return new CommandReply(ok, ok ? ("Front Defrost " + (next ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_vent_set".equals(cmd) || "climate_vent_cycle".equals(cmd)) {
            int next = "climate_vent_set".equals(cmd)
                    ? parseVentArg(arg)
                    : ((parseIntSafe(snap.get("climate.vent_mode"), 1) % 5) + 1);
            boolean ok = callDatcSetVr(6, next, 0) || callDatcSetFront(6, next);
            if (ok) syncDualZoneTemps(snap, null);
            return new CommandReply(ok, ok ? ("Vent -> " + ventModeName(next)) : "DATC unavailable");
        }
        if ("climate_fan_up".equals(cmd) || "climate_fan_down".equals(cmd) || "climate_fan_set".equals(cmd)) {
            int cur = parseIntSafe(snap.get("climate.fan_speed"), lastKnownFanStep);
            int target = "climate_fan_set".equals(cmd)
                    ? Math.max(0, Math.min(8, parseIntSafe(arg, cur)))
                    : ("climate_fan_up".equals(cmd) ? Math.min(8, Math.max(1, cur + 1)) : Math.max(0, cur - 1));
            if (target > 0) lastKnownFanStep = target;
            boolean ok = target == 0
                    ? (callDatcSetVr(5, 0, 0) || callDatcSetFront(51, 0))
                    : callDatcSetVr(5, target + 1, 0);
            if (target == 0 && (shouldUseRemoteClimate(snap) || remoteClimateActive || isIg3Active(snap))) {
                CommandReply remOff = stopBluelinkRemoteClimate(snap);
                return new CommandReply(remOff.ok || ok, remOff.ok ? remOff.message : "Climate Fan OFF");
            }
            if (ok && target > 0) syncDualZoneTemps(snap, null);
            if (target > 0 && shouldUseRemoteClimate(snap)) {
                return new CommandReply(ok, "Fan " + target + " Sent (Ign-Off Remote locks Auto 1)");
            }
            return new CommandReply(ok, ok ? (target == 0 ? "Climate Fan OFF" : ("Fan Speed -> " + target)) : "DATC unavailable");
        }
        if ("climate_set_temp".equals(cmd) || "climate_driver_temp_set".equals(cmd)) {
            CommandReply dRes = applyZoneTempValue(true, arg, snap);
            syncDualZoneTemps(snap, arg);
            if (shouldUseRemoteClimate(snap)) {
                rememberRemoteTemp(arg, isCelsius(snap));
                if ("1".equals(snap.get("climate.on")) || remoteClimateActive) {
                    CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, arg, null, null, null);
                    if (rem.ok) return rem;
                }
            }
            return dRes;
        }
        if ("climate_passenger_temp_set".equals(cmd)) {
            CommandReply pRes = applyZoneTempValue(false, arg, snap);
            applyZoneTempValue(true, arg, snap);
            enableDualZoneSync();
            if (shouldUseRemoteClimate(snap)) {
                rememberRemoteTemp(arg, isCelsius(snap));
                if ("1".equals(snap.get("climate.on")) || remoteClimateActive) {
                    CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, arg, null, null, null);
                    if (rem.ok) return rem;
                }
            }
            return pRes;
        }
        if ("climate_driver_temp_up".equals(cmd) || "climate_driver_temp_down".equals(cmd)) {
            return adjustZoneTempSynced("climate_driver_temp_up".equals(cmd), snap);
        }
        if ("climate_passenger_temp_up".equals(cmd) || "climate_passenger_temp_down".equals(cmd)) {
            return adjustZoneTempSynced("climate_passenger_temp_up".equals(cmd), snap);
        }

        // --- Rear Defrost, Steering Wheel & Seats ---
        if ("rear_defrost_toggle".equals(cmd) || "rear_defrost_on".equals(cmd) || "rear_defrost_off".equals(cmd)) {
            boolean wantOn = "rear_defrost_on".equals(cmd)
                    || ("rear_defrost_toggle".equals(cmd) && !"1".equals(snap.get("defogger")));
            int want = wantOn ? 1 : 0;
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_RrDefogger_Cmd_AVN", want);
            if (shouldUseRemoteClimate(snap)) {
                boolean wheelNow = bcmWheelHeat == 1 || "1".equals(snap.get("wheel_heat"));
                ok = applyBcmTeleHeatingOnly(wantOn, wheelNow) || ok;
            }
            if (ok) bcmDefogger = want;
            return new CommandReply(ok, ok ? ("Rear Defrost " + (wantOn ? "ON" : "OFF")) : "VRService unavailable");
        }
        if ("wheel_heat_toggle".equals(cmd) || "wheel_heat_on".equals(cmd) || "wheel_heat_off".equals(cmd)) {
            boolean wantOn = "wheel_heat_on".equals(cmd)
                    || ("wheel_heat_toggle".equals(cmd) && !"1".equals(snap.get("wheel_heat")));
            return setWheelHeat(wantOn, snap);
        }
        if ("seat_heat_driver_cycle".equals(cmd) || "seat_heat_passenger_cycle".equals(cmd)) {
            boolean driver = "seat_heat_driver_cycle".equals(cmd);
            boolean twoStep = isSeatTwoStep(driver ? "driver" : "passenger");
            int cur = parseIntSafe(snap.get(driver ? "seat_heat.driver" : "seat_heat.passenger"), 0);
            int next = twoStep ? (cur <= 0 ? 2 : (cur == 2 ? 1 : 0)) : cycleSeatStep(cur);
            return setSeatClimate(driver ? "driver" : "passenger", true, next, twoStep, snap);
        }
        if ("seat_heat_driver_set".equals(cmd) || "seat_heat_passenger_set".equals(cmd)
                || "seat_heat_rl_set".equals(cmd) || "seat_heat_rr_set".equals(cmd)) {
            String seat = "seat_heat_driver_set".equals(cmd) ? "driver"
                    : ("seat_heat_passenger_set".equals(cmd) ? "passenger"
                    : ("seat_heat_rl_set".equals(cmd) ? "rear_left" : "rear_right"));
            return setSeatClimate(seat, true, Math.max(0, Math.min(3, parseIntSafe(arg, 0))), isSeatTwoStep(seat), snap);
        }
        if ("seat_vent_driver_cycle".equals(cmd) || "seat_vent_passenger_cycle".equals(cmd)) {
            boolean driver = "seat_vent_driver_cycle".equals(cmd);
            int cur = parseIntSafe(snap.get(driver ? "seat_vent.driver" : "seat_vent.passenger"), 0);
            return setSeatClimate(driver ? "driver" : "passenger", false, cycleSeatStep(cur), false, snap);
        }
        if ("seat_vent_driver_set".equals(cmd) || "seat_vent_passenger_set".equals(cmd)) {
            boolean driver = "seat_vent_driver_set".equals(cmd);
            return setSeatClimate(driver ? "driver" : "passenger", false, Math.max(0, Math.min(3, parseIntSafe(arg, 0))), false, snap);
        }
        if ("seat_heat_rl_cycle".equals(cmd) || "seat_heat_rr_cycle".equals(cmd)) {
            boolean left = "seat_heat_rl_cycle".equals(cmd);
            String seat = left ? "rear_left" : "rear_right";
            boolean twoStep = isSeatTwoStep(seat);
            int cur = parseIntSafe(snap.get(left ? "seat_heat.rear_left" : "seat_heat.rear_right"), 0);
            int next = twoStep ? (cur <= 0 ? 2 : (cur >= 2 ? 1 : 0)) : cycleSeatStep(cur);
            return setSeatClimate(seat, true, next, twoStep, snap);
        }

        // --- EV Preconditioning & Charge Port ---
        if ("precondition_toggle".equals(cmd) || "precondition_on".equals(cmd) || "precondition_off".equals(cmd)) {
            String condSt = snap.get("conditioning.state");
            boolean curActive = "1".equals(snap.get("hv.conditioning_active"))
                    || "active".equalsIgnoreCase(condSt)
                    || "waiting".equalsIgnoreCase(condSt)
                    || "requested".equalsIgnoreCase(condSt)
                    || directCondActive;
            boolean wantActive = "precondition_on".equals(cmd)
                    || ("precondition_toggle".equals(cmd) && !curActive);
            if (!wantActive) {
                boolean wasDirect = directCondActive;
                stopDirectBatteryConditioning();
                snap.put("conditioning.state", "off");
                try {
                    String reply = HmgApi.connect().timeout(1200).command("conditioning", "stop");
                    return new CommandReply(true, wasDirect ? "Battery Preconditioning Stopped" : reply);
                } catch (Throwable err) {
                    return new CommandReply(true, "Battery Preconditioning Stopped");
                }
            }
            // Try HMG-API first (with "warm" flag to avoid 70°F block); if HMG-API refuses
            // (e.g. "refused: the car isn't READY" while in Utility Mode), bypass directly via IHEVService!
            try {
                String reply = HmgApi.connect().timeout(1200).command("conditioning", "start", "warm");
                directCondActive = false;
                directCondTerminalState = null;
                return new CommandReply(true, reply);
            } catch (Throwable hmgErr) {
                return startDirectBatteryConditioning(snap, hmgErr.getMessage());
            }
        }
        if ("charge_door_open".equals(cmd) || "charge_door_close".equals(cmd) || "charge_door_toggle".equals(cmd)) {
            boolean currentlyOpen = "1".equals(snap.get("charge_door.open")) || bcmCdmState == 1;
            boolean open = "charge_door_open".equals(cmd)
                    || ("charge_door_toggle".equals(cmd) && !currentlyOpen);
            if ("0".equals(snap.get("gear.park"))) {
                return new CommandReply(false, "Refused: Car must be in PARK");
            }
            try {
                String reply = HmgApi.connect().timeout(1200).command("charge_door", open ? "open" : "close");
                bcmCdmState = open ? 1 : 2;
                return new CommandReply(true, reply);
            } catch (Throwable hmgErr) {
                boolean ok = callBoolWrite1(SVC_BCM, IFACE_BCM, "requestChargeLockDoor", !open);
                if (ok) bcmCdmState = open ? 1 : 2;
                return new CommandReply(ok, ok ? ("Charge Port " + (open ? "OPEN" : "CLOSE")) : String.valueOf(hmgErr.getMessage()));
            }
        }

        // --- EV Utility Mode & Ignition-Off TMU Climate (Testing Controls) ---
        if ("utility_mode_on".equals(cmd) || "utility_mode_test".equals(cmd) || "utility_mode_off".equals(cmd)) {
            boolean wantOn = !"utility_mode_off".equals(cmd);
            int reqMode = wantOn ? 2 : 1;
            boolean ok = callIntWrite1(SVC_HEV, IFACE_HEV, "setUtilModeSet", reqMode);
            if (!ok) {
                return new CommandReply(false, "IHEVService.setUtilModeSet unavailable");
            }
            // HEVService pulses EV_HU_UTILMODESETREQ_C (0x0c5d) for 120ms then resets to 0.
            // Poll up to 1.25s for VCU_UTILMODUSMSTA (0x50) to latch to 2 (ON) or VCU_UTILMODWRNMSGSTA (0x51) == 2.
            Integer utilMode = null;
            Integer utilWarn = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                sleepMs(250L);
                utilMode = pollHevUtilityMode();
                utilWarn = pollHevUtilityWarn();
                if (wantOn && ((utilMode != null && utilMode == 2) || (utilWarn != null && utilWarn == 2))) {
                    break;
                }
            }
            Integer evRdy = pollHevEvReady();
            if (utilMode != null && utilMode >= 0 && utilMode != 0xFFFF) {
                snap.put("hv.utility_mode", String.valueOf(utilMode));
            }
            if (utilWarn != null && utilWarn >= 0 && utilWarn != 0xFFFF) {
                snap.put("hv.utility_warn", String.valueOf(utilWarn));
            }
            if (evRdy != null && evRdy >= 0 && evRdy != 0xFFFF) {
                snap.put("hv.ready", String.valueOf(evRdy));
            }
            String rdyNote = (evRdy != null && evRdy == 1) ? "EV Ready" : ("VCU_RDY=" + (evRdy == null ? "--" : evRdy));
            if (wantOn) {
                if (utilMode != null && utilMode == 2) {
                    return new CommandReply(true, "Utility Mode ENGAGED (VCU=ON)");
                }
                if (utilWarn != null && utilWarn == 2) {
                    return new CommandReply(false, "VCU Refused Utility Mode (Conditions Not Met \u00B7 " + rdyNote + ")");
                }
                String stNote = (utilMode != null && utilMode == 1) ? "OFF" : String.valueOf(utilMode);
                return new CommandReply(false, "Pulse Sent, VCU Stayed " + stNote + " (" + rdyNote + ")");
            } else {
                String stNote = (utilMode != null && utilMode == 2) ? "Still ON (press Start button)" : "OFF";
                return new CommandReply(true, "Utility Mode Off Sent (" + stNote + ")");
            }
        }
        if ("tmu_fatc_on".equals(cmd)) {
            CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, null, null, null, null);
            if (rem.ok) return rem;
            callIntWrite1(SVC_HEV, IFACE_HEV, "setTmuRdy", 1);
            boolean ig3Ok = callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", true);
            sleepMs(80L);
            int tmuTemp = computeBluelinkTempCode(null, isCelsius(snap), snap);
            callIntWrite1(SVC_TMU, IFACE_TMU, "requestFATCTempSet", tmuTemp);
            boolean fatcOk = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestFATCOnOff", true);
            boolean acOk = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestAirConOnOff", true);
            boolean ok = fatcOk || acOk;
            return new CommandReply(ok, ok
                    ? ("TMU FATC + IG3 Sent (tempCode=" + tmuTemp + ", IG3=" + ig3Ok + ")")
                    : "HSCAN/ITMUService unavailable");
        }
        if ("tmu_nostart_ac".equals(cmd)) {
            callIntWrite1(SVC_HEV, IFACE_HEV, "setTmuRdy", 1);
            callHscanSendIg3RlyMode((byte) 3);
            callHscanTmuIg3DrvState(1);
            boolean ig3Ok = callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", true);
            sleepMs(80L);
            boolean ok = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestNoStartAC", true);
            return new CommandReply(ok, ok
                    ? ("TMU No-Start Park A/C Sent (IG3=" + ig3Ok + ")")
                    : "ITMUService.requestNoStartAC unavailable");
        }
        if ("tmu_remote_start".equals(cmd)) {
            callIntWrite1(SVC_HEV, IFACE_HEV, "setTmuRdy", 1);
            callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", true);
            boolean engOk = callVoidNoArg(SVC_TMU, IFACE_TMU, "requestStartEngine");
            sleepMs(120L);
            int tmuTemp = computeBluelinkTempCode(null, isCelsius(snap), snap);
            callIntWrite1(SVC_TMU, IFACE_TMU, "requestFATCTempSet", tmuTemp);
            boolean fatcOk = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestFATCOnOff", true);
            return new CommandReply(engOk || fatcOk, (engOk || fatcOk)
                    ? ("TMU StartEngine + FATC Sent (tempCode=" + tmuTemp + ")")
                    : "ITMUService.requestStartEngine unavailable");
        }
        if ("tmu_fatc_off".equals(cmd)) {
            callDatcSetFront(51, 0);
            callDatcSetVr(5, 0, 0);
            return stopBluelinkRemoteClimate(snap);
        }

        // --- Audio ---
        if ("volume_up".equals(cmd) || "volume_down".equals(cmd)) {
            boolean up = "volume_up".equals(cmd);
            Integer curVol = callVolumeGet(lastAudioStream);
            int base = (curVol != null && curVol >= 0) ? curVol : parseIntSafe(snap.get("audio.volume"), 10);
            int target = Math.max(0, Math.min(MAX_AUDIO_LEVEL, base + (up ? 1 : -1)));
            boolean ok = callVolumeSet(lastAudioStream, target);
            return new CommandReply(ok, ok ? ("Volume -> " + target) : "Audio unavailable");
        }
        if ("volume_mute_toggle".equals(cmd)) {
            Integer m = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getUserMuteState", false);
            boolean muted = (m != null) ? (m != 0) : "1".equals(snap.get("audio.muted"));
            boolean ok = callVoidNoArg(SVC_AUDIO, IFACE_AUDIO, muted ? "unMuteAV" : "muteAV");
            return new CommandReply(ok, ok ? (muted ? "Audio Unmuted" : "Audio Muted") : "Audio unavailable");
        }

        return new CommandReply(false, "Unknown car command: " + cmd);
    }

    private CommandReply applyClimateThemeMacro(String jsonArg, Map<String, String> snap) {
        if (jsonArg == null || jsonArg.isEmpty()) {
            return new CommandReply(false, "Empty Climate Theme payload");
        }
        ClimateTheme theme;
        try {
            theme = ClimateTheme.fromJson(new JSONObject(jsonArg));
        } catch (Throwable t) {
            return new CommandReply(false, "Invalid Climate Theme JSON");
        }

        boolean remoteMode = shouldUseRemoteClimate(snap);
        CommandReply remReply = null;
        if (remoteMode) {
            int drvSeatOp = theme.driverSeatVent > 0
                    ? encodeBcmSeatOp(false, theme.driverSeatVent, false)
                    : encodeBcmSeatOp(true, theme.driverSeatHeat, isSeatTwoStep("driver"));
            int astSeatOp = theme.passengerSeatVent > 0
                    ? encodeBcmSeatOp(false, theme.passengerSeatVent, false)
                    : encodeBcmSeatOp(true, theme.passengerSeatHeat, isSeatTwoStep("passenger"));
            int rlSeatOp = theme.rearLeftSeatHeat >= 0
                    ? encodeBcmSeatOp(true, theme.rearLeftSeatHeat, isSeatTwoStep("rear_left")) : 0;
            int rrSeatOp = theme.rearRightSeatHeat >= 0
                    ? encodeBcmSeatOp(true, theme.rearRightSeatHeat, isSeatTwoStep("rear_right")) : 0;
            remReply = startBluelinkRemoteClimate(
                    snap,
                    theme.driverTemp,
                    theme.celsius,
                    theme.frontDefrost,
                    theme.rearDefrost,
                    theme.wheelHeat,
                    drvSeatOp,
                    astSeatOp,
                    rlSeatOp,
                    rrSeatOp
            );
        }

        boolean hvacOk;
        // 1. Wake HVAC via AUTO or manual fan speed (1..8) using both Front panel & VR paths
        if (theme.autoLevel > 0) {
            int autoLvl = Math.max(1, Math.min(3, theme.autoLevel));
            boolean fOk = callDatcSetFront(8, autoLvl);
            boolean vOk = callDatcSetVr(8, autoLvl, 0);
            hvacOk = fOk || vOk;
        } else {
            int fan = Math.max(1, Math.min(8, theme.fanSpeed));
            lastKnownFanStep = fan;
            boolean fOk = callDatcSetFront(5, fan + 1);
            boolean vOk = callDatcSetVr(5, fan + 1, 0);
            hvacOk = fOk || vOk;
        }
        paceSleep();

        // 2. A/C and Cabin Heat
        callDatcSetFront(9, theme.acOn ? 1 : 0);
        callDatcSetVr(9, theme.acOn ? 1 : 0, 0);
        paceSleep();
        if (callDatcSetFront(48, theme.heatOn ? 1 : 0)) {
            lastKnownHeatState = theme.heatOn ? 1 : 0;
        }
        paceSleep();

        // 3. Target Temperatures & Sync (Always sync Driver & Passenger per user preference)
        applyZoneTempWithExplicitUnit(true, theme.driverTemp, theme.celsius, snap);
        paceSleep();
        enableDualZoneSync();
        applyZoneTempWithExplicitUnit(false, theme.driverTemp, theme.celsius, snap);
        paceSleep();

        // 4. Vent, Recirculate & Defrost
        if (theme.autoLevel == 0 && theme.ventMode >= 1 && theme.ventMode <= 5) {
            callDatcSetVr(6, theme.ventMode, 0);
            paceSleep();
        }
        callDatcSetVr(11, theme.recirculate ? 1 : 2, 0);
        callDatcSetVr(10, theme.frontDefrost ? 1 : 0, 0);
        callIntWrite1(SVC_VR, IFACE_VR, "set_RrDefogger_Cmd_AVN", theme.rearDefrost ? 1 : 0);
        paceSleep();

        // 5. Steering Wheel Heat & Multi-Zone Seats
        if (!remoteMode) {
            setWheelHeat(theme.wheelHeat, snap);
            paceSleep();

            if (theme.driverSeatVent > 0) {
                setSeatClimate("driver", false, theme.driverSeatVent, false, snap);
            } else {
                setSeatClimate("driver", true, theme.driverSeatHeat, isSeatTwoStep("driver"), snap);
            }
            if (theme.passengerSeatVent > 0) {
                setSeatClimate("passenger", false, theme.passengerSeatVent, false, snap);
            } else {
                setSeatClimate("passenger", true, theme.passengerSeatHeat, isSeatTwoStep("passenger"), snap);
            }
            if (theme.rearLeftSeatHeat >= 0) {
                setSeatClimate("rear_left", true, theme.rearLeftSeatHeat, isSeatTwoStep("rear_left"), snap);
            }
            if (theme.rearRightSeatHeat >= 0) {
                setSeatClimate("rear_right", true, theme.rearRightSeatHeat, isSeatTwoStep("rear_right"), snap);
            }
        }

        if (remReply != null && remReply.ok) {
            String dispTemp = convertTempLabelForDisplay(theme.driverTemp, theme.celsius, isCelsius(snap));
            String unit = isCelsius(snap) ? "\u00B0C" : "\u00B0F";
            String tempBadge = dispTemp != null ? (dispTemp + unit) : "";
            return new CommandReply(true, "Remote Profile Applied: " + theme.name
                    + (tempBadge.isEmpty() ? " (Auto 1)" : (" (" + tempBadge + " \u00B7 Auto 1)")));
        }

        return new CommandReply(hvacOk,
                hvacOk ? ("Applied Climate Theme: " + theme.name) : "DATC did not acknowledge HVAC start");
    }

    private static void paceSleep() {
        try {
            Thread.sleep(60L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private CommandReply setWheelHeat(boolean wantOn, Map<String, String> snap) {
        boolean bcmOk = false;
        if (shouldUseRemoteClimate(snap)) {
            boolean defogNow = bcmDefogger == 1 || (snap != null && "1".equals(snap.get("defogger")));
            bcmOk = applyBcmTeleHeatingOnly(defogNow, wantOn);
            if (bcmOk) {
                bcmWheelHeat = wantOn ? 1 : 0;
                if (snap != null) snap.put("wheel_heat", wantOn ? "1" : "0");
            }
        }
        CommandReply hmg = tryHmgCommandChecked("wheel_heat", wantOn ? "on" : "off");
        if (hmg != null) {
            if (hmg.ok) {
                bcmWheelHeat = wantOn ? 1 : 0;
                return new CommandReply(true, "Steering Wheel Heat " + (wantOn ? "ON" : "OFF"));
            }
            if (bcmOk) {
                return new CommandReply(true, "Steering Wheel Heat " + (wantOn ? "ON" : "OFF"));
            }
            return hmg;
        }
        boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_StrgWhlHeated_Cmd_AVN", wantOn ? 1 : 0) || bcmOk;
        if (ok) bcmWheelHeat = wantOn ? 1 : 0;
        return new CommandReply(ok, ok ? ("Steering Wheel Heat " + (wantOn ? "ON" : "OFF")) : "Wheel heat unavailable");
    }

    private void applyZoneTempWithExplicitUnit(boolean driver, String rawTemp, boolean themeIsCelsius, Map<String, String> snap) {
        String a = rawTemp == null ? "" : rawTemp.trim().toUpperCase(Locale.US);
        boolean carCelsius = isCelsius(snap);
        if ("LO".equals(a) || "LOW".equals(a)) {
            applyTempExact(driver, carCelsius, carCelsius ? 17.0f : 62f);
            return;
        }
        if ("HI".equals(a) || "HIGH".equals(a)) {
            applyTempExact(driver, carCelsius, carCelsius ? 32.0f : 90f);
            return;
        }
        float v = parseFloatSafe(a, themeIsCelsius ? 21.0f : 70.0f);
        if (themeIsCelsius && !carCelsius) {
            v = Math.round(v * 1.8f + 32.0f);
        } else if (!themeIsCelsius && carCelsius) {
            v = Math.round(((v - 32.0f) * 5.0f / 9.0f) * 2.0f) / 2.0f;
        }
        applyTempExact(driver, carCelsius, v);
    }

    private void ensureVrSignalsEnabled() {
        if (vrSignalsEnabled) return;
        // Mirrors OEM HKMC_VoiceHandler FeatureVehicle.AW() CAN TX enable flags
        boolean any = callIntWrite1(SVC_VR, IFACE_VR, "All_Window_Req", 1);
        callIntWrite1(SVC_VR, IFACE_VR, "All_Sunroof_Req", 1);
        callIntWrite1(SVC_VR, IFACE_VR, "All_TrunkTl_Req", 1);
        callIntWrite1(SVC_VR, IFACE_VR, "All_PTGCNTL_Req", 1);
        any = callIntWrite1(SVC_VR, IFACE_VR, "set_TxSignal_Exist_Req", 1) || any;
        if (any) {
            vrSignalsEnabled = true;
            sleepMs(60L);
        }
    }

    private void refreshTrunkVrSignals() {
        callIntWrite1(SVC_VR, IFACE_VR, "All_TrunkTl_Req", 1);
        callIntWrite1(SVC_VR, IFACE_VR, "All_PTGCNTL_Req", 1);
        boolean any = callIntWrite1(SVC_VR, IFACE_VR, "set_TxSignal_Exist_Req", 1);
        if (any && !vrSignalsEnabled) {
            callIntWrite1(SVC_VR, IFACE_VR, "All_Window_Req", 1);
            callIntWrite1(SVC_VR, IFACE_VR, "All_Sunroof_Req", 1);
            vrSignalsEnabled = true;
        }
        sleepMs(80L);
    }

    private void enableDualZoneSync() {
        if (!callDatcSetVr(22, 1, 0)) {
            callDatcSetFront(22, 1);
        }
    }

    private void syncDualZoneTemps(Map<String, String> snap, String explicitDriverTemp) {
        enableDualZoneSync();
        String target = (explicitDriverTemp != null && !explicitDriverTemp.trim().isEmpty())
                ? explicitDriverTemp.trim()
                : (snap == null ? null : snap.get("climate.driver_temp"));
        if (target != null && !target.isEmpty() && !"--".equals(target) && !"OFF".equalsIgnoreCase(target)) {
            applyZoneTempValue(false, target, snap);
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private CommandReply setAllWindows(int vrCmd, int bcmWindowCmd, String okLabel) {
        ensureVrSignalsEnabled();
        boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Window_ALL_UPDOWN", vrCmd);
        if (!ok) {
            ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd",
                    bcmWindowCmd, bcmWindowCmd, 7, bcmWindowCmd, bcmWindowCmd);
        }
        return new CommandReply(ok, ok ? okLabel : "Window control unavailable");
    }

    private CommandReply setSingleWindow(String vrMethod, int vrVal, int drv, int ast, int rl, int rr, String okLabel) {
        ensureVrSignalsEnabled();
        boolean ok = callIntWrite1(SVC_VR, IFACE_VR, vrMethod, vrVal);
        if (!ok) {
            ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd", drv, ast, 7, rl, rr);
        }
        return new CommandReply(ok, ok ? okLabel : "Window control unavailable");
    }

    private boolean isCelsius(Map<String, String> snap) {
        String u = snap == null ? null : snap.get("climate.temp_unit");
        if ("C".equals(u)) return true;
        if ("F".equals(u)) return false;
        return lastKnownCelsius != null && lastKnownCelsius.booleanValue();
    }

    private CommandReply applyZoneTempValue(boolean driver, String rawArg, Map<String, String> snap) {
        String a = rawArg == null ? "" : rawArg.trim().toUpperCase(Locale.US);
        boolean celsius = isCelsius(snap);
        if ("LO".equals(a) || "LOW".equals(a)) {
            boolean ok = applyTempExact(driver, celsius, celsius ? 17.0f : 62f);
            return new CommandReply(ok, ok ? ((driver ? "Driver" : "Pass") + " Temp -> LO") : "DATC unavailable");
        }
        if ("HI".equals(a) || "HIGH".equals(a)) {
            boolean ok = applyTempExact(driver, celsius, celsius ? 32.0f : 90f);
            return new CommandReply(ok, ok ? ((driver ? "Driver" : "Pass") + " Temp -> HI") : "DATC unavailable");
        }
        float val = parseFloatSafe(a, celsius ? 22.0f : 72.0f);
        if (celsius && val > 40.0f) {
            val = Math.round(((val - 32.0f) * 5.0f / 9.0f) * 2.0f) / 2.0f;
        } else if (!celsius && val <= 40.0f) {
            val = Math.round(val * 1.8f + 32.0f);
        }
        boolean ok = applyTempExact(driver, celsius, val);
        String label = celsius ? (formatHalf(val) + "\u00B0C") : (Math.round(val) + "\u00B0F");
        return new CommandReply(ok, ok ? ((driver ? "Driver Temp -> " : "Pass Temp -> ") + label) : "DATC unavailable");
    }

    private boolean applyTempExact(boolean driver, boolean celsius, float targetVal) {
        if (celsius) {
            float clamped = Math.max(17.0f, Math.min(32.0f, Math.round(targetVal * 2.0f) / 2.0f));
            int rawC = Math.max(6, Math.min(36, Math.round(clamped * 2.0f) - 28));
            if (callDatcSetFront(driver ? 28 : 29, rawC)) return true;
            return driver && callDatcSetVr(2, Math.round(clamped), 1);
        }
        int clampedF = Math.max(62, Math.min(90, Math.round(targetVal)));
        int rawF = Math.max(6, Math.min(34, clampedF - 56));
        if (callDatcSetFront(driver ? 30 : 31, rawF)) return true;
        return driver && callDatcSetVr(2, clampedF, 2);
    }

    private CommandReply adjustZoneTempSynced(boolean up, Map<String, String> snap) {
        boolean celsius = isCelsius(snap);
        boolean remoteMode = shouldUseRemoteClimate(snap);
        String curStr = snap.get("climate.driver_temp");
        if ((curStr == null || curStr.isEmpty() || "--".equals(curStr)) && lastRemoteTargetTemp != null) {
            curStr = convertTempLabelForDisplay(lastRemoteTargetTemp,
                    lastRemoteTargetCelsius != null && lastRemoteTargetCelsius.booleanValue(), celsius);
        }
        boolean okStep = callDatcSetFront(50, up ? 1 : 2);
        if (celsius) {
            float maxC = remoteMode ? 27.0f : 32.0f;
            float curC = "LO".equalsIgnoreCase(curStr) ? 17.0f
                    : ("HI".equalsIgnoreCase(curStr) ? maxC : parseFloatSafe(curStr, 22.0f));
            float nextC = Math.max(17.0f, Math.min(maxC, Math.round((curC + (up ? 0.5f : -0.5f)) * 2.0f) / 2.0f));
            String nextStr = formatHalf(nextC);
            boolean ok = applyTempExact(true, true, nextC) || okStep;
            enableDualZoneSync();
            applyTempExact(false, true, nextC);
            if (remoteMode) {
                rememberRemoteTemp(nextStr, true);
                snap.put("climate.driver_temp", nextStr);
                snap.put("climate.passenger_temp", nextStr);
                if ("1".equals(snap.get("climate.on")) || remoteClimateActive) {
                    CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, nextStr, null, null, null);
                    if (rem.ok) return rem;
                }
                return new CommandReply(true, "Remote Target Temp -> " + nextStr + "\u00B0C");
            }
            return new CommandReply(ok, ok ? ("Cabin Temp -> " + nextStr + "\u00B0C (Synced)") : "DATC unavailable");
        }
        int maxF = remoteMode ? 82 : 90;
        int curF = "LO".equalsIgnoreCase(curStr) ? 62
                : ("HI".equalsIgnoreCase(curStr) ? maxF : Math.round(parseFloatSafe(curStr, 72.0f)));
        int nextF = Math.max(62, Math.min(maxF, curF + (up ? 1 : -1)));
        String nextStr = String.valueOf(nextF);
        boolean ok = applyTempExact(true, false, nextF) || okStep;
        enableDualZoneSync();
        applyTempExact(false, false, nextF);
        if (remoteMode) {
            rememberRemoteTemp(nextStr, false);
            snap.put("climate.driver_temp", nextStr);
            snap.put("climate.passenger_temp", nextStr);
            if ("1".equals(snap.get("climate.on")) || remoteClimateActive) {
                CommandReply rem = startBluelinkRemoteClimateFromSnapshot(snap, nextStr, null, null, null);
                if (rem.ok) return rem;
            }
            return new CommandReply(true, "Remote Target Temp -> " + nextStr + "\u00B0F");
        }
        return new CommandReply(ok, ok ? ("Cabin Temp -> " + nextF + "\u00B0F (Synced)") : "DATC unavailable");
    }

    private boolean isSeatTwoStep(String seat) {
        String optMethod = "driver".equals(seat) ? "getDrvSeatHeatOption"
                : ("passenger".equals(seat) ? "getAstSeatHeatOption"
                : ("rear_left".equals(seat) ? "getRLSeatHeatOption" : "getRRSeatHeatOption"));
        Integer opt = callIntNoArg(SVC_BCM, IFACE_BCM, optMethod, false);
        return opt != null && opt == 1;
    }

    private CommandReply setSeatClimate(String seatName, boolean heat, int level, boolean twoStep, Map<String, String> snap) {
        int clamped = Math.max(0, Math.min(twoStep && heat ? 2 : 3, level));
        String levelWord = clamped == 0 ? "off"
                : (twoStep && heat ? (clamped == 1 ? "low" : "high")
                : (clamped == 1 ? "low" : (clamped == 2 ? "medium" : "high")));
        String prettySeat = "driver".equals(seatName) ? "Driver "
                : ("passenger".equals(seatName) ? "Passenger "
                : ("rear_left".equals(seatName) ? "Rear Left " : "Rear Right "));

        int usmCode = encodeBcmSeatOp(heat, clamped, twoStep);
        boolean bcmOk = false;
        if (shouldUseRemoteClimate(snap)) {
            bcmOk = applyBcmSingleSeatOp(seatName, usmCode);
        }

        CommandReply hmg = tryHmgCommandChecked(heat ? "seat_heat" : "seat_vent", seatName, levelWord);
        if (hmg != null) {
            if (hmg.ok || bcmOk) {
                return new CommandReply(true, prettySeat + (heat ? "Heat -> " : "Vent -> ") + levelWord.toUpperCase(Locale.US));
            }
            return hmg;
        }
        String usmMethod = "driver".equals(seatName) ? "setFrontSeatHeatDrvStep"
                : ("passenger".equals(seatName) ? "setFrontSeatHeatAstStep"
                : ("rear_left".equals(seatName) ? "setRearSeatHeatLeftStep" : "setRearSeatHeatRightStep"));
        boolean ok = callIntWrite1(SVC_USM, IFACE_USM, usmMethod, usmCode) || bcmOk;
        return new CommandReply(ok, ok
                ? (prettySeat + (heat ? "Heat -> " : "Vent -> ") + levelWord.toUpperCase(Locale.US))
                : "Seat command unavailable");
    }

    private CommandReply tryHmgCommandChecked(String name, String... args) {
        try {
            String res = HmgApi.connect().timeout(1200).command(name, args);
            return new CommandReply(true, res);
        } catch (Throwable err) {
            String msg = err.getMessage();
            if (msg != null && msg.trim().toLowerCase(Locale.US).startsWith("refused:")) {
                return new CommandReply(false, msg.trim());
            }
            return null;
        }
    }

    private synchronized void ensureBcmListenerRegistered() {
        IBinder b = getBinder(SVC_BCM);
        if (b == null || !b.isBinderAlive()) {
            registeredBcmService = null;
            return;
        }
        if (registeredBcmService == b && bcmListenerBinder != null) return;
        try {
            txBcmDoorAll = resolveListenerTx(IFACE_BCM_LISTENER, "onDoorAllStateChanged", FB_BCM_ON_DOOR_ALL);
            txBcmDoorLock = resolveListenerTx(IFACE_BCM_LISTENER, "onDoorLockStateChanged", FB_BCM_ON_DOOR_LOCK);
            txBcmSeatHeat = resolveListenerTx(IFACE_BCM_LISTENER, "onSeatHeaterStatusChanged", FB_BCM_ON_SEAT_HEAT);
            txBcmSeatVent = resolveListenerTx(IFACE_BCM_LISTENER, "onSeatVentilationStatusChanged", FB_BCM_ON_SEAT_VENT);
            txBcmParkBrake = resolveListenerTx(IFACE_BCM_LISTENER, "onParkBrakeStatusChanged", FB_BCM_ON_PARK_BRAKE);
            txBcmGearInfo = resolveListenerTx(IFACE_BCM_LISTENER, "onGearStatusInfoChanged", FB_BCM_ON_GEAR_INFO);
            txBcmRemoteHeat = resolveListenerTx(IFACE_BCM_LISTENER, "onRemoteTeleHeatingStatusChanged", FB_BCM_ON_REMOTE_HEAT);
            txBcmWindowOpen = resolveListenerTx(IFACE_BCM_LISTENER, "onWindowOpenStateChanged", FB_BCM_ON_WINDOW_OPEN);
            txBcmDigitalKey = resolveListenerTx(IFACE_BCM_LISTENER, "onDigitalKeyDataChanged", FB_BCM_ON_DIGITAL_KEY);
            txBcmModemInfo = resolveListenerTx(IFACE_BCM_LISTENER, "onModemInfoChanged", FB_BCM_ON_MODEM_INFO);
            txBcmBattCond = resolveListenerTx(IFACE_BCM_LISTENER, "onBatteryPreconditioning", FB_BCM_ON_BATT_COND);
            txBcmSunroof = resolveListenerTx(IFACE_BCM_LISTENER, "onsunroofOpen", FB_BCM_ON_SUNROOF_OPEN);
            txBcmCdm = resolveListenerTx(IFACE_BCM_LISTENER, "onCDMChanged", FB_BCM_ON_CDM_CHANGED);

            if (bcmListenerToken == null) bcmListenerToken = new Binder();
            if (bcmListenerBinder == null) {
                bcmListenerBinder = new Binder() {
                    {
                        attachInterface(null, IFACE_BCM_LISTENER);
                    }

                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                        if (code == INTERFACE_TRANSACTION) {
                            if (reply != null) reply.writeString(IFACE_BCM_LISTENER);
                            return true;
                        }
                        try {
                            if (code == txBcmDigitalKey || code == txBcmModemInfo) {
                                data.enforceInterface(IFACE_BCM_LISTENER);
                                int len = data.readInt();
                                if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                                    reply.writeNoException();
                                    reply.writeByteArray(len >= 0 && len <= MAX_OUT_ARRAY_BYTES ? new byte[len] : null);
                                }
                                return true;
                            }
                            if (isHandledBcmCode(code)) {
                                data.enforceInterface(IFACE_BCM_LISTENER);
                                handleBcmCallback(code, data);
                            }
                        } catch (Throwable ignored) {
                        }
                        if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                            answerSafeReply(data, reply);
                        }
                        return true;
                    }
                };
            }
            TxEntry regTx = resolveTxByName(IFACE_BCM, "registerBCMListener");
            int regCode = regTx != null ? regTx.code : 1;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(IFACE_BCM);
                data.writeStrongBinder(bcmListenerBinder);
                data.writeStrongBinder(bcmListenerToken);
                b.transact(regCode, data, reply, 0);
                reply.readException();
                registeredBcmService = b;
            } finally {
                data.recycle();
                reply.recycle();
            }
            callVoidNoArg(SVC_BCM, IFACE_BCM, "getDoorLockStatus");
            callVoidNoArg(SVC_BCM, IFACE_BCM, "getHoodStatusInfo");
        } catch (Throwable ignored) {
        }
    }

    private synchronized void ensureDatcListenerRegistered() {
        IBinder b = getBinder(SVC_DATC);
        if (b == null || !b.isBinderAlive()) {
            registeredDatcService = null;
            return;
        }
        if (registeredDatcService == b && datcListenerBinder != null) return;
        try {
            ClassLoader cl = getInterfaceLoader(false);
            if (datcStatusCreator == null) {
                Class<?> stCls = Class.forName(CLS_DATC_STATUS, false, cl);
                Field creatorField = stCls.getField("CREATOR");
                datcStatusCreator = creatorField.get(null);
                mAmbientF = stCls.getMethod("getAmbientTempF");
                mAmbientC = stCls.getMethod("getAmbientTempC");
                mAmbientStateC = stCls.getMethod("getAmbientTempStateC");
            }
            txDatcStatus = resolveListenerTx(IFACE_DATC_LISTENER, "onDatcStatus", 1);
            if (datcListenerToken == null) datcListenerToken = new Binder();
            if (datcListenerBinder == null) {
                datcListenerBinder = new Binder() {
                    {
                        attachInterface(null, IFACE_DATC_LISTENER);
                    }

                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                        if (code == INTERFACE_TRANSACTION) {
                            if (reply != null) reply.writeString(IFACE_DATC_LISTENER);
                            return true;
                        }
                        try {
                            if (code == txDatcStatus && datcStatusCreator instanceof android.os.Parcelable.Creator) {
                                data.enforceInterface(IFACE_DATC_LISTENER);
                                if (data.readInt() != 0) {
                                    Object st = ((android.os.Parcelable.Creator<?>) datcStatusCreator).createFromParcel(data);
                                    float f = ((Float) mAmbientF.invoke(st)).floatValue();
                                    float cc = ((Float) mAmbientC.invoke(st)).floatValue();
                                    int stateC = ((Integer) mAmbientStateC.invoke(st)).intValue();
                                    if (!Float.isNaN(f) && f >= -60f && f <= 160f) {
                                        datcOutsideF10 = Math.round(f * 10f);
                                        datcOutsideC10 = (stateC == 255 || Float.isNaN(cc))
                                                ? Integer.MIN_VALUE
                                                : Math.round(cc * 10f);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                            answerSafeReply(data, reply);
                        }
                        return true;
                    }
                };
            }
            TxEntry regTx = resolveTxByName(IFACE_DATC, "registerDATCListener");
            if (regTx == null) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(IFACE_DATC);
                data.writeStrongBinder(datcListenerBinder);
                data.writeStrongBinder(datcListenerToken);
                b.transact(regTx.code, data, reply, 0);
                reply.readException();
                registeredDatcService = b;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void answerSafeReply(Parcel data, Parcel reply) {
        reply.writeNoException();
        int end = data.dataSize();
        if (end < 4) return;
        data.setDataPosition(end - 4);
        int n = data.readInt();
        if (n >= 0 && n <= MAX_OUT_ARRAY_BYTES) {
            reply.writeByteArray(new byte[n]);
        }
    }

    private boolean isHandledBcmCode(int code) {
        return code == txBcmDoorAll || code == txBcmDoorLock || code == txBcmSeatHeat
                || code == txBcmSeatVent || code == txBcmParkBrake || code == txBcmRemoteHeat
                || code == txBcmWindowOpen || code == txBcmBattCond || code == txBcmSunroof
                || code == txBcmCdm;
    }

    private void handleBcmCallback(int code, Parcel data) {
        if (code == txBcmDoorAll) {
            bcmUnlockFl = data.readInt();
            bcmUnlockFr = data.readInt();
            bcmUnlockRl = data.readInt();
            bcmUnlockRr = data.readInt();
            bcmOpenFl = data.readInt();
            bcmOpenFr = data.readInt();
            bcmOpenRl = data.readInt();
            bcmOpenRr = data.readInt();
            bcmTrunkOpen = data.readInt();
            bcmHoodOpen = data.readInt();
        } else if (code == txBcmDoorLock) {
            bcmLockState = data.readInt();
        } else if (code == txBcmSeatHeat) {
            bcmSeatHeatDrv = data.readInt();
            bcmSeatHeatAst = data.readInt();
            bcmSeatHeatRl = data.readInt();
            bcmSeatHeatRr = data.readInt();
        } else if (code == txBcmSeatVent) {
            bcmSeatVentDrv = data.readInt();
            bcmSeatVentAst = data.readInt();
        } else if (code == txBcmParkBrake) {
            bcmParkBrake = data.readInt();
        } else if (code == txBcmRemoteHeat) {
            bcmWheelHeat = data.readInt();
            bcmDefogger = data.readInt();
        } else if (code == txBcmWindowOpen) {
            bcmWdwFl = data.readInt();
            bcmWdwFr = data.readInt();
            bcmWdwRl = data.readInt();
            bcmWdwRr = data.readInt();
        } else if (code == txBcmBattCond) {
            bcmBattCond = data.readInt();
        } else if (code == txBcmSunroof) {
            bcmSunroofOpen = data.readInt();
        } else if (code == txBcmCdm) {
            bcmCdmState = data.readInt();
        }
    }

    private int resolveHevFlagField(String fieldName, int fallbackVal) {
        try {
            Class<?> flagCls = Class.forName("com.hkmc.hev.HEVManager$HEVFlag", false, getInterfaceLoader(false));
            Field f = flagCls.getField(fieldName);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallbackVal;
        }
    }

    private Integer pollHevEvReady() {
        try {
            if (cachedHevVcuRdyFlag == null) {
                cachedHevVcuRdyFlag = Integer.valueOf(resolveHevFlagField("VCU_RDY", 0x2d));
            }
            Integer vcuRdy = callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevVcuRdyFlag.intValue(), false);
            if (vcuRdy != null && vcuRdy >= 0 && vcuRdy != 0xFFFF) {
                return vcuRdy;
            }
            if (cachedHevEvRdyFlag == null) {
                cachedHevEvRdyFlag = Integer.valueOf(resolveHevFlagField("EV_RDY", 0x2c));
            }
            return callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevEvRdyFlag.intValue(), false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer pollHevUtilityMode() {
        try {
            if (cachedHevUtilModeFlag == null) {
                cachedHevUtilModeFlag = Integer.valueOf(resolveHevFlagField("VCU_UTILMODUSMSTA", 0x50));
            }
            return callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevUtilModeFlag.intValue(), false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer pollHevUtilityWarn() {
        try {
            if (cachedHevUtilWarnFlag == null) {
                cachedHevUtilWarnFlag = Integer.valueOf(resolveHevFlagField("VCU_UTILMODWRNMSGSTA", 0x51));
            }
            return callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevUtilWarnFlag.intValue(), false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer pollHevBattConditioningSetting() {
        try {
            if (cachedHevBattCondFlag == null) {
                Class<?> flagCls = Class.forName("com.hkmc.hev.HEVManager$HEVFlag", false, getInterfaceLoader(false));
                Field f = flagCls.getField("EV_BATTERYCONDITIONING");
                cachedHevBattCondFlag = Integer.valueOf(f.getInt(null));
            }
            return callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevBattCondFlag.intValue(), false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private synchronized void ensureClusterListenerAndRequestBattInfo() {
        IBinder b = getBinder(SVC_CLUSTER);
        if (b == null || !b.isBinderAlive()) {
            registeredClusterService = null;
            return;
        }
        if (registeredClusterService != b || clusterListenerBinder == null) {
            try {
                txClusterBattInfo = resolveListenerTx(IFACE_CLUSTER_LISTENER, "onHandleBatteryInfoResp", FB_CLUSTER_ON_BATT_INFO);
                if (clusterListenerBinder == null) {
                    clusterListenerBinder = new Binder() {
                        {
                            attachInterface(null, IFACE_CLUSTER_LISTENER);
                        }

                        @Override
                        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                            if (code == INTERFACE_TRANSACTION) {
                                if (reply != null) reply.writeString(IFACE_CLUSTER_LISTENER);
                                return true;
                            }
                            try {
                                if (code == txClusterBattInfo) {
                                    data.enforceInterface(IFACE_CLUSTER_LISTENER);
                                    int[] v = data.createIntArray();
                                    if (v != null && v.length >= 8) {
                                        if (v[1] >= -40 && v[1] <= 60) {
                                            clusterBattTempMinC = v[1];
                                        }
                                        if (v[5] == 0 || v[5] == 1) {
                                            clusterBattCondInd = v[5];
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                                answerSafeReply(data, reply);
                            }
                            return true;
                        }
                    };
                }
                TxEntry addTx = resolveTxByName(IFACE_CLUSTER, "addNavigationClusterListener");
                if (addTx != null) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(IFACE_CLUSTER);
                        data.writeStrongBinder(clusterListenerBinder);
                        b.transact(addTx.code, data, reply, 0);
                        reply.readException();
                        registeredClusterService = b;
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastClusterBattReqMs >= 5000L) {
            lastClusterBattReqMs = now;
            callVoidNoArg(SVC_CLUSTER, IFACE_CLUSTER, "requestBatteryInfoReq");
        }
    }

    private int resolveListenerTx(String ifaceName, String methodName, int fallback) {
        try {
            Class<?> stubCls = Class.forName(ifaceName + "$Stub", false, getInterfaceLoader(false));
            Field f = stubCls.getDeclaredField("TRANSACTION_" + methodName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private synchronized IBinder getBinder(String svcName) {
        IBinder b = binderCache.get(svcName);
        if (b != null && b.isBinderAlive()) return b;
        try {
            if (mGetService == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                mGetService = sm.getMethod("getService", String.class);
            }
            Object raw = mGetService.invoke(null, svcName);
            if (raw instanceof IBinder) {
                IBinder fresh = (IBinder) raw;
                binderCache.put(svcName, fresh);
                return fresh;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private synchronized ClassLoader getInterfaceLoader(boolean useHcanJar) {
        if (!useHcanJar) return DirectCarServiceBridge.class.getClassLoader();
        if (hscanLoader != null) return hscanLoader;
        ClassLoader base = DirectCarServiceBridge.class.getClassLoader();
        try {
            Class.forName(IFACE_HSCAN, false, base);
            hscanLoader = base;
            return hscanLoader;
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(JAR_HSCAN);
            if (f.isFile()) {
                hscanLoader = new dalvik.system.PathClassLoader(JAR_HSCAN, base);
                return hscanLoader;
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    private synchronized TxEntry resolveTxByName(String iface, String method) {
        String key = iface + "#ANY#" + method;
        TxEntry cached = txCache.get(key);
        if (cached != null) return cached.code == MISSING_TX ? null : cached;
        try {
            ClassLoader cl = getInterfaceLoader(false);
            Class<?> stubCls = Class.forName(iface + "$Stub", false, cl);
            Field txField = stubCls.getDeclaredField("TRANSACTION_" + method);
            txField.setAccessible(true);
            TxEntry entry = new TxEntry(txField.getInt(null), void.class);
            txCache.put(key, entry);
            return entry;
        } catch (Throwable t) {
            txCache.put(key, new TxEntry(MISSING_TX, void.class));
            return null;
        }
    }

    private synchronized TxEntry resolveTx(String iface, String method, boolean useHcanJar, Class<?>... expectedParams) {
        String key = iface + "#" + method + "#" + paramSig(expectedParams);
        TxEntry cached = txCache.get(key);
        if (cached != null) return cached.code == MISSING_TX ? null : cached;
        try {
            ClassLoader cl = getInterfaceLoader(useHcanJar);
            Class<?> ifaceCls = Class.forName(iface, false, cl);
            Method match = null;
            for (Method m : ifaceCls.getMethods()) {
                if (!m.getName().equals(method)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != expectedParams.length) continue;
                boolean same = true;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i] != expectedParams[i]) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    match = m;
                    break;
                }
            }
            if (match == null) {
                txCache.put(key, new TxEntry(MISSING_TX, void.class));
                return null;
            }
            Class<?> stubCls = Class.forName(iface + "$Stub", false, cl);
            Field txField = stubCls.getDeclaredField("TRANSACTION_" + method);
            txField.setAccessible(true);
            TxEntry entry = new TxEntry(txField.getInt(null), match.getReturnType());
            txCache.put(key, entry);
            return entry;
        } catch (Throwable t) {
            txCache.put(key, new TxEntry(MISSING_TX, void.class));
            return null;
        }
    }

    private Integer callIntNoArg(String svc, String iface, String method, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callIntArgInt(String svc, String iface, String method, int arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callNumericNoArgAsFloat(String svc, String iface, String method, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            if (tx.returnType == float.class) return Float.valueOf(reply.readFloat());
            if (tx.returnType == double.class) return Float.valueOf((float) reply.readDouble());
            return Float.valueOf((float) reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callIntArgByte(String svc, String iface, String method, byte arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, byte.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeByte(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callFloatArgByte(String svc, String iface, String method, byte arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, byte.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeByte(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            if (tx.returnType == float.class) return Float.valueOf(reply.readFloat());
            return Float.valueOf((float) reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callDatcStatus(int flag) {
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "getVRClimateStatus", false, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(flag);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Float.valueOf(reply.readFloat());
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private CommandReply startDirectBatteryConditioning(Map<String, String> snap, String hmgReason) {
        if ("1".equals(snap.get("hv.conditioning"))) {
            // Attempt to enable EV Battery Conditioning option (2 = ON) if disabled in EV Settings
            callIntWrite1(SVC_HEV, IFACE_HEV, "setBatteryConditioning", 2);
            sleepMs(60L);
        }
        boolean okRoute = callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRouteDis", DIRECT_COND_ROUTE_KM);
        boolean okTime = callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRemainTime", DIRECT_COND_ROUTE_MIN);
        sleepMs(15L);
        boolean okStation = callIntWrite2(SVC_HEV, IFACE_HEV, "setBattCondEvStation", 2, 3);
        if (!(okRoute && okTime && okStation)) {
            return new CommandReply(false, hmgReason != null && !hmgReason.isEmpty()
                    ? hmgReason
                    : "Direct IHEVService preconditioning unavailable");
        }
        long now = System.currentTimeMillis();
        directCondActive = true;
        directCondSawIndicator = false;
        directCondTerminalState = null;
        directCondStartMs = now;
        lastDirectCondKeepMs = now;
        snap.put("conditioning.state", "waiting");
        return new CommandReply(true, "Preconditioning Sent (Direct HEV Bypass \u00B7 9km/18m)");
    }

    private void stopDirectBatteryConditioning() {
        directCondActive = false;
        directCondSawIndicator = false;
        directCondTerminalState = null;
        callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRouteDis", 2047);
        callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRemainTime", 1023);
        callIntWrite2(SVC_HEV, IFACE_HEV, "setBattCondEvStation", 0, 0);
    }

    private void maintainDirectBatteryConditioning(Map<String, String> out) {
        if (!directCondActive) {
            if (directCondTerminalState != null) {
                out.put("conditioning.state", directCondTerminalState);
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean indOn = "1".equals(out.get("hv.conditioning_active"));
        if (indOn) {
            directCondSawIndicator = true;
            out.put("conditioning.state", "active");
        } else if (directCondSawIndicator) {
            stopDirectBatteryConditioning();
            directCondTerminalState = "ended";
            out.put("conditioning.state", "ended");
            return;
        } else if (now - directCondStartMs >= DIRECT_COND_TIMEOUT_MS) {
            stopDirectBatteryConditioning();
            directCondTerminalState = "timed_out";
            out.put("conditioning.state", "timed_out");
            return;
        } else {
            out.put("conditioning.state", "waiting");
        }

        // Refresh ONLY route distance & remaining time every ~4.5s (never repeat setBattCondEvStation)
        if (now - lastDirectCondKeepMs >= DIRECT_COND_KEEP_MS) {
            lastDirectCondKeepMs = now;
            callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRouteDis", DIRECT_COND_ROUTE_KM);
            callIntWrite1(SVC_HEV, IFACE_HEV, "setBattCondRemainTime", DIRECT_COND_ROUTE_MIN);
        }
    }

    private boolean shouldUseRemoteClimate(Map<String, String> snap) {
        String rdy = snap == null ? null : snap.get("hv.ready");
        if (rdy != null) return !"1".equals(rdy);
        Integer liveRdy = pollHevEvReady();
        return liveRdy == null || liveRdy.intValue() != 1;
    }

    private boolean isIg3Active(Map<String, String> snap) {
        if (snap != null && "1".equals(snap.get("hv.ig3"))) return true;
        Integer rly = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getIG3RlyMode", true);
        return rly != null && rly.intValue() != 0;
    }

    private void ensureIg3AwakeForRemoteClimate(Map<String, String> snap) {
        callIntWrite1(SVC_HEV, IFACE_HEV, "setTmuRdy", 1);
        callHscanSendIg3RlyMode((byte) 3); // MONITORING_MODE = 3 on EV
        callHscanTmuIg3DrvState(1);
        callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", true);
        Integer rly = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getIG3RlyMode", true);
        if (rly != null && rly.intValue() == 1) {
            sleepMs(120L);
            return;
        }
        // Poll up to ~1050ms (min 250ms) matching OEM IG3Checker so DATC/BCM wake on IG3 before climate TX
        sleepMs(250L);
        for (int i = 0; i < 5; i++) {
            rly = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getIG3RlyMode", true);
            Boolean eng = callBoolNoArg(SVC_BCM, IFACE_BCM, "getEngineStatus", false);
            if ((rly != null && rly.intValue() == 1) || Boolean.TRUE.equals(eng)) {
                if (snap != null) snap.put("hv.ig3", "1");
                sleepMs(80L);
                return;
            }
            sleepMs(160L);
        }
    }

    private CommandReply startBluelinkRemoteClimateFromSnapshot(
            Map<String, String> snap,
            String explicitTemp,
            Boolean explicitFrontDefrost,
            Boolean explicitRearDefrost,
            Boolean explicitWheelHeat) {
        boolean celsius = isCelsius(snap);
        String targetTemp = explicitTemp;
        boolean targetCelsius = celsius;
        if (targetTemp == null || targetTemp.trim().isEmpty() || "--".equals(targetTemp) || "OFF".equalsIgnoreCase(targetTemp)) {
            String snapTemp = snap == null ? null : snap.get("climate.driver_temp");
            if (snapTemp != null && !snapTemp.isEmpty() && !"--".equals(snapTemp) && !"OFF".equalsIgnoreCase(snapTemp)) {
                targetTemp = snapTemp;
                targetCelsius = celsius;
            } else if (lastRemoteTargetTemp != null) {
                targetTemp = lastRemoteTargetTemp;
                targetCelsius = lastRemoteTargetCelsius != null ? lastRemoteTargetCelsius.booleanValue() : celsius;
            } else {
                targetTemp = celsius ? "21" : "70";
                targetCelsius = celsius;
            }
        }
        boolean frontDef = explicitFrontDefrost != null
                ? explicitFrontDefrost.booleanValue()
                : ((snap != null && "1".equals(snap.get("climate.front_defrost"))) || lastRemoteFrontDefrost);
        Boolean rearDef = explicitRearDefrost != null
                ? explicitRearDefrost
                : ((snap != null && "1".equals(snap.get("defogger"))) || bcmDefogger == 1 ? Boolean.TRUE : null);
        Boolean wheel = explicitWheelHeat != null
                ? explicitWheelHeat
                : ((snap != null && "1".equals(snap.get("wheel_heat"))) || bcmWheelHeat == 1 ? Boolean.TRUE : null);
        int drvOp = snapshotSeatBcmOp(snap, "driver", true);
        int astOp = snapshotSeatBcmOp(snap, "passenger", true);
        return startBluelinkRemoteClimate(snap, targetTemp, targetCelsius, frontDef, rearDef, wheel, drvOp, astOp, 0, 0);
    }

    private int snapshotSeatBcmOp(Map<String, String> snap, String seat, boolean hasVent) {
        if (snap == null) return 0;
        if (hasVent) {
            int vent = parseIntSafe(snap.get("seat_vent." + seat), 0);
            if (vent > 0) return encodeBcmSeatOp(false, vent, false);
        }
        int heat = parseIntSafe(snap.get("seat_heat." + seat), 0);
        if (heat > 0) return encodeBcmSeatOp(true, heat, isSeatTwoStep(seat));
        return 0;
    }

    private CommandReply startBluelinkRemoteClimate(
            Map<String, String> snap,
            String rawTemp,
            boolean inputCelsius,
            boolean frontDefrost,
            Boolean rearDefrost,
            Boolean wheelHeat,
            int drvSeatOp,
            int astSeatOp,
            int rlSeatOp,
            int rrSeatOp) {
        ensureIg3AwakeForRemoteClimate(snap);

        int tempCode = computeBluelinkTempCode(rawTemp, inputCelsius, snap);
        rememberRemoteTemp(rawTemp, inputCelsius);
        lastRemoteFrontDefrost = frontDefrost;

        boolean hscanOk = callHscanImmediatelyAirCon(1, 0, tempCode, frontDefrost);
        callHscanBool1("requestFrontDefrosterOnOff", frontDefrost);

        // Supplementary ITMUService signals
        callIntWrite1(SVC_TMU, IFACE_TMU, "requestFATCTempSet", tempCode);
        boolean tmuFatc = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestFATCOnOff", true);
        boolean tmuAc = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestAirConOnOff", true);

        // Apply BCM Telematics Heating & Seat Profile (ALL_HU_GW_E_02) if any body heat/seat option requested
        boolean anyBody = rearDefrost != null || wheelHeat != null
                || drvSeatOp != 0 || astSeatOp != 0 || rlSeatOp != 0 || rrSeatOp != 0;
        if (anyBody) {
            sleepMs(80L);
            int defogMode = rearDefrost == null ? 3 : (rearDefrost.booleanValue() ? 1 : 0);
            int wheelMode = wheelHeat == null ? 3 : (wheelHeat.booleanValue() ? 1 : 0);
            int teleMode = (defogMode == 1 && wheelMode == 1) ? 1
                    : ((defogMode == 0 && wheelMode == 0) ? 0 : 3);
            int drv = isSeatEquipped("driver") ? drvSeatOp : 0;
            int ast = isSeatEquipped("passenger") ? astSeatOp : 0;
            int rl = isSeatEquipped("rear_left") ? rlSeatOp : 0;
            int rr = isSeatEquipped("rear_right") ? rrSeatOp : 0;
            if (callBcmHeatingSeatProfileCmd(defogMode, wheelMode, 3, drv, ast, rl, rr, teleMode)) {
                if (rearDefrost != null) bcmDefogger = rearDefrost.booleanValue() ? 1 : 0;
                if (wheelHeat != null) bcmWheelHeat = wheelHeat.booleanValue() ? 1 : 0;
            }
        }

        boolean ok = hscanOk || tmuFatc || tmuAc;
        if (ok) {
            remoteClimateActive = true;
            boolean dispC = isCelsius(snap);
            String dispTemp = convertTempLabelForDisplay(rawTemp, inputCelsius, dispC);
            if (snap != null) {
                snap.put("climate.on", "1");
                snap.put("climate.auto", "1");
                snap.put("climate.auto_level", "1");
                snap.put("climate.front_defrost", frontDefrost ? "1" : "0");
                if (dispTemp != null) {
                    snap.put("climate.driver_temp", dispTemp);
                    snap.put("climate.passenger_temp", dispTemp);
                }
            }
            String unit = dispC ? "\u00B0C" : "\u00B0F";
            String label = dispTemp != null ? (dispTemp + unit) : ("code " + tempCode);
            return new CommandReply(true, "Remote Climate ON (" + label
                    + (frontDefrost ? " \u00B7 Defrost" : "") + " \u00B7 Auto 1)");
        }
        return new CommandReply(false, "Bluelink HSCAN climate service unavailable");
    }

    private CommandReply stopBluelinkRemoteClimate(Map<String, String> snap) {
        // 1. Ensure IG3 is awake if needed so DATC/VCU receives the cancel pulse
        Integer ig3 = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getIG3RlyMode", true);
        if (ig3 == null || ig3.intValue() == 0) {
            callIntWrite1(SVC_HEV, IFACE_HEV, "setTmuRdy", 1);
            callHscanSendIg3RlyMode((byte) 3);
            callHscanTmuIg3DrvState(1);
            callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", true);
            sleepMs(200L);
        }

        // 2. OEM RemoteControlCancelClimateMgr rising edge: whereFrom=1, reqType=1 (Cancel), tempSet=0, defrost=false
        boolean riseOk = callHscanImmediatelyAirCon(1, 1, 0, false);

        // 3. Turn off seats, steering wheel heat & rear defogger via BCM telematics profile (equipped=2 OFF, unequipped=15)
        int drvOff = isSeatEquipped("driver") ? 2 : 15;
        int astOff = isSeatEquipped("passenger") ? 2 : 15;
        int rlOff = isSeatEquipped("rear_left") ? 2 : 15;
        int rrOff = isSeatEquipped("rear_right") ? 2 : 15;
        if (callBcmHeatingSeatProfileCmd(0, 0, 3, drvOff, astOff, rlOff, rrOff, 0)) {
            bcmDefogger = 0;
            bcmWheelHeat = 0;
        }
        callHscanBool1("requestFrontDefrosterOnOff", false);

        // 4. Wait ~250ms and poll up to ~900ms for getAirConOnOffStatus() == 0
        sleepMs(250L);
        for (int i = 0; i < 4; i++) {
            Integer acSt = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getAirConOnOffStatus", true);
            if (acSt != null && acSt.intValue() == 0) break;
            sleepMs(160L);
        }

        // 5. OEM RemoteControlCancelClimateMgr falling edge: whereFrom=0, reqType=1, tempSet=0, defrost=false
        boolean fallOk = callHscanImmediatelyAirCon(0, 1, 0, false);
        callHscanBool1("requestAirConUnitControl", false);

        // 6. Legacy ITMUService off signals
        boolean tmuOff = callBoolWrite1(SVC_TMU, IFACE_TMU, "requestFATCOnOff", false)
                || callBoolWrite1(SVC_TMU, IFACE_TMU, "requestAirConOnOff", false);
        callBoolWrite1(SVC_TMU, IFACE_TMU, "requestNoStartAC", false);
        callBoolWrite1(SVC_TMU, IFACE_TMU, "requestNoStartHeater", false);
        callVoidNoArg(SVC_TMU, IFACE_TMU, "requestStopEngine");

        // 7. Tear down IG3 relay (OEM IG3Checker.vW) if ignition is not READY
        if (shouldUseRemoteClimate(snap)) {
            callBoolWrite1(SVC_HEV, IFACE_HEV, "reqEVIG3Change", false);
            sleepMs(150L);
            callHscanSendIg3RlyMode((byte) 0);
            callHscanTmuIg3DrvState(0);
            if (snap != null) snap.put("hv.ig3", "0");
        }

        remoteClimateActive = false;
        lastRemoteFrontDefrost = false;
        if (snap != null) {
            snap.put("climate.on", "0");
            snap.put("climate.fan_speed", "0");
            snap.put("climate.front_defrost", "0");
            snap.put("defogger", "0");
            snap.put("wheel_heat", "0");
        }
        boolean ok = riseOk || fallOk || tmuOff;
        return new CommandReply(ok, ok ? "Remote Climate OFF" : "HSCAN climate cancel unavailable");
    }

    private boolean applyBcmTeleHeatingOnly(boolean rearDefogOn, boolean wheelHeatOn) {
        ensureIg3AwakeForRemoteClimate(null);
        int defog = rearDefogOn ? 1 : 0;
        int wheel = wheelHeatOn ? 1 : 0;
        int tele = (rearDefogOn && wheelHeatOn) ? 1 : ((!rearDefogOn && !wheelHeatOn) ? 0 : 3);
        return callBcmHeatingSeatProfileCmd(defog, wheel, 3, 0, 0, 0, 0, tele);
    }

    private boolean applyBcmSingleSeatOp(String seatName, int usmCode) {
        if (!isSeatEquipped(seatName)) return false;
        ensureIg3AwakeForRemoteClimate(null);
        int drv = "driver".equals(seatName) ? usmCode : 0;
        int ast = "passenger".equals(seatName) ? usmCode : 0;
        int rl = "rear_left".equals(seatName) ? usmCode : 0;
        int rr = "rear_right".equals(seatName) ? usmCode : 0;
        return callBcmHeatingSeatProfileCmd(3, 3, 3, drv, ast, rl, rr, 3);
    }

    private static int encodeBcmSeatOp(boolean heat, int clampedLevel, boolean twoStep) {
        if (clampedLevel <= 0) return 2; // 2 = OFF
        if (!heat) return Math.max(3, Math.min(5, clampedLevel + 2)); // 3=Vent1, 4=Vent2, 5=Vent3
        if (twoStep) return clampedLevel >= 2 ? 8 : 6; // 6=Low, 8=High
        return Math.max(6, Math.min(8, clampedLevel + 5)); // 6=Heat1, 7=Heat2, 8=Heat3
    }

    private boolean isSeatEquipped(String seat) {
        String optMethod = "driver".equals(seat) ? "getDrvSeatHeatOption"
                : ("passenger".equals(seat) ? "getAstSeatHeatOption"
                : ("rear_left".equals(seat) ? "getRLSeatHeatOption" : "getRRSeatHeatOption"));
        Integer opt = callIntNoArg(SVC_BCM, IFACE_BCM, optMethod, false);
        // OEM RemoteControlImmediatelyClimateMgr.ds(opt): equipped if opt > 0 && opt < 10 && opt != 7
        if (opt == null) return "driver".equals(seat) || "passenger".equals(seat);
        int v = opt.intValue();
        return v > 0 && v < 10 && v != 7;
    }

    /**
     * Computes OEM E-GMP Bluelink EV temperature code (strictly 6..26 = 62..82F / 17.0..27.0C).
     * Codes <6 (including 1=LO) or >26 (including 254=HI) are rejected by hscand on EVs!
     */
    private int computeBluelinkTempCode(String rawArg, boolean inputCelsius, Map<String, String> snap) {
        Integer hscanDisp = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getTempDisplyStatus", true);
        boolean hscanFahrenheit = (hscanDisp != null) ? (hscanDisp.intValue() == 1) : !isCelsius(snap);

        String a = rawArg == null ? "" : rawArg.trim().toUpperCase(Locale.US);
        if (a.isEmpty() || "--".equals(a) || "OFF".equals(a)) {
            if (snap != null) {
                String st = snap.get("climate.driver_temp");
                if (st != null && !st.isEmpty() && !"--".equals(st) && !"OFF".equalsIgnoreCase(st)) {
                    a = st.trim().toUpperCase(Locale.US);
                    inputCelsius = isCelsius(snap);
                }
            }
            if ((a.isEmpty() || "--".equals(a) || "OFF".equals(a)) && lastRemoteTargetTemp != null) {
                a = lastRemoteTargetTemp.trim().toUpperCase(Locale.US);
                inputCelsius = lastRemoteTargetCelsius != null && lastRemoteTargetCelsius.booleanValue();
            }
        }
        if ("LO".equals(a) || "LOW".equals(a)) return 6;   // 62°F / 17.0°C
        if ("HI".equals(a) || "HIGH".equals(a)) return 26; // 82°F / 27.0°C

        float val = parseFloatSafe(a, inputCelsius ? 21.0f : 70.0f);
        if (inputCelsius && val > 40.0f) {
            inputCelsius = false;
        } else if (!inputCelsius && val <= 40.0f) {
            inputCelsius = true;
        }

        if (hscanFahrenheit) {
            int tempF = inputCelsius ? Math.round(val * 1.8f + 32.0f) : Math.round(val);
            return Math.max(6, Math.min(26, tempF - 56));
        } else {
            float tempC = inputCelsius ? val : ((val - 32.0f) * 5.0f / 9.0f);
            return Math.max(6, Math.min(26, Math.round((tempC - 14.0f) * 2.0f)));
        }
    }

    private void rememberRemoteTemp(String rawTemp, boolean celsius) {
        if (rawTemp == null) return;
        String clean = convertTempLabelForDisplay(rawTemp, celsius, celsius);
        if (clean != null) {
            lastRemoteTargetTemp = clean;
            lastRemoteTargetCelsius = Boolean.valueOf(celsius);
        }
    }

    private static String convertTempLabelForDisplay(String rawTemp, boolean inputCelsius, boolean displayCelsius) {
        if (rawTemp == null) return null;
        String a = rawTemp.trim().toUpperCase(Locale.US);
        if (a.isEmpty() || "--".equals(a) || "OFF".equals(a)) return null;
        float cVal;
        if ("LO".equals(a) || "LOW".equals(a)) {
            cVal = 17.0f;
        } else if ("HI".equals(a) || "HIGH".equals(a)) {
            cVal = 27.0f;
        } else {
            float v = parseFloatSafe(a, Float.NaN);
            if (Float.isNaN(v)) return null;
            if (inputCelsius && v > 40.0f) inputCelsius = false;
            else if (!inputCelsius && v <= 40.0f) inputCelsius = true;
            cVal = inputCelsius ? v : ((v - 32.0f) * 5.0f / 9.0f);
        }
        if (displayCelsius) {
            float clampedC = Math.max(17.0f, Math.min(27.0f, Math.round(cVal * 2.0f) / 2.0f));
            return formatHalf(clampedC);
        } else {
            int clampedF = Math.max(62, Math.min(82, Math.round(cVal * 1.8f + 32.0f)));
            return String.valueOf(clampedF);
        }
    }

    private static String decodeHscanTempString(Integer codeObj, boolean hscanFahrenheit, boolean displayCelsius) {
        if (codeObj == null) return null;
        int code = codeObj.intValue();
        if (code < 6 || code > 32) return null;
        if (hscanFahrenheit) {
            int tempF = code + 56;
            if (!displayCelsius) return String.valueOf(tempF);
            float tempC = Math.round(((tempF - 32.0f) * 5.0f / 9.0f) * 2.0f) / 2.0f;
            return formatHalf(tempC);
        } else {
            float tempC = 14.0f + (code / 2.0f);
            if (displayCelsius) return formatHalf(tempC);
            return String.valueOf(Math.round(tempC * 1.8f + 32.0f));
        }
    }

    private Boolean callBoolNoArg(String svc, String iface, String method, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Boolean.valueOf(reply.readInt() != 0);
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callHscanSendIg3RlyMode(byte mode) {
        IBinder b = getBinder(SVC_HSCAN);
        TxEntry tx = resolveTx(IFACE_HSCAN, "sendIG3RlyMode", true, byte.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_HSCAN);
            data.writeByte(mode);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_HSCAN, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callHscanTmuIg3DrvState(int state) {
        IBinder b = getBinder(SVC_HSCAN);
        TxEntry tx = resolveTx(IFACE_HSCAN, "requestTMUIG3DrvState", true, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_HSCAN);
            data.writeInt(state);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_HSCAN, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callHscanImmediatelyAirCon(int whereFrom, int reqType, int tempSet, boolean defrostOn) {
        IBinder b = getBinder(SVC_HSCAN);
        TxEntry tx = resolveTx(IFACE_HSCAN, "requestImmediatelyAirCon", true,
                int.class, int.class, int.class, boolean.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_HSCAN);
            data.writeInt(whereFrom);
            data.writeInt(reqType);
            data.writeInt(tempSet);
            data.writeInt(defrostOn ? 1 : 0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_HSCAN, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callHscanBool1(String method, boolean val) {
        IBinder b = getBinder(SVC_HSCAN);
        TxEntry tx = resolveTx(IFACE_HSCAN, method, true, boolean.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_HSCAN);
            data.writeInt(val ? 1 : 0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_HSCAN, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callBcmHeatingSeatProfileCmd(
            int defogHeatMode,
            int strWhlHeatMode,
            int profileId,
            int drvOption,
            int astOption,
            int rlOption,
            int rrOption,
            int teleHeatMode) {
        IBinder b = getBinder(SVC_BCM);
        TxEntry tx = resolveTx(IFACE_BCM, "requestHeatingSeatProfileCmd", false,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_BCM);
            data.writeInt(defogHeatMode);
            data.writeInt(strWhlHeatMode);
            data.writeInt(profileId);
            data.writeInt(drvOption);
            data.writeInt(astOption);
            data.writeInt(rlOption);
            data.writeInt(rrOption);
            data.writeInt(teleHeatMode);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_BCM, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void ensureDatcVrActivityEnabled() {
        if (!datcVrEnabled) {
            if (callBoolWrite1(SVC_DATC, IFACE_DATC, "setVRActivity", true)) {
                datcVrEnabled = true;
                sleepMs(25L);
            }
        }
    }

    private boolean callDatcSetVr(int a, int bArg, int c) {
        ensureDatcVrActivityEnabled();
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "setVRClimateStatus", false, int.class, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(a);
            data.writeInt(bArg);
            data.writeInt(c);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callDatcSetFront(int a, int bArg) {
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "setFrontClimateControl", false, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(a);
            data.writeInt(bArg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callVolumeGet(int streamType) {
        IBinder b = getBinder(SVC_AUDIO);
        TxEntry tx = resolveTx(IFACE_AUDIO, "getVolume", false, int.class, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_AUDIO);
            data.writeInt(streamType);
            data.writeInt(0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_AUDIO, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callVolumeSet(int streamType, int level) {
        IBinder b = getBinder(SVC_AUDIO);
        TxEntry tx = resolveTx(IFACE_AUDIO, "setVolume", false, int.class, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_AUDIO);
            data.writeInt(streamType);
            data.writeInt(level);
            data.writeInt(0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_AUDIO, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callIntWrite1(String svc, String iface, String method, int val) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(val);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callIntWrite2(String svc, String iface, String method, int a, int bArg) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(a);
            data.writeInt(bArg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callIntWrite5(String svc, String iface, String method, int a, int bArg, int c, int d, int e) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, int.class, int.class, int.class, int.class, int.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(a);
            data.writeInt(bArg);
            data.writeInt(c);
            data.writeInt(d);
            data.writeInt(e);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callBoolWrite1(String svc, String iface, String method, boolean val) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, boolean.class);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(val ? 1 : 0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callVoidNoArg(String svc, String iface, String method) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false);
        if (b == null || tx == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized void invalidateBinderIfDead(String svcName, IBinder b) {
        if (b == null || !b.isBinderAlive()) {
            binderCache.remove(svcName);
            if (SVC_BCM.equals(svcName)) registeredBcmService = null;
            else if (SVC_DATC.equals(svcName)) {
                registeredDatcService = null;
                datcVrEnabled = false;
            }
            else if (SVC_CLUSTER.equals(svcName)) registeredClusterService = null;
        }
    }

    private static int parseVentArg(String arg) {
        if (arg == null) return 1;
        String low = arg.trim().toLowerCase(Locale.US);
        if ("face".equals(low) || "1".equals(low)) return 1;
        if ("bi".equals(low) || "bilevel".equals(low) || "2".equals(low)) return 2;
        if ("floor".equals(low) || "3".equals(low)) return 3;
        if ("mix".equals(low) || "floor_def".equals(low) || "4".equals(low)) return 4;
        if ("defrost".equals(low) || "def".equals(low) || "5".equals(low)) return 5;
        return Math.max(1, Math.min(5, parseIntSafe(low, 1)));
    }

    private static int cycleSeatStep(int cur) {
        return cur <= 0 ? 3 : (cur - 1);
    }

    private static void putDatcBool(Map<String, String> out, String key, Float val, int trueVal) {
        if (val != null && !Float.isNaN(val) && val >= 0f) {
            out.put(key, Math.round(val) == trueVal ? "1" : "0");
        }
    }

    private static void putWindowFlag(Map<String, String> out, String key, Integer val) {
        if (val != null && (val == 0 || val == 1)) out.put(key, val == 1 ? "1" : "0");
    }

    private static void putByteFlag(Map<String, String> out, String key, Integer val) {
        if (val != null && val >= 0 && val <= 255) {
            out.put(key, String.valueOf(val));
        }
    }

    private static void putOpenFlag(Map<String, String> out, String key, int raw) {
        if (raw == 0 || raw == 1) out.put(key, raw == 1 ? "1" : "0");
    }

    private static void putLockFlag(Map<String, String> out, String key, int unlockState) {
        if (unlockState == 0 || unlockState == 1) out.put(key, unlockState == 0 ? "1" : "0");
    }

    private static int normalizeVentValue(int raw) {
        if (raw == 0) return 0;
        if (raw >= 2 && raw <= 4) return raw - 1;
        return raw == 1 ? 1 : 0;
    }

    private static int normalizeLegacyHeaterCallback(int raw) {
        if (raw == 0) return 0;
        if (raw >= 2 && raw <= 4) return raw - 1;
        return raw == 1 ? 1 : 0;
    }

    private static void decodeSeatState(Map<String, String> out, String seat, Integer state, boolean hasVent, boolean twoStep) {
        if (state == null || state <= 0 || state == 0xF) return;
        int heat = -1;
        int vent = -1;
        if (state == 2) {
            heat = 0;
            vent = 0;
        } else if (state >= 3 && state <= 5) {
            heat = 0;
            vent = state - 2;
        } else if (state >= 6 && state <= 8) {
            heat = twoStep ? (state == 6 ? 1 : 2) : (state - 5);
            vent = 0;
        }
        if (heat >= 0) out.put("seat_heat." + seat, String.valueOf(heat));
        if (hasVent && vent >= 0) out.put("seat_vent." + seat, String.valueOf(vent));
    }

    private static String ventModeName(int mode) {
        switch (mode) {
            case 1: return "FACE";
            case 2: return "BI-LEVEL";
            case 3: return "FLOOR";
            case 4: return "FLOOR+DEF";
            case 5: return "DEFROST";
            default: return "MODE " + mode;
        }
    }

    private static String paramSig(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : params) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.getName());
        }
        return sb.toString();
    }

    private static String formatCompactFloat(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String formatHalf(float v) {
        float rounded = Math.round(v * 2.0f) / 2.0f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05f) return String.valueOf(Math.round(rounded));
        return String.format(Locale.US, "%.1f", rounded);
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            if (raw == null || raw.isEmpty() || "--".equals(raw) || "OFF".equalsIgnoreCase(raw)) return fallback;
            return Math.round(Float.parseFloat(raw));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float parseFloatSafe(String raw, float fallback) {
        try {
            if (raw == null || raw.isEmpty() || "--".equals(raw) || "OFF".equalsIgnoreCase(raw)) return fallback;
            return Float.parseFloat(raw);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
