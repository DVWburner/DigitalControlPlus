# Gen5W Digital Control — Self-Hostable Zero-Trust E2EE Remote Relay Specification

> **Standalone Server Agent Handoff:** For a 100% self-contained copy-paste prompt for an external server agent with zero access to this repository, use [`HANDOFF_PROMPT.md`](./HANDOFF_PROMPT.md).

This specification defines the exact HTTPS, JSON, ECDSA Proof-of-Possession, End-to-End Encryption (E2EE), 5-minute expiration, command cancellation, and self-hostable multi-user contract implemented in the unified **Gen5W Digital Control (`v1.0.3+`)** clients:
- **In-Car Head Unit Relay Worker:** [`CarRemoteRelayWorker.java`](../car-app/src/common/com/gen5w/digitalcontrol/edition/CarRemoteRelayWorker.java) (Android 4.4.2 KitKat, TLS 1.2 + ISRG Root X1/X2; completely idle until the user specifies an `https://...` server URL)
- **Android Companion Phone Client:** [`PhoneRemoteClient.java`](../phone-android/src/common/com/gen5w/digitalcontrol/phone/edition/PhoneRemoteClient.java)
- **iOS Companion Phone Client:** [`RemoteRelayService.swift`](../phone-ios/Gen5WDigitalControl/Services/RemoteRelayService.swift)
- **Shared Crypto & Envelope Primitives:** [`CryptoBox.java`](../shared/java/com/gen5w/digitalcontrol/shared/CryptoBox.java), [`RemoteEnvelope.java`](../shared/java/com/gen5w/digitalcontrol/shared/RemoteEnvelope.java), [`CarCrypto.swift`](../phone-ios/Gen5WDigitalControl/Crypto/CarCrypto.swift)

---

## 1. Security Model: Zero-Trust End-to-End Encrypted Relay

The self-hosted relay server (e.g. `https://remote.example.com`) acts strictly as a **blind, untrusted store-and-forward relay**:

1. **No Raw VIN or Hardware Serial Numbers Sent to Server:**
   - On initial PIN setup in the car, the head unit generates a 256-bit random `installSalt` and derives a pseudonymous `carId`:
     `carId = "car_" + SHA256_HEX("GEN5W-CAR-ID-V1|" + installSaltHex + "|" + hardwareHint)[0..24]`
   - Uninstalling and reinstalling the car app destroys `/data/data/<pkg>/files/trust_vault.json`, rotating both `carId` and the car's `EC P-256` keypair.
2. **In-Person Bluetooth Trust Ceremony (`PairingGrant`):**
   - A phone can **never** pair with a car over the internet. Pairing happens exclusively in-person over Bluetooth after the car owner unlocks Pairing Mode with their local **4–8 digit PIN** and confirms a matching **4-character SAS code** on both screens.
   - During Bluetooth pairing, the car signs a `PairingGrant` certificate with `carPrivateKey` (`EC P-256` / `secp256r1`, `SHA256withECDSA` ASN.1 DER Base64):
     ```text
     GEN5W-GRANT-V1|<carId>|<phoneId>|<SHA256_HEX(phonePubKeySpkiDer)>|<pairedAtMs>
     ```
3. **Why a Compromised/Hacked Server Cannot Forge or Read Commands:**
   - Every command inner JSON (`action`, `arg`, `climateTheme`, `nonce`, `issuedAtMs`, `expiresAtMs`) is **end-to-end encrypted** to `carPublicKey` on the phone using:
     - Ephemeral `EC P-256` ECDH -> 32-byte normalized big-endian `Z`
     - `HKDF-SHA256(ikm = Z, salt = UTF8(commandId), info = UTF8("GEN5W-E2EE-V1"), len = 64)` -> `encKey[0..32]` + `macKey[32..64]`
     - `AES-256-CBC-PKCS7` with random 16-byte `IV`
     - `HMAC-SHA256(macKey, INT32_BE(len(effAad)) || effAad || iv || ciphertext)` where `effAad = "GEN5W-AAD-V1|<commandId>|<carId>|<phoneId>|<issuedAtMs>|<expiresAtMs>|<ephemeralPubKeyB64>"`
   - The outer envelope is signed by `phonePrivateKey` (`SHA256withECDSA` DER Base64):
     ```text
     boxDigest = SHA256_HEX(UTF8("<ephemeralPubKey>.<iv>.<ciphertext>.<mac>"))
     canonical = "GEN5W-ENV-V1|<commandId>|<carId>|<phoneId>|<issuedAtMs>|<expiresAtMs>|" + boxDigest
     ```
   - Before touching vehicle hardware, the car independently verifies `phoneSignature` against its local `trust_vault.json` list of paired `phonePublicKey`s, checks `nowMs <= expiresAtMs + 90s` (`<= 5 min` TTL), checks its persistent `executedCommandIds` anti-replay ring buffer, and verifies the E2EE HMAC & inner bindings.

---

## 2. Scaling Architecture: Personal Mode vs. Wide Public Release

To support **both** zero-friction personal deployment today and wide multi-user public deployment tomorrow without changing client cryptography, the server should implement two configurable modes via `AUTH_MODE` env var:

| Mode | How It Works | Best For |
|---|---|---|
| **`device_pop_only`** *(Default for personal server)* | No user accounts/passwords required. Every endpoint is still 100% cryptographically authenticated using `EC P-256` Proof-of-Possession (`carSignature` + `phoneSignature` + car-signed `PairingGrant`), plus per-IP & per-`carId` rate limits. Random bots cannot enqueue commands because the server verifies `PairingGrant` + `phoneSignature` before storing anything! | Single owner / small trusted group with zero login friction. |
| **`account_plus_device_pop`** *(For wide public release)* | Requires `Authorization: Bearer <token>` (`POST /api/v1/auth/register` & `POST /api/v1/auth/login`) **in addition to** full E2EE + `EC P-256` Proof-of-Possession. Binds each `carId` to an account on first registration (`maxCarsPerAccount`, `maxCommandsPerMinute`, abuse suspension), while keeping 100% Zero-Trust E2EE so the server operator still cannot unlock or control anyone's car. | Hundreds/thousands of users with per-account quotas & abuse controls. |

---

## 3. Cryptographic Formats & Timestamp Rules

- **Public Keys (`carPublicKey`, `phonePublicKey`, `ephemeralPubKey`):** Base64 (RFC 4648, no line wraps) of **91-byte X.509 `SubjectPublicKeyInfo` (SPKI) DER** for NIST `P-256` (`secp256r1` / `prime256v1`).
- **Signatures (`carSignature`, `phoneSignature`, `carGrantSignature`, `X-Gen5W-Phone-Signature`):** Base64 of **ASN.1 DER `ecdsa-with-SHA256`** over the UTF-8 bytes of the canonical string.
- **Request Freshness Window:** Reject any signed `timestampMs` (`ts`) where `abs(serverNowMs - timestampMs) > 120_000` (2 minutes) to prevent replay of poll/cancel/status requests.
- **HTTPS Mandatory:** Clients refuse plain `http://` URLs. Serve TLS 1.2 + TLS 1.3 with standard Let's Encrypt (ISRG Root X1/X2), Google Trust Services, or DigiCert certificates (remember Android 4.4.2 in-car requires **TLS 1.2** enabled with `TLS_ECDHE_*` cipher suites — do not disable TLS 1.2 in Nginx/Caddy/Cloudflare!).

---

## 4. HTTP Endpoints Contract

### 4.1 Server Info (`GET /api/v1/server-info`)
Returns server health and active authentication policy:
```json
{
  "ok": true,
  "service": "gen5w-remote-relay",
  "apiVersion": 1,
  "authMode": "device_pop_only",
  "maxTtlSeconds": 300
}
```

### 4.2 Optional Account Auth (`account_plus_device_pop` mode)
Standard Bearer token endpoints for multi-user deployments:
- `POST /api/v1/auth/register` (`{ "username", "password" }`) -> `201 Created` `{ "ok": true, "token": "...", "expiresAt": "..." }`
- `POST /api/v1/auth/login` (`{ "username", "password" }`) -> `200 OK` `{ "ok": true, "token": "...", "expiresAt": "..." }`
- `GET /api/v1/auth/me` (`Authorization: Bearer <token>`) -> `200 OK`

---

### 4.3 Car Enrollment & Trust Sync (`POST /api/v1/cars/register`)
Called opportunistically by `CarRemoteRelayWorker` on startup and whenever paired phones are added or revoked.

**Canonical string verified with `carPublicKey`:**
```text
GEN5W-CAR-REG-V1|<carId>|<SHA256_HEX(Base64Decode(carPublicKey))>|<timestampMs>
```

**Request Body:**
```json
{
  "carId": "car_9f4a2c8e11047d3b6a21e8c0",
  "carPublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
  "timestampMs": 1795192000123,
  "carSignature": "MEUCIQ...",
  "authorizedPhones": [
    {
      "phoneId": "ph_77a1c09e22b44108aa12",
      "phoneName": "Driver's iPhone",
      "platform": "ios",
      "phonePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
      "pairedAtMs": 1795191500000,
      "carGrantSignature": "MEYCIQ..."
    }
  ]
}
```

**Server Validation Rules:**
1. Validate `carId` matches `^car_[0-9a-f]{24}$`.
2. Decode `carPublicKey` SPKI DER and verify `carSignature` over `GEN5W-CAR-REG-V1|...`.
3. If `carId` already exists in DB, verify `carPublicKey` matches the stored `carPublicKey` (`403 Forbidden` if another key tries to hijack an existing `carId`).
4. Verify each entry's `carGrantSignature` against `carPublicKey` over `GEN5W-GRANT-V1|<carId>|<phoneId>|<SHA256_HEX(phonePubDer)>|<pairedAtMs>`.
5. Replace the stored authorized phones set for `carId` with this authoritative list (so phones revoked in-car are immediately revoked on the server too!).
6. Return `200 OK`: `{ "ok": true, "carId": "car_...", "authorizedCount": 1 }`.

---

### 4.4 Enqueue E2EE Command (`POST /api/v1/commands`)
Called by the phone app when Bluetooth is unavailable and the user triggers a command or Climate Theme.

**Request Body:**
```json
{
  "commandId": "cmd_4d8f29e0a1124c8bb0129f8a771234ef",
  "carId": "car_9f4a2c8e11047d3b6a21e8c0",
  "phoneId": "ph_77a1c09e22b44108aa12",
  "issuedAtMs": 1795192100000,
  "expiresAtMs": 1795192400000,
  "ttlSeconds": 300,
  "e2ee": {
    "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
    "iv": "8f2a...",
    "ciphertext": "kL9p...",
    "mac": "3vQx..."
  },
  "phoneSignature": "MEUCIQ...",
  "pairingProof": {
    "phonePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
    "pairedAtMs": 1795191500000,
    "carGrantSignature": "MEYCIQ..."
  },
  "clientHint": "Climate Theme: Warm Cozy 78°F"
}
```

**Server Validation Rules:**
1. Enforce payload size `<= 32 KB` and active queued commands per `carId` `<= 10`.
2. Enforce **5-minute maximum server timeout**:
   - Require `expiresAtMs > issuedAtMs` and `(expiresAtMs - issuedAtMs) <= 305_000`.
   - Clamp `serverExpiresAtMs = min(expiresAtMs, serverNowMs + 300_000)`!
3. Look up `carId`:
   - Verify `phoneId` is in `authorizedPhones` for `carId` (or if the car hasn't synced `/cars/register` yet since pairing, if `carId` is registered, verify `pairingProof.carGrantSignature` against `carPublicKey`!). If `carId` has never registered yet, return `404` `{ "ok": false, "error": "Car has not connected to remote server yet. Connect the car to Wi-Fi/Hotspot once after pairing." }`.
4. Verify `phoneSignature` using `phonePublicKey` over:
   `GEN5W-ENV-V1|<commandId>|<carId>|<phoneId>|<issuedAtMs>|<expiresAtMs>|<SHA256_HEX(ephemeralPubKey + "." + iv + "." + ciphertext + "." + mac)>`.
5. Store command with `status = "QUEUED"`, `serverExpiresAtMs`, and **immediately wake any active `/api/v1/car/poll` long-poll** waiting on `carId`!
6. Return `201 Created`:
   ```json
   {
     "ok": true,
     "commandId": "cmd_4d8f29e0a1124c8bb0129f8a771234ef",
     "status": "QUEUED",
     "expiresAtMs": 1795192400000
   }
   ```

---

### 4.5 Cancel Pending Command (`DELETE /api/v1/commands/{commandId}`)
Called when the user taps **Cancel** in the phone app before the car executes the command.

- **Query Parameters:** `?carId=<carId>&phoneId=<phoneId>&ts=<timestampMs>`
- **Header:** `X-Gen5W-Phone-Signature: <Base64 DER ECDSA>`
- **Canonical string verified with `phonePublicKey`:**
  ```text
  GEN5W-CANCEL-V1|<commandId>|<carId>|<phoneId>|<ts>
  ```
- **Behavior:**
  - Verify timestamp freshness (`<= 120s`) and `X-Gen5W-Phone-Signature` against the authorized `phonePublicKey` for `(carId, phoneId)`.
  - If command is currently `QUEUED`, atomically transition `status = "CANCELED"`, `message = "Canceled by phone"` so `/api/v1/car/poll` will **never** return it to the car!
  - If command was already `EXECUTED` or `EXPIRED`, return `409 Conflict` with `{ "ok": false, "status": "<currentStatus>", "error": "Command already <currentStatus>" }`.
  - On success return `200 OK`: `{ "ok": true, "commandId": "...", "status": "CANCELED" }`.

---

### 4.6 Poll Command Status & Encrypted Telemetry (`GET /api/v1/commands`)
Called by the phone app to watch pending command countdown/execution status and retrieve the latest E2EE vehicle telemetry blob addressed to `phoneId`.

- **Query Parameters:** `?carId=<carId>&phoneId=<phoneId>&ts=<timestampMs>`
- **Header:** `X-Gen5W-Phone-Signature: <Base64 DER ECDSA>`
- **Canonical string verified with `phonePublicKey`:**
  ```text
  GEN5W-STATUS-V1|<carId>|<phoneId>|<ts>
  ```
- **Behavior:**
  1. First sweep any `QUEUED` commands where `serverNowMs >= serverExpiresAtMs` -> mark `status = "EXPIRED"`, `message = "Timed out after 5 minutes"`.
  2. Return recent commands (last 15 minutes, newest first) for `(carId, phoneId)` plus the latest `encryptedTelemetry[phoneId]` uploaded by the car:
     ```json
     {
       "ok": true,
       "commands": [
         {
           "commandId": "cmd_4d8f29e0a1124c8bb0129f8a771234ef",
           "status": "EXECUTED",
           "issuedAtMs": 1795192100000,
           "expiresAtMs": 1795192400000,
           "executedAtMs": 1795192104210,
           "message": "Applied Climate Theme: Warm Cozy 78°F"
         }
       ],
       "encryptedTelemetry": {
         "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
         "iv": "...",
         "ciphertext": "...",
         "mac": "...",
         "updatedAtMs": 1795192104500
       }
     }
     ```

---

### 4.7 Car Poll & E2EE Telemetry Upload (`POST /api/v1/car/poll`)
Called opportunistically by `CarRemoteRelayWorker` whenever the Gen5W head unit has Wi-Fi or Bluetooth PAN internet.

**Canonical string verified with `carPublicKey`:**
```text
GEN5W-POLL-V1|<carId>|<timestampMs>|<nonce>
```

**Request Body:**
```json
{
  "carId": "car_9f4a2c8e11047d3b6a21e8c0",
  "timestampMs": 1795192103000,
  "nonce": "a91f002c8811ef09ab341122",
  "waitSeconds": 12,
  "carSignature": "MEUCIQ...",
  "encryptedTelemetry": {
    "ph_77a1c09e22b44108aa12": {
      "ephemeralPubKey": "...",
      "iv": "...",
      "ciphertext": "...",
      "mac": "...",
      "updatedAtMs": 1795192103000
    }
  }
}
```

**Server Behavior:**
1. Verify `carSignature` against registered `carPublicKey` and check `nonce` replay cache (`5 min` TTL).
2. Store latest `encryptedTelemetry` per `phoneId` (memory or DB, capped at `<= 16 KB` per phone).
3. Expire any `QUEUED` commands where `serverNowMs >= serverExpiresAtMs` (`status = "EXPIRED"`).
4. If one or more non-expired `QUEUED` commands exist for `carId`, return them immediately (ordered `issuedAtMs ASC`).
5. If queue is empty and `waitSeconds > 0` (`<= 15`), hold the HTTP request open (long-poll) for up to `waitSeconds` and wake immediately if `POST /api/v1/commands` enqueues a command for `carId`! Re-check `status == "QUEUED"` and `serverNowMs < serverExpiresAtMs` right before returning (in case the phone canceled it milliseconds later).
6. **Response (`200 OK`):**
   ```json
   {
     "ok": true,
     "serverTimeMs": 1795192103900,
     "commands": [
       {
         "commandId": "cmd_4d8f29e0a1124c8bb0129f8a771234ef",
         "carId": "car_9f4a2c8e11047d3b6a21e8c0",
         "phoneId": "ph_77a1c09e22b44108aa12",
         "issuedAtMs": 1795192100000,
         "expiresAtMs": 1795192400000,
         "e2ee": {
           "ephemeralPubKey": "...",
           "iv": "...",
           "ciphertext": "...",
           "mac": "..."
         },
         "phoneSignature": "..."
       }
     ]
   }
   ```

---

### 4.8 Car Execution Receipt (`POST /api/v1/car/ack`)
Called by the car immediately after executing (or rejecting) a polled command.

**Canonical string verified with `carPublicKey`:**
```text
GEN5W-ACK-V1|<commandId>|<carId>|<"1" if ok else "0">|<executedAtMs>|<SHA256_HEX(UTF8(message))>
```

**Request Body:**
```json
{
  "commandId": "cmd_4d8f29e0a1124c8bb0129f8a771234ef",
  "carId": "car_9f4a2c8e11047d3b6a21e8c0",
  "ok": true,
  "status": "EXECUTED",
  "message": "Applied Climate Theme: Warm Cozy 78°F",
  "executedAtMs": 1795192104210,
  "carSignature": "MEYCIQ..."
}
```

**Server Behavior:**
- Verify `carSignature` against `carPublicKey`.
- Mark command `status = ok ? "EXECUTED" : "FAILED"`, store `message` and `executedAtMs`, so the command is never returned again by `/api/v1/car/poll` and the phone immediately sees the result!

---

## 5. Server Operational & TLS Checklist for Gen5W Head Units

1. **TLS 1.2 Support is Mandatory:**
   - The Hyundai/Kia Gen5W head unit runs **Android 4.4.2 KitKat (API 19)**. `RemoteTlsHelper` enables `TLSv1.2` + `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` / `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` / `CBC` suites and embeds Let's Encrypt **ISRG Root X1 & X2**, **Google Trust Services R1 & R4**, and **DigiCert Global Root G2**.
   - Ensure your reverse proxy (Nginx / Caddy / Cloudflare) allows **TLS 1.2 AND TLS 1.3** (do NOT set TLS 1.3-only!).
2. **Background 5-Minute Expiry Sweeper:**
   - Run a periodic 15-second background timer (in addition to lazy expiry on read/poll) marking `UPDATE commands SET status='EXPIRED', message='Timed out after 5 minutes' WHERE status='QUEUED' AND server_expires_at_ms <= ?`, and pruning completed/expired command rows older than 24 hours so database storage stays tiny even at massive scale.
