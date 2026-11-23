import com.gen5w.digitalcontrol.edition.RemoteTlsHelper;
import com.gen5w.digitalcontrol.shared.ClimateTheme;
import com.gen5w.digitalcontrol.shared.CommandLogEntry;
import com.gen5w.digitalcontrol.shared.CryptoBox;
import com.gen5w.digitalcontrol.shared.KeyBackupBundle;
import com.gen5w.digitalcontrol.shared.ProtocolFrames;
import com.gen5w.digitalcontrol.shared.RemoteEnvelope;
import com.gen5w.digitalcontrol.shared.VehicleSnapshot;

import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * Host JVM security & protocol test suite for Gen5W Digital Control.
 */
public final class DigitalControlHostTest {
    public static void main(String[] args) throws Exception {
        testKeyEncodingAndEcdsa();
        testSas4ConfirmationAndMitmDetection();
        testSessionEncryptionAndReplayGuard();
        testZeroTrustRemoteEnvelopeE2eeAndExpiry();
        testBlePacketChunking20ByteMtu();
        testPinPbkdf2AndClimateThemes();
        testRemoteTlsTrustStore();
        testKeyBackupExportAndImport();
        testCommandLogAndTpmsSensors();
        testUrlNormalizationAndBatteryPrecond();
        testSimpleJsonCodecRoundTrip();
        System.out.println("ok  All 11 DigitalControlHostTest suites passed!");
    }

    private static void testKeyEncodingAndEcdsa() throws Exception {
        KeyPair kp = CryptoBox.generateEcP256KeyPair();
        String spkiB64 = CryptoBox.encodePublicKeySpkiB64(kp.getPublic());
        String pkcs8B64 = CryptoBox.encodePrivateKeyPkcs8B64(kp.getPrivate());

        byte[] spkiDer = CryptoBox.base64Decode(spkiB64);
        assertTrue(spkiDer.length == 91, "Expected 91-byte P-256 X.509 SPKI DER, got " + spkiDer.length);

        PublicKey pub2 = CryptoBox.decodePublicKeySpkiB64(spkiB64);
        PrivateKey priv2 = CryptoBox.decodePrivateKeyPkcs8B64(pkcs8B64);

        String sig = CryptoBox.signEcdsaSha256B64(priv2, "GEN5W-TEST|door_lock|12345");
        assertTrue(CryptoBox.verifyEcdsaSha256B64(pub2, "GEN5W-TEST|door_lock|12345", sig), "Valid ECDSA must verify");
        assertTrue(!CryptoBox.verifyEcdsaSha256B64(pub2, "GEN5W-TEST|door_unlock|12345", sig), "Tampered payload must fail ECDSA");
    }

    private static void testSas4ConfirmationAndMitmDetection() throws Exception {
        KeyPair carStatic = CryptoBox.generateEcP256KeyPair();
        KeyPair phoneStatic = CryptoBox.generateEcP256KeyPair();
        KeyPair carEph = CryptoBox.generateEcP256KeyPair();
        KeyPair phoneEph = CryptoBox.generateEcP256KeyPair();
        String carNonce = CryptoBox.randomHex(16);
        String phoneNonce = CryptoBox.randomHex(16);

        String carStaticB64 = CryptoBox.encodePublicKeySpkiB64(carStatic.getPublic());
        String phoneStaticB64 = CryptoBox.encodePublicKeySpkiB64(phoneStatic.getPublic());
        String carEphB64 = CryptoBox.encodePublicKeySpkiB64(carEph.getPublic());
        String phoneEphB64 = CryptoBox.encodePublicKeySpkiB64(phoneEph.getPublic());

        String carSas = CryptoBox.deriveSas4Code(
                carEph.getPrivate(), phoneEph.getPublic(),
                carStaticB64, phoneStaticB64, carEphB64, phoneEphB64, carNonce, phoneNonce);
        String phoneSas = CryptoBox.deriveSas4Code(
                phoneEph.getPrivate(), carEph.getPublic(),
                carStaticB64, phoneStaticB64, carEphB64, phoneEphB64, carNonce, phoneNonce);

        assertTrue(carSas.length() == 4, "SAS must be 4 chars");
        assertTrue(carSas.equals(phoneSas), "Car and Phone SAS-4 codes must match: " + carSas + " vs " + phoneSas);

        // MITM check: substituting an attacker's ephemeral key yields a mismatched code
        KeyPair mitmEph = CryptoBox.generateEcP256KeyPair();
        String mitmB64 = CryptoBox.encodePublicKeySpkiB64(mitmEph.getPublic());
        String mitmSas = CryptoBox.deriveSas4Code(
                carEph.getPrivate(), mitmEph.getPublic(),
                carStaticB64, phoneStaticB64, carEphB64, mitmB64, carNonce, phoneNonce);
        assertTrue(!carSas.equals(mitmSas), "MITM ephemeral key substitution must alter SAS transcript");
    }

    private static void testSessionEncryptionAndReplayGuard() throws Exception {
        byte[] sessKey = CryptoBox.randomBytes(64);
        String f1 = CryptoBox.encryptSessionFrame(sessKey, 1L, "{\"op\":\"cmd\",\"action\":\"door_lock\"}");
        String p1 = CryptoBox.decryptSessionFrame(sessKey, 1L, f1);
        assertTrue(p1.contains("door_lock"), "Decrypted session frame must match");

        boolean replayBlocked = false;
        try {
            CryptoBox.decryptSessionFrame(sessKey, 2L, f1);
        } catch (GeneralSecurityException expected) {
            replayBlocked = true;
        }
        assertTrue(replayBlocked, "Replayed session frame with seq < minAllowedSeq must be rejected");
    }

    private static void testZeroTrustRemoteEnvelopeE2eeAndExpiry() throws Exception {
        KeyPair carKp = CryptoBox.generateEcP256KeyPair();
        KeyPair phoneKp = CryptoBox.generateEcP256KeyPair();
        KeyPair rogueKp = CryptoBox.generateEcP256KeyPair();

        String carId = CryptoBox.derivePseudonymousCarId(CryptoBox.randomHex(32), "0000210830152959001F|10E7C62DB037");
        String phoneId = "ph_" + CryptoBox.randomHex(8);
        String phonePubB64 = CryptoBox.encodePublicKeySpkiB64(phoneKp.getPublic());
        long pairedAt = 1_795_000_000_000L;

        String grantSig = RemoteEnvelope.signPairingGrant(
                carKp.getPrivate(), carId, phoneId, phonePubB64, pairedAt);
        assertTrue(RemoteEnvelope.verifyPairingGrant(
                carKp.getPublic(), carId, phoneId, phonePubB64, pairedAt, grantSig),
                "PairingGrant certificate must verify against car public key");

        ClimateTheme cozy = ClimateTheme.createStarterPresets().get(1); // Warm Cozy 78F
        long now = 1_795_000_100_000L;

        JSONObject outer = RemoteEnvelope.createSignedEncryptedCommand(
                carId,
                carKp.getPublic(),
                phoneId,
                phoneKp.getPrivate(),
                phonePubB64,
                pairedAt,
                grantSig,
                "apply_climate_theme",
                "",
                cozy.toJson(),
                "Climate Theme: Warm Cozy 78F",
                now);

        // Server cannot see raw climateTheme or action in outer JSON!
        String outerWire = outer.toString();
        assertTrue(!outerWire.contains("apply_climate_theme"), "Plaintext action must NOT leak in outer envelope");
        assertTrue(!outerWire.contains("driverSeatHeat"), "Plaintext climate settings must NOT leak in outer envelope");

        // Genuine car decrypts and verifies within 5-minute window
        JSONObject decrypted = RemoteEnvelope.verifyAndDecryptOnCar(
                outer, carId, carKp.getPrivate(), phoneKp.getPublic(), now + 30_000L);
        assertTrue("apply_climate_theme".equals(decrypted.optString("action")), "Decrypted action mismatch");
        ClimateTheme unpacked = ClimateTheme.fromJson(decrypted.optJSONObject("climateTheme"));
        assertTrue("78".equals(unpacked.driverTemp) && unpacked.wheelHeat && unpacked.driverSeatHeat == 2,
                "Decrypted ClimateTheme fields must match exact preset");

        // Hacked server attempts to forge or sign with rogue key -> REJECTED!
        boolean rogueRejected = false;
        try {
            RemoteEnvelope.verifyAndDecryptOnCar(
                    outer, carId, carKp.getPrivate(), rogueKp.getPublic(), now + 10_000L);
        } catch (GeneralSecurityException expected) {
            rogueRejected = true;
        }
        assertTrue(rogueRejected, "Unpaired/rogue phone public key must be rejected");

        // Expired command (> 5 minutes + skew) -> REJECTED!
        boolean expiredRejected = false;
        try {
            RemoteEnvelope.verifyAndDecryptOnCar(
                    outer, carId, carKp.getPrivate(), phoneKp.getPublic(), now + (7L * 60_000L));
        } catch (GeneralSecurityException expected) {
            expiredRejected = true;
        }
        assertTrue(expiredRejected, "Command older than 5-minute TTL must be rejected by car");
    }

    private static void testBlePacketChunking20ByteMtu() throws Exception {
        StringBuilder bigJson = new StringBuilder("{\"type\":\"enc\",\"frame\":\"");
        for (int i = 0; i < 120; i++) {
            bigJson.append("ABCDEF0123456789");
        }
        bigJson.append("\"}");
        String original = bigJson.toString();

        List<byte[]> packets = ProtocolFrames.chunkForBle(original, 17, 20);
        assertTrue(packets.size() > 50, "Expected multi-packet split for 20-byte ATT MTU");
        for (byte[] p : packets) {
            assertTrue(p.length <= 20, "Every BLE packet must fit in 20-byte KitKat ATT payload");
        }

        ProtocolFrames.BleReassembler rx = new ProtocolFrames.BleReassembler();
        String rebuilt = null;
        for (int i = 0; i < packets.size(); i++) {
            String out = rx.acceptPacket(packets.get(i));
            if (i < packets.size() - 1) {
                assertTrue(out == null, "Reassembler must wait until final END packet");
            } else {
                rebuilt = out;
            }
        }
        assertTrue(original.equals(rebuilt), "Reassembled BLE payload must match byte-for-byte");
    }

    private static void testPinPbkdf2AndClimateThemes() throws Exception {
        byte[] salt = CryptoBox.randomBytes(16);
        byte[] h1 = CryptoBox.pbkdf2HmacSha256("4829", salt, CryptoBox.PIN_PBKDF2_ITERATIONS, 32);
        byte[] h2 = CryptoBox.pbkdf2HmacSha256("4829", salt, CryptoBox.PIN_PBKDF2_ITERATIONS, 32);
        byte[] wrong = CryptoBox.pbkdf2HmacSha256("4820", salt, CryptoBox.PIN_PBKDF2_ITERATIONS, 32);
        assertTrue(Arrays.equals(h1, h2), "PBKDF2-HMAC-SHA256 must be deterministic");
        assertTrue(!CryptoBox.constantTimeEquals(h1, wrong), "Wrong PIN must not match");

        List<ClimateTheme> presets = ClimateTheme.createStarterPresets();
        String raw = ClimateTheme.serializeList(presets);
        List<ClimateTheme> parsed = ClimateTheme.parseList(raw);
        assertTrue(parsed.size() == 3, "Expected 3 starter Climate Themes");
        assertTrue("69".equals(parsed.get(0).driverTemp) && parsed.get(0).fanSpeed == 2, "Preset 1 must be 69F Fan 2");

        JSONObject snapJson = new JSONObject("{\"values\":{\"hv.soc\":\"74\",\"charging\":\"1\",\"charging.remain_min\":\"95\",\"hv.range\":\"360\"},\"updatedMs\":1000}");
        VehicleSnapshot snap = VehicleSnapshot.fromJson(snapJson);
        assertTrue(snap.isCharging(), "Snapshot must report charging=true");
        assertTrue("1h 35m remaining".equals(snap.formatChargeRemain()), "Charge time format mismatch: " + snap.formatChargeRemain());
    }

    private static void testRemoteTlsTrustStore() throws Exception {
        X509TrustManager tm = RemoteTlsHelper.getCompositeTrustManager();
        assertTrue(tm != null && tm.getAcceptedIssuers().length >= 5,
                "Composite X509TrustManager must include embedded ISRG/GTS/DigiCert roots");
    }

    private static void testKeyBackupExportAndImport() throws Exception {
        KeyPair phoneKp = CryptoBox.generateEcP256KeyPair();
        KeyPair carKp = CryptoBox.generateEcP256KeyPair();

        byte[] rawScalar32 = CryptoBox.extractEcPrivateScalar32(phoneKp.getPrivate());
        assertTrue(rawScalar32.length == 32, "P-256 raw scalar must be 32 bytes");

        PrivateKey rebuiltPriv = CryptoBox.decodeEcPrivateKeyFromScalar32(rawScalar32, phoneKp.getPublic());
        byte[] zOrig = CryptoBox.ecdhSharedSecret32(phoneKp.getPrivate(), carKp.getPublic());
        byte[] zRebuilt = CryptoBox.ecdhSharedSecret32(rebuiltPriv, carKp.getPublic());
        assertTrue(Arrays.equals(zOrig, zRebuilt), "Rebuilt P-256 scalar must yield identical ECDH secret");

        String carId = "car_" + CryptoBox.randomHex(12);
        String phoneId = "ph_" + CryptoBox.randomHex(10);
        String phonePubB64 = CryptoBox.encodePublicKeySpkiB64(phoneKp.getPublic());
        String carPubB64 = CryptoBox.encodePublicKeySpkiB64(carKp.getPublic());
        long pairedAt = 1_796_000_000_000L;
        String grantSig = RemoteEnvelope.signPairingGrant(
                carKp.getPrivate(), carId, phoneId, phonePubB64, pairedAt);

        KeyBackupBundle out = new KeyBackupBundle();
        out.phoneId = phoneId;
        out.phoneName = "Pixel 9 Pro";
        out.phonePrivRawB64 = CryptoBox.base64Encode(rawScalar32);
        out.phonePubSpkiB64 = phonePubB64;
        out.carId = carId;
        out.carName = "Ioniq 5";
        out.carBtAddress = "10:E7:C6:2D:B0:37";
        out.carPubSpkiB64 = carPubB64;
        out.pairedAtMs = pairedAt;
        out.pairingGrantSigB64 = grantSig;
        out.climateThemes = ClimateTheme.createStarterPresets();

        String encryptedFile = KeyBackupBundle.exportEncryptedJson(out, "correct-horse-battery", 25_000);
        assertTrue(!encryptedFile.contains(phoneId), "Plaintext phoneId must not leak in encrypted .g5wkey file");
        assertTrue(!encryptedFile.contains(out.phonePrivRawB64), "Plaintext private scalar must not leak in .g5wkey");

        KeyBackupBundle restored = KeyBackupBundle.importEncryptedJson(encryptedFile, "correct-horse-battery");
        assertTrue(phoneId.equals(restored.phoneId), "Restored phoneId mismatch");
        assertTrue(carId.equals(restored.carId), "Restored carId mismatch");
        assertTrue(restored.climateThemes.size() == 3, "Restored Climate Themes count mismatch");

        PrivateKey restoredPriv = restored.reconstructVerifiedPrivateKey();
        String challengeSig = CryptoBox.signEcdsaSha256B64(restoredPriv, "GEN5W-BT-CHALLENGE|abc123");
        assertTrue(CryptoBox.verifyEcdsaSha256B64(phoneKp.getPublic(), "GEN5W-BT-CHALLENGE|abc123", challengeSig),
                "Restored key must authenticate against car's trusted public key");
        assertTrue(RemoteEnvelope.verifyPairingGrant(
                carKp.getPublic(), restored.carId, restored.phoneId, restored.phonePubSpkiB64,
                restored.pairedAtMs, restored.pairingGrantSigB64),
                "Restored PairingGrant must remain valid");

        boolean wrongPassRejected = false;
        try {
            KeyBackupBundle.importEncryptedJson(encryptedFile, "wrong-passphrase");
        } catch (GeneralSecurityException expected) {
            wrongPassRejected = true;
        }
        assertTrue(wrongPassRejected, "Wrong backup passphrase must be rejected");
    }

    private static void testCommandLogAndTpmsSensors() throws Exception {
        long t0 = 1_796_500_000_000L;
        CommandLogEntry sent = new CommandLogEntry(
                "cmd_abc123", "Lock Doors", "REMOTE", CommandLogEntry.STATE_SENT, t0, t0, t0 + 300_000L, "");
        assertTrue("Lock Doors sent.".equals(sent.formatFriendlyHeadline()), "Sent headline mismatch");
        assertTrue(sent.isPendingRemote(t0 + 60_000L), "Must be pending before 5m expiry");
        assertTrue(!sent.isPendingRemote(t0 + 301_000L), "Must not be pending after 5m expiry");

        CommandLogEntry exec = sent.withState(CommandLogEntry.STATE_EXECUTED, t0 + 4_000L, "Doors LOCKED");
        assertTrue("Lock Doors executed.".equals(exec.formatFriendlyHeadline()), "Executed headline mismatch");

        CommandLogEntry timedOut = sent.withState(CommandLogEntry.STATE_TIMED_OUT, t0 + 300_000L, "Timed out after 5 minutes");
        assertTrue("Lock Doors timed out.".equals(timedOut.formatFriendlyHeadline()), "Timed out headline mismatch");

        String serialized = CommandLogEntry.serializeList(Arrays.asList(exec, timedOut));
        List<CommandLogEntry> restored = CommandLogEntry.parseList(serialized);
        assertTrue(restored.size() == 2, "Expected 2 serialized CommandLogEntry items");
        assertTrue("Lock Doors executed.".equals(restored.get(0).formatFriendlyHeadline()), "Deserialized item 0 mismatch");

        JSONObject okTpms = new JSONObject("{\"values\":{\"tyre.fl_status\":\"0\",\"tyre.fr_status\":\"0\",\"tyre.rl_status\":\"0\",\"tyre.rr_status\":\"0\"},\"updatedMs\":2000}");
        VehicleSnapshot snapOk = VehicleSnapshot.fromJson(okTpms);
        assertTrue("All 4 Tires OK".equals(snapOk.buildTiresSummary()), "Expected All 4 Tires OK, got " + snapOk.buildTiresSummary());

        JSONObject lowTpms = new JSONObject("{\"values\":{\"tyre.fl_status\":\"0\",\"tyre.fr_status\":\"1\",\"tyre.rl_status\":\"0\",\"tyre.rr_status\":\"0\"},\"updatedMs\":2000}");
        VehicleSnapshot snapLow = VehicleSnapshot.fromJson(lowTpms);
        assertTrue(snapLow.isSingleTireLow("tyre.fr_status"), "FR must report low tire");
        assertTrue("LOW PRESSURE: FR".equals(snapLow.buildTiresSummary()), "Expected LOW PRESSURE: FR, got " + snapLow.buildTiresSummary());
    }

    private static void testUrlNormalizationAndBatteryPrecond() throws Exception {
        assertTrue("".equals(RemoteEnvelope.normalizeServerUrl(null)), "Null URL must normalize to empty");
        assertTrue("".equals(RemoteEnvelope.normalizeServerUrl("   ")), "Blank URL must normalize to empty");
        String host = "mycar" + ".example.org";
        assertTrue(("https://" + host).equals(RemoteEnvelope.normalizeServerUrl(host)),
                "Bare domain must assume https://");
        assertTrue(("https://" + host).equals(RemoteEnvelope.normalizeServerUrl("http://" + host + "/")),
                "http:// URL must upgrade to https:// and strip trailing slash");
        assertTrue(("https://" + host).equals(RemoteEnvelope.normalizeServerUrl("HTTPS://" + host + "///")),
                "Uppercase HTTPS:// and trailing slashes must normalize cleanly");

        JSONObject preActive = new JSONObject("{\"values\":{\"hv.conditioning_active\":\"1\",\"hv.battery_temp_min\":\"5\"},\"updatedMs\":3000}");
        VehicleSnapshot snapAct = VehicleSnapshot.fromJson(preActive);
        assertTrue(snapAct.isPreconditioningActive(), "Expected active preconditioning");
        assertTrue("RUNNING (Heating Pack)".equals(snapAct.formatPreconditioningStatus()), "Unexpected active status");
        assertTrue("41\u00B0F (5\u00B0C)".equals(snapAct.formatBatteryTemp(true)),
                "Expected 41°F (5°C), got " + snapAct.formatBatteryTemp(true));
        assertTrue("5\u00B0C (41\u00B0F)".equals(snapAct.formatBatteryTemp(false)),
                "Expected 5°C (41°F), got " + snapAct.formatBatteryTemp(false));

        JSONObject preWait = new JSONObject("{\"values\":{\"hv.conditioning_active\":\"0\",\"conditioning.state\":\"waiting\"},\"updatedMs\":3000}");
        VehicleSnapshot snapWait = VehicleSnapshot.fromJson(preWait);
        assertTrue(snapWait.isPreconditioningPending(), "Expected pending preconditioning");
        assertTrue("STARTING / WAITING".equals(snapWait.formatPreconditioningStatus()), "Unexpected waiting status");

        JSONObject utilOn = new JSONObject("{\"values\":{\"hv.utility_mode\":\"2\",\"hv.utility_warn\":\"0\",\"hv.ready\":\"1\"},\"updatedMs\":4000}");
        VehicleSnapshot snapUtilOn = VehicleSnapshot.fromJson(utilOn);
        assertTrue(snapUtilOn.isUtilityModeActive(), "Expected Utility Mode active when hv.utility_mode=2");
        assertTrue("ON (Active)".equals(snapUtilOn.formatUtilityModeStatus()), "Expected ON (Active)");
        assertTrue("EV READY".equals(snapUtilOn.formatEvReadyStatus()), "Expected EV READY");

        JSONObject utilRefused = new JSONObject("{\"values\":{\"hv.utility_mode\":\"1\",\"hv.utility_warn\":\"2\",\"hv.ready\":\"0\"},\"updatedMs\":4000}");
        VehicleSnapshot snapUtilRefused = VehicleSnapshot.fromJson(utilRefused);
        assertTrue(!snapUtilRefused.isUtilityModeActive(), "Expected Utility Mode inactive when hv.utility_mode=1");
        assertTrue("OFF (Conditions Not Met)".equals(snapUtilRefused.formatUtilityModeStatus()), "Expected OFF (Conditions Not Met)");
        assertTrue("NOT READY (IG OFF/ACC)".equals(snapUtilRefused.formatEvReadyStatus()), "Expected NOT READY (IG OFF/ACC)");
    }

    private static void testSimpleJsonCodecRoundTrip() throws Exception {
        String raw = "{\"title\":\"Temp 70\\u00B0F\",\"neg\":-12,\"dec\":94.5,\"ok\":true,\"arr\":[{\"k\":\"v\"},42,false,null]}";
        JSONObject obj = new JSONObject(raw);
        assertTrue("Temp 70\u00B0F".equals(obj.getString("title")), "Unicode escape decode failed");
        assertTrue(obj.optLong("neg", 0L) == -12L, "Negative long mismatch");
        assertTrue(obj.optBoolean("ok", false), "Boolean mismatch");
        assertTrue(obj.has("dec"), "Expected has(\"dec\") true");
        obj.remove("dec");
        assertTrue(!obj.has("dec"), "Expected remove(\"dec\") to remove key");

        JSONObject reparsed = new JSONObject(obj.toString());
        assertTrue("v".equals(reparsed.optJSONArray("arr").optJSONObject(0).getString("k")), "Nested array object roundtrip failed");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("TEST FAILED: " + msg);
        }
    }
}
