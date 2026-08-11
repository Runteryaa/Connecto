# Connecto - Central Relay Proxy Architecture & Future Migration Plan

## 1. Problem Statement & Background
Free Minecraft hosting providers like `play.hosting`, `Aternos`, `PloudOS`, etc., implement strict anti-inactivity measures to automatically sleep or terminate idle Minecraft servers:
1. **Network Activity Monitoring**: Hosting providers monitor external TCP traffic at their edge firewall/router level. Internal connections (e.g. `127.0.0.1` or internal container interfaces) are not registered as "external user activity".
2. **Hairpin NAT / SSRF Protection**: Hosting providers block outgoing TCP connections originating from inside their container network targeting their own public domain/IP (`Stream closed` / `Connection refused`).

Consequently, an **internal bot** running solely inside the Fabric server mod cannot prevent the host from sleeping on providers with strict edge firewall monitoring.

---

## 2. Solution: Multi-Tenant Relay Proxy Architecture
To bypass host anti-AFK without requiring end users to run local background software or buy dedicated Minecraft accounts:

```
+--------------------------+       WebSocket / TCP       +-----------------------------+       Raw TCP       +-----------------------------------+
|  Connecto Fabric Mod     |  ------------------------>  |  Connecto Central Relay     |  ---------------->  | Target Minecraft Host             |
|  (In Minecraft Server)   |  (Target: IP & Port Header) |  (Render.com / Oracle VPS)  |  (Target: 25565)    | (play.hosting / Aternos / etc.)  |
+--------------------------+                             +-----------------------------+                     +-----------------------------------+
```

### Key Features:
- **Zero End-User Setup**: The Mod automatically connects to the configured Central Relay.
- **Multi-Tenant**: A single Relay Proxy instance can simultaneously route connections for hundreds of distinct Minecraft servers.
- **Genuine External Traffic**: The target hosting provider sees incoming TCP connections originating from the Relay Proxy's external IP (e.g. Render / Oracle IP), registering as 100% legitimate player activity.

---

## 3. Implementation Phases

### Phase 1: Render.com WebSocket-to-TCP Relay (Current Phase)
- **Host**: Render.com Free Web Service (750 free hours/month, 24/7 uptime for single service).
- **Protocol**: Java 21 `java.net.http.WebSocket` client (Mod side) <---> Node.js WebSocket Server (Render side) <---> Raw TCP Socket (Target Server).
- **Routing**: Mod sends target host & port via WebSocket query parameters (`wss://proxy.onrender.com?host=TARGET_IP&port=25565`).

### Phase 2: Oracle Cloud Always Free VPS Migration (Future Option)
If migrating to an Oracle Cloud Always Free ARM VPS (4 vCPUs, 24 GB RAM, Static IPv4):
- **Protocol**: Direct TCP Proxy / HAProxy / Pure Node.js `net` module on port 25565.
- **Advantage**: Bypasses WebSocket encapsulation entirely; Mod can use raw TCP socket directly to Oracle IP.
- **Config Update**: Simply update `relayProxyUrl` in `connecto.properties` to point to the Oracle VPS IP.

---

## 4. Relay Proxy Specification (Node.js)
The relay code resides in `relay-proxy/index.js`.
It handles incoming WebSocket connections, extracts `host` and `port` parameters, opens a raw TCP connection to the destination Minecraft server, and pipes binary streams bidirectionally.
