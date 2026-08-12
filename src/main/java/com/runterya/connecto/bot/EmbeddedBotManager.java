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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Multi-instance Embedded TCP client manager for Connecto.
 * Supports launching multiple uptime bots simultaneously.
 */
public class EmbeddedBotManager {

    private static final Map<String, BotRunner> RUNNING_BOTS = new ConcurrentHashMap<>();

    // Login state – Server → Client
    private static final int S_LOGIN_DISCONNECT      = 0x00;
    private static final int S_LOGIN_SUCCESS         = 0x02;
    private static final int S_LOGIN_SET_COMPRESSION = 0x03;
    // Login state – Client → Server
    private static final int C_LOGIN_ACKNOWLEDGED    = 0x03;
    // Configuration state – Server → Client (MC 1.21.4 / protocol 775)
    private static final int S_CONFIG_FINISH             = 0x03;
    private static final int S_CONFIG_KEEPALIVE          = 0x04;
    private static final int S_CONFIG_PING               = 0x05;
    private static final int S_CONFIG_KNOWN_PACKS        = 0x0E;

    // Configuration state – Client → Server (MC 1.21.4 / protocol 775)
    private static final int C_CONFIG_CLIENT_INFO        = 0x00;
    private static final int C_CONFIG_FINISH             = 0x03;
    private static final int C_CONFIG_KEEPALIVE          = 0x04;
    private static final int C_CONFIG_PONG               = 0x05;
    private static final int C_CONFIG_KNOWN_PACKS        = 0x07;

    private enum BotState { LOGIN, CONFIGURATION, PLAY }

    public static synchronized void start(String ip, int port, String botName) {
        startBots(ip, port, List.of(botName));
    }

    public static synchronized void startBots(String ip, int port, List<String> botNames) {
        if (botNames == null || botNames.isEmpty()) return;
        for (String botName : botNames) {
            if (botName == null || botName.isBlank()) continue;
            String key = botName.toLowerCase().trim();
            if (!RUNNING_BOTS.containsKey(key)) {
                BotRunner runner = new BotRunner(ip, port, botName.trim());
                RUNNING_BOTS.put(key, runner);
                runner.start();
            }
        }
    }

    public static boolean isRunning() {
        return !RUNNING_BOTS.isEmpty();
    }

    public static synchronized void stop() {
        for (BotRunner runner : RUNNING_BOTS.values()) {
            runner.stop();
        }
        RUNNING_BOTS.clear();
        ConnectoMod.LOGGER.info("[Connecto] All embedded TCP bots stopped.");
    }

    private static class BotRunner {
        private final String ip;
        private final int port;
        private final String botName;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread workerThread = null;
        private volatile Socket currentSocket = null;
        private volatile WebSocketStreamAdapter currentWsAdapter = null;

        public BotRunner(String ip, int port, String botName) {
            this.ip = ip;
            this.port = port;
            this.botName = botName;
        }

        public void start() {
            if (running.get()) return;
            running.set(true);
            workerThread = new Thread(this::runBotLoop, "Connecto-Bot-" + botName);
            workerThread.setDaemon(true);
            workerThread.start();

            String proxyUrl = com.runterya.connecto.ConnectoConfig.getInstance().relayProxyUrl;
            boolean useRelay = com.runterya.connecto.ConnectoConfig.getInstance().relayProxy;
            if (useRelay && proxyUrl != null && !proxyUrl.isBlank()) {
                ConnectoMod.LOGGER.info("[Connecto] Embedded TCP Bot started for username '{}' on {}:{} via WebSocket Proxy: {}", botName, ip, port, proxyUrl);
            } else {
                ConnectoMod.LOGGER.info("[Connecto] Embedded TCP Bot started for username '{}' on {}:{}.", botName, ip, port);
            }
        }

        public void stop() {
            running.set(false);
            Socket s = currentSocket;
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
                currentSocket = null;
            }
            WebSocketStreamAdapter ws = currentWsAdapter;
            if (ws != null) {
                try { ws.close(); } catch (Exception ignored) {}
                currentWsAdapter = null;
            }
            if (workerThread != null) {
                workerThread.interrupt();
                workerThread = null;
            }
        }

        private void runBotLoop() {
            while (running.get()) {
                try {
                    String proxyUrl = com.runterya.connecto.ConnectoConfig.getInstance().relayProxyUrl;
                    boolean useRelay = com.runterya.connecto.ConnectoConfig.getInstance().relayProxy;
                    InputStream in;
                    OutputStream out;
                    boolean isWs = useRelay && proxyUrl != null && !proxyUrl.isBlank();

                    if (isWs) {
                        String cleanUrl = proxyUrl.replaceFirst("^(wss?://)", "");
                        String fullUrl = "wss://" + cleanUrl + "?host=" + ip + "&port=" + port;
                        ConnectoMod.LOGGER.info("[Connecto] Bot '{}' connecting via WebSocket Proxy {}...", botName, fullUrl);
                        WebSocketStreamAdapter adapter = new WebSocketStreamAdapter(fullUrl);
                        currentWsAdapter = adapter;
                        in = adapter.getInputStream();
                        out = adapter.getOutputStream();
                    } else {
                        ConnectoMod.LOGGER.info("[Connecto] Bot '{}' connecting to {}:{}...", botName, ip, port);
                        Socket socket = new Socket(ip, port);
                        socket.setSoTimeout(0);
                        socket.setTcpNoDelay(true);
                        currentSocket = socket;
                        in = socket.getInputStream();
                        out = socket.getOutputStream();
                    }

                    int compressionThreshold = -1;

                    sendHandshake(out, ip, port);
                    sendLoginStart(out, botName);
                    ConnectoMod.LOGGER.info("[Connecto] [{}] Handshake sent. Waiting for Login Success...", botName);

                    BotState state = BotState.LOGIN;

                    while (running.get()) {
                        if (isWs && currentWsAdapter.isClosed()) break;
                        if (!isWs && currentSocket.isClosed()) break;

                        int packetLen = readVarInt(in);
                        if (packetLen <= 0) continue;
                        byte[] raw = readFully(in, packetLen);
                        if (raw == null) break;

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

                        if (state == BotState.LOGIN) {
                            if (packetId == S_LOGIN_SET_COMPRESSION) {
                                compressionThreshold = readVarIntFromBytes(data, off);
                            } else if (packetId == S_LOGIN_SUCCESS) {
                                sendPacket(out, compressionThreshold, C_LOGIN_ACKNOWLEDGED, new byte[0]);
                                state = BotState.CONFIGURATION;
                                sendClientInformation(out, compressionThreshold);
                            } else if (packetId == S_LOGIN_DISCONNECT) {
                                String reason = readString(data, off);
                                ConnectoMod.LOGGER.warn("[Connecto] [{}] Login rejected: {}", botName, reason);
                                break;
                            }
                        } else if (state == BotState.CONFIGURATION) {
                            if (packetId == S_CONFIG_PING) {
                                byte[] pingPayload = Arrays.copyOfRange(data, off[0], data.length);
                                sendPacket(out, compressionThreshold, C_CONFIG_PONG, pingPayload);
                            } else if (packetId == S_CONFIG_KEEPALIVE) {
                                byte[] kaPayload = Arrays.copyOfRange(data, off[0], data.length);
                                sendPacket(out, compressionThreshold, C_CONFIG_KEEPALIVE, kaPayload);
                            } else if (packetId == S_CONFIG_KNOWN_PACKS) {
                                sendPacket(out, compressionThreshold, C_CONFIG_KNOWN_PACKS, writeVarIntBytes(0));
                            } else if (packetId == S_CONFIG_FINISH) {
                                sendPacket(out, compressionThreshold, C_CONFIG_FINISH, new byte[0]);
                                state = BotState.PLAY;
                                ConnectoMod.LOGGER.info("[Connecto] ✓ Bot '{}' is IN-GAME!", botName);
                            }
                        } else {
                            // BotState.PLAY
                        }
                    }

                } catch (Exception e) {
                    if (running.get()) {
                        ConnectoMod.LOGGER.warn("[Connecto] Bot '{}' disconnected ({}). Reconnecting in 5s...", botName, e.getMessage());
                    }
                } finally {
                    Socket s = currentSocket;
                    if (s != null) { try { s.close(); } catch (Exception ignored) {} currentSocket = null; }
                    WebSocketStreamAdapter ws = currentWsAdapter;
                    if (ws != null) { try { ws.close(); } catch (Exception ignored) {} currentWsAdapter = null; }
                }

                if (running.get()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                }
            }
        }

        // ---- Packet senders ----

        private void sendHandshake(OutputStream out, String ip, int port) throws IOException {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            writeVarInt(p, 0x00);        // Packet ID: Handshake
            writeVarInt(p, 775);         // Protocol version (MC 26.1.2)
            writeString(p, ip);
            p.write((port >> 8) & 0xFF);
            p.write(port & 0xFF);
            writeVarInt(p, 2);           // Next state: Login
            writeFrame(out, p.toByteArray());
        }

        private void sendLoginStart(OutputStream out, String botName) throws IOException {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            writeVarInt(p, 0x00);        // Packet ID: Login Start
            writeString(p, botName);
            UUID uuid = UUIDUtil.createOfflinePlayerUUID(botName);
            DataOutputStream dos = new DataOutputStream(p);
            dos.writeLong(uuid.getMostSignificantBits());
            dos.writeLong(uuid.getLeastSignificantBits());
            writeFrame(out, p.toByteArray());
        }

        private void sendClientInformation(OutputStream out, int compressionThreshold) throws IOException {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            writeString(p, "en_us");
            p.write(10); // View Distance
            writeVarInt(p, 0); // Chat mode: Full
            p.write(1); // Chat colors: true
            p.write(0x7F); // Skin parts: All
            writeVarInt(p, 1); // Main hand: Right
            p.write(0); // Text filtering: false
            p.write(1); // Allow server listings: true
            writeVarInt(p, 0); // Particle Status

            sendPacket(out, compressionThreshold, C_CONFIG_CLIENT_INFO, p.toByteArray());
        }

        private void sendPacket(OutputStream out, int compressionThreshold, int packetId, byte[] payload) throws IOException {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            writeVarInt(p, packetId);
            p.write(payload);
            byte[] rawData = p.toByteArray();

            if (compressionThreshold >= 0) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                if (rawData.length >= compressionThreshold) {
                    byte[] compressed = deflate(rawData);
                    writeVarInt(frame, rawData.length);
                    frame.write(compressed);
                } else {
                    writeVarInt(frame, 0);
                    frame.write(rawData);
                }
                writeFrame(out, frame.toByteArray());
            } else {
                writeFrame(out, rawData);
            }
        }

        private void writeFrame(OutputStream out, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarInt(frame, payload.length);
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        // ---- Encoding & Decoding helpers ----

        private void writeVarInt(OutputStream out, int value) throws IOException {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }

        private byte[] writeVarIntBytes(int value) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try { writeVarInt(b, value); } catch (IOException ignored) {}
            return b.toByteArray();
        }

        private void writeString(OutputStream out, String str) throws IOException {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, bytes.length);
            out.write(bytes);
        }

        private int readVarInt(InputStream in) throws IOException {
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

        private int readVarIntFromBytes(byte[] data, int[] offset) {
            int value = 0, pos = 0;
            while (true) {
                int b = data[offset[0]++] & 0xFF;
                value |= (b & 0x7F) << pos;
                if ((b & 0x80) == 0) return value;
                pos += 7;
            }
        }

        private String readString(byte[] data, int[] offset) {
            int len = readVarIntFromBytes(data, offset);
            int avail = Math.min(len, data.length - offset[0]);
            String s = new String(data, offset[0], avail, StandardCharsets.UTF_8);
            offset[0] += avail;
            return s;
        }

        private byte[] readFully(InputStream in, int n) throws IOException {
            byte[] buf = new byte[n];
            int read = 0;
            while (read < n) {
                int r = in.read(buf, read, n - read);
                if (r == -1) return null;
                read += r;
            }
            return buf;
        }

        private byte[] inflate(byte[] compressed, int expectedLen) throws DataFormatException {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            byte[] out = new byte[expectedLen];
            int len = inflater.inflate(out);
            inflater.end();
            return len == expectedLen ? out : Arrays.copyOf(out, len);
        }

        private byte[] deflate(byte[] data) {
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
}
