package com.gen5w.digitalcontrol.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared Bluetooth service UUIDs, bounded UTF-8 JSON line framing for RFCOMM / BT-PAN sockets,
 * and MTU-safe BLE GATT packet chunking/reassembly (compatible with Android 4.4.2 20-byte default
 * BLE ATT MTU as well as negotiated 185+ byte MTUs on iOS/Android).
 */
public final class ProtocolFrames {
    // Classic Bluetooth RFCOMM Service UUIDs (Android <-> Gen5W Head Unit)
    public static final UUID UUID_CAR_RFCOMM_SERVER =
            UUID.fromString("d5c45701-6765-4e35-9744-6374726c0001");
    public static final UUID UUID_PHONE_RFCOMM_SERVER =
            UUID.fromString("d5c45702-6765-4e35-9744-6374726c0002");

    // Bluetooth Low Energy GATT UUIDs (iOS CBPeripheralManager <-> Gen5W BLE Central)
    public static final UUID UUID_BLE_SERVICE =
            UUID.fromString("d5c45710-6765-4e35-9744-6374726c0010");
    // Car writes frames to RX_FROM_CAR; Phone notifies frames on TX_FROM_PHONE
    public static final UUID UUID_BLE_CHAR_CAR_TO_PHONE =
            UUID.fromString("d5c45711-6765-4e35-9744-6374726c0011");
    public static final UUID UUID_BLE_CHAR_PHONE_TO_CAR =
            UUID.fromString("d5c45712-6765-4e35-9744-6374726c0012");
    public static final UUID UUID_BLE_CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Bluetooth PAN (bnep0) local peer port (strictly bound to bnep* interface when active)
    public static final int BT_PAN_TCP_PORT = 45755;

    public static final int MAX_FRAME_BYTES = 32768;
    // Default BLE ATT payload is 20 bytes on Android 4.4.2 when requestMtu (API 21) is unavailable!
    // Header is 2 bytes: [flags_and_msgId, seqIndex] + up to 18 payload bytes = 20 bytes exact!
    public static final int DEFAULT_BLE_MTU_PAYLOAD = 20;

    private ProtocolFrames() {}

    public static void writeLineFrame(OutputStream out, String jsonLine) throws IOException {
        byte[] raw = jsonLine.replace("\r", "").replace("\n", "").getBytes(CryptoBox.UTF8);
        if (raw.length > MAX_FRAME_BYTES) {
            throw new IOException("Frame exceeds MAX_FRAME_BYTES: " + raw.length);
        }
        synchronized (out) {
            out.write(raw);
            out.write('\n');
            out.flush();
        }
    }

    public static String readLineFrame(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;
            }
            if (bos.size() >= MAX_FRAME_BYTES) {
                throw new IOException("Incoming frame exceeded " + MAX_FRAME_BYTES + " bytes");
            }
            bos.write(b);
        }
        if (!any && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), CryptoBox.UTF8);
    }

    /**
     * Splits a UTF-8 JSON frame into MTU-safe BLE packets.
     * Byte 0: bit 7 = START (1 on chunk 0), bit 6 = END (1 on final chunk), bits 0..5 = msgId (0..63)
     * Byte 1: chunk index (0..255)
     * Bytes 2..N: UTF-8 slice
     */
    public static List<byte[]> chunkForBle(String jsonFrame, int msgId, int maxPacketBytes) {
        int mtu = Math.max(20, maxPacketBytes);
        int sliceCap = mtu - 2;
        byte[] payload = jsonFrame.getBytes(CryptoBox.UTF8);
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("BLE frame too large: " + payload.length);
        }
        int totalChunks = Math.max(1, (payload.length + sliceCap - 1) / sliceCap);
        if (totalChunks > 256) {
            throw new IllegalArgumentException("BLE frame exceeds 256 chunks");
        }
        List<byte[]> packets = new ArrayList<byte[]>(totalChunks);
        int id6 = msgId & 0x3F;
        for (int idx = 0; idx < totalChunks; idx++) {
            int start = idx * sliceCap;
            int len = Math.min(sliceCap, payload.length - start);
            int flags = id6;
            if (idx == 0) flags |= 0x80;
            if (idx == totalChunks - 1) flags |= 0x40;
            byte[] pkt = new byte[2 + len];
            pkt[0] = (byte) flags;
            pkt[1] = (byte) (idx & 0xFF);
            if (len > 0) {
                System.arraycopy(payload, start, pkt, 2, len);
            }
            packets.add(pkt);
        }
        return packets;
    }

    public static final class BleReassembler {
        private int currentMsgId = -1;
        private int expectedIndex = 0;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);

        public synchronized String acceptPacket(byte[] pkt) {
            if (pkt == null || pkt.length < 2) {
                return null;
            }
            int b0 = pkt[0] & 0xFF;
            boolean isStart = (b0 & 0x80) != 0;
            boolean isEnd = (b0 & 0x40) != 0;
            int msgId = b0 & 0x3F;
            int idx = pkt[1] & 0xFF;

            if (isStart) {
                buffer.reset();
                currentMsgId = msgId;
                expectedIndex = 0;
            }
            if (msgId != currentMsgId || idx != expectedIndex) {
                buffer.reset();
                currentMsgId = -1;
                expectedIndex = 0;
                return null;
            }
            int dataLen = pkt.length - 2;
            if (buffer.size() + dataLen > MAX_FRAME_BYTES) {
                buffer.reset();
                currentMsgId = -1;
                return null;
            }
            if (dataLen > 0) {
                buffer.write(pkt, 2, dataLen);
            }
            expectedIndex++;
            if (isEnd) {
                String complete = new String(buffer.toByteArray(), CryptoBox.UTF8);
                buffer.reset();
                currentMsgId = -1;
                expectedIndex = 0;
                return complete;
            }
            return null;
        }
    }
}
