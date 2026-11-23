import CommonCrypto
import CryptoKit
import Foundation

/// Byte-for-byte wire-compatible cryptography matching Java `CryptoBox` & `RemoteEnvelope`
/// on Android 4.4.2 KitKat (Gen5W Head Unit) and the remote relay protocol.
enum CarCrypto {
    static let sasAlphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

    struct EncryptedBoxPayload {
        let ephemeralPubSpkiB64: String
        let ivB64: String
        let ciphertextB64: String
        let macB64: String
    }

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    static func randomHex(_ byteCount: Int) -> String {
        toHex(randomBytes(byteCount))
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    static func sha256Hex(_ data: Data) -> String {
        toHex(sha256(data))
    }

    static func toHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func signEcdsaDerB64(privRaw: Data, canonical: String) throws -> String {
        let priv = try P256.Signing.PrivateKey(rawRepresentation: privRaw)
        let sig = try priv.signature(for: Data(canonical.utf8))
        return sig.derRepresentation.base64EncodedString()
    }

    static func verifyEcdsaDerB64(pubSpkiB64: String, canonical: String, sigDerB64: String) -> Bool {
        guard let pubDer = Data(base64Encoded: pubSpkiB64),
              let sigDer = Data(base64Encoded: sigDerB64),
              let pub = try? P256.Signing.PublicKey(derRepresentation: pubDer),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: sigDer) else {
            return false
        }
        return pub.isValidSignature(sig, for: Data(canonical.utf8))
    }

    static func deriveSas4Code(
        myEphemeralPriv: P256.KeyAgreement.PrivateKey,
        peerEphemeralPubSpkiB64: String,
        carStaticPubB64: String,
        phoneStaticPubB64: String,
        carEphPubB64: String,
        phoneEphPubB64: String,
        carNonce: String,
        phoneNonce: String
    ) throws -> String {
        guard let peerDer = Data(base64Encoded: peerEphemeralPubSpkiB64) else {
            throw NSError(domain: "CarCrypto", code: -1, userInfo: [NSLocalizedDescriptionKey: "Bad peer ephemeral SPKI"])
        }
        let peerPub = try P256.KeyAgreement.PublicKey(derRepresentation: peerDer)
        let transcript = "GEN5W-SAS-V1|\(carStaticPubB64)|\(phoneStaticPubB64)|\(carEphPubB64)|\(phoneEphPubB64)|\(carNonce)|\(phoneNonce)"
        let salt = sha256(Data(transcript.utf8))
        let shared = try myEphemeralPriv.sharedSecretFromKeyAgreement(with: peerPub)
        let okm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("GEN5W-SAS4".utf8),
            outputByteCount: 4
        )
        let rawBytes: [UInt8] = okm.withUnsafeBytes { Array($0) }
        return String(rawBytes.map { sasAlphabet[Int($0) % 32] })
    }

    static func deriveBtSessionKey64(
        myEphemeralPriv: P256.KeyAgreement.PrivateKey,
        peerEphemeralPubSpkiB64: String,
        carNonce: String,
        phoneNonce: String
    ) throws -> Data {
        guard let peerDer = Data(base64Encoded: peerEphemeralPubSpkiB64) else {
            throw NSError(domain: "CarCrypto", code: -2, userInfo: [NSLocalizedDescriptionKey: "Bad peer key"])
        }
        let peerPub = try P256.KeyAgreement.PublicKey(derRepresentation: peerDer)
        let salt = sha256(Data("\(carNonce)|\(phoneNonce)".utf8))
        let shared = try myEphemeralPriv.sharedSecretFromKeyAgreement(with: peerPub)
        let key = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("GEN5W-BT-SESS-V1".utf8),
            outputByteCount: 64
        )
        return key.withUnsafeBytes { Data($0) }
    }

    static func encryptToPublicKey(
        recipientStaticPubSpkiB64: String,
        contextSalt: String,
        aad: String,
        plaintextUtf8: String
    ) throws -> EncryptedBoxPayload {
        guard let recDer = Data(base64Encoded: recipientStaticPubSpkiB64) else {
            throw NSError(domain: "CarCrypto", code: -3, userInfo: [NSLocalizedDescriptionKey: "Invalid car public key"])
        }
        let recPub = try P256.KeyAgreement.PublicKey(derRepresentation: recDer)
        let ephPriv = P256.KeyAgreement.PrivateKey()
        let ephPubB64 = ephPriv.publicKey.derRepresentation.base64EncodedString()

        let shared = try ephPriv.sharedSecretFromKeyAgreement(with: recPub)
        let okm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(contextSalt.utf8),
            sharedInfo: Data("GEN5W-E2EE-V1".utf8),
            outputByteCount: 64
        )
        let keyData = okm.withUnsafeBytes { Data($0) }
        let encKey = keyData.subdata(in: 0..<32)
        let macKey = keyData.subdata(in: 32..<64)

        let iv = randomBytes(16)
        let ct = try aes256CbcCrypt(encrypt: true, key: encKey, iv: iv, input: Data(plaintextUtf8.utf8))
        let effectiveAad = Data("\(aad)|\(ephPubB64)".utf8)
        let mac = computeEtMTag(macKey: macKey, aad: effectiveAad, iv: iv, ct: ct)

        return EncryptedBoxPayload(
            ephemeralPubSpkiB64: ephPubB64,
            ivB64: iv.base64EncodedString(),
            ciphertextB64: ct.base64EncodedString(),
            macB64: mac.base64EncodedString()
        )
    }

    static func decryptWithPrivateKey(
        recipientPrivRaw: Data,
        contextSalt: String,
        aad: String,
        box: EncryptedBoxPayload
    ) throws -> String {
        guard let ephDer = Data(base64Encoded: box.ephemeralPubSpkiB64),
              let iv = Data(base64Encoded: box.ivB64),
              let ct = Data(base64Encoded: box.ciphertextB64),
              let mac = Data(base64Encoded: box.macB64),
              iv.count == 16, mac.count == 32, !ct.isEmpty, ct.count % 16 == 0 else {
            throw NSError(domain: "CarCrypto", code: -4, userInfo: [NSLocalizedDescriptionKey: "Malformed E2EE envelope"])
        }
        let recPriv = try P256.KeyAgreement.PrivateKey(rawRepresentation: recipientPrivRaw)
        let ephPub = try P256.KeyAgreement.PublicKey(derRepresentation: ephDer)
        let shared = try recPriv.sharedSecretFromKeyAgreement(with: ephPub)
        let okm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(contextSalt.utf8),
            sharedInfo: Data("GEN5W-E2EE-V1".utf8),
            outputByteCount: 64
        )
        let keyData = okm.withUnsafeBytes { Data($0) }
        let encKey = keyData.subdata(in: 0..<32)
        let macKey = keyData.subdata(in: 32..<64)

        let effectiveAad = Data("\(aad)|\(box.ephemeralPubSpkiB64)".utf8)
        let expectedMac = computeEtMTag(macKey: macKey, aad: effectiveAad, iv: iv, ct: ct)
        guard constantTimeEquals(expectedMac, mac) else {
            throw NSError(domain: "CarCrypto", code: -5, userInfo: [NSLocalizedDescriptionKey: "E2EE HMAC mismatch"])
        }
        let plain = try aes256CbcCrypt(encrypt: false, key: encKey, iv: iv, input: ct)
        guard let str = String(data: plain, encoding: .utf8) else {
            throw NSError(domain: "CarCrypto", code: -6, userInfo: [NSLocalizedDescriptionKey: "Invalid UTF-8 plaintext"])
        }
        return str
    }

    static func encryptSessionFrame(sessionKey64: Data, seq: Int64, plaintextJson: String) throws -> String {
        let encKey = sessionKey64.subdata(in: 0..<32)
        let macKey = sessionKey64.subdata(in: 32..<64)
        let iv = randomBytes(16)
        let ct = try aes256CbcCrypt(encrypt: true, key: encKey, iv: iv, input: Data(plaintextJson.utf8))
        let aad = Data("SEQ:\(seq)".utf8)
        let mac = computeEtMTag(macKey: macKey, aad: aad, iv: iv, ct: ct)
        return "\(seq).\(iv.base64EncodedString()).\(ct.base64EncodedString()).\(mac.base64EncodedString())"
    }

    static func decryptSessionFrame(sessionKey64: Data, minAllowedSeq: Int64, packedFrame: String) throws -> (Int64, String) {
        let parts = packedFrame.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4,
              let seq = Int64(parts[0]),
              seq >= minAllowedSeq,
              let iv = Data(base64Encoded: String(parts[1])),
              let ct = Data(base64Encoded: String(parts[2])),
              let mac = Data(base64Encoded: String(parts[3])),
              iv.count == 16, mac.count == 32, !ct.isEmpty, ct.count % 16 == 0 else {
            throw NSError(domain: "CarCrypto", code: -7, userInfo: [NSLocalizedDescriptionKey: "Invalid session frame"])
        }
        let encKey = sessionKey64.subdata(in: 0..<32)
        let macKey = sessionKey64.subdata(in: 32..<64)
        let expected = computeEtMTag(macKey: macKey, aad: Data("SEQ:\(seq)".utf8), iv: iv, ct: ct)
        guard constantTimeEquals(expected, mac) else {
            throw NSError(domain: "CarCrypto", code: -8, userInfo: [NSLocalizedDescriptionKey: "Session MAC mismatch"])
        }
        let plain = try aes256CbcCrypt(encrypt: false, key: encKey, iv: iv, input: ct)
        guard let str = String(data: plain, encoding: .utf8) else {
            throw NSError(domain: "CarCrypto", code: -9, userInfo: [NSLocalizedDescriptionKey: "Bad UTF-8"])
        }
        return (seq, str)
    }

    private static func computeEtMTag(macKey: Data, aad: Data, iv: Data, ct: Data) -> Data {
        var hmac = HMAC<SHA256>(key: SymmetricKey(data: macKey))
        var lenBe = UInt32(aad.count).bigEndian
        withUnsafeBytes(of: &lenBe) { hmac.update(data: $0) }
        hmac.update(data: aad)
        hmac.update(data: iv)
        hmac.update(data: ct)
        return Data(hmac.finalize())
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= (a[i] ^ b[i])
        }
        return diff == 0
    }

    private static func aes256CbcCrypt(encrypt: Bool, key: Data, iv: Data, input: Data) throws -> Data {
        let outCap = input.count + kCCBlockSizeAES128
        var outBytes = [UInt8](repeating: 0, count: outCap)
        var outMoved: size_t = 0
        let status = key.withUnsafeBytes { keyPtr in
            iv.withUnsafeBytes { ivPtr in
                input.withUnsafeBytes { inPtr in
                    CCCrypt(
                        CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionPKCS7Padding),
                        keyPtr.baseAddress, kCCKeySizeAES256,
                        ivPtr.baseAddress,
                        inPtr.baseAddress, input.count,
                        &outBytes, outCap,
                        &outMoved
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw NSError(domain: "CarCrypto", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "AES-256-CBC failed"])
        }
        return Data(outBytes.prefix(outMoved))
    }

    // MARK: - Cross-Platform Passphrase Key Backup (Matches Java CryptoBox & KeyBackupBundle)

    struct PassphraseBoxPayload {
        let iterations: Int
        let saltB64: String
        let ivB64: String
        let ciphertextB64: String
        let macB64: String
    }

    static func pbkdf2HmacSha256(passphrase: String, salt: Data, iterations: Int, dkLen: Int) throws -> Data {
        let passData = Data(passphrase.utf8)
        var derived = [UInt8](repeating: 0, count: dkLen)
        let status = passData.withUnsafeBytes { passRaw in
            salt.withUnsafeBytes { saltRaw in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passRaw.bindMemory(to: Int8.self).baseAddress,
                    passData.count,
                    saltRaw.bindMemory(to: UInt8.self).baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derived,
                    dkLen
                )
            }
        }
        guard status == kCCSuccess else {
            throw NSError(domain: "CarCrypto", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "PBKDF2-HMAC-SHA256 failed"])
        }
        return Data(derived)
    }

    static func encryptWithPassphrase(
        passphrase: String,
        aadDomain: String,
        iterations: Int = 100_000,
        plaintextUtf8: String
    ) throws -> PassphraseBoxPayload {
        guard passphrase.count >= 6 else {
            throw NSError(domain: "CarCrypto", code: -20, userInfo: [NSLocalizedDescriptionKey: "Passphrase must be at least 6 characters"])
        }
        let iter = max(20_000, iterations)
        let salt = randomBytes(16)
        let iv = randomBytes(16)
        let key64 = try pbkdf2HmacSha256(passphrase: passphrase, salt: salt, iterations: iter, dkLen: 64)
        let encKey = key64.subdata(in: 0..<32)
        let macKey = key64.subdata(in: 32..<64)

        let ct = try aes256CbcCrypt(encrypt: true, key: encKey, iv: iv, input: Data(plaintextUtf8.utf8))
        let saltB64 = salt.base64EncodedString()
        let aad = Data("\(aadDomain)|\(iter)|\(saltB64)".utf8)
        let mac = computeEtMTag(macKey: macKey, aad: aad, iv: iv, ct: ct)

        return PassphraseBoxPayload(
            iterations: iter,
            saltB64: saltB64,
            ivB64: iv.base64EncodedString(),
            ciphertextB64: ct.base64EncodedString(),
            macB64: mac.base64EncodedString()
        )
    }

    static func decryptWithPassphrase(
        passphrase: String,
        aadDomain: String,
        box: PassphraseBoxPayload
    ) throws -> String {
        guard !passphrase.isEmpty,
              box.iterations >= 10_000, box.iterations <= 2_000_000,
              let salt = Data(base64Encoded: box.saltB64),
              let iv = Data(base64Encoded: box.ivB64),
              let ct = Data(base64Encoded: box.ciphertextB64),
              let mac = Data(base64Encoded: box.macB64),
              salt.count >= 16, iv.count == 16, mac.count == 32, !ct.isEmpty, ct.count % 16 == 0 else {
            throw NSError(domain: "CarCrypto", code: -21, userInfo: [NSLocalizedDescriptionKey: "Malformed encrypted backup file"])
        }

        let key64 = try pbkdf2HmacSha256(passphrase: passphrase, salt: salt, iterations: box.iterations, dkLen: 64)
        let encKey = key64.subdata(in: 0..<32)
        let macKey = key64.subdata(in: 32..<64)

        let aad = Data("\(aadDomain)|\(box.iterations)|\(box.saltB64)".utf8)
        let expectedMac = computeEtMTag(macKey: macKey, aad: aad, iv: iv, ct: ct)
        guard constantTimeEquals(expectedMac, mac) else {
            throw NSError(domain: "CarCrypto", code: -22, userInfo: [NSLocalizedDescriptionKey: "Incorrect passphrase or corrupted backup file"])
        }

        let plain = try aes256CbcCrypt(encrypt: false, key: encKey, iv: iv, input: ct)
        guard let str = String(data: plain, encoding: .utf8) else {
            throw NSError(domain: "CarCrypto", code: -23, userInfo: [NSLocalizedDescriptionKey: "Invalid UTF-8 backup payload"])
        }
        return str
    }
}
