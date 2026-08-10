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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded TCP client manager for Connecto.
 * Completes the full Minecraft login flow (Handshake → Login → Configuration → Play)
 * so the bot appears as a real in-game player, preventing server auto-pause
 * and keeping hosting providers (Play.Hosting, Aternos, etc.) active 24/7.
 *
 * Protocol: 775 (MC 26.1.2)
 * Login Success     server→client: 0x02
 * Login Acknowledged client→server: 0x03
 * Config: SelectKnownPacks server→client: 0x0E, client→server: 0x07 (empty)
 * Config: FinishConfiguration server→client: 0x03, client→server: 0x02
 */
public class EmbeddedBotManager {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static Thread workerThread = null;
    private static volatile Socket currentSocket = null;

    // Login state packet IDs (server → client)
    private static final int S_LOGIN_SUCCESS     = 0x02;
    private static final int S_LOGIN_DISCONNECT  = 0x04;
    // Login state packet IDs (client → server)
    private static final int C_LOGIN_ACKNOWLEDGED = 0x03;
    // Configuration state packet IDs (server → client)
    private static final int S_CONFIG_SELECT_KNOWN_PACKS  = 0x0E;
    private static final int S_CONFIG_FINISH               = 0x03;
    // Configuration state packet IDs (client → server)
    private static final int C_CONFIG_SELECT_KNOWN_PACKS  = 0x07;
    private static final int C_CONFIG_FINISH               = 0x02;
    // Play state packet IDs (server → client) – just used for keep-alive drain
    private static final int S_PLAY_KEEPALIVE = 0x27;
    // Play state packet IDs (client → server)
    private static final int C_PLAY_KEEPALIVE = 0x1A;

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

                InputStream in   = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                // 1. Handshake
                sendHandshake(out, port);
                // 2. Login Start
                sendLoginStart(out, botName);

                ConnectoMod.LOGGER.info("[Connecto] ✓ Handshake sent. Waiting for Login Success...");

                // 3. State machine
                State state = State.LOGIN;
                while (RUNNING.get() && !socket.isClosed()) {
                    // Read one packet
                    int length = readVarInt(in);
                    if (length <= 0) continue;

                    byte[] data = readFully(in, length);
                    if (data == null) break;

                    int[] offsetHolder = {0};
                    int packetId = readVarIntFromBytes(data, offsetHolder);

                    if (state == State.LOGIN) {
                        if (packetId == S_LOGIN_SUCCESS) {
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Login Success received! Sending Login Acknowledged...");
                            sendPacket(out, C_LOGIN_ACKNOWLEDGED, new byte[0]);
                            state = State.CONFIGURATION;
                        } else if (packetId == S_LOGIN_DISCONNECT) {
                            String reason = readString(data, offsetHolder);
                            ConnectoMod.LOGGER.warn("[Connecto] Login rejected: {}", reason);
                            break;
                        }
                    } else if (state == State.CONFIGURATION) {
                        if (packetId == S_CONFIG_SELECT_KNOWN_PACKS) {
                            // Respond with empty known packs list
                            byte[] payload = writeVarIntToBytes(0); // 0 packs
                            sendPacket(out, C_CONFIG_SELECT_KNOWN_PACKS, payload);
                        } else if (packetId == S_CONFIG_FINISH) {
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Configuration complete! Sending Finish Configuration...");
                            sendPacket(out, C_CONFIG_FINISH, new byte[0]);
                            state = State.PLAY;
                            ConnectoMod.LOGGER.info("[Connecto] ✓ Uptime bot is now in-game! Server will not auto-pause.");
                        }
                    } else if (state == State.PLAY) {
                        // Respond to keep-alive pings from server
                        if (packetId == S_PLAY_KEEPALIVE) {
                            // Echo back the keep-alive ID (8 bytes / long)
                            sendPacket(out, C_PLAY_KEEPALIVE, copyBytes(data, offsetHolder[0], data.length - offsetHolder[0]));
                        }
                        // All other play packets are silently discarded
                    }
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (RUNNING.get()) {
                    ConnectoMod.LOGGER.warn("[Connecto] Embedded TCP bot disconnected ({}). Reconnecting in 5s...", e.getMessage());
                }
            } finally {
                Socket s = currentSocket;
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                    currentSocket = null;
                }
            }

            if (RUNNING.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // ---- Packet senders ----

    private static void sendHandshake(OutputStream out, int port) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);         // Packet ID: Handshake
        writeVarInt(payload, 775);          // Protocol version (MC 26.1.2)
        writeString(payload, "127.0.0.1");  // Server address
        payload.write((port >> 8) & 0xFF);  // Port high byte
        payload.write(port & 0xFF);         // Port low byte
        writeVarInt(payload, 2);            // Next state: Login
        writePacket(out, payload.toByteArray());
    }

    private static void sendLoginStart(OutputStream out, String botName) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);         // Packet ID: Login Start
        writeString(payload, botName);      // Username
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(botName);
        DataOutputStream dos = new DataOutputStream(payload);
        dos.writeLong(uuid.getMostSignificantBits());
        dos.writeLong(uuid.getLeastSignificantBits());
        writePacket(out, payload.toByteArray());
    }

    private static void sendPacket(OutputStream out, int packetId, byte[] data) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        payload.write(data);
        writePacket(out, payload.toByteArray());
    }

    private static void writePacket(OutputStream out, byte[] payload) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    // ---- VarInt / String helpers ----

    private static void writeVarInt(OutputStream out, int value) throws Exception {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] writeVarIntToBytes(int value) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try { writeVarInt(buf, value); } catch (Exception ignored) {}
        return buf.toByteArray();
    }

    private static void writeString(OutputStream out, String str) throws Exception {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /** Read a VarInt from the InputStream byte by byte. */
    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        int b;
        while (true) {
            b = in.read();
            if (b == -1) throw new IOException("Stream closed");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too large");
        }
        return value;
    }

    /** Read a VarInt from a byte array at the given offset. Updates offset. */
    private static int readVarIntFromBytes(byte[] data, int[] offset) {
        int value = 0;
        int pos = 0;
        while (true) {
            int b = data[offset[0]++] & 0xFF;
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) break;
            pos += 7;
        }
        return value;
    }

    /** Read a string from a byte array at the given offset. */
    private static String readString(byte[] data, int[] offset) {
        int len = readVarIntFromBytes(data, offset);
        String s = new String(data, offset[0], Math.min(len, data.length - offset[0]), StandardCharsets.UTF_8);
        offset[0] += len;
        return s;
    }

    /** Read exactly n bytes from the stream. Returns null if stream closes. */
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

    private static byte[] copyBytes(byte[] src, int from, int len) {
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, Math.min(len, src.length - from));
        return out;
    }

    private enum State { LOGIN, CONFIGURATION, PLAY }
}
