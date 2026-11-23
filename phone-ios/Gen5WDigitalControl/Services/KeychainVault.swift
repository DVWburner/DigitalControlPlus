import CryptoKit
import Foundation
import Security
import UIKit

/// Stores the iPhone's EC P-256 private key in the iOS Keychain, plus paired car identity
/// and custom Climate Themes in UserDefaults.
///
/// Supports both:
///  1. Native Apple iPhone / iCloud Backup (`kSecAttrAccessibleAfterFirstUnlock` when enabled)
///  2. Cross-platform passphrase-encrypted `.g5wkey` local file export & import (`GEN5W-KEY-BACKUP-V1`)
final class KeychainVault: ObservableObject {
    static let backupFormatId = "GEN5W-KEY-BACKUP-V1"
    private static let keychainAccount = "com.gen5w.digitalcontrol.phonePrivKeyV1"
    private static let localBackupFilename = "gen5w-security-key.g5wkey"

    private static let defaultRemoteUrl = ""

    private let defaults = UserDefaults.standard

    @Published var phoneId: String = ""
    @Published var phonePubSpkiB64: String = ""
    @Published var pairedCarId: String = ""
    @Published var pairedCarName: String = "Ioniq 5"
    @Published var pairedCarPubSpkiB64: String = ""
    @Published var pairedAtMs: Int64 = 0
    @Published var pairingGrantSigB64: String = ""
    @Published var remoteServerUrl: String = KeychainVault.defaultRemoteUrl
    @Published var remoteAccountToken: String = ""
    @Published var useMiles: Bool = true
    @Published var appleBackupEnabled: Bool = true
    @Published var climateThemes: [ClimateThemeModel] = []
    @Published var cachedSnapshot: VehicleTelemetrySnapshot = VehicleTelemetrySnapshot()
    @Published var lastCarRemoteCheckInMs: Int64 = 0
    @Published var commandLog: [CommandLogItem] = []

    private var phonePrivRaw: Data = Data()

    var isCarPaired: Bool {
        !pairedCarId.isEmpty && !pairedCarPubSpkiB64.isEmpty
    }

    var isRemoteServerConfigured: Bool {
        remoteServerUrl.hasPrefix("https://")
    }

    var phoneDisplayName: String {
        UIDevice.current.name
    }

    var keyFingerprintShort: String {
        guard let der = Data(base64Encoded: phonePubSpkiB64), !der.isEmpty else {
            return "----"
        }
        let hex = CarCrypto.sha256Hex(der).uppercased()
        guard hex.count >= 12 else { return hex }
        let p1 = hex.prefix(4)
        let p2 = hex.dropFirst(4).prefix(4)
        let p3 = hex.dropFirst(8).prefix(4)
        return "\(p1)-\(p2)-\(p3)"
    }

    init() {
        if defaults.object(forKey: "apple_backup_enabled") != nil {
            appleBackupEnabled = defaults.bool(forKey: "apple_backup_enabled")
        } else {
            appleBackupEnabled = true
        }
        loadOrCreateIdentity()
        loadPairedState()
        loadThemes()
        loadCommandLog()
    }

    func getPhonePrivateKeyRaw() -> Data {
        phonePrivRaw
    }

    func setAppleBackupEnabled(_ enabled: Bool) {
        appleBackupEnabled = enabled
        defaults.set(enabled, forKey: "apple_backup_enabled")
        if !phonePrivRaw.isEmpty {
            saveKeychainPrivateKey(phonePrivRaw, allowAppleBackup: enabled)
        }
    }

    private func loadOrCreateIdentity() {
        if let existingRaw = readKeychainPrivateKey(),
           let priv = try? P256.Signing.PrivateKey(rawRepresentation: existingRaw),
           let savedId = defaults.string(forKey: "phone_id"), !savedId.isEmpty {
            self.phonePrivRaw = existingRaw
            self.phoneId = savedId
            self.phonePubSpkiB64 = priv.publicKey.derRepresentation.base64EncodedString()
            // Migrate legacy ThisDeviceOnly Keychain entry so iPhone/iCloud Backup works seamlessly
            if !defaults.bool(forKey: "keychain_backup_migrated_v1") {
                saveKeychainPrivateKey(existingRaw, allowAppleBackup: appleBackupEnabled)
                defaults.set(true, forKey: "keychain_backup_migrated_v1")
            }
            return
        }
        let fresh = P256.Signing.PrivateKey()
        let raw = fresh.rawRepresentation
        saveKeychainPrivateKey(raw, allowAppleBackup: appleBackupEnabled)
        defaults.set(true, forKey: "keychain_backup_migrated_v1")
        let pid = "ph_" + CarCrypto.randomHex(10)
        defaults.set(pid, forKey: "phone_id")
        self.phonePrivRaw = raw
        self.phoneId = pid
        self.phonePubSpkiB64 = fresh.publicKey.derRepresentation.base64EncodedString()
    }

    private func loadPairedState() {
        pairedCarId = defaults.string(forKey: "car_id") ?? ""
        pairedCarName = defaults.string(forKey: "car_name") ?? "Ioniq 5"
        pairedCarPubSpkiB64 = defaults.string(forKey: "car_pub_b64") ?? ""
        pairedAtMs = Int64(defaults.integer(forKey: "paired_at_ms"))
        pairingGrantSigB64 = defaults.string(forKey: "pairing_grant_sig") ?? ""
        remoteServerUrl = defaults.string(forKey: "remote_url") ?? Self.defaultRemoteUrl
        remoteAccountToken = defaults.string(forKey: "remote_account_token") ?? ""
        if defaults.object(forKey: "use_miles") != nil {
            useMiles = defaults.bool(forKey: "use_miles")
        }
        if let snapData = defaults.data(forKey: "cached_snapshot"),
           let snap = try? JSONDecoder().decode(VehicleTelemetrySnapshot.self, from: snapData) {
            cachedSnapshot = snap
        }
        lastCarRemoteCheckInMs = Int64(defaults.integer(forKey: "last_car_remote_checkin_ms"))
    }

    func savePairedCar(
        carId: String,
        carName: String,
        carPubB64: String,
        pairedAtMs: Int64,
        grantSigB64: String,
        remoteUrl: String
    ) {
        self.pairedCarId = carId
        self.pairedCarName = carName.isEmpty ? "Ioniq 5" : carName
        self.pairedCarPubSpkiB64 = carPubB64
        self.pairedAtMs = pairedAtMs
        self.pairingGrantSigB64 = grantSigB64
        defaults.set(carId, forKey: "car_id")
        defaults.set(self.pairedCarName, forKey: "car_name")
        defaults.set(carPubB64, forKey: "car_pub_b64")
        defaults.set(Int(pairedAtMs), forKey: "paired_at_ms")
        defaults.set(grantSigB64, forKey: "pairing_grant_sig")
        let normRemote = Self.normalizeServerUrl(remoteUrl)
        if !normRemote.isEmpty {
            setRemoteSettings(url: normRemote, token: remoteAccountToken)
        }
    }

    static func normalizeServerUrl(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return "" }
        while s.lowercased().hasPrefix("https://") || s.lowercased().hasPrefix("http://") {
            if s.lowercased().hasPrefix("https://") {
                s = String(s.dropFirst(8)).trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                s = String(s.dropFirst(7)).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        while s.hasSuffix("/") {
            s.removeLast()
            s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return s.isEmpty ? "" : "https://\(s)"
    }

    func clearPairedCar() {
        pairedCarId = ""
        pairedCarPubSpkiB64 = ""
        pairedAtMs = 0
        pairingGrantSigB64 = ""
        cachedSnapshot = VehicleTelemetrySnapshot()
        defaults.removeObject(forKey: "car_id")
        defaults.removeObject(forKey: "car_pub_b64")
        defaults.removeObject(forKey: "paired_at_ms")
        defaults.removeObject(forKey: "pairing_grant_sig")
        defaults.removeObject(forKey: "cached_snapshot")
    }

    func setRemoteSettings(url: String, token: String) {
        let normalized = Self.normalizeServerUrl(url)
        if normalized.isEmpty {
            remoteServerUrl = ""
            remoteAccountToken = ""
            defaults.removeObject(forKey: "remote_url")
            defaults.removeObject(forKey: "remote_account_token")
            return
        }
        remoteServerUrl = normalized
        defaults.set(normalized, forKey: "remote_url")
        remoteAccountToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(remoteAccountToken, forKey: "remote_account_token")
    }

    func saveSnapshot(_ snap: VehicleTelemetrySnapshot) {
        cachedSnapshot = snap
        if let d = try? JSONEncoder().encode(snap) {
            defaults.set(d, forKey: "cached_snapshot")
        }
    }

    func setLastCarRemoteCheckInMs(_ ts: Int64) {
        guard ts > 0 else { return }
        lastCarRemoteCheckInMs = ts
        defaults.set(Int(ts), forKey: "last_car_remote_checkin_ms")
    }

    private func loadCommandLog() {
        if let data = defaults.data(forKey: "command_log_v1"),
           let decoded = try? JSONDecoder().decode([CommandLogItem].self, from: data) {
            commandLog = decoded
            sweepExpiredLogItems(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        }
    }

    func sweepExpiredLogItems(nowMs: Int64) {
        var changed = false
        for idx in commandLog.indices {
            if commandLog[idx].state == "SENT" && commandLog[idx].expiresAtMs > 0 && nowMs >= commandLog[idx].expiresAtMs {
                commandLog[idx].state = "TIMED_OUT"
                commandLog[idx].updatedAtMs = commandLog[idx].expiresAtMs
                commandLog[idx].detail = "Timed out after 5 minutes"
                changed = true
            }
        }
        if changed { persistCommandLog() }
    }

    func recordCommandSent(id: String, label: String, transport: String, sentAtMs: Int64, expiresAtMs: Int64) {
        commandLog.insert(
            CommandLogItem(
                id: id,
                label: label,
                transport: transport,
                state: "SENT",
                sentAtMs: sentAtMs,
                updatedAtMs: sentAtMs,
                expiresAtMs: expiresAtMs,
                detail: ""
            ),
            at: 0
        )
        if commandLog.count > 60 {
            commandLog = Array(commandLog.prefix(60))
        }
        persistCommandLog()
    }

    @discardableResult
    func updateCommandLogState(id: String, state: String, nowMs: Int64, detail: String) -> Bool {
        guard let idx = commandLog.firstIndex(where: { $0.id == id }) else { return false }
        if commandLog[idx].state != state || commandLog[idx].detail != detail {
            commandLog[idx].state = state
            commandLog[idx].updatedAtMs = nowMs
            commandLog[idx].detail = detail
            persistCommandLog()
            return true
        }
        return false
    }

    func clearCommandLog() {
        commandLog.removeAll()
        defaults.removeObject(forKey: "command_log_v1")
    }

    private func persistCommandLog() {
        if let data = try? JSONEncoder().encode(commandLog) {
            defaults.set(data, forKey: "command_log_v1")
        }
    }

    private func loadThemes() {
        if let data = defaults.data(forKey: "climate_themes_v1"),
           let decoded = try? JSONDecoder().decode([ClimateThemeModel].self, from: data),
           !decoded.isEmpty {
            climateThemes = decoded
        } else {
            climateThemes = ClimateThemeModel.starterPresets
        }
    }

    func upsertTheme(_ theme: ClimateThemeModel) {
        if let idx = climateThemes.firstIndex(where: { $0.id == theme.id }) {
            climateThemes[idx] = theme
        } else {
            climateThemes.append(theme)
        }
        persistThemes()
    }

    func deleteTheme(id: String) {
        climateThemes.removeAll { $0.id == id }
        if climateThemes.isEmpty {
            climateThemes = ClimateThemeModel.starterPresets
        }
        persistThemes()
    }

    private func persistThemes() {
        if let data = try? JSONEncoder().encode(climateThemes) {
            defaults.set(data, forKey: "climate_themes_v1")
        }
    }

    // MARK: - Cross-Platform Encrypted Backup (.g5wkey) Export & Restore

    func exportEncryptedBackupJson(passphrase: String) throws -> String {
        guard phonePrivRaw.count == 32 else {
            throw NSError(domain: "KeychainVault", code: -10, userInfo: [NSLocalizedDescriptionKey: "Missing 32-byte phone private key"])
        }
        var inner: [String: Any] = [
            "v": 1,
            "phoneId": phoneId,
            "phoneName": phoneDisplayName,
            "phonePrivRawB64": phonePrivRaw.base64EncodedString(),
            "phonePubSpkiB64": phonePubSpkiB64,
            "carId": pairedCarId,
            "carName": pairedCarName,
            "carBtAddress": defaults.string(forKey: "car_bt_address") ?? "",
            "carPubSpkiB64": pairedCarPubSpkiB64,
            "pairedAtMs": pairedAtMs,
            "pairingGrantSigB64": pairingGrantSigB64,
            "useMiles": useMiles,
            "climateThemes": climateThemes.map { $0.toDictionary() }
        ]
        if !remoteServerUrl.isEmpty {
            inner["remoteUrl"] = remoteServerUrl
        }
        if !remoteAccountToken.isEmpty {
            inner["remoteAccountToken"] = remoteAccountToken
        }

        let plainData = try JSONSerialization.data(withJSONObject: inner, options: [])
        guard let plainJson = String(data: plainData, encoding: .utf8) else {
            throw NSError(domain: "KeychainVault", code: -11, userInfo: [NSLocalizedDescriptionKey: "Failed encoding backup JSON"])
        }

        let box = try CarCrypto.encryptWithPassphrase(
            passphrase: passphrase,
            aadDomain: Self.backupFormatId,
            iterations: 100_000,
            plaintextUtf8: plainJson
        )
        var outer: [String: Any] = [
            "format": Self.backupFormatId,
            "kdf": "PBKDF2-HMAC-SHA256",
            "iter": box.iterations,
            "salt": box.saltB64,
            "iv": box.ivB64,
            "ct": box.ciphertextB64,
            "mac": box.macB64,
            "createdAtMs": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        if !pairedCarName.isEmpty {
            outer["hintCarName"] = pairedCarName
        }
        let outerData = try JSONSerialization.data(withJSONObject: outer, options: [])
        guard let outerStr = String(data: outerData, encoding: .utf8) else {
            throw NSError(domain: "KeychainVault", code: -12, userInfo: [NSLocalizedDescriptionKey: "Failed serializing backup envelope"])
        }
        try? saveLocalBackupSnapshot(outerStr)
        return outerStr
    }

    func importEncryptedBackupJson(_ encryptedText: String, passphrase: String) throws {
        let trimmed = encryptedText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let envData = trimmed.data(using: .utf8),
              let env = try JSONSerialization.jsonObject(with: envData) as? [String: Any] else {
            throw NSError(domain: "KeychainVault", code: -13, userInfo: [NSLocalizedDescriptionKey: "Invalid backup file JSON"])
        }
        guard (env["format"] as? String) == Self.backupFormatId else {
            throw NSError(domain: "KeychainVault", code: -14, userInfo: [NSLocalizedDescriptionKey: "Unrecognized backup format"])
        }
        let box = CarCrypto.PassphraseBoxPayload(
            iterations: (env["iter"] as? Int) ?? 0,
            saltB64: (env["salt"] as? String) ?? "",
            ivB64: (env["iv"] as? String) ?? "",
            ciphertextB64: (env["ct"] as? String) ?? "",
            macB64: (env["mac"] as? String) ?? ""
        )
        let plainStr = try CarCrypto.decryptWithPassphrase(
            passphrase: passphrase,
            aadDomain: Self.backupFormatId,
            box: box
        )
        guard let plainData = plainStr.data(using: .utf8),
              let inner = try JSONSerialization.jsonObject(with: plainData) as? [String: Any] else {
            throw NSError(domain: "KeychainVault", code: -15, userInfo: [NSLocalizedDescriptionKey: "Corrupted decrypted backup JSON"])
        }

        let newPhoneId = ((inner["phoneId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let privB64 = ((inner["phonePrivRawB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let pubSpkiB64 = ((inner["phonePubSpkiB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !newPhoneId.isEmpty,
              let privRaw = Data(base64Encoded: privB64), privRaw.count == 32,
              let priv = try? P256.Signing.PrivateKey(rawRepresentation: privRaw) else {
            throw NSError(domain: "KeychainVault", code: -16, userInfo: [NSLocalizedDescriptionKey: "Invalid EC P-256 private key in backup"])
        }
        let derivedPubB64 = priv.publicKey.derRepresentation.base64EncodedString()
        guard derivedPubB64 == pubSpkiB64 else {
            throw NSError(domain: "KeychainVault", code: -17, userInfo: [NSLocalizedDescriptionKey: "Private key does not match public key in backup"])
        }

        let cId = ((inner["carId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cName = ((inner["carName"] as? String) ?? "Ioniq 5").trimmingCharacters(in: .whitespacesAndNewlines)
        let cBt = ((inner["carBtAddress"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cPub = ((inner["carPubSpkiB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cPairedAt = (inner["pairedAtMs"] as? NSNumber)?.int64Value ?? 0
        let cGrant = ((inner["pairingGrantSigB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let miles = (inner["useMiles"] as? Bool) ?? true

        if !cPub.isEmpty {
            guard let carDer = Data(base64Encoded: cPub),
                  (try? P256.Signing.PublicKey(derRepresentation: carDer)) != nil else {
                throw NSError(domain: "KeychainVault", code: -18, userInfo: [NSLocalizedDescriptionKey: "Invalid car public key in backup"])
            }
        }

        var parsedThemes: [ClimateThemeModel] = []
        if let rawThemes = inner["climateThemes"] as? [[String: Any]] {
            for rt in rawThemes {
                let rawAccent = (rt["accentColor"] as? NSNumber)?.int64Value ?? 0xFF0EA5E9
                let normAccent = Int(UInt32(truncatingIfNeeded: rawAccent))
                parsedThemes.append(ClimateThemeModel(
                    id: (rt["id"] as? String) ?? ("theme_" + CarCrypto.randomHex(5)),
                    name: (rt["name"] as? String) ?? "Climate Preset",
                    accentColor: normAccent,
                    celsius: (rt["celsius"] as? Bool) ?? false,
                    driverTemp: (rt["driverTemp"] as? String) ?? "70",
                    passengerTemp: (rt["passengerTemp"] as? String) ?? "70",
                    syncZones: (rt["syncZones"] as? Bool) ?? true,
                    fanSpeed: (rt["fanSpeed"] as? NSNumber)?.intValue ?? 3,
                    autoLevel: (rt["autoLevel"] as? NSNumber)?.intValue ?? 0,
                    acOn: (rt["acOn"] as? Bool) ?? true,
                    heatOn: (rt["heatOn"] as? Bool) ?? false,
                    recirculate: (rt["recirculate"] as? Bool) ?? false,
                    ventMode: (rt["ventMode"] as? NSNumber)?.intValue ?? 1,
                    frontDefrost: (rt["frontDefrost"] as? Bool) ?? false,
                    rearDefrost: (rt["rearDefrost"] as? Bool) ?? false,
                    wheelHeat: (rt["wheelHeat"] as? Bool) ?? false,
                    driverSeatHeat: (rt["driverSeatHeat"] as? NSNumber)?.intValue ?? 0,
                    passengerSeatHeat: (rt["passengerSeatHeat"] as? NSNumber)?.intValue ?? 0,
                    rearLeftSeatHeat: (rt["rearLeftSeatHeat"] as? NSNumber)?.intValue ?? 0,
                    rearRightSeatHeat: (rt["rearRightSeatHeat"] as? NSNumber)?.intValue ?? 0,
                    driverSeatVent: (rt["driverSeatVent"] as? NSNumber)?.intValue ?? 0,
                    passengerSeatVent: (rt["passengerSeatVent"] as? NSNumber)?.intValue ?? 0
                ))
            }
        }

        // Persist restored identity & pairing
        saveKeychainPrivateKey(privRaw, allowAppleBackup: appleBackupEnabled)
        self.phonePrivRaw = privRaw
        self.phoneId = newPhoneId
        self.phonePubSpkiB64 = derivedPubB64
        defaults.set(newPhoneId, forKey: "phone_id")

        self.useMiles = miles
        defaults.set(miles, forKey: "use_miles")

        if !cId.isEmpty && !cPub.isEmpty {
            savePairedCar(
                carId: cId,
                carName: cName,
                carPubB64: cPub,
                pairedAtMs: cPairedAt,
                grantSigB64: cGrant,
                remoteUrl: (inner["remoteUrl"] as? String) ?? ""
            )
            defaults.set(cBt, forKey: "car_bt_address")
        } else {
            clearPairedCar()
        }

        let rUrl = ((inner["remoteUrl"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rTok = ((inner["remoteAccountToken"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !rUrl.isEmpty || !rTok.isEmpty {
            setRemoteSettings(url: rUrl.isEmpty ? remoteServerUrl : rUrl, token: rTok)
        }

        if !parsedThemes.isEmpty {
            self.climateThemes = parsedThemes
            persistThemes()
        }
    }

    // MARK: - Local On-Phone Snapshot Helpers (Application Support)

    private func localBackupFileURL() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("Backups", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(Self.localBackupFilename)
    }

    var hasLocalBackupSnapshot: Bool {
        guard let url = localBackupFileURL() else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    var localBackupSnapshotDate: Date? {
        guard let url = localBackupFileURL(),
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path) else {
            return nil
        }
        return attrs[.modificationDate] as? Date
    }

    func saveLocalBackupSnapshot(_ encryptedJson: String) throws {
        guard let url = localBackupFileURL() else { return }
        try Data(encryptedJson.utf8).write(to: url, options: .atomic)
    }

    func readLocalBackupSnapshot() throws -> String {
        guard let url = localBackupFileURL() else {
            throw NSError(domain: "KeychainVault", code: -19, userInfo: [NSLocalizedDescriptionKey: "No local snapshot found"])
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    // MARK: - iOS Keychain Storage

    private func readKeychainPrivateKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: Self.keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func saveKeychainPrivateKey(_ raw: Data, allowAppleBackup: Bool) {
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: Self.keychainAccount
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        // kSecAttrAccessibleAfterFirstUnlock includes the item in encrypted Apple iPhone / iCloud Backups
        // and Quick Start phone-to-phone transfers; ThisDeviceOnly restricts it strictly to this device.
        let accessibility = allowAppleBackup
            ? kSecAttrAccessibleAfterFirstUnlock
            : kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: Self.keychainAccount,
            kSecValueData as String: raw,
            kSecAttrAccessible as String: accessibility
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
    }
}
