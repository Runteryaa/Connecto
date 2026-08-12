# Connecto — Fabric Server Mod (MC 1.20.2 – 26.1.2+)

> Allows designated offline-mode accounts/users to join an **online-mode** Fabric server by bypassing Mojang session verification **only** for whitelisted usernames.

AI used to make this mod

---

## 🎮 Supported Minecraft Versions

| Minecraft Version Range | Status | Notes |
|---|---|---|
| **26.1.2 / 26.x** | ✅ Tested & Fully Supported | Native target build |
| **1.21.0 – 1.21.4** | ✅ Supported | Share modern login & network pipeline |
| **1.20.2 – 1.20.6** | ✅ Supported | Introduces `ServerCommonPacketListenerImpl` architecture |
| **< 1.20.1** | ❌ Unsupported | Pre-refactor legacy packet pipeline |

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
#    build/libs/connecto-1.3.1.jar
```

---

## 🖥️ Installing on the Server

1. Copy `build/libs/connecto-*.jar` into the server's `mods/` folder.
2. Start the server once to generate `config/connecto.properties`.
3. Set `enabled=true` in `config/connecto.properties` and add your username to `whitelist`.
4. If you want `uptimeBot` to keep your server active 24/7, enable and configure it in the config (modes: `ALWAYS` or `SMART`).
5. Configure `fetchOfflineSkins` and `defaultOfflineSkinUser` if you want offline players to display Mojang skins.
6. Restart the server.

---

## ⚙️ In-Game Commands (Requires OP Level 2)

* `/connecto status` — Displays mod status, active bot state, proxy status, skin settings, and whitelist count.
* `/connecto reload` — Reloads `connecto.properties` dynamically and re-fetches the default offline skin.
* `/connecto whitelist list` — Lists all whitelisted usernames.
* `/connecto whitelist add <username>` — Adds a username to the auth bypass whitelist.
* `/connecto whitelist remove <username>` — Removes a username from the whitelist.

---

## 🔐 How the Bypass & Security Work (Technical)

```
Normal online-mode flow:
  Client → handleHello → encryption challenge → handleKey
        → sessionserver.mojang.com hasJoined? → startPlay

Connecto flow (for whitelisted usernames & internal bot):
  Client → handleHello (Authentication bypassed via Mixin)
         → Server generates Offline UUID & GameProfile
         → Secret Session UUID Token verifies internal bot connections
         → Unauthorized connections using bot names trigger Security Alerts
         → SkinFetcher applies official Mojang / default fallback skin
         → Server suppresses Netty keepAlive timeouts to keep session stable
         → startPlay()
```

The offline UUID is derived with `UUIDUtil.createOfflinePlayerUUID("<name>")`, identical to how vanilla offline-mode servers generate UUIDs, ensuring world data (inventories, stats) and UUID displays are consistent across reconnects.

---

## 📄 License

MIT

