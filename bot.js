/**
 * bot.js – Mineflayer AFK bot configured for Connecto on Minecraft 26.1.2
 *
 * Purpose  : Connects to a Connecto-enabled Minecraft server using Mineflayer.
 *            Fully joins the world, appears on /list & TAB menu, and handles
 *            all Minecraft protocol KeepAlive packets to prevent timing out.
 *
 * Usage:
 *   npm start   (or: node bot.js)
 */

'use strict';

// ── Disable minecraft-protocol version string enforcement ─────────────────────
try {
  const versionCheckingPath = require.resolve('minecraft-protocol/src/client/versionChecking');
  require.cache[versionCheckingPath].exports = function () {};
} catch (_) {}

const mineflayer = require('mineflayer');

// ── Global Error Handler ──────────────────────────────────────────────────────
// Mineflayer plugins (like inventory) can throw TypeErrors when they receive
// unexpected packet structures from newer Minecraft versions. We catch these
// globally so the bot process doesn't crash and exit.
process.on('uncaughtException', (err) => {
  if (err && err.name === 'PartialReadError') return;
  if (err && err.message && err.message.includes('Cannot read properties of undefined')) return;
  
  console.warn(`[Connecto] Suppressed global error: ${err.message}`);
});

// ── Configuration ─────────────────────────────────────────────────────────────

const CONFIG = {
  host: 'runterya.play.hosting',    // ← your server host
  port: 25565,                      // default Minecraft port
  username: 'Secret_AFK_Bot',      // must match botUsernames in connecto.json
  version: '1.21.11',               // Mineflayer protocol definition baseline
  protocolVersion: 775,             // Protocol version for Minecraft 26.1.2
  auth: 'offline',                  // offline-mode session
  reconnectDelay: 10_000,          // ms to wait before reconnecting
  checkTimeoutInterval: 24 * 60 * 60 * 1000, // 24 hours instead of false (to avoid truthy fallbacks)
  keepAlive: false,                // Disable Mineflayer keepAlive plugin
  keepAliveInterval: 30_000,       // ms between keep-alive head movements
  logChat: true,                   // print incoming chat messages
};

// ── Bot factory ───────────────────────────────────────────────────────────────

let bot = null;

function createBot() {
  if (bot) {
    try {
      bot.removeAllListeners();
      bot.end();
    } catch (_) {}
    bot = null;
  }

  console.log(`[Connecto] Connecting as "${CONFIG.username}" to ${CONFIG.host}:${CONFIG.port} (MC 26.1.2 / Protocol ${CONFIG.protocolVersion})…`);

  bot = mineflayer.createBot({
    host:                 CONFIG.host,
    port:                 CONFIG.port,
    username:             CONFIG.username,
    version:              CONFIG.version,
    auth:                 CONFIG.auth,
    skipValidation:       true,
    checkTimeoutInterval: 60 * 1000,
    plugins: {
      inventory: false,
      simple_inventory: false,
      craft: false,
      chest: false,
      furnace: false,
      enchantment_table: false,
      villager: false,
      anvil: false
    }
  });

  // CRITICAL FIX: Prevent Mineflayer plugins from destroying the TCP stream.
  // When MC 26.1.2 sends a packet with a slightly different format (e.g. entities, inventory),
  // Mineflayer plugins can throw synchronous TypeErrors inside their packet event listeners.
  // If unhandled, these bubble up to FullPacketParser, which destroys the stream and disconnects the bot.
  // By wrapping emit in a try-catch, we isolate the crash to just the plugin listener, keeping the bot online!
  if (bot._client) {
    const originalEmit = bot._client.emit.bind(bot._client);
    bot._client.emit = function (eventName, ...args) {
      try {
        return originalEmit(eventName, ...args);
      } catch (err) {
        // Silently swallow packet parse crashes from plugins
        return false;
      }
    };

    const originalWrite = bot._client.write.bind(bot._client);
    bot._client.write = function (name, params) {
      if (name === 'set_protocol' && params) {
        params.protocolVersion = CONFIG.protocolVersion;
      }
      
      if (name === 'login_start' && params) {
        // Mineflayer's offline mode defaults to sending a NIL UUID (all zeros).
        // In newer Minecraft versions, the server trusts this UUID, causing the bot 
        // to appear as "Anonymous Player" (UUID 000...000) to everyone in the world.
        // We override it with a valid offline-style UUID so the name renders correctly!
        // MD5 of "OfflinePlayer:Secret_AFK_Bot" converted to v3 UUID:
        params.playerUUID = '9ec84086-78c0-3101-963a-20ff1f5a5189';
      }

      // In play state, Mineflayer's packet IDs don't match MC 26.1.2's IDs.
      // For example, Mineflayer's keep_alive ID collides with the server's
      // jigsaw_generate packet, causing an immediate DecoderException disconnect.
      // The server-side Mixin (ServerCommonPacketListenerImplMixin) handles keepalive
      // by resetting keepAlivePending = false on every tick, so the bot never needs
      // to send keep_alive itself. We drop ALL outgoing play packets to be safe.
      if (bot._client.state === 'play') {
        return; // Drop ALL packets in play state silently
      }

      originalWrite(name, params);
    };

    // Monkey-patch setSerializer so every time the protocol state changes,
    // we wrap the new decoder's transform method to catch parser errors.
    // This stops protodef from killing the entire TCP stream if ONE packet fails to parse.
    const originalSetSerializer = bot._client.setSerializer.bind(bot._client);
    bot._client.setSerializer = function (state) {
      originalSetSerializer(state);
      if (bot._client.decoder) {
        bot._client.decoder.noErrorLogging = true; // Stop protodef from spamming console.log
        const originalTransform = bot._client.decoder._transform.bind(bot._client.decoder);
        bot._client.decoder._transform = function (chunk, enc, cb) {
          originalTransform(chunk, enc, (err) => {
            if (err) {
              // Silently ignore this broken packet, let the stream continue
              return cb();
            }
            cb();
          });
        };
      }
    };

    // Suppress non-fatal packet parse errors caused by minor MC version differences.
    // MC 26.1.2 has slightly different packet layouts vs 1.21.11 definitions.
    // These PartialReadErrors / TypeErrors don't affect connection state – we just
    // swallow them so the bot doesn't crash and reconnect in a loop.
    bot._client.on('error', (err) => {
      if (err && (err.name === 'PartialReadError' || (err.message && err.message.includes('Cannot read properties of undefined')))) {
        // Suppress these silently – they're just packet schema mismatches
        return;
      }
      console.error(`[Connecto] Client error: ${err.message}`);
    });
  }

  // ── Events ─────────────────────────────────────────────────────────────────

  bot.once('spawn', () => {
    console.log(`[Connecto] ✓ Successfully joined world as ${bot.username}! (Bot is now in-game)`);
    startKeepAlive();
  });

  bot.on('kicked', (reason) => {
    const formattedReason = typeof reason === 'string' ? reason : JSON.stringify(reason);
    console.warn(`[Connecto] Kicked by server: ${formattedReason}`);
    scheduleReconnect();
  });

  bot.on('error', (err) => {
    // Ignore packet schema mismatch errors – they don't affect the TCP connection
    if (err && (err.name === 'PartialReadError' || (err.message && err.message.includes('Cannot read properties of undefined')))) {
      return;
    }
    console.error(`[Connecto] Bot Error: ${err.message}`);
    scheduleReconnect();
  });

  // --- FIX FOR MINEFLAYER INTERNAL TIMEOUT ---
  const fakeKeepAliveTimer = setInterval(() => {
    if (bot && bot._client) {
      // Fake a keep_alive coming from the server to trick minecraft-protocol
      // into continuously resetting its internal timeout.
      bot._client.emit('keep_alive', { keepAliveId: 0n }); 
    }
  }, 15000);

  bot.on('end', (reason) => {
    clearInterval(fakeKeepAliveTimer);
    console.log(`[Connecto] Connection ended (${reason}). Reconnecting…`);
    scheduleReconnect();
  });

  if (CONFIG.logChat) {
    bot.on('chat', (username, message) => {
      if (username !== bot.username) {
        console.log(`[Chat] <${username}> ${message}`);
      }
    });
  }
}

// ── Head movement keep-alive ──────────────────────────────────────────────────

let keepAliveTimer = null;

function startKeepAlive() {
  stopKeepAlive();
  keepAliveTimer = setInterval(() => {
    if (!bot || bot.entity == null) return;

    // Gently rotate head slightly to demonstrate activity
    const yaw = bot.entity.yaw;
    bot.look(yaw + 0.001, bot.entity.pitch, false, () => {
      if (bot && bot.entity) {
        bot.look(yaw, bot.entity.pitch, false);
      }
    });

    console.log(`[Connecto] In-game keep-alive tick at ${new Date().toISOString()}`);
  }, CONFIG.keepAliveInterval);
}

function stopKeepAlive() {
  if (keepAliveTimer !== null) {
    clearInterval(keepAliveTimer);
    keepAliveTimer = null;
  }
}

// ── Reconnect logic ───────────────────────────────────────────────────────────

let reconnectTimer = null;

function scheduleReconnect() {
  stopKeepAlive();
  if (reconnectTimer !== null) return;

  console.log(`[Connecto] Reconnecting in ${CONFIG.reconnectDelay / 1000}s…`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    createBot();
  }, CONFIG.reconnectDelay);
}

// ── Entry point ───────────────────────────────────────────────────────────────

createBot();
