const http = require('http');
const net = require('net');
const WebSocket = require('ws');

const PORT = process.env.PORT || 10000;

// Create HTTP server for health checks & Render binding
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Connecto Relay Proxy: https://github.com/Runteryaa/Connecto!');
});

// Create WebSocket server attached to HTTP server
const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
    // Parse target host and port from URL query parameters (e.g., ?host=example.play.hosting&port=25565)
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const targetHost = url.searchParams.get('host');
    const targetPort = parseInt(url.searchParams.get('port') || '25565', 10);

    if (!targetHost) {
        console.warn('[Connecto-Relay] Connection rejected: Missing "host" parameter.');
        ws.close(1008, 'Target host parameter missing');
        return;
    }

    console.log(`[Connecto-Relay] Incoming connection -> Tunneling to ${targetHost}:${targetPort}`);

    // Create raw TCP socket to the target Minecraft server
    const tcpSocket = net.connect(targetPort, targetHost, () => {
        console.log(`[Connecto-Relay] ✓ TCP Connected to target ${targetHost}:${targetPort}`);
    });

    // Forward WebSocket binary data to Minecraft TCP server
    ws.on('message', (message, isBinary) => {
        if (tcpSocket.writable) {
            tcpSocket.write(message);
        }
    });

    // Forward Minecraft TCP data back to WebSocket client
    tcpSocket.on('data', (data) => {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(data, { binary: true });
        }
    });

    // Error & Close handling
    ws.on('close', () => {
        console.log(`[Connecto-Relay] Client WebSocket closed for ${targetHost}:${targetPort}`);
        tcpSocket.destroy();
    });

    ws.on('error', (err) => {
        console.error(`[Connecto-Relay] WS Error (${targetHost}): ${err.message}`);
        tcpSocket.destroy();
    });

    tcpSocket.on('close', () => {
        console.log(`[Connecto-Relay] Target TCP closed for ${targetHost}:${targetPort}`);
        ws.close();
    });

    tcpSocket.on('error', (err) => {
        console.error(`[Connecto-Relay] TCP Error (${targetHost}): ${err.message}`);
        ws.close();
    });
});

server.listen(PORT, () => {
    console.log(`[Connecto-Relay] Server running on port ${PORT}`);
});
