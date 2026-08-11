package com.runterya.connecto.bot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

public class WebSocketStreamAdapter implements WebSocket.Listener {
    private final PipedOutputStream pos;
    private final PipedInputStream pis;
    private final OutputStream out;
    private final WebSocket webSocket;
    private volatile boolean closed = false;

    public WebSocketStreamAdapter(String url) throws Exception {
        pos = new PipedOutputStream();
        pis = new PipedInputStream(pos, 65536);
        HttpClient client = HttpClient.newHttpClient();
        webSocket = client.newWebSocketBuilder().buildAsync(URI.create(url), this).join();
        
        out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                if(closed) throw new IOException("closed");
                webSocket.sendBinary(ByteBuffer.wrap(new byte[]{(byte)b}), true).join();
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if(closed) throw new IOException("closed");
                webSocket.sendBinary(ByteBuffer.wrap(b, off, len), true).join();
            }
        };
    }

    public InputStream getInputStream() { return pis; }
    public OutputStream getOutputStream() { return out; }
    
    public void close() {
        if (closed) return;
        closed = true;
        try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join(); } catch(Exception ignored){}
        try { pos.close(); } catch(Exception ignored){}
    }

    public boolean isClosed() { return closed; }

    @Override
    public void onOpen(WebSocket webSocket) { 
        webSocket.request(1); 
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            pos.write(bytes);
            pos.flush();
        } catch(Exception ignored){}
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        close();
        return null;
    }
    
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        close();
    }
}
