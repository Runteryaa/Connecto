package com.runterya.connecto.bot;

import com.runterya.connecto.ConnectoMod;
import net.minecraft.core.UUIDUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded TCP client manager for Connecto.
 * Connects over a real local TCP network socket to port 25565 (or server port)
 * when the Fabric server finishes booting, keeping hosting providers (Play.Hosting, Aternos, etc.)
 * active 24/7 without auto-shutdown.
 */
public class EmbeddedBotManager {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static Thread workerThread = null;
    private static Socket currentSocket = null;

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
        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (Exception ignored) {}
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
                currentSocket = new Socket("127.0.0.1", port);
                currentSocket.setSoTimeout(0); // 0 = infinite read timeout (no SocketTimeoutException)
                currentSocket.setTcpNoDelay(true);

                OutputStream out = currentSocket.getOutputStream();
                InputStream in = currentSocket.getInputStream();

                // 1. Send Handshake Packet (ID 0x00)
                sendHandshake(out, port);

                // 2. Send Login Start Packet (ID 0x00)
                sendLoginStart(out, botName);

                ConnectoMod.LOGGER.info("[Connecto] ✓ Embedded TCP bot connected successfully! Active TCP session on 127.0.0.1:{}.", port);

                // 3. Start background reader thread to drain incoming bytes and prevent buffer overflow
                Thread readerThread = new Thread(() -> {
                    byte[] buffer = new byte[2048];
                    try {
                        while (RUNNING.get() && !currentSocket.isClosed()) {
                            int read = in.read(buffer);
                            if (read == -1) break;
                        }
                    } catch (Exception ignored) {}
                }, "Connecto-EmbeddedBot-Reader");
                readerThread.setDaemon(true);
                readerThread.start();

                // 4. Keep TCP connection active permanently
                long lastPing = System.currentTimeMillis();
                while (RUNNING.get() && !currentSocket.isClosed() && readerThread.isAlive()) {
                    long now = System.currentTimeMillis();
                    if (now - lastPing >= 15000) {
                        try {
                            out.write(0); // Flush 1-byte heartbeat
                            out.flush();
                        } catch (Exception e) {
                            break; // Connection lost
                        }
                        lastPing = now;
                    }
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (RUNNING.get()) {
                    ConnectoMod.LOGGER.warn("[Connecto] Embedded TCP bot disconnected ({}). Retrying in 10s...", e.getMessage());
                }
            } finally {
                if (currentSocket != null) {
                    try { currentSocket.close(); } catch (Exception ignored) {}
                    currentSocket = null;
                }
            }

            if (RUNNING.get()) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private static void sendHandshake(OutputStream out, int port) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);         // Handshake Packet ID
        writeVarInt(payload, 775);          // Protocol Version (775 for MC 26.1.2)
        writeString(payload, "127.0.0.1"); // Server Address
        payload.write((port >> 8) & 0xFF);  // Port (unsigned short)
        payload.write(port & 0xFF);
        writeVarInt(payload, 2);            // Next State: Login (2)

        writePacket(out, payload.toByteArray());
    }

    private static void sendLoginStart(OutputStream out, String botName) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00);         // Login Start Packet ID
        writeString(payload, botName);      // Username

        UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(botName);
        DataOutputStream dataOut = new DataOutputStream(payload);
        dataOut.writeLong(offlineUuid.getMostSignificantBits());
        dataOut.writeLong(offlineUuid.getLeastSignificantBits());

        writePacket(out, payload.toByteArray());
    }

    private static void writePacket(OutputStream out, byte[] payload) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void writeVarInt(OutputStream out, int value) throws Exception {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(OutputStream out, String str) throws Exception {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
