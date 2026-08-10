# Connecto — Fabric Server Mod (MC 26.1.2)

> Allows designated offline-mode bot accounts to join an **online-mode** Fabric server by bypassing Mojang session verification **only** for whitelisted usernames.

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
│   │   └── mixin/
│   │       └── ServerLoginPacketListenerImplMixin.java  ← core logic
│   └── resources/
│       ├── fabric.mod.json
│       └── connecto.mixins.json
├── bot.js                              # Mineflayer AFK bot (Node.js)
└── package.json                        # npm manifest for the bot
```

---

## ⚙️ Toolchain

| Tool | Version |
|---|---|
| Minecraft | **26.1.2** |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.150.0+26.1.2 |
| Fabric Loom | 1.15 |
| Gradle | 9.4.0 |
| Java | **25** (JDK 25 required) |
| Mappings | **Unobfuscated** (MC 26.1+ is unobfuscated natively) |

---

## 🚀 Building the Mod

```powershell
# 1. Ensure JDK 25 is on your PATH
java -version

# 2. Run the Gradle build
.\gradlew build

# 3. Output JAR is at:
#    build/libs/connecto-1.1.3.jar
```

---

## 🖥️ Installing on the Server

1. Copy `build/libs/connecto-1.1.3.jar` into the server's `mods/` folder.
2. Start the server once to generate `config/connecto.json`.
3. Set `"enabled": true` in `config/connecto.json` and add your bot's username to `botUsernames`.
4. Restart the server.

### Server requirements
- Fabric Loader ≥ 0.19.3 installed on the server
- Fabric API jar present in `mods/`
- `online-mode=true` in `server.properties` (the mod works *alongside* online-mode, not by disabling it)

---

## 🔧 Configuration (`config/connecto.json`)

```json
{
  "enabled": false,
  "botUsernames": [
    "Secret_AFK_Bot"
  ],
  "secretPrefix": ""
}
```

| Key | Type | Description |
|---|---|---|
| `enabled` | boolean | Master toggle. Set to `true` to enable auth bypass for bots |
| `botUsernames` | string[] | **Exact** usernames exempt from Mojang auth (case-insensitive) |
| `secretPrefix` | string | Any username that **starts with** this string is also treated as a bot. Leave `""` to disable |

> ⚠️ Restart the server after editing `connecto.json`.

---

## 🤖 Running the Mineflayer Bot

```powershell
# Install dependencies
npm install

# Edit bot.js and set CONFIG.host to your server address, then:
npm start
```

The bot:
- Connects in **offline mode** (`auth: 'offline'`) using the whitelisted username
- Executes a subtle head-rotation every 30 s to keep the TCP session alive
- Automatically reconnects after kicks or disconnects

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
         → Server suppresses Netty keepAlive timeouts to keep bot stable
         → startPlay()
```

The offline UUID is derived with `UUIDUtil.createOfflinePlayerUUID("<name>")`, identical to how vanilla offline-mode servers generate UUIDs, ensuring world data (inventories, stats) and UUID displays are consistent across reconnects.

---

## ⚠️ Security Considerations

- **Keep `botUsernames` secret.** Anyone who knows the bot username can join without a Mojang account.
- Consider combining with a **whitelist** (`/whitelist on`) and adding the bot to the whitelist using its offline UUID so no other player can steal that slot.
- The `secretPrefix` feature is convenient but reduces security surface if many names share the prefix.

---

## 📄 License

MIT
