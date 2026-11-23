import CoreBluetooth
import CryptoKit
import Foundation

/// iOS Bluetooth Low Energy Peripheral transport (`CBPeripheralManager`) matching
/// `CarLinkService.BleGattChannel` on the Gen5W head unit (Android 4.4.2 BLE Central).
final class BleCarTransport: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    static let serviceUuid = CBUUID(string: "d5c45710-6765-4e35-9744-6374726c0010")
    static let charCarToPhoneUuid = CBUUID(string: "d5c45711-6765-4e35-9744-6374726c0011")
    static let charPhoneToCarUuid = CBUUID(string: "d5c45712-6765-4e35-9744-6374726c0012")

    @Published var isConnected: Bool = false
    @Published var statusMessage: String = "Looking for car BT signal..."
    @Published var pendingSas4Code: String? = nil
    @Published var pendingCarName: String = "Ioniq 5"
    @Published var lastActionFeedback: String = ""

    private let vault: KeychainVault
    private var peripheralManager: CBPeripheralManager?
    private var txCharacteristic: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?

    private var outgoingChunks: [Data] = []
    private var msgSeq: Int = 1

    // BLE Reassembler state
    private var currentMsgId: Int = -1
    private var expectedChunkIdx: Int = 0
    private var rxBuffer = Data()

    // Handshake & Session state
    private var forcePairingOnNextConnect: Bool = false
    private var phoneEphPriv = P256.KeyAgreement.PrivateKey()
    private var phoneEphPubB64: String = ""
    private var phoneNonce: String = ""
    private var carId: String = ""
    private var carName: String = "Ioniq 5"
    private var carStaticPubB64: String = ""
    private var carNonce: String = ""
    private var sas4Code: String = ""
    private var sessionKey64: Data?
    private var txFrameSeq: Int64 = 1
    private var minRxFrameSeq: Int64 = 1

    init(vault: KeychainVault) {
        self.vault = vault
        super.init()
        self.peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func requestManualPairingMode() {
        forcePairingOnNextConnect = true
        lastActionFeedback = "Ready to pair — tap 'Start Pairing Mode' on your car screen"
        if subscribedCentral != nil {
            beginHandshake()
        } else {
            ensureAdvertising()
        }
    }

    func confirmSas4Code() {
        guard !sas4Code.isEmpty else { return }
        do {
            let canonical = "GEN5W-PAIR-CONFIRM|\(sas4Code)|\(carNonce)|\(phoneNonce)"
            let sig = try CarCrypto.signEcdsaDerB64(privRaw: vault.getPhonePrivateKeyRaw(), canonical: canonical)
            sendJsonFrame(["type": "pair_phone_confirm", "sig": sig])
            lastActionFeedback = "Confirmed \(sas4Code) — tap 'Approve & Pair Phone' on car screen"
        } catch {
            resetSession(status: "Pairing signature error")
        }
    }

    func cancelPairing() {
        pendingSas4Code = nil
        forcePairingOnNextConnect = false
        resetSession(status: "Looking for car BT signal...")
    }

    func restartAfterKeyRestore() {
        pendingSas4Code = nil
        forcePairingOnNextConnect = false
        resetSession(status: "Security key restored — looking for car BT signal...")
        if subscribedCentral != nil {
            beginHandshake()
        } else {
            ensureAdvertising()
        }
    }

    func sendCommand(action: String, arg: String = "", theme: ClimateThemeModel? = nil, label: String) {
        guard isConnected, let key64 = sessionKey64 else {
            lastActionFeedback = "Bluetooth not connected"
            return
        }
        let reqId = "req_" + CarCrypto.randomHex(5)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        vault.recordCommandSent(id: reqId, label: label, transport: "BT", sentAtMs: nowMs, expiresAtMs: nowMs + 15_000)
        var inner: [String: Any] = [
            "op": "cmd",
            "reqId": reqId,
            "action": action,
            "arg": arg
        ]
        if let theme = theme {
            inner["climateTheme"] = theme.toDictionary()
        }
        guard let plainData = try? JSONSerialization.data(withJSONObject: inner),
              let plainStr = String(data: plainData, encoding: .utf8),
              let packed = try? CarCrypto.encryptSessionFrame(sessionKey64: key64, seq: txFrameSeq, plaintextJson: plainStr) else {
            vault.updateCommandLogState(id: reqId, state: "FAILED", nowMs: nowMs, detail: "Encryption failed")
            return
        }
        txFrameSeq += 1
        lastActionFeedback = "\(label) sent."
        sendJsonFrame(["type": "enc", "frame": packed])
    }

    func syncRemoteServerToCar(url: String, token: String) {
        guard isConnected, let key64 = sessionKey64 else { return }
        let inner: [String: Any] = [
            "op": "configure_remote_server",
            "reqId": "cfg_" + CarCrypto.randomHex(5),
            "remoteUrl": url,
            "remoteAccountToken": token
        ]
        guard let plainData = try? JSONSerialization.data(withJSONObject: inner),
              let plainStr = String(data: plainData, encoding: .utf8),
              let packed = try? CarCrypto.encryptSessionFrame(sessionKey64: key64, seq: txFrameSeq, plaintextJson: plainStr) else {
            return
        }
        txFrameSeq += 1
        sendJsonFrame(["type": "enc", "frame": packed])
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            setupGattServiceAndAdvertise()
        } else {
            resetSession(status: "Turn on Bluetooth to connect to car")
        }
    }

    private func setupGattServiceAndAdvertise() {
        guard let pm = peripheralManager, txCharacteristic == nil else {
            ensureAdvertising()
            return
        }
        let rxChar = CBMutableCharacteristic(
            type: Self.charCarToPhoneUuid,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let txChar = CBMutableCharacteristic(
            type: Self.charPhoneToCarUuid,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let svc = CBMutableService(type: Self.serviceUuid, primary: true)
        svc.characteristics = [rxChar, txChar]
        self.txCharacteristic = txChar
        pm.add(svc)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if error == nil {
            ensureAdvertising()
        }
    }

    private func ensureAdvertising() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        if !pm.isAdvertising {
            pm.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [Self.serviceUuid],
                CBAdvertisementDataLocalNameKey: "Gen5WCtl"
            ])
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentral = central
        statusMessage = "Negotiating encrypted link with car..."
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if subscribedCentral?.identifier == central.identifier {
            subscribedCentral = nil
            resetSession(status: "Looking for car BT signal...")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if req.characteristic.uuid == Self.charCarToPhoneUuid, let val = req.value {
                if let frame = acceptBlePacket(val) {
                    handleIncomingJson(frame)
                }
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushOutgoingChunks()
    }

    // MARK: - Protocol Handshake & Encrypted Frames

    private func beginHandshake() {
        phoneEphPriv = P256.KeyAgreement.PrivateKey()
        phoneEphPubB64 = phoneEphPriv.publicKey.derRepresentation.base64EncodedString()
        phoneNonce = CarCrypto.randomHex(16)
        txFrameSeq = 1
        minRxFrameSeq = 1
        sessionKey64 = nil

        if forcePairingOnNextConnect || !vault.isCarPaired {
            sendJsonFrame([
                "type": "pair_hello",
                "phoneId": vault.phoneId,
                "phoneName": vault.phoneDisplayName,
                "platform": "ios",
                "phoneStaticPub": vault.phonePubSpkiB64,
                "phoneEphPub": phoneEphPubB64,
                "phoneNonce": phoneNonce
            ])
        } else {
            sendJsonFrame([
                "type": "auth_hello",
                "phoneId": vault.phoneId,
                "phoneEphPub": phoneEphPubB64,
                "phoneNonce": phoneNonce
            ])
        }
    }

    private func handleIncomingJson(_ raw: String) {
        guard let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = obj["type"] as? String else {
            return
        }

        switch type {
        case "car_ready":
            beginHandshake()

        case "pair_challenge":
            carId = obj["carId"] as? String ?? ""
            carName = obj["carName"] as? String ?? "Ioniq 5"
            carStaticPubB64 = obj["carStaticPub"] as? String ?? ""
            let carEphPubB64 = obj["carEphPub"] as? String ?? ""
            carNonce = obj["carNonce"] as? String ?? ""

            guard let code = try? CarCrypto.deriveSas4Code(
                myEphemeralPriv: phoneEphPriv,
                peerEphemeralPubSpkiB64: carEphPubB64,
                carStaticPubB64: carStaticPubB64,
                phoneStaticPubB64: vault.phonePubSpkiB64,
                carEphPubB64: carEphPubB64,
                phoneEphPubB64: phoneEphPubB64,
                carNonce: carNonce,
                phoneNonce: phoneNonce
            ),
            let key64 = try? CarCrypto.deriveBtSessionKey64(
                myEphemeralPriv: phoneEphPriv,
                peerEphemeralPubSpkiB64: carEphPubB64,
                carNonce: carNonce,
                phoneNonce: phoneNonce
            ) else {
                resetSession(status: "Pairing key derivation failed")
                return
            }
            self.sas4Code = code
            self.sessionKey64 = key64
            self.pendingCarName = carName
            self.pendingSas4Code = code

        case "pair_complete":
            let pairedAt = (obj["pairedAtMs"] as? NSNumber)?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)
            let grantSig = obj["pairingGrantSig"] as? String ?? ""
            let remUrl = obj["remoteUrl"] as? String ?? ""
            vault.savePairedCar(
                carId: carId,
                carName: carName,
                carPubB64: carStaticPubB64,
                pairedAtMs: pairedAt,
                grantSigB64: grantSig,
                remoteUrl: remUrl
            )
            forcePairingOnNextConnect = false
            pendingSas4Code = nil
            isConnected = true
            statusMessage = "Connected to \(vault.pairedCarName) via Bluetooth"
            lastActionFeedback = "✓ Paired with \(vault.pairedCarName)"

        case "auth_challenge":
            let cId = obj["carId"] as? String ?? ""
            let carEphPubB64 = obj["carEphPub"] as? String ?? ""
            carNonce = obj["carNonce"] as? String ?? ""
            let carSig = obj["carSig"] as? String ?? ""

            guard cId == vault.pairedCarId else {
                resetSession(status: "Looking for car BT signal...")
                return
            }
            let expectedCarCanonical = "GEN5W-AUTH-CAR|\(vault.phoneId)|\(phoneNonce)|\(carNonce)|\(carEphPubB64)"
            guard CarCrypto.verifyEcdsaDerB64(
                pubSpkiB64: vault.pairedCarPubSpkiB64,
                canonical: expectedCarCanonical,
                sigDerB64: carSig
            ),
            let key64 = try? CarCrypto.deriveBtSessionKey64(
                myEphemeralPriv: phoneEphPriv,
                peerEphemeralPubSpkiB64: carEphPubB64,
                carNonce: carNonce,
                phoneNonce: phoneNonce
            ) else {
                resetSession(status: "Car signature verification failed")
                return
            }
            self.sessionKey64 = key64
            let phoneCanonical = "GEN5W-AUTH-PHONE|\(cId)|\(carNonce)|\(phoneNonce)|\(phoneEphPubB64)"
            if let phoneSig = try? CarCrypto.signEcdsaDerB64(privRaw: vault.getPhonePrivateKeyRaw(), canonical: phoneCanonical) {
                sendJsonFrame(["type": "auth_verify", "phoneSig": phoneSig])
            }

        case "auth_ok":
            if let carRemUrl = obj["remoteUrl"] as? String,
               carRemUrl.hasPrefix("https://"),
               !vault.isRemoteServerConfigured {
                vault.setRemoteSettings(url: carRemUrl, token: vault.remoteAccountToken)
            }
            isConnected = true
            statusMessage = "Connected to \(vault.pairedCarName) via Bluetooth"

        case "enc":
            guard let key64 = sessionKey64,
                  let packed = obj["frame"] as? String,
                  let (seq, plain) = try? CarCrypto.decryptSessionFrame(
                      sessionKey64: key64,
                      minAllowedSeq: minRxFrameSeq,
                      packedFrame: packed
                  ),
                  let innerData = plain.data(using: .utf8),
                  let inner = (try? JSONSerialization.jsonObject(with: innerData)) as? [String: Any] else {
                return
            }
            minRxFrameSeq = seq + 1
            handleDecryptedInner(inner)

        case "pair_error", "auth_error", "protocol_error":
            let code = obj["code"] as? String ?? ""
            let reason = obj["reason"] as? String ?? "Connection refused by car"
            if code == "UNTRUSTED" {
                vault.clearPairedCar()
            }
            pendingSas4Code = nil
            lastActionFeedback = "⚠ \(reason)"
            resetSession(status: "Looking for car BT signal...")

        default:
            break
        }
    }

    private func handleDecryptedInner(_ inner: [String: Any]) {
        let op = inner["op"] as? String ?? ""
        if op == "telemetry" {
            let vals = (inner["values"] as? [String: String]) ?? [:]
            let ts = (inner["updatedMs"] as? NSNumber)?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)
            vault.saveSnapshot(VehicleTelemetrySnapshot(values: vals, updatedMs: ts))
        } else if op == "cmd_result" {
            let reqId = (inner["reqId"] as? String) ?? ""
            let ok = (inner["ok"] as? Bool) ?? false
            let msg = (inner["message"] as? String) ?? ""
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            if !reqId.isEmpty {
                vault.updateCommandLogState(
                    id: reqId,
                    state: ok ? "EXECUTED" : "FAILED",
                    nowMs: nowMs,
                    detail: msg
                )
            }
            lastActionFeedback = (ok ? "✓ " : "⚠ ") + msg
            if let vals = inner["values"] as? [String: String] {
                vault.saveSnapshot(VehicleTelemetrySnapshot(
                    values: vals,
                    updatedMs: nowMs
                ))
            }
        }
    }

    // MARK: - BLE Chunking & Reassembly (matches ProtocolFrames.java)

    private func sendJsonFrame(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let jsonStr = String(data: data, encoding: .utf8) else { return }
        let maxLen = max(20, min(180, subscribedCentral?.maximumUpdateValueLength ?? 20))
        let sliceCap = maxLen - 2
        let payload = Array(jsonStr.utf8)
        let totalChunks = max(1, (payload.count + sliceCap - 1) / sliceCap)
        let id6 = msgSeq & 0x3F
        msgSeq += 1

        for idx in 0..<totalChunks {
            let start = idx * sliceCap
            let len = min(sliceCap, payload.count - start)
            var flags = id6
            if idx == 0 { flags |= 0x80 }
            if idx == totalChunks - 1 { flags |= 0x40 }
            var pkt = Data(capacity: 2 + len)
            pkt.append(UInt8(flags))
            pkt.append(UInt8(idx & 0xFF))
            if len > 0 {
                pkt.append(contentsOf: payload[start..<(start + len)])
            }
            outgoingChunks.append(pkt)
        }
        flushOutgoingChunks()
    }

    private func flushOutgoingChunks() {
        guard let pm = peripheralManager, let tx = txCharacteristic else { return }
        while !outgoingChunks.isEmpty {
            let next = outgoingChunks[0]
            let sent = pm.updateValue(next, for: tx, onSubscribedCentrals: nil)
            if sent {
                outgoingChunks.removeFirst()
            } else {
                break // Wait for peripheralManagerIsReady(toUpdateSubscribers:)
            }
        }
    }

    private func acceptBlePacket(_ pkt: Data) -> String? {
        guard pkt.count >= 2 else { return nil }
        let b0 = Int(pkt[0])
        let isStart = (b0 & 0x80) != 0
        let isEnd = (b0 & 0x40) != 0
        let msgId = b0 & 0x3F
        let idx = Int(pkt[1])

        if isStart {
            rxBuffer.removeAll(keepingCapacity: true)
            currentMsgId = msgId
            expectedChunkIdx = 0
        }
        guard msgId == currentMsgId, idx == expectedChunkIdx else {
            rxBuffer.removeAll()
            currentMsgId = -1
            return nil
        }
        if pkt.count > 2 {
            rxBuffer.append(pkt.subdata(in: 2..<pkt.count))
        }
        expectedChunkIdx += 1
        if isEnd {
            let str = String(data: rxBuffer, encoding: .utf8)
            rxBuffer.removeAll(keepingCapacity: true)
            currentMsgId = -1
            expectedChunkIdx = 0
            return str
        }
        return nil
    }

    private func resetSession(status: String) {
        isConnected = false
        sessionKey64 = nil
        statusMessage = status
        ensureAdvertising()
    }
}
