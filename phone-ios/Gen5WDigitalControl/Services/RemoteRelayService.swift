import Foundation

/// Zero-Trust E2EE Remote Command Client for user-configured self-hosted servers.
/// Stays completely idle (zero network requests) unless `vault.isRemoteServerConfigured` is true.
@MainActor
final class RemoteRelayService: ObservableObject {
    static let commandTtlMs: Int64 = 5 * 60 * 1000
    static let carWifiOnlineThresholdMs: Int64 = 60 * 1000

    @Published var remoteFallbackEnabled: Bool = true
    @Published var pendingCommands: [PendingRemoteCommandItem] = []
    @Published var lastRemoteFeedback: String = ""

    private let vault: KeychainVault
    private var pollTask: Task<Void, Never>?

    init(vault: KeychainVault) {
        self.vault = vault
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        self.pendingCommands = vault.commandLog
            .filter { $0.transport == "REMOTE" && $0.state == "SENT" && $0.expiresAtMs > nowMs }
            .map {
                PendingRemoteCommandItem(
                    id: $0.id,
                    label: $0.label,
                    status: "QUEUED",
                    expiresAtMs: $0.expiresAtMs,
                    resultMessage: "Waiting for car Wi-Fi..."
                )
            }
    }

    func isCarWifiRecentlyOnline(nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let last = vault.lastCarRemoteCheckInMs
        guard last > 0 else { return false }
        let age = nowMs - last
        return age >= -120_000 && age <= Self.carWifiOnlineThresholdMs
    }

    func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }
                await self.pollOnce()
                let hasActive = self.pendingCommands.contains {
                    $0.status == "QUEUED" && Int64(Date().timeIntervalSince1970 * 1000) < $0.expiresAtMs
                }
                try? await Task.sleep(nanoseconds: hasActive ? 3_000_000_000 : 8_000_000_000)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    func enqueueCommand(action: String, arg: String = "", theme: ClimateThemeModel? = nil, label: String) {
        guard vault.isCarPaired else {
            lastRemoteFeedback = "⚠ Pair with your car over Bluetooth first"
            return
        }
        let baseUrl = vault.remoteServerUrl
        guard baseUrl.hasPrefix("https://") else {
            lastRemoteFeedback = "⚠ HTTPS remote URL required"
            return
        }

        Task {
            do {
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                let expMs = nowMs + Self.commandTtlMs
                let cmdId = "cmd_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()

                var inner: [String: Any] = [
                    "v": 1,
                    "commandId": cmdId,
                    "carId": vault.pairedCarId,
                    "phoneId": vault.phoneId,
                    "action": action,
                    "arg": arg,
                    "issuedAtMs": nowMs,
                    "expiresAtMs": expMs,
                    "nonce": CarCrypto.randomHex(16)
                ]
                if let theme = theme {
                    inner["climateTheme"] = theme.toDictionary()
                }
                let innerJson = String(data: try JSONSerialization.data(withJSONObject: inner), encoding: .utf8) ?? "{}"
                let aad = "GEN5W-AAD-V1|\(cmdId)|\(vault.pairedCarId)|\(vault.phoneId)|\(nowMs)|\(expMs)"

                let box = try CarCrypto.encryptToPublicKey(
                    recipientStaticPubSpkiB64: vault.pairedCarPubSpkiB64,
                    contextSalt: cmdId,
                    aad: aad,
                    plaintextUtf8: innerJson
                )

                let boxDigest = CarCrypto.sha256Hex(Data("\(box.ephemeralPubSpkiB64).\(box.ivB64).\(box.ciphertextB64).\(box.macB64)".utf8))
                let signCanonical = "GEN5W-ENV-V1|\(cmdId)|\(vault.pairedCarId)|\(vault.phoneId)|\(nowMs)|\(expMs)|\(boxDigest)"
                let phoneSig = try CarCrypto.signEcdsaDerB64(privRaw: vault.getPhonePrivateKeyRaw(), canonical: signCanonical)

                let outer: [String: Any] = [
                    "commandId": cmdId,
                    "carId": vault.pairedCarId,
                    "phoneId": vault.phoneId,
                    "issuedAtMs": nowMs,
                    "expiresAtMs": expMs,
                    "ttlSeconds": 300,
                    "e2ee": [
                        "ephemeralPubKey": box.ephemeralPubSpkiB64,
                        "iv": box.ivB64,
                        "ciphertext": box.ciphertextB64,
                        "mac": box.macB64
                    ],
                    "phoneSignature": phoneSig,
                    "pairingProof": [
                        "phonePublicKey": vault.phonePubSpkiB64,
                        "pairedAtMs": vault.pairedAtMs,
                        "carGrantSignature": vault.pairingGrantSigB64
                    ],
                    "clientHint": label
                ]

                let (code, body) = try await sendRequest(
                    method: "POST",
                    urlStr: "\(baseUrl)/api/v1/commands",
                    jsonBody: outer,
                    phoneSigHeader: nil
                )
                if (200..<300).contains(code) {
                    vault.recordCommandSent(id: cmdId, label: label, transport: "REMOTE", sentAtMs: nowMs, expiresAtMs: expMs)
                    pendingCommands.insert(
                        PendingRemoteCommandItem(
                            id: cmdId,
                            label: label,
                            status: "QUEUED",
                            expiresAtMs: expMs,
                            resultMessage: "Waiting for car Wi-Fi..."
                        ),
                        at: 0
                    )
                    lastRemoteFeedback = "✓ \(label) sent."
                    startPolling()
                } else {
                    lastRemoteFeedback = "⚠ Queue failed (HTTP \(code)): \(body)"
                }
            } catch {
                lastRemoteFeedback = "⚠ Remote error: \(error.localizedDescription)"
            }
        }
    }

    func cancelCommand(commandId: String) {
        let baseUrl = vault.remoteServerUrl
        guard baseUrl.hasPrefix("https://") else { return }
        Task {
            do {
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                let canonical = "GEN5W-CANCEL-V1|\(commandId)|\(vault.pairedCarId)|\(vault.phoneId)|\(nowMs)"
                let sig = try CarCrypto.signEcdsaDerB64(privRaw: vault.getPhonePrivateKeyRaw(), canonical: canonical)
                var comps = URLComponents(string: "\(baseUrl)/api/v1/commands/\(commandId)")
                comps?.queryItems = [
                    URLQueryItem(name: "carId", value: vault.pairedCarId),
                    URLQueryItem(name: "phoneId", value: vault.phoneId),
                    URLQueryItem(name: "ts", value: "\(nowMs)")
                ]
                guard let urlStr = comps?.url?.absoluteString else { return }
                let (code, _) = try await sendRequest(method: "DELETE", urlStr: urlStr, jsonBody: nil, phoneSigHeader: sig)
                if (200..<300).contains(code) {
                    vault.updateCommandLogState(id: commandId, state: "CANCELED", nowMs: nowMs, detail: "Canceled by phone")
                    if let idx = pendingCommands.firstIndex(where: { $0.id == commandId }) {
                        pendingCommands[idx].status = "CANCELED"
                        pendingCommands[idx].resultMessage = "Canceled by phone"
                    }
                    lastRemoteFeedback = "✓ Command canceled."
                }
            } catch {
                lastRemoteFeedback = "⚠ Cancel failed: \(error.localizedDescription)"
            }
        }
    }

    private func pollOnce() async {
        guard vault.isCarPaired, vault.remoteServerUrl.hasPrefix("https://") else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        vault.sweepExpiredLogItems(nowMs: nowMs)
        for i in pendingCommands.indices {
            if pendingCommands[i].status == "QUEUED" && nowMs >= pendingCommands[i].expiresAtMs {
                pendingCommands[i].status = "EXPIRED"
                pendingCommands[i].resultMessage = "Timed out after 5 minutes"
            }
        }
        do {
            let canonical = "GEN5W-STATUS-V1|\(vault.pairedCarId)|\(vault.phoneId)|\(nowMs)"
            let sig = try CarCrypto.signEcdsaDerB64(privRaw: vault.getPhonePrivateKeyRaw(), canonical: canonical)
            var comps = URLComponents(string: "\(vault.remoteServerUrl)/api/v1/commands")
            comps?.queryItems = [
                URLQueryItem(name: "carId", value: vault.pairedCarId),
                URLQueryItem(name: "phoneId", value: vault.phoneId),
                URLQueryItem(name: "ts", value: "\(nowMs)")
            ]
            guard let urlStr = comps?.url?.absoluteString else { return }
            let (code, bodyStr) = try await sendRequest(method: "GET", urlStr: urlStr, jsonBody: nil, phoneSigHeader: sig)
            guard (200..<300).contains(code),
                  let data = bodyStr.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                return
            }
            if let topSeen = (root["carLastSeenAtMs"] as? NSNumber)?.int64Value, topSeen > 0 {
                vault.setLastCarRemoteCheckInMs(topSeen)
            }
            if let cmds = root["commands"] as? [[String: Any]] {
                for c in cmds {
                    guard let cid = c["commandId"] as? String,
                          let status = c["status"] as? String else { continue }
                    let msg = c["message"] as? String ?? ""
                    let execAt = (c["executedAtMs"] as? NSNumber)?.int64Value ?? nowMs
                    let mapped: String? = {
                        switch status.uppercased() {
                        case "EXECUTED": return "EXECUTED"
                        case "EXPIRED": return "TIMED_OUT"
                        case "CANCELED": return "CANCELED"
                        case "FAILED": return "FAILED"
                        default: return nil
                        }
                    }()
                    if let m = mapped {
                        let changed = vault.updateCommandLogState(id: cid, state: m, nowMs: execAt > 0 ? execAt : nowMs, detail: msg)
                        if changed, let item = vault.commandLog.first(where: { $0.id == cid }) {
                            lastRemoteFeedback = (m == "EXECUTED" ? "✓ " : "⚠ ") + item.friendlyHeadline
                        }
                    }
                    if let idx = pendingCommands.firstIndex(where: { $0.id == cid }) {
                        pendingCommands[idx].status = status
                        pendingCommands[idx].resultMessage = msg
                    }
                }
            }
            if let encTelem = root["encryptedTelemetry"] as? [String: Any] {
                let telemUpdated = (encTelem["updatedAtMs"] as? NSNumber)?.int64Value ?? 0
                if telemUpdated > 0 {
                    vault.setLastCarRemoteCheckInMs(telemUpdated)
                }
                let box = CarCrypto.EncryptedBoxPayload(
                    ephemeralPubSpkiB64: encTelem["ephemeralPubKey"] as? String ?? "",
                    ivB64: encTelem["iv"] as? String ?? "",
                    ciphertextB64: encTelem["ciphertext"] as? String ?? "",
                    macB64: encTelem["mac"] as? String ?? ""
                )
                if let plain = try? CarCrypto.decryptWithPrivateKey(
                    recipientPrivRaw: vault.getPhonePrivateKeyRaw(),
                    contextSalt: "telemetry|\(vault.pairedCarId)",
                    aad: "GEN5W-TELEM-V1|\(vault.pairedCarId)|\(vault.phoneId)",
                    box: box
                ),
                let pData = plain.data(using: .utf8),
                let pJson = (try? JSONSerialization.jsonObject(with: pData)) as? [String: Any],
                let vals = pJson["values"] as? [String: String] {
                    let ts = (pJson["updatedMs"] as? NSNumber)?.int64Value ?? (telemUpdated > 0 ? telemUpdated : nowMs)
                    vault.saveSnapshot(VehicleTelemetrySnapshot(values: vals, updatedMs: ts))
                }
            }
        } catch {
            // Quietly retry on next tick
        }
    }

    private func sendRequest(
        method: String,
        urlStr: String,
        jsonBody: [String: Any]?,
        phoneSigHeader: String?
    ) async throws -> (Int, String) {
        guard urlStr.hasPrefix("https://"), let url = URL(string: urlStr) else {
            throw NSError(domain: "RemoteRelay", code: -1, userInfo: [NSLocalizedDescriptionKey: "HTTPS URL required"])
        }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Gen5WDigitalControliOS/1.0.5", forHTTPHeaderField: "User-Agent")
        if !vault.remoteAccountToken.isEmpty {
            req.setValue("Bearer \(vault.remoteAccountToken)", forHTTPHeaderField: "Authorization")
        }
        if let sig = phoneSigHeader, !sig.isEmpty {
            req.setValue(sig, forHTTPHeaderField: "X-Gen5W-Phone-Signature")
        }
        if let body = jsonBody {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let httpCode = (resp as? HTTPURLResponse)?.statusCode ?? 0
        return (httpCode, String(data: data.prefix(131072), encoding: .utf8) ?? "")
    }
}
