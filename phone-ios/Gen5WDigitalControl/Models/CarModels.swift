import Foundation

struct ClimateThemeModel: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var accentColor: Int
    var celsius: Bool
    var driverTemp: String
    var passengerTemp: String
    var syncZones: Bool
    var fanSpeed: Int
    var autoLevel: Int
    var acOn: Bool
    var heatOn: Bool
    var recirculate: Bool
    var ventMode: Int
    var frontDefrost: Bool
    var rearDefrost: Bool
    var wheelHeat: Bool
    var driverSeatHeat: Int
    var passengerSeatHeat: Int
    var rearLeftSeatHeat: Int
    var rearRightSeatHeat: Int
    var driverSeatVent: Int
    var passengerSeatVent: Int

    init(
        id: String = "theme_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10).lowercased(),
        name: String = "Custom AC Profile",
        accentColor: Int = 0xFF0EA5E9,
        celsius: Bool = false,
        driverTemp: String = "70",
        passengerTemp: String = "70",
        syncZones: Bool = true,
        fanSpeed: Int = 3,
        autoLevel: Int = 0,
        acOn: Bool = true,
        heatOn: Bool = false,
        recirculate: Bool = false,
        ventMode: Int = 1,
        frontDefrost: Bool = false,
        rearDefrost: Bool = false,
        wheelHeat: Bool = false,
        driverSeatHeat: Int = 0,
        passengerSeatHeat: Int = 0,
        rearLeftSeatHeat: Int = 0,
        rearRightSeatHeat: Int = 0,
        driverSeatVent: Int = 0,
        passengerSeatVent: Int = 0
    ) {
        self.id = id
        self.name = name
        self.accentColor = accentColor
        self.celsius = celsius
        self.driverTemp = driverTemp
        self.passengerTemp = passengerTemp
        self.syncZones = syncZones
        self.fanSpeed = fanSpeed
        self.autoLevel = autoLevel
        self.acOn = acOn
        self.heatOn = heatOn
        self.recirculate = recirculate
        self.ventMode = ventMode
        self.frontDefrost = frontDefrost
        self.rearDefrost = rearDefrost
        self.wheelHeat = wheelHeat
        self.driverSeatHeat = driverSeatHeat
        self.passengerSeatHeat = passengerSeatHeat
        self.rearLeftSeatHeat = rearLeftSeatHeat
        self.rearRightSeatHeat = rearRightSeatHeat
        self.driverSeatVent = driverSeatVent
        self.passengerSeatVent = passengerSeatVent
    }

    var summaryLine: String {
        let unit = celsius ? "°C" : "°F"
        let dt = driverTemp.uppercased()
        var parts: [String] = [(dt == "LO" || dt == "HI") ? dt : "\(dt)\(unit)"]
        parts.append(autoLevel > 0 ? "AUTO \(autoLevel)" : "Fan \(fanSpeed)")
        if acOn { parts.append("A/C") }
        if heatOn { parts.append("Heat") }
        if wheelHeat { parts.append("Wheel") }
        if max(driverSeatHeat, passengerSeatHeat) > 0 {
            parts.append("SeatHeat \(max(driverSeatHeat, passengerSeatHeat))")
        } else if max(driverSeatVent, passengerSeatVent) > 0 {
            parts.append("SeatCool \(max(driverSeatVent, passengerSeatVent))")
        }
        if frontDefrost || rearDefrost { parts.append("Defrost") }
        return parts.joined(separator: " · ")
    }

    func toDictionary() -> [String: Any] {
        return [
            "id": id,
            "name": name,
            "accentColor": accentColor,
            "celsius": celsius,
            "driverTemp": driverTemp,
            "passengerTemp": passengerTemp,
            "syncZones": syncZones,
            "fanSpeed": fanSpeed,
            "autoLevel": autoLevel,
            "acOn": acOn,
            "heatOn": heatOn,
            "recirculate": recirculate,
            "ventMode": ventMode,
            "frontDefrost": frontDefrost,
            "rearDefrost": rearDefrost,
            "wheelHeat": wheelHeat,
            "driverSeatHeat": driverSeatHeat,
            "passengerSeatHeat": passengerSeatHeat,
            "rearLeftSeatHeat": 0,
            "rearRightSeatHeat": 0,
            "driverSeatVent": driverSeatVent,
            "passengerSeatVent": passengerSeatVent
        ]
    }

    static var starterPresets: [ClimateThemeModel] {
        [
            ClimateThemeModel(
                id: "preset_cool_69",
                name: "Cool Breeze 69°F",
                accentColor: 0xFF0EA5E9,
                celsius: false,
                driverTemp: "69",
                passengerTemp: "69",
                fanSpeed: 2,
                acOn: true,
                heatOn: false,
                ventMode: 1,
                driverSeatVent: 1
            ),
            ClimateThemeModel(
                id: "preset_cozy_78",
                name: "Warm Cozy 78°F",
                accentColor: 0xFFF97316,
                celsius: false,
                driverTemp: "78",
                passengerTemp: "78",
                fanSpeed: 4,
                acOn: true,
                heatOn: true,
                ventMode: 3,
                wheelHeat: true,
                driverSeatHeat: 2,
                passengerSeatHeat: 2
            ),
            ClimateThemeModel(
                id: "preset_defrost_82",
                name: "Morning Defrost",
                accentColor: 0xFFEF4444,
                celsius: false,
                driverTemp: "82",
                passengerTemp: "82",
                fanSpeed: 6,
                acOn: true,
                heatOn: true,
                ventMode: 4,
                frontDefrost: true,
                rearDefrost: true,
                wheelHeat: true,
                driverSeatHeat: 3,
                passengerSeatHeat: 2
            )
        ]
    }
}

struct VehicleTelemetrySnapshot: Codable, Equatable {
    var values: [String: String] = [:]
    var updatedMs: Int64 = 0

    func raw(_ key: String) -> String {
        let v = values[key] ?? ""
        return v.isEmpty ? "--" : v
    }

    func flag(_ key: String) -> Bool {
        let v = raw(key).lowercased()
        return v == "1" || v == "true" || v == "active"
    }

    func intVal(_ key: String, fallback: Int = -1) -> Int {
        guard let d = Double(raw(key)) else { return fallback }
        return Int(d.rounded())
    }

    var isCharging: Bool {
        let chg = raw("charging")
        if chg == "1" || chg.lowercased() == "true" { return true }
        return (Int(chg) ?? 0) > 0
    }

    var isCelsius: Bool {
        raw("climate.temp_unit").uppercased() == "C"
    }

    var chargeRemainingFormatted: String {
        guard let mins = Int(raw("charging.remain_min")), mins > 0, mins < 9999 else {
            return isCharging ? "Calculating..." : "Not actively charging"
        }
        let h = mins / 60
        let m = mins % 60
        return h > 0 ? String(format: "%dh %02dm left", h, m) : "\(m)m left"
    }

    func rangeFormatted(useMiles: Bool) -> String {
        guard let km = Double(raw("hv.range")), km >= 0 else { return "--" }
        if useMiles {
            return "\(Int((km * 0.621371).rounded())) mi"
        }
        return "\(Int(km.rounded())) km"
    }

    var ambientTempFormatted: String {
        let f = raw("outside_temp_f")
        if f != "--" { return "\(f)°F" }
        let c = raw("outside_temp")
        return c == "--" ? "--" : "\(c)°C"
    }

    var ventModeFormatted: String {
        switch intVal("climate.vent_mode", fallback: 0) {
        case 1: return "Face"
        case 2: return "Bi-Level"
        case 3: return "Floor"
        case 4: return "Floor+Def"
        case 5: return "Defrost"
        default: return "--"
        }
    }

    var doorsSummary: String {
        let any = raw("door.any_open")
        if any == "--" { return "--" }
        if any != "1" { return "All Closed" }
        var open: [String] = []
        if flag("door.fl") { open.append("FL") }
        if flag("door.fr") { open.append("FR") }
        if flag("door.rl") { open.append("RL") }
        if flag("door.rr") { open.append("RR") }
        return open.isEmpty ? "Door Open" : "Open: \(open.joined(separator: " "))"
    }

    func singleTireStatus(_ key: String) -> String {
        let code = intVal(key, fallback: -1)
        if code < 0 || code == 255 { return "--" }
        return code == 0 ? "OK" : "LOW"
    }

    func isTireLow(_ key: String) -> Bool {
        let code = intVal(key, fallback: -1)
        return code > 0 && code != 255
    }

    var tiresSummary: String {
        let keys = [("FL", "tyre.fl_status"), ("FR", "tyre.fr_status"), ("RL", "tyre.rl_status"), ("RR", "tyre.rr_status")]
        let statuses = keys.map { ($0.0, singleTireStatus($0.1)) }
        if statuses.allSatisfy({ $0.1 == "--" }) { return "Waiting for TPMS" }
        let lows = statuses.filter { $0.1 == "LOW" }.map { $0.0 }
        return lows.isEmpty ? "All 4 Tires OK" : "LOW PRESSURE: \(lows.joined(separator: " "))"
    }

    var isPreconditioningActive: Bool {
        if flag("hv.conditioning_active") { return true }
        return raw("conditioning.state").lowercased() == "active"
    }

    var isPreconditioningPending: Bool {
        if isPreconditioningActive { return false }
        let st = raw("conditioning.state").lowercased()
        return st == "requested" || st == "waiting"
    }

    var preconditioningStatusFormatted: String {
        if isPreconditioningActive { return "RUNNING (Heating Pack)" }
        switch raw("conditioning.state").lowercased() {
        case "requested", "waiting": return "STARTING / WAITING"
        case "stopping": return "STOPPING..."
        case "timed_out": return "TIMED OUT"
        case "ended": return "ENDED"
        default:
            if raw("hv.conditioning") == "1" {
                return "OFF (Disabled in EV Settings)"
            }
            return "OFF"
        }
    }

    func batteryTempFormatted(preferFahrenheit: Bool) -> String {
        let cStr = raw("hv.battery_temp_min")
        if cStr == "--" {
            let fOnly = raw("hv.battery_temp_min_f")
            return fOnly == "--" ? "--" : "\(fOnly)°F"
        }
        guard let cVal = Double(cStr) else { return "\(cStr)°C" }
        let cRound = Int(cVal.rounded())
        let fRound = Int((cVal * 1.8 + 32.0).rounded())
        return preferFahrenheit ? "\(fRound)°F (\(cRound)°C)" : "\(cRound)°C (\(fRound)°F)"
    }

    var isUtilityModeActive: Bool {
        intVal("hv.utility_mode", fallback: 0) == 2
    }

    var utilityModeStatusFormatted: String {
        let mode = intVal("hv.utility_mode", fallback: -1)
        let warn = intVal("hv.utility_warn", fallback: -1)
        if mode == 2 { return "ON (Active)" }
        if warn == 2 { return "OFF (Conditions Not Met)" }
        if mode == 1 { return "OFF" }
        return "--"
    }

    var evReadyStatusFormatted: String {
        let rawRdy = raw("hv.ready")
        if rawRdy == "--" { return "--" }
        let v = intVal("hv.ready", fallback: -1)
        if v == 1 { return "EV READY" }
        if v == 0 { return "NOT READY (IG OFF/ACC)" }
        return "RAW \(rawRdy)"
    }
}

struct CommandLogItem: Codable, Identifiable, Equatable {
    var id: String          // commandId or reqId
    var label: String
    var transport: String   // "BT" or "REMOTE"
    var state: String       // "SENT", "EXECUTED", "TIMED_OUT", "CANCELED", "FAILED"
    var sentAtMs: Int64
    var updatedAtMs: Int64
    var expiresAtMs: Int64
    var detail: String

    func isPendingRemote(nowMs: Int64) -> Bool {
        transport == "REMOTE" && state == "SENT" && (expiresAtMs <= 0 || nowMs < expiresAtMs)
    }

    var friendlyHeadline: String {
        switch state {
        case "EXECUTED": return "\(label) executed."
        case "TIMED_OUT": return "\(label) timed out."
        case "CANCELED": return "\(label) canceled."
        case "FAILED": return "\(label) failed."
        default: return "\(label) sent."
        }
    }

    func subline(nowMs: Int64) -> String {
        let stamp = state == "SENT" ? sentAtMs : (updatedAtMs > 0 ? updatedAtMs : sentAtMs)
        let date = Date(timeIntervalSince1970: Double(stamp) / 1000.0)
        let tf = DateFormatter()
        tf.dateFormat = "h:mm:ss a"
        let timeStr = stamp > 0 ? tf.string(from: date) : ""
        let via = transport == "REMOTE" ? "Wi-Fi Server" : "Bluetooth"
        if isPendingRemote(nowMs: nowMs), expiresAtMs > nowMs {
            let remSec = max(0, (expiresAtMs - nowMs) / 1000)
            return String(format: "%@ · %@ · Waiting (%d:%02d left)", timeStr, via, remSec / 60, remSec % 60)
        }
        if !detail.isEmpty && state != "SENT" && detail.caseInsensitiveCompare(label) != .orderedSame {
            return "\(timeStr) · \(via) · \(detail)"
        }
        return "\(timeStr) · \(via)"
    }
}

struct PendingRemoteCommandItem: Identifiable, Equatable {
    let id: String // commandId
    let label: String
    var status: String
    let expiresAtMs: Int64
    var resultMessage: String
}
