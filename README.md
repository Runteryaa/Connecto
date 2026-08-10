# Connecto — Fabric Server Mod (MC 1.20.2 – 26.1.2+) [v1.2.0-beta.1]

> Allows designated offline-mode accounts/users to join an **online-mode** Fabric server by bypassing Mojang session verification **only** for whitelisted usernames.

🤖 **Developed with AI** — Built and maintained using Google Antigravity AI agent.

---

## 🎮 Supported Minecraft Versions

| Minecraft Version Range | Status | Notes |
|---|---|---|
| **26.1.2 / 26.x** | ✅ Tested & Fully Supported | Native target build |
| **1.21.0 – 1.21.4** | ✅ Supported | Share modern login & network pipeline |
| **1.20.2 – 1.20.6** | ✅ Supported | Introduces `ServerCommonPacketListenerImpl` architecture |
| **< 1.20.1** | ❌ Unsupported | Pre-refactor legacy packet pipeline |

---

## 📁 Project Layout

```
Connecto/
├── build.gradle                        # Fabric Loom build script
├── gradle.properties                   # Version pins (MC, Loader, API)
├── settings.gradle                     # Root project name
├── gradle/wrapper/
│   └── gradle-wrapper.properties       # Gradle 9.4.0
├── config/
│   └── connecto.json                   # Server-side config (auto-generated)
├── src/main/
│   ├── java/com/runterya/connecto/
│   │   ├── ConnectoMod.java            # Mod initializer
│   │   ├── ConnectoConfig.java         # Config loader/saver (Gson)
│   │   ├── bot/
│   │   │   └── EmbeddedBotManager.java # Auto-connecting TCP bot daemon (BETA)
│   │   └── mixin/
│   │       └── ServerLoginPacketListenerImplMixin.java  ← core logic
│   └── resources/
│       ├── fabric.mod.json
│       └── connecto.mixins.json
├── bot.js                              # Mineflayer AFK test client (Node.js)
└── package.json                        # npm manifest
```

---

## ⚙️ Toolchain

| Tool | Version |
|---|---|
| Minecraft | **26.1.2** (Compatible with 1.20.2+) |
| Fabric Loader | 0.19.3 (≥ 0.14.22) |
| Fabric API | 0.150.0+26.1.2 |
| Fabric Loom | 1.15 |
| Gradle | 9.4.0 |
| Java | **25** (JDK 25 required for build) |
| Mappings | **Unobfuscated** (MC 26.1+ is unobfuscated natively) |

---

## 🚀 Building the Mod

```powershell
# 1. Ensure JDK 25 is on your PATH
java -version

# 2. Run the Gradle build
.\gradlew build

# 3. Output JAR is at:
#    build/libs/connecto-1.2.0-beta.1.jar
```

---

## 🖥️ Installing on the Server

1. Copy `build/libs/connecto-1.2.0-beta.1.jar` into the server's `mods/` folder.
2. Start the server once to generate `config/connecto.json`.
3. Set `"enabled": true` in `config/connecto.json` and add your username to `whitelist`.
4. Restart the server.

---

## 🔧 Configuration (`config/connecto.json`)

```json
{
  "enabled": true,
  "whitelist": [
    "uptime",
    "Runterya"
  ],
  "whitelistPrefix": "",
  "uptimeBot": true,
  "botName": "uptime"
}
```

| Key | Type | Description |
|---|---|---|
| `enabled` | boolean | Master toggle. Set to `true` to enable auth bypass for whitelisted users |
| `whitelist` | string[] | **Exact** usernames exempt from Mojang auth (case-insensitive) |
| `whitelistPrefix` | string | Any username starting with this prefix is exempt. Use `"*"` to allow **all** usernames. Leave `""` to disable |
| `uptimeBot` | boolean | **(Beta)** Automatically launches an embedded TCP client on server startup to keep host server active 24/7 |
| `botName` | string | **(Beta)** Username used by the embedded uptime TCP bot (defaults to `"uptime"`) |

---

## ⚡ Embedded Auto-Connect Uptime Bot (Beta)

When `"uptimeBot": true`, Connecto automatically launches a background TCP client daemon on server startup connecting as `"uptime"`.

**Benefits:**
- Establishes an **actual active TCP network socket** on `127.0.0.1:<server_port>`.
- Prevents hosting providers (**Play.Hosting**, Aternos, etc.) from auto-shutting down the server due to inactivity.
- Operates 100% inside the Fabric mod — **no Node.js or `bot.js` required**.

---

## 🔐 How the Bypass Works (Technical)

```
Normal online-mode flow:
  Client → handleHello → encryption challenge → handleKey
        → sessionserver.mojang.com hasJoined? → startPlay

Connecto flow (for whitelisted usernames):
  Client → handleHello (Authentication bypassed via Mixin)
         → Server generates Offline UUID & GameProfile
         → Client finishes Login Phase (UUID injected during verifyLoginAndFinishConnectionSetup)
         → Server suppresses Netty keepAlive timeouts to keep session stable
         → startPlay()
```

The offline UUID is derived with `UUIDUtil.createOfflinePlayerUUID("<name>")`, identical to how vanilla offline-mode servers generate UUIDs, ensuring world data (inventories, stats) and UUID displays are consistent across reconnects.

---

## 📄 License

MIT
