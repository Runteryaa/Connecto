package com.runterya.connecto.bot;

import com.runterya.connecto.ConnectoMod;
import net.minecraft.core.UUIDUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Embedded TCP client manager for Connecto.
 * Implements the full Minecraft Login → Configuration → Play protocol
 * including packet compression, so the uptime bot actually enters the game
 * and prevents the server from auto-pausing when empty.
 *
 * Protocol version 775 (MC 26.1.2 / 1.21.x family)
 */
public class EmbeddedBotManager {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static Thread workerThread = null;
    private static volatile Socket currentSocket = null;

    // Login state – Server → Client
    private static final int S_LOGIN_DISCONNECT      = 0x00;
    private static final int S_LOGIN_SUCCESS         = 0x02;
    private static final int S_LOGIN_SET_COMPRESSION = 0x03;
    // Login state – Client → Server
    private static final int C_LOGIN_ACKNOWLEDGED    = 0x03;
    // Configuration state – Server → Client (MC 26.x / protocol 775, +1 shift vs 1.21.4)
    private static final int S_CONFIG_FINISH             = 0x03; // was 0x02 in 1.21.4
    private static final int S_CONFIG_PING               = 0x05; // was 0x04 in 1.21.4
    private static final int S_CONFIG_SELECT_KNOWN_PACKS = 0x0E; // was 0x0D in 1.21.4
    // Configuration state – Client → Server (unchanged from 1.21.4)
    private static final int C_CONFIG_FINISH             = 0x03; // Acknowledge Finish Configuration
    private static final int C_CONFIG_PONG               = 0x05; // Pong (echo int from Ping)
    private static final int C_CONFIG_SELECT_KNOWN_PACKS = 0x07; // Known Packs response

    private enum BotState { LOGIN, CONFIGURATION, PLAY }

    public static synchronized void start(int port, String botName) {
        if (RUNNING.get()) return;
        RUNNING.set(true);
        workerThread = new Thread(() -> runBotLoop(port, botName), "Connecto-EmbeddedBot");
        workerThread.setDaemon(true);
        workerThread.start();
        ConnectoMod.LOGGER.info("[Connecto] Embedded TCP Bot Manager started for username '{}' on port {}.", botName, port);
    }

    public static synchronized void stop() {
        RUNNING.set(false);
        Socket s = currentSocket;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
            currentSocket = null;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        ConnectoMod.LOGGER.info("[Connecto] Embedded TCP Bot Manager stopped.");
    }

    private static void runBotLoop(int port, String botName) {
        while (RUNNING.get()) {
            try {
                ConnectoMod.LOGGER.info("[Connecto] Embedded TCP bot connecting to 127.0.0.1:{} as '{}'...", port, botName);
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(0);
                socket.setTcpNoDelay(true);
                currentSocket = socket;

                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                // Compression state
                int compressionThreshold = -1; // -1 = disabled

                // 1. Handshake
                sendHandshake(out, port);
                // 2. Login Start
                sendLoginStart(out, botName);
                ConnectoMod.LOGGER.info("[Connecto] Handshake sent. Waiting for Login Success...");

                BotState state = BotState.LOGIN;

                while (RUNNING.get() && !socket.isClosed()) {
                    // Read length-prefixed packet
                    int packetLen = readVarInt(in);
                    if (packetLen <= 0) continue;
                    byte[] raw = readFully(in, packetLen);
                    if (raw == null) break;

                    // Decompress if needed
                    byte[] data;
                    if (compressionThreshold >= 0) {
                        int[] off = {0};
                        int uncompressedLen = readVarIntFromBytes(raw, off);
                        if (uncompressedLen == 0) {
                            data = Arrays.copyOfRange(raw, off[0], raw.length);
                        } else {
                            data = inflate(Arrays.copyOfRange(raw, off[0], raw.length), uncompressedLen);
                        }
                    } else {
                        data = raw;
                    }

                    int[] off = {0};
                    int packetId = readVarIntFromBytes(data, off);
                    ConnectoMod.LOGGER.debug("[Connecto] [{}] rx packet 0x{}", state, Integer.toHexString(packetId));

                    if (state == BotState.LOGIN) {
                        if (packetId == S_LOGIN_SET_COMPRESSION) {
                            compressionThreshold = readVarIntFromBytes(data, off);
                            ConnectoMod.LOGGER.info("[Connecto] Compression enabled (threshold={}).", compressionThreshold);
                        } else if (packetId == S_LOGIN_SUCCESS) {
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Login Success! Sending Login Acknowledged...");
                            sendPacket(out, compressionThreshold, C_LOGIN_ACKNOWLEDGED, new byte[0]);
                            state = BotState.CONFIGURATION;
                        } else if (packetId == S_LOGIN_DISCONNECT) {
                            String reason = readString(data, off);
                            ConnectoMod.LOGGER.warn("[Connecto] Login rejected: {}", reason);
                            break;
                        }

                    } else if (state == BotState.CONFIGURATION) {
                        if (packetId == S_CONFIG_PING) {
                            // Echo the 4-byte int back as a Pong so server proceeds
                            byte[] pingPayload = Arrays.copyOfRange(data, off[0], data.length);
                            ConnectoMod.LOGGER.info("[Connecto] [Config] Ping received. Sending Pong...");
                            sendPacket(out, compressionThreshold, C_CONFIG_PONG, pingPayload);
                        } else if (packetId == S_CONFIG_SELECT_KNOWN_PACKS) {
                            // Respond with empty known packs (0 entries) so server sends registry data and proceeds
                            ConnectoMod.LOGGER.info("[Connecto] [Config] Select Known Packs received. Responding with empty list...");
                            sendPacket(out, compressionThreshold, C_CONFIG_SELECT_KNOWN_PACKS, writeVarIntBytes(0));
                        } else if (packetId == S_CONFIG_FINISH) {
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Finish Configuration received! Acknowledging...");
                            sendPacket(out, compressionThreshold, C_CONFIG_FINISH, new byte[0]);
                            state = BotState.PLAY;
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Uptime bot is IN-GAME! Server will not auto-pause.");
                        } else {
                            ConnectoMod.LOGGER.info("[Connecto] [Config] Unknown packet 0x{} (len={}) – ignoring.", Integer.toHexString(packetId), data.length);
                        }

                    } else {
                        // BotState.PLAY – drain all packets; server-side keepalive suppressed by our mixin
                    }
                }

            } catch (Exception e) {
                if (RUNNING.get()) {
                    ConnectoMod.LOGGER.warn("[Connecto] Embedded bot disconnected ({}). Reconnecting in 5s...", e.getMessage());
                }
            } finally {
                Socket s = currentSocket;
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                    currentSocket = null;
                }
            }

            if (RUNNING.get()) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            }
        }
    }

    // ---- Packet senders ----

    private static void sendHandshake(OutputStream out, int port) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, 0x00);        // Packet ID: Handshake
        writeVarInt(p, 775);         // Protocol version (MC 26.1.2)
        writeString(p, "127.0.0.1");
        p.write((port >> 8) & 0xFF);
        p.write(port & 0xFF);
        writeVarInt(p, 2);           // Next state: Login
        writeFrame(out, p.toByteArray());
    }

    private static void sendLoginStart(OutputStream out, String botName) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, 0x00);        // Packet ID: Login Start
        writeString(p, botName);
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(botName);
        DataOutputStream dos = new DataOutputStream(p);
        dos.writeLong(uuid.getMostSignificantBits());
        dos.writeLong(uuid.getLeastSignificantBits());
        writeFrame(out, p.toByteArray());
    }

    private static void sendPacket(OutputStream out, int compressionThreshold, int packetId, byte[] payload) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, packetId);
        p.write(payload);
        byte[] rawData = p.toByteArray();

        if (compressionThreshold >= 0) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            if (rawData.length >= compressionThreshold) {
                byte[] compressed = deflate(rawData);
                writeVarInt(frame, rawData.length); // uncompressed length
                frame.write(compressed);
            } else {
                writeVarInt(frame, 0);              // 0 = not compressed
                frame.write(rawData);
            }
            writeFrame(out, frame.toByteArray());
        } else {
            writeFrame(out, rawData);
        }
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    // ---- Encoding helpers ----

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] writeVarIntBytes(int value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try { writeVarInt(b, value); } catch (IOException ignored) {}
        return b.toByteArray();
    }

    private static void writeString(OutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    // ---- Decoding helpers ----

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("Stream closed");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too large");
        }
    }

    private static int readVarIntFromBytes(byte[] data, int[] offset) {
        int value = 0, pos = 0;
        while (true) {
            int b = data[offset[0]++] & 0xFF;
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) return value;
            pos += 7;
        }
    }

    private static String readString(byte[] data, int[] offset) {
        int len = readVarIntFromBytes(data, offset);
        int avail = Math.min(len, data.length - offset[0]);
        String s = new String(data, offset[0], avail, StandardCharsets.UTF_8);
        offset[0] += avail;
        return s;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r == -1) return null;
            read += r;
        }
        return buf;
    }

    // ---- Compression ----

    private static byte[] inflate(byte[] compressed, int expectedLen) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] out = new byte[expectedLen];
        int len = inflater.inflate(out);
        inflater.end();
        return len == expectedLen ? out : Arrays.copyOf(out, len);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            baos.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return baos.toByteArray();
    }
}
