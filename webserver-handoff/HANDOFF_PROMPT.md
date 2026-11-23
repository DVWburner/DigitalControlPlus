# Standalone Server Agent Handoff — Gen5W Digital Control Relay Server

> **Copy and paste this entire document directly to the Antigravity agent on your server / other computer.**
> It is **100% self-contained** — the server agent does **not** need access to any files from the client repository.

---

## Mission Prompt for the Server Agent

You are building the **self-hostable Zero-Trust End-to-End Encrypted (E2EE) Relay Server** for **Gen5W Digital Control** — an in-car Android 4.4.2 head-unit daemon/app + Android & iOS companion phone apps for Hyundai/Kia Wide Gen5W EVs.

### Product & Packaging Goals
1. **Primary Deployment + Distributable Self-Hosted Installer:**
   - The server must be ready to deploy cleanly on Linux or Windows (e.g. at `https://remote.example.com`), **AND** must be packaged as an easy-to-distribute **self-hosted server kit** (`docker-compose.yml` + `Dockerfile` + `.env.example` + automated `install.sh` / reverse-proxy examples for Caddy & Nginx) so other car owners can spin up their own private relay servers and enter their own `https://...` URL into the mobile/car apps.
2. **Zero-Trust Relay Design:**
   - The server **never** holds vehicle decryption keys and **never** sees plaintext vehicle commands or plaintext EV telemetry.
   - All commands are end-to-end encrypted by the phone to the car's `EC P-256` public key and signed by the phone's `EC P-256` private key.
   - Even so, the server **must cryptographically verify every ECDSA signature, Car Pairing Grant certificate, timestamp skew, and 5-minute TTL** before accepting, queueing, or canceling any command so unauthenticated traffic and replay attacks are rejected at the edge.
3. **Configurable Operator Access Modes (`AUTH_MODE` in `.env`):**
   - Both the in-car app and the Android/iOS phone apps send an optional header `Authorization: Bearer <token>` whenever the user enters an "Optional Server / Account Bearer Token" in settings. Support three operator modes via `.env`:
     - `AUTH_MODE=shared_token` *(Recommended default for self-hosters)*: Require `Authorization: Bearer <SERVER_SHARED_TOKEN>` on all `/api/v1/*` endpoints (except `/api/v1/health`) in addition to ECDSA signature verification. Generate a strong random token in `install.sh` if unset.
     - `AUTH_MODE=open`: Require only valid ECDSA signatures + Car Pairing Grant certificates (no Bearer token required).
     - `AUTH_MODE=accounts`: Validate Bearer tokens against an accounts/API-tokens table (include a simple CLI command such as `npm run token:create <label>` or `./relay-cli create-token` so multi-user hosts can issue per-user tokens easily).

---

## CRITICAL TLS & HTTP Requirements (Gen5W Android 4.4.2 KitKat Compatibility!)

The car head unit runs **Android 4.4.2 KitKat (API 19)** with a custom `SSLSocketFactory` (`RemoteTlsHelper`) that enables **`TLSv1.2`** and trusts **Let's Encrypt (`ISRG Root X1` / `X2`)**, **Google Trust Services (`GTS Root R1` / `R4`)**, and **DigiCert Global Root G2**.

Your reverse proxy / HTTPS listener **MUST** satisfy all four rules below, or the car head unit will fail the TLS handshake:
1. **Protocol Versions:** Enable **`TLSv1.2` AND `TLSv1.3`** (do **NOT** configure `TLSv1.3`-only — Android 4.4.2 maxes out at `TLSv1.2`!).
2. **ECDHE Elliptic Curves:** Must include **`prime256v1` (`secp256r1` / `P-256`)** and `secp384r1` (do **NOT** restrict ECDHE curves to `X25519`-only — KitKat `Conscrypt` does not support `X25519`!).
3. **TLS 1.2 Cipher Suites:** Include standard Mozilla Intermediate TLS 1.2 ciphers:
   - `ECDHE-ECDSA-AES128-GCM-SHA256`
   - `ECDHE-RSA-AES128-GCM-SHA256`
   - `ECDHE-ECDSA-AES256-GCM-SHA384`
   - `ECDHE-RSA-AES256-GCM-SHA384`
   - `ECDHE-RSA-AES128-SHA256`
   - `ECDHE-RSA-AES256-CBC-SHA`
4. **No HTTP Redirects on API Routes:** Both car and phone clients set `followRedirects = false` for security. Serve `/api/v1/...` directly with `200 OK` — never redirect `/api/v1/commands` to `/api/v1/commands/`.

*(Include ready-to-use **Nginx** `ssl_protocols TLSv1.2 TLSv1.3; ssl_ecdh_curve X25519:prime256v1:secp384r1;` and **Caddy** `protocols tls1.2 tls1.3` examples in the installer!)*

---

## Exact Cryptographic Specification (Byte-for-Byte Wire Compatibility)

### 1. Keys, Hashes, & Signatures
- **Curve:** NIST **P-256** (`secp256r1` / `prime256v1`).
- **Public Key Encoding (`carPublicKey`, `phonePublicKey`, `ephemeralPubKey`):**
  - Standard RFC 4648 **Base64** (no line wraps) of the **91-byte X.509 `SubjectPublicKeyInfo` (SPKI) DER** structure.
- **Public Key Fingerprint (`keyFingerprintHex(spkiB64)`):**
  - Decode `spkiB64` from Base64 into raw SPKI DER bytes (`91` bytes), compute `SHA-256` over those **decoded DER bytes** (NOT over the Base64 string!), and encode as **64 lowercase hexadecimal characters** (`[0-9a-f]{64}`).
- **ECDSA Signature Encoding (`carSignature`, `carGrantSignature`, `phoneSignature`, `X-Gen5W-Phone-Signature`):**
  - Standard RFC 4648 **Base64** of **ASN.1 DER `SHA256withECDSA`** (`SEQUENCE { r INTEGER, s INTEGER }`, typically 70–72 bytes) computed over the **UTF-8 bytes** of the canonical pipe-delimited string.
  - **IMPORTANT:** Java/Android (`SHA256withECDSA`) and Apple CryptoKit (`signature.derRepresentation`) produce **ASN.1 DER** signatures, **NOT** raw 64-byte IEEE P1363 `(r || s)` signatures!

#### Reference Verification Snippet (Node.js 20+ built-in `node:crypto` — zero native deps needed)
```js
import crypto from 'node:crypto';

export function sha256HexBytes(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex'); // lowercase hex
}

export function sha256HexUtf8(str) {
  return sha256HexBytes(Buffer.from(str, 'utf8'));
}

export function deriveKeyFingerprintHex(spkiB64) {
  const der = Buffer.from(spkiB64, 'base64');
  if (der.length < 80 || der.length > 120) {
    throw new Error('Invalid P-256 SPKI DER length');
  }
  return sha256HexBytes(der);
}

export function verifyEcdsaP256DerB64(spkiB64, canonicalUtf8, sigDerB64) {
  try {
    const pubKey = crypto.createPublicKey({
      key: Buffer.from(spkiB64, 'base64'),
      format: 'der',
      type: 'spki',
    });
    if (pubKey.asymmetricKeyType !== 'ec' || pubKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
      return false;
    }
    return crypto.verify(
      'sha256',
      Buffer.from(canonicalUtf8, 'utf8'),
      pubKey,
      Buffer.from(sigDerB64, 'base64') // Node verifies ASN.1 DER by default!
    );
  } catch {
    return false;
  }
}
```

### 2. Exact Canonical Signing Strings (7 Signatures Total)

Every signature in the protocol signs the exact UTF-8 string shown below (fields separated by literal `|`, no trailing newline):

| # | Purpose | Signed By | Canonical String Formula |
|---|---|---|---|
| **1** | **Car Registration** (`carSignature`) | Car Private Key | `"GEN5W-CAR-REG-V1\|" + carId + "\|" + deriveKeyFingerprintHex(carPublicKey) + "\|" + timestampMs` |
| **2** | **Car Pairing Grant** (`carGrantSignature`) | Car Private Key | `"GEN5W-GRANT-V1\|" + carId + "\|" + phoneId + "\|" + deriveKeyFingerprintHex(phonePublicKey) + "\|" + pairedAtMs` |
| **3** | **Command Submission** (`phoneSignature`) | Phone Private Key | `"GEN5W-ENV-V1\|" + commandId + "\|" + carId + "\|" + phoneId + "\|" + issuedAtMs + "\|" + expiresAtMs + "\|" + boxDigestHex` *(see `boxDigestHex` below!)* |
| **4** | **Phone Status Poll** (`X-Gen5W-Phone-Signature`) | Phone Private Key | `"GEN5W-STATUS-V1\|" + carId + "\|" + phoneId + "\|" + ts` |
| **5** | **Phone Cancel Command** (`X-Gen5W-Phone-Signature`) | Phone Private Key | `"GEN5W-CANCEL-V1\|" + commandId + "\|" + carId + "\|" + phoneId + "\|" + ts` |
| **6** | **Car Long-Poll** (`carSignature`) | Car Private Key | `"GEN5W-POLL-V1\|" + carId + "\|" + timestampMs + "\|" + nonce` |
| **7** | **Car Execution Receipt** (`carSignature`) | Car Private Key | `"GEN5W-ACK-V1\|" + commandId + "\|" + carId + "\|" + (ok ? "1" : "0") + "\|" + executedAtMs + "\|" + sha256HexUtf8(message)` |

#### Exact `boxDigestHex` Formula for Signature #3 (`GEN5W-ENV-V1`)
```js
// Concatenate the 4 Base64 strings from body.e2ee with literal '.' dots, then SHA-256 its UTF-8 bytes:
const boxJoined = `${e2ee.ephemeralPubKey}.${e2ee.iv}.${e2ee.ciphertext}.${e2ee.mac}`;
const boxDigestHex = sha256HexUtf8(boxJoined); // 64-char lowercase hex
```

### 3. Timestamp Skew, Replay Protection, & 5-Minute TTL Rules
- **Clock Skew Tolerance:** Reject signed requests whose `timestampMs` / `ts` / `issuedAtMs` differs from `Date.now()` by more than **`±90,000 ms` (`90 seconds`)**.
- **5-Minute Command Timeout (`300,000 ms`):**
  - Every command has `expiresAtMs = issuedAtMs + 300000` (5 minutes).
  - Reject `POST /api/v1/commands` if `expiresAtMs <= issuedAtMs` or `(expiresAtMs - issuedAtMs) > 305000`.
  - Any command still in `"QUEUED"` state once `Date.now() >= expiresAtMs` **MUST** automatically transition to `status = "EXPIRED"` with `message = "Timed out after 5 minutes"` and **MUST NEVER** be returned to `POST /api/v1/car/poll`.

---

## Complete HTTP API Specification

All request and response bodies are `application/json; charset=utf-8`.
Identifier formats:
- `carId`: `^car_[0-9a-f]{16,40}$` (e.g. `car_8f14e45fceea167a5a36dedd`)
- `phoneId`: `^ph_[0-9a-f]{12,40}$` (e.g. `ph_4c91a0f3b219e801d4c2`)
- `commandId`: `^cmd_[0-9a-f]{24,40}$` (e.g. `cmd_550e8400e29b41d4a716446655440000`)

---

### 1. `GET /api/v1/health`
Public health check (no Bearer token required).
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "service": "gen5w-digital-control-relay",
    "version": "1.0.0",
    "timeMs": 1763665000000
  }
  ```

---

### 2. `POST /api/v1/cars/register`
*(Note plural `cars` in `/api/v1/cars/register`!)*
Called by the car whenever it comes online or updates its paired phone trust list (e.g. after pairing or revoking a phone).

- **Request Body:**
  ```json
  {
    "carId": "car_8f14e45fceea167a5a36dedd",
    "carPublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
    "timestampMs": 1763665000123,
    "carSignature": "MEUCIQ...",
    "authorizedPhones": [
      {
        "phoneId": "ph_4c91a0f3b219e801d4c2",
        "phoneName": "Pixel 9 Pro",
        "platform": "android",
        "phonePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
        "pairedAtMs": 1763664000000,
        "carGrantSignature": "MEYCIQ..."
      }
    ]
  }
  ```
- **Server Verification & Storage:**
  1. Check `timestampMs` within `±90,000 ms` of `Date.now()`.
  2. Verify `carSignature` against `carPublicKey` using Signature #1 (`GEN5W-CAR-REG-V1|...`).
  3. **Trust-On-First-Use (TOFU) Pinning:**
     - If `carId` does not exist yet in DB, insert `(carId, carPublicKey)`.
     - If `carId` **already exists** in DB with a *different* `carPublicKey`, **reject with `403 Forbidden`**! *(When the car app is uninstalled/reinstalled, it generates a brand-new random `carId`, so a legitimate `carId` never changes its public key.)*
  4. Verify each entry in `authorizedPhones` using Signature #2 (`GEN5W-GRANT-V1|...`) against `carPublicKey`.
  5. **Replace** the stored authorized phone set for `carId` with `authorizedPhones` (so phones revoked on the car screen are immediately revoked on the server too!).
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "carId": "car_8f14e45fceea167a5a36dedd",
    "authorizedPhoneCount": 1
  }
  ```

---

### 3. `POST /api/v1/commands`
Called by the Android or iOS phone app when out of Bluetooth range to queue a signed, E2EE command.

- **Request Body:**
  ```json
  {
    "commandId": "cmd_550e8400e29b41d4a716446655440000",
    "carId": "car_8f14e45fceea167a5a36dedd",
    "phoneId": "ph_4c91a0f3b219e801d4c2",
    "issuedAtMs": 1763665010000,
    "expiresAtMs": 1763665310000,
    "ttlSeconds": 300,
    "e2ee": {
      "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
      "iv": "base64-16-bytes==",
      "ciphertext": "base64-aes256-cbc-ciphertext==",
      "mac": "base64-32-byte-hmac-sha256=="
    },
    "phoneSignature": "MEUCIQ...",
    "pairingProof": {
      "phonePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
      "pairedAtMs": 1763664000000,
      "carGrantSignature": "MEYCIQ..."
    },
    "clientHint": "Climate Theme: Warm Cozy 78°F"
  }
  ```
- **Server Verification & Queueing:**
  1. Look up `carId` in DB to get its pinned `carPublicKey`. (If `carId` has never registered yet, return `404` `{ "ok": false, "error": "Car has not connected to this relay server yet" }`.)
  2. Validate `issuedAtMs` within `±90,000 ms` of `Date.now()`, and `0 < (expiresAtMs - issuedAtMs) <= 305,000`.
  3. Authorize `phoneId`:
     - Either `phoneId` is in `carId`'s registered `authorizedPhones` with matching `phonePublicKey`, **OR** `pairingProof.carGrantSignature` verifies cleanly against `carId`'s pinned `carPublicKey` using Signature #2 (`GEN5W-GRANT-V1|...`) *and* `phoneId` was not explicitly revoked after `pairedAtMs` (track `lastRevocationSyncAtMs` or require membership once registered; allowing `pairingProof` enrollment if `pairedAtMs >= car.updatedAtMs` handles the case where a newly paired phone queues a command before the car next syncs!).
  4. Verify `phoneSignature` against `phonePublicKey` using Signature #3 (`GEN5W-ENV-V1|...`).
  5. Enforce uniqueness on `commandId` (reject duplicate `commandId` with `409`) and cap active `QUEUED` commands per `carId` (e.g. max `20`).
  6. Store the **exact outer JSON envelope** (`commandId`, `carId`, `phoneId`, `issuedAtMs`, `expiresAtMs`, `ttlSeconds`, `e2ee`, `phoneSignature`, `pairingProof`, `clientHint`) with `status = "QUEUED"`, and **immediately wake any active `/api/v1/car/poll` long-poll waiting on `carId`!**
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "commandId": "cmd_550e8400e29b41d4a716446655440000",
    "status": "QUEUED",
    "expiresAtMs": 1763665310000
  }
  ```

---

### 4. `GET /api/v1/commands?carId=<carId>&phoneId=<phoneId>&ts=<epochMs>`
Called by the phone app (every 3s while a command is `QUEUED`, or every 10s idle) to update command statuses and fetch the latest E2EE telemetry uploaded by the car for this `phoneId`.

- **Required Header:**
  - `X-Gen5W-Phone-Signature: <base64-DER-ECDSA>`
- **Server Verification:**
  1. Check `ts` within `±90,000 ms` of `Date.now()`.
  2. Verify `phoneId` is authorized for `carId` and verify `X-Gen5W-Phone-Signature` against `phonePublicKey` using Signature #4 (`"GEN5W-STATUS-V1|" + carId + "|" + phoneId + "|" + ts`).
  3. Sweep any `"QUEUED"` commands with `Date.now() >= expiresAtMs` to `"EXPIRED"` (`message = "Timed out after 5 minutes"`).
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "commands": [
      {
        "commandId": "cmd_550e8400e29b41d4a716446655440000",
        "status": "QUEUED",
        "issuedAtMs": 1763665010000,
        "expiresAtMs": 1763665310000,
        "executedAtMs": 0,
        "message": "Waiting for car internet..."
      }
    ],
    "encryptedTelemetry": {
      "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
      "iv": "base64-16-bytes==",
      "ciphertext": "base64-ciphertext==",
      "mac": "base64-32-bytes==",
      "updatedAtMs": 1763665005000
    }
  }
  ```
  *(Omit `encryptedTelemetry` or set to `null` if the car has not uploaded an encrypted telemetry blob for this `phoneId` yet. Return the 20 most recent commands for `(carId, phoneId)`.)*

---

### 5. `DELETE /api/v1/commands/:commandId?carId=<carId>&phoneId=<phoneId>&ts=<epochMs>`
Called when the user taps **Cancel** on a queued command in the phone app.

- **Required Header:**
  - `X-Gen5W-Phone-Signature: <base64-DER-ECDSA>`
- **Server Verification & Atomic Cancel:**
  1. Check `ts` within `±90,000 ms` of `Date.now()`.
  2. Verify `X-Gen5W-Phone-Signature` against `phoneId`'s `phonePublicKey` using Signature #5 (`"GEN5W-CANCEL-V1|" + commandId + "|" + carId + "|" + phoneId + "|" + ts`).
  3. If the command exists for `carId` and is currently `"QUEUED"` (and `Date.now() < expiresAtMs`), atomically update `status = "CANCELED"`, `message = "Canceled by phone"`, and return `200 OK`.
  4. If already `"CANCELED"`, return `200 OK` (idempotent). If already `"EXECUTED"`, `"FAILED"`, or `"EXPIRED"`, return `409 Conflict` `{ "ok": false, "error": "Command already " + status }`.
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "commandId": "cmd_550e8400e29b41d4a716446655440000",
    "status": "CANCELED"
  }
  ```

---

### 6. `POST /api/v1/car/poll`
*(Note singular `car` in `/api/v1/car/poll`!)*
Opportunistic HTTPS long-poll called by the car daemon/service (`READ_TIMEOUT_MS = 20000`, `waitSeconds = 12`).

- **Request Body:**
  ```json
  {
    "carId": "car_8f14e45fceea167a5a36dedd",
    "timestampMs": 1763665012000,
    "nonce": "a1b2c3d4e5f60718293a4b5c",
    "waitSeconds": 12,
    "carSignature": "MEUCIQ...",
    "encryptedTelemetry": {
      "ph_4c91a0f3b219e801d4c2": {
        "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
        "iv": "base64==",
        "ciphertext": "base64==",
        "mac": "base64==",
        "updatedAtMs": 1763665012000
      }
    }
  }
  ```
- **Server Verification & Long-Poll Behavior:**
  1. Look up `carId`'s pinned `carPublicKey`. If `carId` is not registered in the database (e.g. fresh server install or wiped DB), **return `404 Not Found`** `{ "ok": false, "error": "CAR_NOT_REGISTERED" }` — the car client watches for `HTTP 404` on `/api/v1/car/poll` and automatically re-triggers `POST /api/v1/cars/register` on the next tick!
  2. Check `timestampMs` within `±90,000 ms` of `Date.now()` and reject replayed `(carId, nonce)` within a 5-minute sliding cache.
  3. Verify `carSignature` against `carPublicKey` using Signature #6 (`"GEN5W-POLL-V1|" + carId + "|" + timestampMs + "|" + nonce`).
  4. If `encryptedTelemetry` is present (a map of `phoneId -> encryptedBox`), store/overwrite the latest E2EE telemetry object per `phoneId` for `carId` in memory/DB.
  5. Sweep expired commands (`Date.now() >= expiresAtMs` -> `status = "EXPIRED"`).
  6. **Long-Poll:**
     - If one or more commands for `carId` currently have `status == "QUEUED"` and `Date.now() < expiresAtMs`, return them **immediately** (ordered oldest `issuedAtMs` first).
     - Otherwise, hold the request open for up to `min(waitSeconds, 15)` seconds (default `12s`, well under the car's `20s` socket read timeout!) using an in-memory `EventEmitter` / condition channel keyed by `carId`. If a `POST /api/v1/commands` arrives for `carId` during that wait, wake immediately, re-check `status == "QUEUED"` and `Date.now() < expiresAtMs`, and return the queued command(s)! If timeout elapses with no commands, return `200 OK` with `"commands": []`.
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "commands": [
      {
        "commandId": "cmd_550e8400e29b41d4a716446655440000",
        "carId": "car_8f14e45fceea167a5a36dedd",
        "phoneId": "ph_4c91a0f3b219e801d4c2",
        "issuedAtMs": 1763665010000,
        "expiresAtMs": 1763665310000,
        "ttlSeconds": 300,
        "e2ee": {
          "ephemeralPubKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
          "iv": "...",
          "ciphertext": "...",
          "mac": "..."
        },
        "phoneSignature": "MEUCIQ...",
        "pairingProof": {
          "phonePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
          "pairedAtMs": 1763664000000,
          "carGrantSignature": "MEYCIQ..."
        },
        "clientHint": "Climate Theme: Warm Cozy 78°F"
      }
    ]
  }
  ```
  *(CRITICAL: Do **not** alter `commandId`, `carId`, `phoneId`, `issuedAtMs`, `expiresAtMs`, `e2ee`, or `phoneSignature` inside each returned command object — the car independently verifies `phoneSignature` and AAD over those exact fields before decrypting!)*

---

### 7. `POST /api/v1/car/ack`
*(Note singular `car` in `/api/v1/car/ack`!)*
Called by the car right after executing (or rejecting) a command returned by `/api/v1/car/poll`.

- **Request Body:**
  ```json
  {
    "commandId": "cmd_550e8400e29b41d4a716446655440000",
    "carId": "car_8f14e45fceea167a5a36dedd",
    "ok": true,
    "status": "EXECUTED",
    "message": "Applied Climate Theme: Warm Cozy 78°F",
    "executedAtMs": 1763665013250,
    "carSignature": "MEYCIQ..."
  }
  ```
- **Server Verification & State Update:**
  1. Check `executedAtMs` within `±90,000 ms` of `Date.now()`.
  2. Verify `carSignature` against `carId`'s pinned `carPublicKey` using Signature #7:
     `"GEN5W-ACK-V1|" + commandId + "|" + carId + "|" + (ok ? "1" : "0") + "|" + executedAtMs + "|" + sha256HexUtf8(message)`
  3. Update the command record to `status = ok ? "EXECUTED" : "FAILED"`, storing `message` and `executedAtMs` so the phone's next `GET /api/v1/commands` displays `"EXECUTED · Applied Climate Theme: Warm Cozy 78°F"` and stops delivering the command on future car polls.
- **Response `200 OK`:**
  ```json
  {
    "ok": true,
    "commandId": "cmd_550e8400e29b41d4a716446655440000",
    "status": "EXECUTED"
  }
  ```

---

## Recommended Implementation Stack & Deliverables

1. **Stack:** Node.js 20+ (TypeScript or ESM JavaScript) with Fastify/Express + SQLite (`better-sqlite3` or Node 22 `node:sqlite` in WAL mode) — lightweight, single-directory, zero external database server required, trivial to self-host on a \$5 VPS, Raspberry Pi, Home Server, or Docker host! (Go single-binary + SQLite is also great if preferred.)
2. **Background Maintenance Loop (every 15s):**
   - Sweep `"QUEUED"` commands with `Date.now() >= expiresAtMs` -> `"EXPIRED"`.
   - Prune terminal commands (`EXECUTED`, `FAILED`, `CANCELED`, `EXPIRED`) older than 24 hours and expired poll nonces older than 10 minutes so the SQLite DB stays tiny forever.
3. **Rate Limiting & Body Size Caps:**
   - Enforce a `256 KB` JSON body limit (matching client `MAX_HTTP_BYTES = 262144`) and sensible per-IP / per-`carId` rate limits.
4. **Distributable Self-Host Installer Kit:**
   - `Dockerfile` + `docker-compose.yml`
   - `.env.example` (`PORT`, `AUTH_MODE`, `SERVER_SHARED_TOKEN`, `DB_PATH`)
   - `Caddyfile.example` & `nginx-gen5w-relay.conf` (explicitly configured with `TLSv1.2` + `TLSv1.3` and `prime256v1` curve for Gen5W Android 4.4.2 compatibility!)
   - `install.sh` one-command setup script + clear `README.md` for self-hosters explaining how to copy their `https://...` URL and `SERVER_SHARED_TOKEN` into the Gen5W phone app's **Pair / Car → Optional Self-Hosted Remote Server** settings.
5. **Automated End-to-End Integration Test Suite (`npm test`):**
   - Generate real `prime256v1` SPKI keypairs simulating a Car and a Phone, and verify all 7 signatures + full lifecycle:
     1. `POST /api/v1/cars/register` (plus TOFU rejection of a different `carPublicKey` on the same `carId`)
     2. Concurrent `POST /api/v1/car/poll` woken in `< 200ms` by `POST /api/v1/commands`
     3. `POST /api/v1/car/ack` updating status seen by `GET /api/v1/commands` (plus `encryptedTelemetry` round-trip)
     4. `DELETE /api/v1/commands/:id` canceling a queued command so `/api/v1/car/poll` never receives it
     5. 5-minute TTL expiration (`EXPIRED`) preventing delivery to `/api/v1/car/poll`
     6. Rejection of forged signatures, wrong `AUTH_MODE` Bearer tokens, and replayed nonces.
