package com.example.stt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SttSocketHandler extends AbstractWebSocketHandler {

    private final String apiKey;
    private final String model;
    private final Map<String, GeminiBridge> bridges = new ConcurrentHashMap<>();

    public SttSocketHandler(@Value("${gemini.api.key:}") String apiKey,
                            @Value("${gemini.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("PASTE_")) {
            send(session, "{\"type\":\"error\",\"message\":\"Gemini API key is not set. Add it in src/main/resources/secrets.properties and restart.\"}");
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        bridges.put(session.getId(),
                new GeminiBridge(apiKey, model, json -> send(session, json)));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        GeminiBridge bridge = bridges.get(session.getId());
        if (bridge == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] pcm = new byte[buf.remaining()];
        buf.get(pcm);
        bridge.sendAudio(pcm);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        GeminiBridge bridge = bridges.get(session.getId());
        if (bridge != null && "end".equals(message.getPayload())) {
            bridge.endAudio();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        GeminiBridge bridge = bridges.remove(session.getId());
        if (bridge != null) bridge.close();
    }

    private void send(WebSocketSession session, String json) {
        synchronized (session) {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(json));
            } catch (IOException ignored) {
            }
        }
    }
}
